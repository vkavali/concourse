package ai.concourse.audio;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Needs ffmpeg on PATH; skipped otherwise. */
class AudioTranscoderTest {
    private static boolean ffmpegPresent;

    @BeforeAll
    static void detectFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            ffmpegPresent = p.waitFor() == 0;
        } catch (Exception e) {
            ffmpegPresent = false;
        }
    }

    @Test
    void wavToOggOpusAndBackToWav16k() {
        assumeTrue(ffmpegPresent, "ffmpeg not installed");
        AudioTranscoder t = new AudioTranscoder("ffmpeg");
        byte[] wav44k = sineWav(44_100, 1, 1.0);

        byte[] ogg = t.toOggOpusVoiceNote(wav44k);
        assertEquals("OggS", new String(ogg, 0, 4, StandardCharsets.US_ASCII));
        assertTrue(ogg.length < 20_000, "1 s at 32 kbps should be a few KB, was " + ogg.length);

        byte[] wav16 = t.toWav16kMono(ogg);
        assertEquals("RIFF", new String(wav16, 0, 4, StandardCharsets.US_ASCII));
        ByteBuffer bb = ByteBuffer.wrap(wav16).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(1, bb.getShort(22), "mono");
        assertEquals(16_000, bb.getInt(24), "16 kHz");
        assertEquals(16, bb.getShort(34), "16-bit");
        long ms = AudioTranscoder.wavDurationMs(wav16);
        assertTrue(ms > 900 && ms < 1100, "about 1 s, was " + ms);
    }

    @Test
    void garbageInputFails() {
        assumeTrue(ffmpegPresent, "ffmpeg not installed");
        AudioTranscoder t = new AudioTranscoder("ffmpeg");
        assertThrows(AudioTranscoder.AudioException.class, () -> t.toWav16kMono("not audio".getBytes(StandardCharsets.UTF_8)));
    }

    /** Canonical 44-byte-header PCM WAV with a 440 Hz tone. */
    static byte[] sineWav(int rate, int channels, double seconds) {
        int frames = (int) (rate * seconds);
        int dataLen = frames * channels * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataLen).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        h.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataLen);
        out.writeBytes(h.array());
        ByteBuffer d = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 12000);
            for (int c = 0; c < channels; c++) d.putShort(v);
        }
        out.writeBytes(d.array());
        return out.toByteArray();
    }
}
