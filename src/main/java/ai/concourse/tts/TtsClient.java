package ai.concourse.tts;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.audio.Wav;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Telugu text → OGG/Opus voice note bytes, from a Meta open-weight voice served by {@code modal/}
 * (SeamlessM4T v2, 200 speakers, or MMS-TTS Telugu — same HTTP contract). No other voice provider exists here:
 * every model Concourse calls at runtime is a Meta model.
 * <p>
 * The message is synthesised <b>phrase by phrase</b> ({@link Phrases}) and joined with silence we control: a long
 * beat after each sentence, a short one at commas — so a gate code sits on its own and "చూడండి, లేదా" never runs
 * together. Each phrase and each whole note is cached on disk (the folder is per speaker), so recurring phrases cost
 * nothing after the first use and a judge replaying the tape hears the recorded notes without any TTS server.
 */
@Component
public class TtsClient {
    private static final Logger log = LoggerFactory.getLogger(TtsClient.class);
    /** The open voices speak ~6 syllables/s at 1.0; 0.7 lands near 4, the pace elderly listeners follow on a phone speaker. */
    public static final double DEFAULT_RATE = 0.7;

    private final WebClient client;
    private final AudioTranscoder transcoder;
    private final Path cacheDir;
    private final boolean serverConfigured;
    private final int speaker;
    private final String modelLabel;
    private volatile String resolvedModel;
    private ModelCallLog callLog;   // optional
    private ModelTape tape;         // optional
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "tts-phrase");
        t.setDaemon(true);
        return t;
    });

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public TtsClient(ConcourseProperties props, WebClient.Builder builder, AudioTranscoder transcoder) {
        this(props.tts().baseUrl(), props.tts().token(), props.tts().cacheDir(), props.tts().speaker(), props.tts().model(),
                builder, transcoder);
    }

    public TtsClient(String baseUrl, String token, String cacheDir, WebClient.Builder builder, AudioTranscoder transcoder) {
        this(baseUrl, token, cacheDir, 0, null, builder, transcoder);
    }

    public TtsClient(String baseUrl, String token, String cacheDir, int speaker, String modelLabel,
                     WebClient.Builder builder, AudioTranscoder transcoder) {
        this.transcoder = transcoder;
        this.speaker = speaker;
        this.modelLabel = modelLabel == null || modelLabel.isBlank() ? "facebook/seamless-m4t-v2-large" : modelLabel;
        this.serverConfigured = baseUrl != null && !baseUrl.isBlank();
        ExchangeStrategies big = ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();
        ReactorClientHttpConnector connector = new ReactorClientHttpConnector(HttpClient.create().responseTimeout(Duration.ofSeconds(90)));
        WebClient.Builder b = builder.clone()
                .baseUrl(serverConfigured ? baseUrl : "http://localhost:0")
                .clientConnector(connector)
                .exchangeStrategies(big);
        if (token != null && !token.isBlank()) b.defaultHeader("X-Concourse-Token", token);
        this.client = b.build();
        this.cacheDir = cacheDir == null || cacheDir.isBlank() ? null : Path.of(cacheDir);
    }

    @Autowired(required = false)
    public void setCallLog(ModelCallLog callLog) { this.callLog = callLog; }

    @Autowired(required = false)
    public void setTape(ModelTape tape) { this.tape = tape; }

    /** True when a voice can be produced: a TTS server, or recordings to replay (the cache folder or the tape). */
    /**
     * Whether asking for a voice can produce one: a server, a cache of earlier notes, a tape with recorded notes —
     * or a recording session, where a note with no server still leaves its text for a later synthesis pass.
     */
    public boolean isConfigured() {
        return serverConfigured
                || (cacheDir != null && Files.isDirectory(cacheDir))
                || (tape != null && tape.replaying() && Files.isDirectory(tape.ttsDir()))
                || (tape != null && tape.recording());
    }

    public boolean hasServer() { return serverConfigured; }

    /** The model id behind the voice: what the server's {@code /health} reported at start-up, else the configured label. */
    public String modelId() {
        String m = resolvedModel;
        return m != null ? m : modelLabel;
    }

    /** Asks the TTS server which Meta model it serves — once, in the background, so no note ever waits for it. */
    @PostConstruct
    void resolveModel() {
        if (!serverConfigured) return;
        pool.submit(() -> {
            try {
                JsonNode h = client.get().uri("/health").retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(8));
                String reported = h == null ? null : h.path("model").asText(null);
                if (reported != null && !reported.isBlank()) resolvedModel = reported;
                log.info("tts server model: {}", modelId());
            } catch (RuntimeException e) {
                log.debug("tts /health not answering ({}); using label {}", e.getMessage(), modelLabel);
            }
        });
    }

    /** OGG/Opus mono bytes ready for {@code WhatsAppClient.uploadMedia(bytes, "audio/ogg", "note.ogg")}. */
    public byte[] synthesizeOggOpus(String teluguText) {
        return synthesizeOggOpus(teluguText, DEFAULT_RATE);
    }

    public byte[] synthesizeOggOpus(String teluguText, double rate) {
        String key = key("note|" + teluguText, rate);
        byte[] cached = readCache(key, ".ogg");
        if (cached != null) {
            record("note", 0, true, "cached");
            return cached;
        }
        byte[] recorded = readTape(key);
        if (recorded != null) {
            String by = tapeModel(key);
            record(by, "note", 0, true, "replayed from tape");
            return recorded;
        }
        if (!serverConfigured) {
            wanted(key, teluguText);
            record("note", 0, false, tape != null && tape.replaying() ? "not on tape and no TTS_BASE_URL" : "TTS_BASE_URL not set");
            throw new TtsException(0, "no voice: TTS_BASE_URL is not set and this note is not in the cache or on the tape");
        }
        long t0 = System.currentTimeMillis();
        List<Phrases.Phrase> phrases = Phrases.split(teluguText);
        byte[] audio;
        try {
            audio = phrased(phrases, rate);
        } catch (RuntimeException e) {
            log.warn("phrase-by-phrase synthesis failed ({}); falling back to one call", e.getMessage());
            try {
                audio = synthesize(teluguText, rate, "ogg");
            } catch (RuntimeException e2) {
                record("note", System.currentTimeMillis() - t0, false, e2.getMessage());
                throw e2;
            }
        }
        if (audio == null || audio.length < 100) {
            record("note", System.currentTimeMillis() - t0, false, "empty audio");
            throw new TtsException(0, "empty audio");
        }
        writeCache(key, ".ogg", audio);
        long ms = System.currentTimeMillis() - t0;
        writeTape(key, audio, ms);
        record("note", ms, true, phrases.size() + " phrases, " + audio.length / 1024 + " KB");
        log.info("tts ok model={} speaker={} phrases={} chars={} bytes={} ms={}", modelId(), speaker, phrases.size(),
                teluguText.length(), audio.length, ms);
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

    /** One phrase → 16-bit mono WAV from the server, cached on disk. */
    byte[] phraseWav(String phrase, double rate) {
        String key = key("phrase|" + phrase, rate);
        byte[] cached = readCache(key, ".wav");
        if (cached != null) return cached;
        byte[] wav = synthesize(phrase, rate, "wav");
        if (wav == null || wav.length < 44) throw new TtsException(0, "empty audio for phrase");
        writeCache(key, ".wav", wav);
        return wav;
    }

    private byte[] synthesize(String text, double rate, String format) {
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

    private void record(String op, long ms, boolean ok, String note) {
        record(modelId(), op, ms, ok, note);
    }

    private void record(String model, String op, long ms, boolean ok, String note) {
        if (callLog != null) callLog.record(model, "tts " + op, ms, ok, note);
    }

    /** The models behind the notes on the tape (from their {@code tts/<key>.json} sidecars), for the status line. */
    public java.util.Set<String> tapeModels() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        if (tape == null || tape.mode() == ModelTape.Mode.OFF || !Files.isDirectory(tape.ttsDir())) return out;
        try (var files = Files.list(tape.ttsDir())) {
            files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(f -> {
                try {
                    JsonNode n = JSON.readTree(Files.readString(f, StandardCharsets.UTF_8));
                    if (n.path("model").isTextual()) out.add(n.path("model").asText());
                } catch (IOException ignored) {
                    // a sidecar that cannot be read says nothing
                }
            });
        } catch (IOException ignored) {
            // no tape dir: nothing to say
        }
        return out;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Which model made the recorded note: its sidecar's {@code model}, else "unknown model" — never a guess. */
    private String tapeModel(String key) {
        try {
            Path side = tape.ttsDir().resolve(key + ".json");
            if (Files.exists(side)) {
                JsonNode n = JSON.readTree(Files.readString(side, StandardCharsets.UTF_8));
                if (n.path("model").isTextual() && !n.path("model").asText().isBlank()) return n.path("model").asText();
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "unknown model";
    }

    // ---- cache and tape ----------------------------------------------------------------------------------------

    /** Content-addressed: the same text at the same rate is the same note, whichever day it was made. */
    static String key(String text, double rate) {
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

    private byte[] readTape(String key) {
        if (tape == null || !tape.replaying()) return null;
        try {
            Path p = tape.ttsDir().resolve(key + ".ogg");
            return Files.exists(p) ? Files.readAllBytes(p) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Recording with no voice server: leave the note's spoken text at {@code tts/<key>.txt} so a later pass
     * ({@code eval/judge-path/prerender_voice.py}, Meta's MMS-TTS Telugu) can synthesize exactly this text under
     * exactly this key. Replaced by the live recording the moment a server synthesizes the same note.
     */
    private void wanted(String key, String spokenText) {
        if (tape == null || !tape.recording()) return;
        try {
            Files.createDirectories(tape.ttsDir());
            Files.writeString(tape.ttsDir().resolve(key + ".txt"), spokenText.strip() + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("tts wanted-note not written: {}", e.getMessage());
        }
    }

    /** The note and its sidecar: which model made it, when, how long it took — so a replay can say so. */
    private void writeTape(String key, byte[] audio, long ms) {
        if (tape == null || !tape.recording()) return;
        try {
            Files.createDirectories(tape.ttsDir());
            Files.write(tape.ttsDir().resolve(key + ".ogg"), audio);
            Map<String, Object> side = new java.util.LinkedHashMap<>();
            side.put("recorded_at", java.time.Instant.now().toString());
            side.put("model", modelId());
            side.put("op", "note");
            side.put("ms", ms);
            side.put("speaker", speaker);
            side.put("source", "live TTS server");
            Files.writeString(tape.ttsDir().resolve(key + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(side) + "\n", StandardCharsets.UTF_8);
            Files.deleteIfExists(tape.ttsDir().resolve(key + ".txt"));
        } catch (IOException e) {
            log.warn("tape tts write failed: {}", e.getMessage());
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
