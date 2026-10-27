package ai.concourse.spark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SparkClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    private SparkClient client(boolean stream) {
        return new SparkClient(server.url("/v1").toString(), "k", "muse-spark-1.3", stream, Duration.ofSeconds(10),
                WebClient.builder(), mapper);
    }

    @Test
    void blockingCallBuildsOpenAiShapedBodyWithImageAndSchema() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("""
                {"id":"x","model":"muse-spark-1.3","choices":[{"message":{"role":"assistant","content":"{\\"ok\\":true}"}}],
                 "usage":{"prompt_tokens":120,"completion_tokens":8}}"""));
        JsonNode schema = mapper.readTree("{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},\"required\":[\"ok\"],\"additionalProperties\":false}");
        SparkRequest req = new SparkRequest("sys", "look", List.of(
                new SparkRequest.Attachment("image/png", new byte[]{1, 2, 3}, "sign.png")),
                new SparkRequest.JsonSchema("obs", schema), 0.0);

        SparkClient.SparkResponse res = client(false).complete(req);

        assertEquals("{\"ok\":true}", res.content());
        assertEquals(120, res.promptTokens());
        RecordedRequest r = server.takeRequest();
        assertEquals("/v1/chat/completions", r.getPath());
        assertEquals("Bearer k", r.getHeader("Authorization"));
        JsonNode body = mapper.readTree(r.getBody().readUtf8());
        assertEquals("muse-spark-1.3", body.path("model").asText());
        assertFalse(body.path("stream").asBoolean());
        assertEquals(0.0, body.path("temperature").asDouble());
        assertEquals("system", body.path("messages").path(0).path("role").asText());
        JsonNode parts = body.path("messages").path(1).path("content");
        assertEquals("text", parts.path(0).path("type").asText());
        assertEquals("image_url", parts.path(1).path("type").asText());
        assertTrue(parts.path(1).path("image_url").path("url").asText().startsWith("data:image/png;base64,AQID"));
        assertEquals("json_schema", body.path("response_format").path("type").asText());
        assertEquals("obs", body.path("response_format").path("json_schema").path("name").asText());
        assertEquals("object", body.path("response_format").path("json_schema").path("schema").path("type").asText());
    }

    @Test
    void pdfGoesAsFilePart() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
        client(false).complete(SparkRequest.text("s", "u").withAttachments(List.of(
                new SparkRequest.Attachment("application/pdf", "%PDF".getBytes(StandardCharsets.US_ASCII), "ticket.pdf"))));
        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        JsonNode file = body.path("messages").path(1).path("content").path(1);
        assertEquals("file", file.path("type").asText());
        assertEquals("ticket.pdf", file.path("file").path("filename").asText());
        assertTrue(file.path("file").path("file_data").asText().startsWith("data:application/pdf;base64,"));
        assertTrue(body.path("response_format").isMissingNode());
    }

    @Test
    void streamingAggregatesDeltasAndUsage() throws Exception {
        String sse = """
                data: {"id":"c","model":"muse-spark-1.3","choices":[{"delta":{"role":"assistant","content":""}}]}

                data: {"choices":[{"delta":{"content":"{\\"a\\":"}}]}

                data: {"choices":[{"delta":{"content":"1}"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":50,"completion_tokens":4}}

                data: [DONE]

                """;
        server.enqueue(new MockResponse().addHeader("Content-Type", "text/event-stream").setBody(sse));
        SparkClient.SparkResponse res = client(true).complete(SparkRequest.text("s", "u"));
        assertEquals("{\"a\":1}", res.content());
        assertEquals(50, res.promptTokens());
        assertEquals(4, res.completionTokens());
        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertTrue(body.path("stream").asBoolean());
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
    }

    @Test
    void streamingRejectedFallsBackToBlocking() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"stream not supported\"}"));
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"plain\"}}]}"));
        SparkClient.SparkResponse res = client(true).complete(SparkRequest.text("s", "u"));
        assertEquals("plain", res.content());
        server.takeRequest();
        JsonNode second = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertFalse(second.path("stream").asBoolean());
        assertTrue(second.path("stream_options").isMissingNode());
    }

    @Test
    void retriesOnceOnServerError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("busy"));
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"second\"}}]}"));
        assertEquals("second", client(false).complete(SparkRequest.text("s", "u")).content());
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void completeJsonParsesIntoRecord() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"name\\\":\\\"B12\\\",\\\"n\\\":2}\"}}]}"));
        record Out(String name, int n) {}
        JsonNode schema = mapper.readTree("{\"type\":\"object\"}");
        Out out = client(false).completeJson(SparkRequest.text("s", "u").withSchema(new SparkRequest.JsonSchema("o", schema)),
                Out.class, mapper);
        assertEquals("B12", out.name());
        assertEquals(2, out.n());
    }
}
