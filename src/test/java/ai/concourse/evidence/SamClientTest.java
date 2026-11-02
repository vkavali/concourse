package ai.concourse.evidence;

import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** SAM 3.1 on the Responses API: streamed special-token text in, one outlined sign out — and never an exception. */
class SamClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    @TempDir Path tapeDir;

    private static final String FRAME = "<0f>0 <|box;x1=211;y1=228;x2=270;y2=254;w=320;h=334;c=0.62|> <|mask;x=0;y=0;c=0.62;data=27,60,!abc~def|>, "
            + "1 <|box;x1=40;y1=30;x2=300;y2=120;w=320;h=334;c=0.91|> <|mask;x=0;y=0;c=0.91;data=90,260,!zzz|>";

    private static String sse(String... deltas) {
        StringBuilder sb = new StringBuilder();
        for (String d : deltas) {
            sb.append("event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":")
              .append('"').append(d.replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}\n\n");
        }
        sb.append("event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"r\"}}\n\n");
        return sb.toString();
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    private SamClient client(boolean enabled) {
        return new SamClient(server.url("/v1").toString(), "k", "sam-3.1", "sign", enabled, Duration.ofSeconds(5), WebClient.builder(), mapper);
    }

    @Test
    void parsesBoxesByKeyAndKeepsTheFirstFrameOnly() {
        List<SamClient.Box> boxes = SamClient.parse(FRAME + "\n<1f>0 <|box;x1=1;y1=1;x2=9;y2=9;w=320;h=334|>");
        assertEquals(2, boxes.size());
        assertEquals(211, boxes.get(0).x1());
        assertEquals(0.62, boxes.get(0).confidence(), 1e-9);
        assertEquals(List.of(), SamClient.parse(null));
        assertEquals(List.of(), SamClient.parse("<0f>0 <|box;x1=oops|>"));
    }

    @Test
    void theMostConfidentSignWinsAndIsNormalisedToTheFrame() {
        SamClient.Box b = SamClient.best(SamClient.parse(FRAME)).orElseThrow();
        assertEquals(40, b.x1());
        assertEquals(0.125, b.normalised().get("x"), 1e-9);   // 40 / 320
        assertEquals(0.813, b.normalised().get("w"), 1e-9);   // 260 / 320, three decimals
        assertEquals(0.91, b.normalised().get("c"), 1e-9);
        assertTrue(SamClient.best(List.of()).isEmpty());
        // without confidences, the largest box
        SamClient.Box big = SamClient.best(SamClient.parse("<0f>0 <|box;x1=0;y1=0;x2=10;y2=10;w=100;h=100|>, 1 <|box;x1=0;y1=0;x2=50;y2=50;w=100;h=100|>")).orElseThrow();
        assertEquals(50, big.x2());
    }

    @Test
    void asksTheResponsesApiForTheSignWithTheImageInlineAndReadsTheStream() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "text/event-stream").setBody(sse("<0f>0 <|box;x1=40;y1=30;", "x2=300;y2=120;w=320;h=334;c=0.91|> <|mask;x=0;y=0;data=9,9,!q|>")));
        ModelCallLog calls = new ModelCallLog();
        SamClient c = client(true);
        c.setCallLog(calls);

        Optional<SamClient.Box> box = c.locateSign(new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3}, "image/jpeg");

        assertEquals(40, box.orElseThrow().x1());
        RecordedRequest r = server.takeRequest();
        assertEquals("/v1/responses", r.getPath());
        assertEquals("Bearer k", r.getHeader("Authorization"));
        JsonNode body = mapper.readTree(r.getBody().readUtf8());
        assertEquals("sam-3.1", body.path("model").asText());
        assertTrue(body.path("stream").asBoolean());
        assertEquals("true", body.path("metadata").path("include_confidence").asText());
        JsonNode content = body.path("input").path(0).path("content");
        assertEquals("input_text", content.path(0).path("type").asText());
        assertEquals("sign", content.path(0).path("text").asText());
        assertTrue(content.path(1).path("image_url").asText().startsWith("data:image/jpeg;base64,/9gB"));
        ModelCallLog.Entry e = calls.since(0).get(0);
        assertEquals("sam-3.1", e.model());
        assertTrue(e.ok());
        assertTrue(e.note().contains("1 sign found"));
    }

    @Test
    void failuresYieldNoBoxAndThreeInARowGoQuiet() {
        for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        SamClient c = client(true);
        for (int i = 0; i < 3; i++) assertTrue(c.locateSign(new byte[]{1, 2, 3}, "image/jpeg").isEmpty());
        assertEquals(3, server.getRequestCount());
        assertTrue(c.locateSign(new byte[]{1, 2, 3}, "image/jpeg").isEmpty());
        assertEquals(3, server.getRequestCount(), "quiet after three failures: no fourth call");
        assertFalse(client(false).isConfigured());
        assertTrue(client(false).locateSign(new byte[]{1}, "image/jpeg").isEmpty());
    }

    @Test
    void recordsOnTheTapeAndReplaysWithoutAKey() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "text/event-stream").setBody(sse(FRAME)));
        SamClient live = client(true);
        live.setTape(new ModelTape("record", tapeDir.toString(), mapper));
        byte[] photo = {9, 8, 7};
        assertEquals(40, live.locateSign(photo, "image/jpeg").orElseThrow().x1());
        assertEquals(1, Files.list(tapeDir.resolve("sam")).count());

        SamClient judge = new SamClient(server.url("/v1").toString(), "", "sam-3.1", "sign", true, Duration.ofSeconds(5), WebClient.builder(), mapper);
        judge.setTape(new ModelTape("replay", tapeDir.toString(), mapper));
        assertEquals(40, judge.locateSign(photo, "image/jpeg").orElseThrow().x1());
        assertTrue(judge.locateSign(new byte[]{1}, "image/jpeg").isEmpty(), "not on tape, no key: no box, no error");
        assertEquals(1, server.getRequestCount());
    }
}
