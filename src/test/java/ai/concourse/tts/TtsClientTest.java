package ai.concourse.tts;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.audio.Wav;
import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TtsClientTest {
    private MockWebServer server;
    private TtsClient client;
    private final List<byte[]> encoded = new ArrayList<>();
    @TempDir Path cache;
    @TempDir Path tapeDir;

    /** Stands in for ffmpeg: hands the joined WAV straight back so the test can measure it. */
    private final AudioTranscoder noFfmpeg = new AudioTranscoder("ffmpeg") {
        @Override public byte[] toOggOpusVoiceNote(byte[] wav) { encoded.add(wav); return wav; }
    };

    private static MockResponse wav(int ms) {
        byte[] pcm = new byte[2 * 16_000 * ms / 1000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 7);
        return new MockResponse().addHeader("Content-Type", "audio/wav").setBody(new Buffer().write(Wav.write(16_000, pcm)));
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new TtsClient(server.url("/").toString(), "t", cache.toString(), 7, "facebook/seamless-m4t-v2-large",
                WebClient.builder(), noFfmpeg);
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void synthesisesPhraseByPhraseAndJoinsThemWithOurPauses() throws Exception {
        for (int i = 0; i < 3; i++) server.enqueue(wav(100));

        byte[] out = client.synthesizeOggOpus("మీ విమానం. గేట్, బీ పన్నెండు.");

        Wav.Pcm pcm = Wav.parse(out);
        assertEquals(300 + Phrases.SENTENCE_MS + Phrases.COMMA_MS, pcm.durationMs());
        assertEquals(3, server.getRequestCount());
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RecordedRequest r = server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/tts", r.getPath());
            assertEquals("t", r.getHeader("X-Concourse-Token"));
            bodies.add(r.getBody().readUtf8());
        }
        assertTrue(bodies.stream().allMatch(b -> b.contains("\"format\":\"wav\"") && b.contains("\"speaker\":7") && b.contains("\"rate\":0.7")), bodies.toString());
        assertTrue(bodies.stream().anyMatch(b -> b.contains("\"text\":\"బీ పన్నెండు.\"")), "the code is its own phrase");
        assertTrue(bodies.stream().anyMatch(b -> b.contains("\"text\":\"గేట్\"")));
    }

    @Test
    void phrasesAndWholeNotesAreCached() {
        for (int i = 0; i < 4; i++) server.enqueue(wav(100));
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
        server.enqueue(new MockResponse().addHeader("Content-Type", "audio/ogg").setBody(new Buffer().write(ogg)));

        byte[] out = client.synthesizeOggOpus("మీ విమానం. కార్డు చూడండి.");

        assertArrayEquals(ogg, out);
        assertEquals(3, server.getRequestCount());
        assertTrue(encoded.isEmpty(), "the fallback is already OGG; nothing to encode");
    }

    @Test
    void recordsEveryNoteOnTheTapeAndReplaysItWithoutAServer() throws Exception {
        ModelTape recorder = new ModelTape("record", tapeDir.toString(), new ObjectMapper());
        ModelCallLog calls = new ModelCallLog();
        client.setTape(recorder);
        client.setCallLog(calls);
        for (int i = 0; i < 2; i++) server.enqueue(wav(100));
        byte[] live = client.synthesizeOggOpus("మీ విమానం. కార్డు చూడండి.");
        assertEquals(1, Files.list(tapeDir.resolve("tts")).count(), "the whole note is copied to the tape");
        assertTrue(calls.since(0).get(0).model().startsWith("facebook/"), "the call log names the Meta model");

        // a judge: no TTS_BASE_URL, no cache, only the tape
        TtsClient judge = new TtsClient(null, null, null, 7, "facebook/seamless-m4t-v2-large", WebClient.builder(), noFfmpeg);
        judge.setTape(new ModelTape("replay", tapeDir.toString(), new ObjectMapper()));
        judge.setCallLog(calls);
        assertTrue(judge.isConfigured(), "recordings count as a voice");
        assertArrayEquals(live, judge.synthesizeOggOpus("మీ విమానం. కార్డు చూడండి."));
        ModelCallLog.Entry replay = calls.since(0).get(calls.since(0).size() - 1);
        assertEquals("replayed from tape (facebook/seamless-m4t-v2-large)", replay.note(), "the sidecar names the model that made the note");
        assertEquals("facebook/seamless-m4t-v2-large", replay.model());
        assertEquals(java.util.Set.of("facebook/seamless-m4t-v2-large"), judge.tapeModels());
        String side = Files.readString(Files.list(tapeDir.resolve("tts")).filter(f -> f.toString().endsWith(".json")).findFirst().orElseThrow());
        assertTrue(side.contains("\"model\" : \"facebook/seamless-m4t-v2-large\"") && side.contains("\"source\" : \"live TTS server\""), side);
        TtsClient.TtsException miss = assertThrows(TtsClient.TtsException.class, () -> judge.synthesizeOggOpus("కొత్త వాక్యం."));
        assertTrue(miss.getMessage().contains("not in the cache or on the tape"));
    }

    @Test
    void recordingWithoutAServerLeavesTheSpokenTextForALaterSynthesisPass() throws Exception {
        TtsClient none = new TtsClient("", null, null, 7, "facebook/mms-tts-tel", WebClient.builder(), noFfmpeg);
        none.setTape(new ModelTape("record", tapeDir.toString(), new ObjectMapper()));
        assertThrows(TtsClient.TtsException.class, () -> none.synthesizeOggOpus("మీ గేట్ డి పద్నాలుగు."));
        String key = TtsClient.key("note|మీ గేట్ డి పద్నాలుగు.", TtsClient.DEFAULT_RATE);
        Path wanted = tapeDir.resolve("tts").resolve(key + ".txt");
        assertEquals("మీ గేట్ డి పద్నాలుగు.\n", Files.readString(wanted));
        // replaying never writes anything into the judge's checkout
        TtsClient judge = new TtsClient("", null, null, 7, "facebook/mms-tts-tel", WebClient.builder(), noFfmpeg);
        judge.setTape(new ModelTape("replay", tapeDir.toString(), new ObjectMapper()));
        assertThrows(TtsClient.TtsException.class, () -> judge.synthesizeOggOpus("ఇంకో వాక్యం."));
        assertEquals(1, Files.list(tapeDir.resolve("tts")).count());
    }

    @Test
    void aNoteSynthesizedByThePrerenderPassReportsItsOwnModel() throws Exception {
        Path tts = Files.createDirectories(tapeDir.resolve("tts"));
        String key = TtsClient.key("note|కార్డు చూడండి.", TtsClient.DEFAULT_RATE);
        Files.write(tts.resolve(key + ".ogg"), new byte[]{'O', 'g', 'g', 'S', 1, 2, 3});
        Files.writeString(tts.resolve(key + ".json"), "{\"model\":\"facebook/mms-tts-tel\",\"source\":\"eval/judge-path/prerender_voice.py\"}");
        Files.write(tts.resolve("orphan.ogg"), new byte[]{'O', 'g', 'g', 'S'});
        TtsClient judge = new TtsClient("", null, null, 7, "facebook/seamless-m4t-v2-large", WebClient.builder(), noFfmpeg);
        judge.setTape(new ModelTape("replay", tapeDir.toString(), new ObjectMapper()));
        ModelCallLog calls = new ModelCallLog();
        judge.setCallLog(calls);
        assertEquals(7, judge.synthesizeOggOpus("కార్డు చూడండి.").length);
        ModelCallLog.Entry e = calls.since(0).get(0);
        assertEquals("facebook/mms-tts-tel", e.model(), "never the configured label for a note another model made");
        assertEquals("replayed from tape (facebook/mms-tts-tel)", e.note());
        assertEquals(java.util.Set.of("facebook/mms-tts-tel"), judge.tapeModels());
    }

    @Test
    void withoutAServerOrRecordingsThereIsNoVoice() {
        TtsClient none = new TtsClient("", null, null, WebClient.builder(), noFfmpeg);
        assertFalse(none.isConfigured());
        assertEquals("facebook/seamless-m4t-v2-large", none.modelId());
    }
}
