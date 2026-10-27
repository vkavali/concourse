package ai.concourse.audio;

import ai.concourse.config.ConcourseProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ffmpeg wrapper for the two conversions Concourse needs:
 * <ul>
 *   <li>inbound WhatsApp voice note (OGG/Opus, or anything) → 16 kHz mono 16-bit PCM WAV for Muse Voice Transcribe</li>
 *   <li>outbound TTS WAV → OGG/Opus mono voice note for WhatsApp ({@code voice: true})</li>
 * </ul>
 * Everything goes through temp files that are deleted afterwards; nothing is kept on disk.
 */
@Component
public class AudioTranscoder {
    private final String ffmpeg;

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public AudioTranscoder(ConcourseProperties props) {
        this(props.audio().ffmpegPath());
    }

    public AudioTranscoder(String ffmpeg) {
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg;
    }

    /** Any container/codec → RIFF WAV, mono, 16 kHz, signed 16-bit PCM, no metadata (the exact format /asr/transcribe accepts). */
    public byte[] toWav16kMono(byte[] input) {
        return run(input, "in", List.of("-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                "-map_metadata", "-1", "-fflags", "+bitexact", "-f", "wav"));
    }

    /** WAV → OGG/Opus mono at 24 kbps, speech-optimised. A 60 s note lands around 180 KB (well under WhatsApp's 512 KB inline-play limit). */
    public byte[] toOggOpusVoiceNote(byte[] wav) {
        return run(wav, "in.wav", List.of("-ac", "1", "-ar", "48000", "-c:a", "libopus", "-b:a", "24k",
                "-application", "voip", "-map_metadata", "-1", "-f", "ogg"));
    }

    /** Duration of a canonical 16 kHz mono 16-bit WAV (as produced by {@link #toWav16kMono}). */
    public static long wavDurationMs(byte[] wav16kMono) {
        long dataBytes = Math.max(0, wav16kMono.length - 44);
        return dataBytes * 1000 / (16_000L * 2);
    }

    private byte[] run(byte[] input, String inputName, List<String> outputArgs) {
        Path dir;
        try {
            dir = Files.createTempDirectory("concourse-audio-");
        } catch (IOException e) {
            throw new AudioException("cannot create temp dir", e);
        }
        try {
            Path in = dir.resolve(inputName);
            Path out = dir.resolve("out.bin");
            Files.write(in, input);
            List<String> cmd = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", in.toString()));
            cmd.addAll(outputArgs);
            cmd.add(out.toString());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new AudioException("ffmpeg timed out");
            }
            if (p.exitValue() != 0) {
                throw new AudioException("ffmpeg exit " + p.exitValue() + ": " + log.strip());
            }
            return Files.readAllBytes(out);
        } catch (IOException e) {
            throw new AudioException("ffmpeg I/O failure (is ffmpeg installed? path=" + ffmpeg + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AudioException("interrupted while transcoding", e);
        } finally {
            deleteQuietly(dir);
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    public static class AudioException extends RuntimeException {
        public AudioException(String message) { super(message); }
        public AudioException(String message, Throwable cause) { super(message, cause); }
    }
}
