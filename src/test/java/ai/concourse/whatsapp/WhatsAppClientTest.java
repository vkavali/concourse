package ai.concourse.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppClientTest {
    private MockWebServer server;
    private WhatsAppClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new WhatsAppClient(server.url("/v21.0").toString(), "123", "tok", WebClient.builder());
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void sendsVoiceNoteWithVoiceFlag() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"messages\":[{\"id\":\"wamid.out\"}]}").addHeader("Content-Type", "application/json"));
        String id = client.sendVoiceNote("919876543210", "media-1");
        assertEquals("wamid.out", id);
        RecordedRequest req = server.takeRequest();
        assertEquals("/v21.0/123/messages", req.getPath());
        assertEquals("Bearer tok", req.getHeader("Authorization"));
        JsonNode body = mapper.readTree(req.getBody().readUtf8());
        assertEquals("audio", body.path("type").asText());
        assertTrue(body.path("audio").path("voice").asBoolean());
        assertEquals("media-1", body.path("audio").path("id").asText());
    }

    @Test
    void sendsUpToThreeButtons() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"messages\":[{\"id\":\"wamid.b\"}]}").addHeader("Content-Type", "application/json"));
        client.sendButtons("919876543210", "Which one?", List.of(
                new WhatsAppClient.Button("gate", "Gate"), new WhatsAppClient.Button("help", "Help")));
        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("interactive", body.path("type").asText());
        assertEquals(2, body.path("interactive").path("action").path("buttons").size());
        assertThrows(IllegalArgumentException.class, () -> client.sendButtons("1", "x", List.of(
                new WhatsAppClient.Button("a", "A"), new WhatsAppClient.Button("b", "B"),
                new WhatsAppClient.Button("c", "C"), new WhatsAppClient.Button("d", "D"))));
    }

    @Test
    void sendsTemplateWithBodyParams() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"messages\":[{\"id\":\"wamid.t\"}]}").addHeader("Content-Type", "application/json"));
        client.sendTemplate("919876543210", "journey_update", "te", List.of("Gate B12"));
        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertEquals("template", body.path("type").asText());
        assertEquals("journey_update", body.path("template").path("name").asText());
        assertEquals("Gate B12", body.path("template").path("components").path(0).path("parameters").path(0).path("text").asText());
    }
}
