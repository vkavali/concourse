package ai.concourse.tts;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.audio.Wav;
import ai.concourse.config.ConcourseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telugu text → OGG/Opus voice note bytes.
 * <p>
 * The message is synthesised <b>phrase by phrase</b> ({@link Phrases}) and joined with silence we control: a long
 * beat after each sentence, a short one at commas — so a gate code sits on its own and "చూడండి, లేదా" never runs
 * together, whichever voice is behind it. Each phrase is cached, so recurring phrases cost nothing after the first use.
 * <p>
 * Providers: {@code mms} = Meta's open-source MMS-TTS (facebook/mms-tts-tel) on Modal ({@code modal/mms_tts.py});
 * {@code google} = Chirp 3 HD te-IN via the Cloud Text-to-Speech REST API — the documented fallback when the MMS
 * voice fails the intelligibility test with a real parent.
 */
@Component
public class TtsClient {
    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);
    /** MMS speaks ~6 syllables/s at 1.0; 0.7 lands near 4, the pace elderly listeners follow on a phone speaker. */
    public static final double DEFAULT_RATE = 0.7;
    private static final int GOOGLE_SAMPLE_RATE = 24_000;

    private final WebClient client;
    private final WebClient google;
    private final AudioTranscoder transcoder;
    private final Path cacheDir;
    private final boolean configured;
    private final String provider;      // "mms" (Modal: MMS-TTS or SeamlessM4T) or "google" (Chirp 3 HD te-IN)
    private final String googleVoice;
    private final int speaker;
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tts-phrase");
        t.setDaemon(true);
        return t;
    });

    @Autowired // two constructors exist (the others are for tests); Spring needs to be told which one
    public TtsClient(ConcourseProperties props, WebClient.Builder builder, AudioTranscoder transcoder) {
        this(props.tts().baseUrl(), props.tts().token(), props.tts().cacheDir(), props.tts().provider(),
                props.tts().googleApiKey(), props.tts().googleVoice(), props.tts().speaker(), builder, transcoder);
    }

    public TtsClient(String baseUrl, String token, String cacheDir, WebClient.Builder builder, AudioTranscoder transcoder) {
        this(baseUrl, token, cacheDir, "mms", null, null, 0, builder, transcoder);
    }

    public TtsClient(String baseUrl, String token, String cacheDir, String provider, String googleApiKey,
                     String googleVoice, int speaker, WebClient.Builder builder, AudioTranscoder transcoder) {
        this(baseUrl, token, cacheDir, provider, googleApiKey, googleVoice, speaker, builder, transcoder, null);
    }

    /** {@code googleBaseUrl} lets tests point the Google client at a mock server; null = the real endpoint. */
    TtsClient(String baseUrl, String token, String cacheDir, String provider, String googleApiKey,
              String googleVoice, int speaker, WebClient.Builder builder, AudioTranscoder transcoder, String googleBaseUrl) {
        this.transcoder = transcoder;
        this.speaker = speaker;
        this.provider = googleApiKey != null && !googleApiKey.isBlank() && "google".equalsIgnoreCase(provider) ? "google" : "mms";
        this.googleVoice = googleVoice == null || googleVoice.isBlank() ? "te-IN-Chirp3-HD-Aoede" : googleVoice;
        boolean mmsConfigured = baseUrl != null && !baseUrl.isBlank();
        this.configured = "google".equals(this.provider) || mmsConfigured;
        ExchangeStrategies big = ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofSeconds(90)));
        WebClient.Builder b = builder.clone()
                .baseUrl(mmsConfigured ? baseUrl : "http://localhost:0")
                .clientConnector(connector)
                .exchangeStrategies(big);
        if (token != null && !token.isBlank()) b.defaultHeader("X-Concourse-Token", token);
        this.client = b.build();
        WebClient.Builder g = builder.clone()
                .baseUrl(googleBaseUrl == null || googleBaseUrl.isBlank() ? "https://texttospeech.googleapis.com/v1" : googleBaseUrl)
                .clientConnector(connector)
                .exchangeStrategies(big);
        if (googleApiKey != null && !googleApiKey.isBlank()) g.defaultHeader("X-Goog-Api-Key", googleApiKey);
        this.google = g.build();
        this.cacheDir = cacheDir == null || cacheDir.isBlank() ? null : Path.of(cacheDir);
    }

    public boolean isConfigured() {
        return configured;
    }

    public String provider() {
        return provider;
    }

    /** OGG/Opus mono bytes ready for {@code WhatsAppClient.uploadMedia(bytes, "audio/ogg", "note.ogg")}. */
    public byte[] synthesizeOggOpus(String teluguText) {
        return synthesizeOggOpus(teluguText, DEFAULT_RATE);
    }

    public byte[] synthesizeOggOpus(String teluguText, double rate) {
        if (!configured) throw new TtsException(0, "TTS is not configured (TTS_BASE_URL or GOOGLE_TTS_API_KEY)");
        String key = key(provider + "|" + speaker + "|note|" + teluguText, rate);
        byte[] cached = readCache(key, ".ogg");
        if (cached != null) return cached;
        long t0 = System.currentTimeMillis();
        List<Phrases.Phrase> phrases = Phrases.split(teluguText);
        byte[] audio;
        try {
            audio = phrased(phrases, rate);
        } catch (RuntimeException e) {
            log.warn("phrase-by-phrase synthesis failed ({}); falling back to one call", e.getMessage());
            audio = "google".equals(provider)
                    ? googleSynthesize(teluguText, rate, "OGG_OPUS", 48_000)
                    : mmsSynthesize(teluguText, rate, "ogg");
        }
        if (audio == null || audio.length < 100) throw new TtsException(0, "empty audio");
        writeCache(key, ".ogg", audio);
        log.info("tts ok provider={} phrases={} chars={} bytes={} ms={}", provider, phrases.size(), teluguText.length(),
                audio.length, System.currentTimeMillis() - t0);
        return audio;
    }

    /** Synthesises each phrase (in parallel, cached), joins them with the pauses from {@link Phrases}, encodes once. */
    private byte[] phrased(List<Phrases.Phrase> phrases, double rate) {
        if (phrases.isEmpty()) throw new TtsException(0, "nothing to say");
        List<CompletableFuture<Wav.Pcm>> futures = new ArrayList<>();
        for (Phrases.Phrase p : phrases) {
            futures.add(CompletableFuture.supplyAsync(() -> Wav.parse(phraseWav(p.text(), rate)), pool));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join(); // wait for every phrase before judging
        List<Wav.Pcm> clips = futures.stream().map(CompletableFuture::join).toList();
        List<Integer> pauses = phrases.stream().map(Phrases.Phrase::pauseAfterMs).toList();
        return transcoder.toOggOpusVoiceNote(Wav.join(clips, pauses));
    }

    /** One phrase → 16-bit mono WAV from the provider, cached on disk. */
    byte[] phraseWav(String phrase, double rate) {
        String key = key(provider + "|" + speaker + "|phrase|" + phrase, rate);
        byte[] cached = readCache(key, ".wav");
        if (cached != null) return cached;
        byte[] wav = "google".equals(provider)
                ? googleSynthesize(phrase, rate, "LINEAR16", GOOGLE_SAMPLE_RATE)
                : mmsSynthesize(phrase, rate, "wav");
        if (wav == null || wav.length < 44) throw new TtsException(0, "empty audio for phrase");
        writeCache(key, ".wav", wav);
        return wav;
    }

    private byte[] mmsSynthesize(String text, double rate, String format) {
        try {
            return client.post().uri("/tts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", text, "format", format, "rate", rate, "speaker", speaker))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(body -> Mono.error(new TtsException(r.statusCode().value(), body))))
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(95));
        } catch (TtsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TtsException(0, "tts call failed: " + e.getMessage());
        }
    }

    /**
     * Google Cloud Text-to-Speech, Chirp 3 HD Telugu. Chirp's rate scale differs from MMS: 0.85 here is already a
     * calm pace, and the pauses between phrases are ours, not the voice's.
     */
    private byte[] googleSynthesize(String text, double rate, String encoding, int sampleRate) {
        double googleRate = Math.round(Math.max(0.5, Math.min(1.5, rate + 0.15)) * 100) / 100.0;
        try {
            return googleCall(text, Map.of("audioEncoding", encoding, "speakingRate", googleRate, "sampleRateHertz", sampleRate));
        } catch (TtsException e) {
            if (e.status() != 400) throw e;
            log.warn("google tts rejected the request ({}); retrying without speakingRate", e.getMessage());
            return googleCall(text, Map.of("audioEncoding", encoding, "sampleRateHertz", sampleRate));
        }
    }

    private byte[] googleCall(String text, Map<String, Object> audioConfig) {
        Map<String, Object> body = Map.of(
                "input", Map.of("text", text),
                "voice", Map.of("languageCode", "te-IN", "name", googleVoice),
                "audioConfig", audioConfig);
        try {
            JsonNode res = google.post().uri("/text:synthesize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.error(new TtsException(r.statusCode().value(), b))))
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(60));
            String b64 = res == null ? null : res.path("audioContent").asText(null);
            if (b64 == null) throw new TtsException(0, "no audioContent in Google TTS response");
            return Base64.getDecoder().decode(b64);
        } catch (TtsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new TtsException(0, "google tts call failed: " + e.getMessage());
        }
    }

    private static String key(String text, double rate) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(text.strip().getBytes(StandardCharsets.UTF_8));
            md.update(("|" + rate).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] readCache(String key, String ext) {
        if (cacheDir == null) return null;
        try {
            Path p = cacheDir.resolve(key + ext);
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCache(String key, String ext, byte[] audio) {
        if (cacheDir == null) return;
        try {
            Files.createDirectories(cacheDir);
            Files.write(cacheDir.resolve(key + ext), audio);
        } catch (IOException e) {
            log.debug("tts cache write skipped: {}", e.getMessage());
        }
    }

    public static class TtsException extends RuntimeException {
        private final int status;
        public TtsException(int status, String message) {
            super("TTS HTTP " + status + ": " + message);
            this.status = status;
        }
        public int status() { return status; }
    }
}
