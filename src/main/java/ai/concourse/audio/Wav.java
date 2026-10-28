package ai.concourse.audio;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Minimal RIFF/WAVE handling for 16-bit PCM mono: parse, join with silence, write. No resampling. */
public final class Wav {
    private Wav() {}

    public record Pcm(int sampleRate, byte[] data) {
        public long durationMs() { return data.length * 1000L / (2L * sampleRate); }
    }

    /** Parses a 16-bit PCM mono WAV; tolerates a wrong data-chunk size (streamed writers leave it 0 or 0xFFFFFFFF). */
    public static Pcm parse(byte[] wav) {
        if (wav == null || wav.length < 44 || !"RIFF".equals(ascii(wav, 0)) || !"WAVE".equals(ascii(wav, 8))) {
            throw new IllegalArgumentException("not a RIFF/WAVE file");
        }
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        int sampleRate = -1, channels = -1, bits = -1;
        while (pos + 8 <= wav.length) {
            String id = ascii(wav, pos);
            long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if ("fmt ".equals(id)) {
                channels = b.getShort(body + 2);
                sampleRate = b.getInt(body + 4);
                bits = b.getShort(body + 14);
            } else if ("data".equals(id)) {
                if (sampleRate <= 0) throw new IllegalArgumentException("WAV data before fmt");
                if (channels != 1 || bits != 16) throw new IllegalArgumentException("expected 16-bit mono PCM, got " + bits + "-bit x" + channels);
                long avail = wav.length - body;
                int len = (int) ((size == 0 || size > avail) ? avail : size);
                len -= len % 2;
                byte[] data = new byte[len];
                System.arraycopy(wav, body, data, 0, len);
                return new Pcm(sampleRate, data);
            }
            pos = body + (int) size + (int) (size & 1); // chunks are word-aligned
        }
        throw new IllegalArgumentException("WAV without a data chunk");
    }

    /** Joins clips in order, inserting {@code pausesMs[i]} of silence after clip i. All clips must share a sample rate. */
    public static byte[] join(List<Pcm> clips, List<Integer> pausesMs) {
        if (clips.isEmpty()) throw new IllegalArgumentException("nothing to join");
        int rate = clips.get(0).sampleRate();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int i = 0; i < clips.size(); i++) {
            Pcm c = clips.get(i);
            if (c.sampleRate() != rate) throw new IllegalArgumentException("sample rate mismatch: " + c.sampleRate() + " vs " + rate);
            data.writeBytes(c.data());
            int pause = i < pausesMs.size() ? pausesMs.get(i) : 0;
            if (pause > 0) data.writeBytes(new byte[2 * (int) (rate * (long) pause / 1000)]);
        }
        return write(rate, data.toByteArray());
    }

    /** 16-bit mono PCM → WAV bytes with a correct 44-byte header. */
    public static byte[] write(int sampleRate, byte[] pcm) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    private static String ascii(byte[] b, int at) {
        return new String(b, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
