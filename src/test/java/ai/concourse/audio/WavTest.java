package ai.concourse.audio;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WavTest {
    private static byte[] tone(int sampleRate, int ms) {
        byte[] pcm = new byte[2 * sampleRate * ms / 1000];
        ByteBuffer b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < pcm.length / 2; i++) b.putShort((short) (8000 * Math.sin(i * 0.1)));
        return Wav.write(sampleRate, pcm);
    }

    @Test
    void joinInsertsExactSilences() {
        Wav.Pcm a = Wav.parse(tone(24_000, 100));
        Wav.Pcm b = Wav.parse(tone(24_000, 50));
        assertEquals(100, a.durationMs());
        byte[] joined = Wav.join(List.of(a, b), List.of(650, 0));
        Wav.Pcm j = Wav.parse(joined);
        assertEquals(24_000, j.sampleRate());
        assertEquals(800, j.durationMs());
        // the gap is digital silence
        int gapStart = 2 * 24_000 * 100 / 1000, gapEnd = gapStart + 2 * 24_000 * 650 / 1000;
        for (int i = gapStart; i < gapEnd; i++) assertEquals(0, j.data()[i]);
    }

    @Test
    void parseToleratesAStreamedHeaderWithZeroDataSize() {
        byte[] wav = tone(16_000, 20);
        ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0); // data size unknown, as streamed writers leave it
        assertEquals(20, Wav.parse(wav).durationMs());
    }

    @Test
    void rejectsWhatItCannotJoin() {
        assertThrows(IllegalArgumentException.class, () -> Wav.parse("not audio at all, just text".getBytes()));
        assertThrows(IllegalArgumentException.class,
                () -> Wav.join(List.of(Wav.parse(tone(16_000, 10)), Wav.parse(tone(24_000, 10))), List.of(0, 0)));
    }
}
