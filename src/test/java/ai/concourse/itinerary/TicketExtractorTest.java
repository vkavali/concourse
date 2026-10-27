package ai.concourse.itinerary;

import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** Spark is mocked; verifies the prompt/schema wiring and the snake_case → record mapping. */
class TicketExtractorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private TicketExtractor extractor;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        SparkClient spark = new SparkClient(server.url("/v1").toString(), "k", "muse-spark-1.3", false,
                Duration.ofSeconds(10), WebClient.builder(), mapper);
        extractor = new TicketExtractor(spark, new PromptLoader(mapper), mapper);
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void extractsItineraryFromPdf() throws Exception {
        String content = """
                {"is_ticket":true,"reason":"Qatar Airways e-ticket","passenger_first_name":"Lakshmi","booking_reference":"X1Y2Z3",
                 "segments":[{"airline":"Qatar Airways","flight_number":"QR4793","origin":"HYD","destination":"DOH",
                 "departure_local":"2026-11-28T03:45","arrival_local":"2026-11-28T06:05","terminal":null,"gate":null,"confidence":1.0,"notes":""},
                 {"airline":"Qatar Airways","flight_number":"QR729","origin":"DOH","destination":"DFW",
                 "departure_local":"2026-11-28T08:40","arrival_local":"2026-11-28T15:50","terminal":"D","gate":null,"confidence":0.95,"notes":"year inferred"}]}""";
        ObjectNode message = mapper.createObjectNode();
        message.put("content", content);
        ObjectNode choice = mapper.createObjectNode();
        choice.set("message", message);
        ObjectNode root = mapper.createObjectNode();
        root.putArray("choices").add(choice);
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(root)));

        Itinerary it = extractor.extract("%PDF-1.4".getBytes(StandardCharsets.US_ASCII), "application/pdf", "ticket.pdf");

        assertTrue(it.isTicket());
        assertEquals("Lakshmi", it.passengerFirstName());
        assertEquals(2, it.segments().size());
        assertEquals("QR4793", it.segments().get(0).flightNumber());
        assertEquals("2026-11-28T03:45", it.segments().get(0).departureLocal());
        assertNull(it.segments().get(0).terminal());
        assertEquals("D", it.segments().get(1).terminal());
        assertEquals(0.95, it.segments().get(1).confidence());

        JsonNode body = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertTrue(body.path("messages").path(0).path("content").asText().contains("Copy only what is printed"));
        assertEquals("file", body.path("messages").path(1).path("content").path(1).path("type").asText());
        assertEquals("itinerary", body.path("response_format").path("json_schema").path("name").asText());
        JsonNode schema = body.path("response_format").path("json_schema").path("schema");
        assertFalse(schema.path("additionalProperties").asBoolean());
        assertEquals("boolean", schema.path("properties").path("is_ticket").path("type").asText());
        assertEquals(0.0, body.path("temperature").asDouble());
    }

    @Test
    void supportsPdfAndImagesOnly() {
        assertTrue(extractor.supports("application/pdf"));
        assertTrue(extractor.supports("image/jpeg"));
        assertTrue(extractor.supports("IMAGE/PNG; charset=binary"));
        assertFalse(extractor.supports("audio/ogg; codecs=opus"));
        assertFalse(extractor.supports(null));
        assertThrows(IllegalArgumentException.class, () -> extractor.extract(new byte[1], "text/plain", "x.txt"));
    }
}
