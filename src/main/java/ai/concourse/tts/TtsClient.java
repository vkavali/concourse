package ai.concourse.tts;

import ai.concourse.config.ConcourseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Telugu text → OGG/Opus voice note bytes, from Meta's open-source MMS-TTS (facebook/mms-tts-tel) served on Modal
 * ({@code modal/mms_tts.py}: {@code POST {base}/tts {"text","format":"ogg","rate"}} → audio/ogg). Results are cached on
 * disk by text hash because stage instructions repeat across journeys. Chirp 3 HD is the documented fallback if MMS
 * fails the Day-1 intelligibility test — it would slot in behind this same interface.
 */
@Component
public class TtsClient {
    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);
    /** Slightly slower than the model default; elderly listeners on a phone speaker. */
    public static final double DEFAULT_RATE = 0.9;

    private final WebClient client;
    private final Path cacheDir;
    private final boolean configured;

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public TtsClient(ConcourseProperties props, WebClient.Builder builder) {
        this(props.tts().baseUrl(), props.tts().token(), props.tts().cacheDir(), builder);
    }

    public TtsClient(String baseUrl, String token, String cacheDir, WebClient.Builder builder) {
        this.configured = baseUrl != null && !baseUrl.isBlank();
        WebClient.Builder b = builder.clone()
                .baseUrl(configured ? baseUrl : "http://localhost:0")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofSeconds(90))))
                .exchangeStrategies(ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build());
        if (token != null && !token.isBlank()) b.defaultHeader("X-Concourse-Token", token);
        this.client = b.build();
        this.cacheDir = cacheDir == null || cacheDir.isBlank() ? null : Path.of(cacheDir);
    }

    public boolean isConfigured() {
        return configured;
    }

    /** OGG/Opus mono bytes ready for {@code WhatsAppClient.uploadMedia(bytes, "audio/ogg", "note.ogg")}. */
    public byte[] synthesizeOggOpus(String teluguText) {
        return synthesizeOggOpus(teluguText, DEFAULT_RATE);
    }

    public byte[] synthesizeOggOpus(String teluguText, double rate) {
        if (!configured) throw new TtsException(0, "TTS_BASE_URL is not configured");
        String key = key(teluguText, rate);
        byte[] cached = readCache(key);
        if (cached != null) return cached;
        byte[] audio;
        try {
            audio = client.post().uri("/tts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("text", teluguText, "format", "ogg", "rate", rate))
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
        if (audio == null || audio.length < 100) throw new TtsException(0, "empty audio");
        writeCache(key, audio);
        log.info("tts ok chars={} bytes={}", teluguText.length(), audio.length);
        return audio;
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

    private byte[] readCache(String key) {
        if (cacheDir == null) return null;
        try {
            Path p = cacheDir.resolve(key + ".ogg");
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCache(String key, byte[] audio) {
        if (cacheDir == null) return;
        try {
            Files.createDirectories(cacheDir);
            Files.write(cacheDir.resolve(key + ".ogg"), audio);
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
