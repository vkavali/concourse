package ai.concourse.stt;

import ai.concourse.config.ConcourseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final String model;
    private final Duration timeout;

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public SttClient(ConcourseProperties props, WebClient.Builder builder) {
        this(props.meta().baseUrl(), props.meta().apiKey(), props.meta().sttModel(),
                Duration.ofSeconds(props.meta().timeoutSeconds()), builder);
    }

    public SttClient(String baseUrl, String apiKey, String model, Duration timeout, WebClient.Builder builder) {
        this.model = model;
        this.timeout = timeout;
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .build();
    }

    /** Transcribe a 16 kHz mono PCM WAV. Language bias is a list of language names, e.g. ["Telugu", "English"]. */
    public Transcript transcribeWav(byte[] wav, List<String> languageBias) {
        return transcribeWav(wav, languageBias, AIRPORT_KEYWORDS);
    }

    public Transcript transcribeWav(byte[] wav, List<String> languageBias, List<String> keywords) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("mode", "PUSH_TO_TALK");          // one utterance per voice note; no turn segmentation needed
        request.put("model", model);
        request.put("audioEncoding", "WAV");
        if (languageBias != null && !languageBias.isEmpty()) request.put("languageBias", languageBias);
        if (keywords != null && !keywords.isEmpty()) request.put("keywords", keywords);

        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("request", request, MediaType.APPLICATION_JSON);
        mb.part("audio", new ByteArrayResource(wav) {
            @Override public String getFilename() { return "audio.wav"; }
        }).contentType(MediaType.parseMediaType("audio/wav"));

        String sessionId = UUID.randomUUID().toString();
        try {
            JsonNode res = client.post()
                    .uri(b -> b.path("/asr/transcribe").queryParam("sessionId", sessionId).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromMultipartData(mb.build()))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new SttException(r.statusCode().value(), body))))
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
