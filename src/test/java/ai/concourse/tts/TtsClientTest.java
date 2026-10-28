package ai.concourse.tts;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.audio.Wav;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TtsClientTest {
    private static final String API_KEY = "test";                 // never a real key: the repo is public and secret-scanned
    private static final String VOICE = "te-IN-Chirp3-HD-Aoede";
    private MockWebServer server;
    private TtsClient client;
    private final List<byte[]> encoded = new ArrayList<>();
    @TempDir Path cache;

    /** Stands in for ffmpeg: hands the joined WAV straight back so the test can measure it. */
    private final AudioTranscoder noFfmpeg = new AudioTranscoder("ffmpeg") {
        @Override public byte[] toOggOpusVoiceNote(byte[] wav) { encoded.add(wav); return wav; }
    };

    private static String audioContent(int ms) {
        byte[] pcm = new byte[2 * 24_000 * ms / 1000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 7);
        return "{\"audioContent\":\"" + Base64.getEncoder().encodeToString(Wav.write(24_000, pcm)) + "\"}";
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new TtsClient(null, null, cache.toString(), "google", API_KEY, VOICE, 0,
                WebClient.builder(), noFfmpeg, server.url("/v1").toString());
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void synthesisesPhraseByPhraseAndJoinsThemWithOurPauses() throws Exception {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody(audioContent(100)));

        byte[] out = client.synthesizeOggOpus("మీ విమానం. గేట్, బీ పన్నెండు.");

        Wav.Pcm pcm = Wav.parse(out);
        assertEquals(300 + Phrases.SENTENCE_MS + Phrases.COMMA_MS, pcm.durationMs());
        assertEquals(3, server.getRequestCount());
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RecordedRequest r = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/v1/text:synthesize", r.getPath());
            assertEquals(API_KEY, r.getHeader("X-Goog-Api-Key"));
            bodies.add(r.getBody().readUtf8());
        }
        assertTrue(bodies.stream().allMatch(b -> b.contains("\"audioEncoding\":\"LINEAR16\"") && b.contains("\"sampleRateHertz\":24000")
                && b.contains("\"name\":\"" + VOICE + "\"") && b.contains("\"speakingRate\":0.85")), bodies.toString());
        assertTrue(bodies.stream().anyMatch(b -> b.contains("\"text\":\"బీ పన్నెండు.\"")), "the code is its own phrase");
        assertTrue(bodies.stream().anyMatch(b -> b.contains("\"text\":\"గేట్\"")));
    }

    @Test
    void phrasesAndWholeNotesAreCached() {
        for (int i = 0; i < 4; i++) server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody(audioContent(100)));
        client.synthesizeOggOpus("మీ విమానం. గేట్, బీ పన్నెండు.");
        assertEquals(3, server.getRequestCount());
        client.synthesizeOggOpus("మీ విమానం. గేట్, బీ పన్నెండు.");
        assertEquals(3, server.getRequestCount(), "same note: no synthesis at all");
        client.synthesizeOggOpus("మళ్ళీ చెబుతున్నాను: గేట్, బీ పన్నెండు.");
        assertEquals(4, server.getRequestCount(), "only the one new phrase is synthesised");
    }

    @Test
    void fallsBackToOneCallWhenAPhraseFails() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        byte[] ogg = new byte[200];
        ogg[0] = 'O'; ogg[1] = 'g'; ogg[2] = 'g'; ogg[3] = 'S';
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"audioContent\":\"" + Base64.getEncoder().encodeToString(ogg) + "\"}"));

        byte[] out = client.synthesizeOggOpus("మీ విమానం. కార్డు చూడండి.");

        assertArrayEquals(ogg, out);
        assertEquals(3, server.getRequestCount());
        assertTrue(encoded.isEmpty(), "the fallback is already OGG; nothing to encode");
    }
}
