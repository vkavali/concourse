package ai.concourse.ops;

import ai.concourse.config.ConcourseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

/**
 * The tape: a folder of recordings from a real run, so the same run can be replayed by someone who has no API keys.
 * <ul>
 *   <li>{@code record}: every Spark and Voice Transcribe call writes {@code <kind>/<key>.json} (model, operation,
 *       milliseconds, the model's output); every voice note or photo she sent is saved under {@code inputs/}; every
 *       synthesized voice note is copied under {@code tts/}.</li>
 *   <li>{@code replay}: a call whose key is on the tape returns the recording and is logged as such; a miss goes to
 *       the live model when a key is configured and fails plainly when it is not.</li>
 * </ul>
 * Keys are content hashes: the audio bytes for a transcription, the image bytes for a sign, the transcript for an
 * intent — so the recorded answer is tied to the exact input, not to the day it was recorded.
 */
@Component
public class ModelTape {
    private static final Logger log = LoggerFactory.getLogger(ModelTape.class);
    public enum Mode { OFF, RECORD, REPLAY }

    private final Mode mode;
    private final Path dir;
    private final ObjectMapper mapper;

    @Autowired
    public ModelTape(ConcourseProperties props, ObjectMapper mapper) {
        this(props.tape().mode(), props.tape().dir(), mapper);
    }

    public ModelTape(String mode, String dir, ObjectMapper mapper) {
        Mode m;
        try {
            m = mode == null || mode.isBlank() ? Mode.OFF : Mode.valueOf(mode.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("unknown TAPE_MODE '{}' — tape off", mode);
            m = Mode.OFF;
        }
        this.mode = m;
        this.dir = dir == null || dir.isBlank() ? Path.of("eval/sample-run") : Path.of(dir);
        this.mapper = mapper;
        if (m != Mode.OFF) log.info("tape {} at {}", m.name().toLowerCase(), this.dir.toAbsolutePath());
    }

    public Mode mode() { return mode; }
    public boolean recording() { return mode == Mode.RECORD; }
    public boolean replaying() { return mode == Mode.REPLAY; }
    public Path dir() { return dir; }
    public Path ttsDir() { return dir.resolve("tts"); }
    public Path inputsDir() { return dir.resolve("inputs"); }

    /** One recording: the model's output and which model produced it. */
    public record Recording(String output, String model) {}

    /** A recorded model output for this kind and key, if the tape has one. */
    public Optional<String> lookup(String kind, String key) {
        return recording(kind, key).map(Recording::output);
    }

    public Optional<Recording> recording(String kind, String key) {
        if (key == null) return Optional.empty();
        Path p = dir.resolve(kind).resolve(key + ".json");
        if (!Files.isRegularFile(p)) return Optional.empty();
        try {
            JsonNode n = mapper.readTree(Files.readString(p, StandardCharsets.UTF_8));
            if (!n.hasNonNull("output")) return Optional.empty();
            return Optional.of(new Recording(n.get("output").asText(), n.path("model").asText("recording")));
        } catch (IOException e) {
            log.warn("tape entry unreadable {}: {}", p, e.getMessage());
            return Optional.empty();
        }
    }

    /** Writes a recording (record mode only). {@code meta} holds ids and numbers, never prompts or transcripts. */
    public void save(String kind, String key, Map<String, ?> meta, String output) {
        if (!recording()) return;
        try {
            Path folder = dir.resolve(kind);
            Files.createDirectories(folder);
            ObjectNode n = mapper.createObjectNode();
            n.put("recorded_at", Instant.now().toString());
            meta.forEach((k, v) -> n.putPOJO(k, v));
            n.put("output", output);
            Files.writeString(folder.resolve(key + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(n), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("tape write failed ({}/{}): {}", kind, key, e.getMessage());
        }
    }

    /** Saves an input she sent (record mode only): {@code inputs/<timestamp>-<kind>.<ext>}. Returns the file name. */
    public Optional<String> saveInput(String kind, byte[] bytes, String ext) {
        if (!recording() || bytes == null || bytes.length == 0) return Optional.empty();
        try {
            Files.createDirectories(inputsDir());
            String name = Instant.now().toString().replace(':', '-').replace('.', '-') + "-" + kind + "." + ext;
            Files.write(inputsDir().resolve(name), bytes);
            return Optional.of(name);
        } catch (IOException e) {
            log.warn("tape input write failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text == null ? new byte[0] : text.strip().getBytes(StandardCharsets.UTF_8));
    }
}
