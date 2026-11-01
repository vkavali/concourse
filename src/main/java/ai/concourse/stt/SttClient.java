package ai.concourse.stt;

import ai.concourse.config.ConcourseProperties;
import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Muse Voice Transcribe, file mode: {@code POST {base}/asr/transcribe?sessionId=...} as multipart with a
 * {@code request} JSON part and an {@code audio} WAV part (mono, 16-bit PCM, 16 or 24 kHz; ≤ 10 min; ≤ 32 MB).
 * Response: {@code {sessionId, transcript, audioDurationMs, turns[]}}. The API returns no confidence score;
 * confidence is derived downstream from Spark's intent classification (see docs/DECISIONS.md, Oct 27).
 */
@Component
public class SttClient {
    private static final Logger log = LoggerFactory.getLogger(SttClient.class);

    /** Vocabulary the parent is likely to say inside Telugu sentences. Biasing helps recognition, it does not force spelling. */
    public static final List<String> AIRPORT_KEYWORDS = List.of(
            "gate", "terminal", "boarding", "boarding pass", "security", "immigration", "customs", "baggage", "luggage",
            "check-in", "counter", "flight", "transit", "transfer", "wheelchair", "passport", "visa",
            "Qatar Airways", "Emirates", "Etihad", "British Airways", "Lufthansa", "American Airlines", "United",
            "Air India", "IndiGo", "Hyderabad", "Dallas", "Doha", "Dubai", "Abu Dhabi", "Frankfurt", "London", "Heathrow",
            "DFW", "HYD", "DOH", "DXB", "AUH", "FRA", "LHR", "JFK");

    public record Transcript(String sessionId, String text, long audioDurationMs) {
        public boolean isEmpty() { return text == null || text.isBlank(); }
    }

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String model;
    private final Duration timeout;
    private final boolean configured;
    private ModelCallLog callLog;   // optional
    private ModelTape tape;         // optional

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public SttClient(ConcourseProperties props, WebClient.Builder builder, ObjectMapper mapper) {
        this(props.meta().baseUrl(), props.meta().apiKey(), props.meta().sttModel(),
                Duration.ofSeconds(props.meta().timeoutSeconds()), builder, mapper);
    }

    public SttClient(String baseUrl, String apiKey, String model, Duration timeout, WebClient.Builder builder, ObjectMapper mapper) {
        this.mapper = mapper;
        this.model = model;
        this.timeout = timeout;
        this.configured = apiKey != null && !apiKey.isBlank();
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .build();
    }

    public String model() { return model; }

    public boolean isConfigured() { return configured; }

    @Autowired(required = false)
    public void setCallLog(ModelCallLog callLog) { this.callLog = callLog; }

    @Autowired(required = false)
    public void setTape(ModelTape tape) { this.tape = tape; }

    /** Transcribe a 16 kHz mono PCM WAV. Language bias is a list of language names, e.g. ["Telugu", "English"]. */
    public Transcript transcribeWav(byte[] wav, List<String> languageBias) {
        return transcribeWav(wav, languageBias, AIRPORT_KEYWORDS);
    }

    /**
     * With the tape replaying, the recording for these exact audio bytes is returned instead of a call; with it
     * recording, the live transcript is saved under the audio's hash.
     */
    public Transcript transcribeWav(byte[] wav, List<String> languageBias, List<String> keywords) {
        String key = tape != null && tape.mode() != ModelTape.Mode.OFF ? ModelTape.sha256(wav) : null;
        if (tape != null && tape.replaying()) {
            Optional<String> hit = tape.lookup("stt", key);
            if (hit.isPresent()) {
                if (callLog != null) callLog.record(model, "transcribe", 0, true, "replayed from tape");
                try {
                    JsonNode n = mapper.readTree(hit.get());
                    return new Transcript("tape", n.path("transcript").asText("").strip(), n.path("audioDurationMs").asLong(0));
                } catch (IOException e) {
                    return new Transcript("tape", hit.get().strip(), 0);
                }
            }
            if (!configured) {
                if (callLog != null) callLog.record(model, "transcribe", 0, false, "not on tape and no MODEL_API_KEY");
                throw new SttException(0, "no MODEL_API_KEY and no recording on the tape for this audio");
            }
        }
        long t0 = System.currentTimeMillis();
        Transcript t;
        try {
            t = live(wav, languageBias, keywords);
        } catch (SttException e) {
            if (callLog != null) callLog.record(model, "transcribe", System.currentTimeMillis() - t0, false, e.getMessage());
            throw e;
        }
        long ms = System.currentTimeMillis() - t0;
        if (callLog != null) callLog.record(model, "transcribe", ms, true, t.audioDurationMs() + " ms of audio → " + t.text().length() + " chars");
        if (tape != null && tape.recording()) {
            try {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("model", model);
                meta.put("op", "transcribe");
                meta.put("ms", ms);
                meta.put("audio_ms", t.audioDurationMs());
                tape.save("stt", key, meta, mapper.writeValueAsString(Map.of("transcript", t.text(), "audioDurationMs", t.audioDurationMs())));
            } catch (IOException e) {
                log.warn("tape stt write failed: {}", e.getMessage());
            }
        }
        return t;
    }

    private Transcript live(byte[] wav, List<String> languageBias, List<String> keywords) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mode", "PUSH_TO_TALK");          // one utterance per voice note; no turn segmentation needed
        request.put("model", model);
        request.put("audioEncoding", "WAV");
        if (languageBias != null && !languageBias.isEmpty()) request.put("languageBias", languageBias);
        if (keywords != null && !keywords.isEmpty()) request.put("keywords", keywords);

        String boundary = "concourse" + UUID.randomUUID().toString().replace("-", "");
        byte[] body;
        try {
            body = multipart(boundary, mapper.writeValueAsString(request), wav);
        } catch (IOException e) {
            throw new SttException(0, "cannot build request: " + e.getMessage());
        }

        String sessionId = UUID.randomUUID().toString();
        try {
            JsonNode res = client.post()
                    .uri(b -> b.path("/asr/transcribe").queryParam("sessionId", sessionId).build())
                    .contentType(MediaType.parseMediaType("multipart/form-data; boundary=" + boundary))
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.error(new SttException(r.statusCode().value(), b))))
                    .bodyToMono(JsonNode.class)
                    .block(timeout.plusSeconds(5));
            if (res == null) throw new SttException(0, "empty response");
            Transcript t = new Transcript(res.path("sessionId").asText(sessionId),
                    res.path("transcript").asText("").strip(), res.path("audioDurationMs").asLong(0));
            log.info("stt ok session={} durationMs={} chars={}", t.sessionId(), t.audioDurationMs(), t.text().length());
            return t;
        } catch (SttException e) {
            throw e;
        } catch (WebClientRequestException e) {
            throw new SttException(0, "request failed: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new SttException(0, "transcription failed: " + e.getMessage());
        }
    }

    /**
     * Hand-built multipart/form-data, byte-for-byte what curl -F sends: a {@code request} JSON part and an
     * {@code audio} file part. Meta's endpoint rejected Spring's MultipartBodyBuilder output ("Malformed multipart body").
     */
    static byte[] multipart(String boundary, String requestJson, byte[] wav) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 1024);
        String crlf = "\r\n";
        out.write(("--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"request\"" + crlf
                + "Content-Type: application/json" + crlf + crlf
                + requestJson + crlf
                + "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"" + crlf
                + "Content-Type: audio/wav" + crlf + crlf).getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write((crlf + "--" + boundary + "--" + crlf).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    public static class SttException extends RuntimeException {
        private final int status;
        public SttException(int status, String body) {
            super("Voice Transcribe HTTP " + status + ": " + (body == null ? "" : body));
            this.status = status;
        }
        public int status() { return status; }
        /** 429 and 5xx are worth one retry; 400/413 (bad or too long audio) are not. */
        public boolean retryable() { return status == 0 || status == 429 || status >= 500; }
    }
}
