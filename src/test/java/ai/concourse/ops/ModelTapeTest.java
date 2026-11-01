package ai.concourse.ops;

import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.stt.SttClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The judge path: a run recorded with real keys replays, answer for answer, on a machine with none —
 * and every replay says so in the model-call log.
 */
class ModelTapeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    @TempDir Path dir;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void sparkAnswersAreRecordedByInputAndReplayedWithoutAKey() throws Exception {
        ModelCallLog calls = new ModelCallLog();
        SparkClient live = new SparkClient(server.url("/v1").toString(), "k", "muse-spark-1.3", false, Duration.ofSeconds(10), WebClient.builder(), mapper);
        live.setTape(new ModelTape("record", dir.toString(), mapper));
        live.setCallLog(calls);
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("""
                {"id":"x","model":"muse-spark-1.3","choices":[{"message":{"role":"assistant","content":"{\\"intent\\":\\"WHERE_IS_GATE\\"}"}}],
                 "usage":{"prompt_tokens":100,"completion_tokens":9}}"""));
        JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"intent\":{\"type\":\"string\"}},\"required\":[\"intent\"],\"additionalProperties\":false}");
        SparkRequest monday = new SparkRequest("sys", "transcript: గేట్ ఎక్కడ\njourney: {\"date\":\"2026-11-02\"}", List.of(),
                new SparkRequest.JsonSchema("parent_turn", schema), 0.2, ModelTape.sha256("గేట్ ఎక్కడ"));

        assertEquals("{\"intent\":\"WHERE_IS_GATE\"}", live.complete(monday).content());
        assertEquals(1, server.getRequestCount());
        assertEquals(1, Files.list(dir.resolve("spark")).count());
        String recording = Files.readString(Files.list(dir.resolve("spark")).findFirst().orElseThrow(), StandardCharsets.UTF_8);
        assertFalse(recording.contains("గేట్ ఎక్కడ"), "the tape holds the answer and the numbers, never her words");
        assertTrue(recording.contains("\"op\" : \"parent_turn\""));

        // a judge, days later: no key, the journey's dates differ, the same words
        SparkClient judge = new SparkClient(server.url("/v1").toString(), "", "muse-spark-1.3", false, Duration.ofSeconds(10), WebClient.builder(), mapper);
        judge.setTape(new ModelTape("replay", dir.toString(), mapper));
        judge.setCallLog(calls);
        SparkRequest thursday = new SparkRequest(monday.system(), "transcript: గేట్ ఎక్కడ\njourney: {\"date\":\"2026-11-05\"}", monday.attachments(),
                monday.schema(), monday.temperature(), ModelTape.sha256("గేట్ ఎక్కడ"));
        SparkClient.SparkResponse replayed = judge.complete(thursday);
        assertEquals("{\"intent\":\"WHERE_IS_GATE\"}", replayed.content());
        assertEquals("muse-spark-1.3 (recording)", replayed.model());
        assertEquals(1, server.getRequestCount(), "no call was made");
        ModelCallLog.Entry last = calls.since(0).get(calls.since(0).size() - 1);
        assertEquals("replayed from tape", last.note());
        assertEquals("parent_turn", last.op());

        SparkClient.SparkException miss = assertThrows(SparkClient.SparkException.class,
                () -> judge.complete(thursday.withTapeKey(ModelTape.sha256("వేరే మాట"))));
        assertTrue(miss.getMessage().contains("no MODEL_API_KEY and no recording"));
    }

    @Test
    void transcriptsAreRecordedByAudioBytesAndReplayed() throws Exception {
        ModelCallLog calls = new ModelCallLog();
        SttClient live = new SttClient(server.url("/v1").toString(), "k", "muse-voice-transcribe-1.0", Duration.ofSeconds(10), WebClient.builder(), mapper);
        live.setTape(new ModelTape("record", dir.toString(), mapper));
        live.setCallLog(calls);
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("""
                {"sessionId":"s-1","transcript":"నా గేట్ ఎక్కడ ఉంది","audioDurationMs":3200,"turns":[]}"""));
        byte[] wav = "RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII);
        assertEquals("నా గేట్ ఎక్కడ ఉంది", live.transcribeWav(wav, List.of("Telugu")).text());

        SttClient judge = new SttClient(server.url("/v1").toString(), "", "muse-voice-transcribe-1.0", Duration.ofSeconds(10), WebClient.builder(), mapper);
        judge.setTape(new ModelTape("replay", dir.toString(), mapper));
        judge.setCallLog(calls);
        SttClient.Transcript t = judge.transcribeWav(wav, List.of("Telugu"));
        assertEquals("నా గేట్ ఎక్కడ ఉంది", t.text());
        assertEquals(3200, t.audioDurationMs());
        assertEquals(1, server.getRequestCount());
        assertTrue(calls.since(0).stream().anyMatch(e -> e.model().equals("muse-voice-transcribe-1.0") && e.note().equals("replayed from tape")));
        assertThrows(SttClient.SttException.class, () -> judge.transcribeWav(new byte[]{1, 2, 3}, List.of("Telugu")));
    }

    @Test
    void inputsAreSavedOnlyWhileRecording() {
        ModelTape off = new ModelTape("off", dir.toString(), mapper);
        assertTrue(off.saveInput("voice", new byte[]{1}, "wav").isEmpty());
        ModelTape rec = new ModelTape("record", dir.toString(), mapper);
        String name = rec.saveInput("voice", new byte[]{1, 2}, "wav").orElseThrow();
        assertTrue(name.endsWith("-voice.wav"));
        assertTrue(Files.isRegularFile(dir.resolve("inputs").resolve(name)));
        assertEquals(ModelTape.Mode.OFF, new ModelTape("nonsense", dir.toString(), mapper).mode());
    }
}
