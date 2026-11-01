package ai.concourse.voice;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyService;
import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkOpen;
import ai.concourse.stt.SttClient;
import ai.concourse.whatsapp.MessagingChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The open-weight classifier answers first; Spark 1.3 answers whenever it cannot. */
class SparkOpenIntentTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SparkClient spark = mock(SparkClient.class);
    private MockWebServer server;
    private ParentTurnService service;

    private static ConcourseProperties props(String openBaseUrl) {
        return new ConcourseProperties("http://x", "a", "salt", null,
                new ConcourseProperties.Meta("k", "http://meta", "muse-spark-1.3", "stt", true, 120,
                        openBaseUrl, "open-test-key", "meta-models/muse-spark-open-1026", 1024, "low"),
                null, null, null, null);
    }

    private SparkOpen open(String baseUrl) {
        DefaultListableBeanFactory empty = new DefaultListableBeanFactory();
        return new SparkOpen(props(baseUrl), WebClient.builder(), mapper,
                empty.getBeanProvider(ModelCallLog.class), empty.getBeanProvider(ModelTape.class));
    }

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        when(spark.model()).thenReturn("muse-spark-1.3");
        ParentMessenger messenger = mock(ParentMessenger.class);
        service = new ParentTurnService(mock(MessagingChannel.class), mock(AudioTranscoder.class), mock(SttClient.class), spark,
                new PromptLoader(mapper), mock(JourneyService.class), mock(JourneyController.class), messenger,
                new PhoneHasher(props("")), mock(EventLog.class), mapper);
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void offWithoutABaseUrl() {
        assertFalse(open("").enabled());
        assertFalse(open(null).enabled());
        assertTrue(open(server.url("/v1").toString()).enabled());
    }

    @Test
    void openWeightModelClassifiesFirst() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("""
                {"id":"o","model":"meta-models/muse-spark-open-1026","choices":[{"message":{"role":"assistant","content":
                 "{\\"intent\\":\\"WHERE_IS_GATE\\",\\"entity\\":null,\\"confidence\\":0.93,\\"tool_proposal\\":\\"get_next_stage\\",\\"reply_te\\":\\"మీ గేట్ కార్డులో ఉంది.\\",\\"english_gloss\\":\\"asks where the gate is\\"}"}}],
                 "usage":{"prompt_tokens":300,"completion_tokens":60}}"""));
        service.setSparkOpen(open(server.url("/v1").toString()));

        ParentTurn t = service.interpret("నా గేట్ ఎక్కడ", null);

        assertEquals("WHERE_IS_GATE", t.intent());
        assertEquals(0.93, t.confidence(), 1e-9);
        verify(spark, never()).completeJson(any(), eq(ParentTurn.class), any());
        RecordedRequest r = server.takeRequest();
        assertEquals("/v1/chat/completions", r.getPath());
        assertEquals("Bearer open-test-key", r.getHeader("Authorization"));
        JsonNode body = mapper.readTree(r.getBody().readUtf8());
        assertEquals("meta-models/muse-spark-open-1026", body.path("model").asText());
        assertEquals(1024, body.path("max_tokens").asInt());
        assertEquals("low", body.path("reasoning_effort").asText());
        assertFalse(body.path("stream").asBoolean(), "blocking on the partner deployment");
        assertEquals("parent_turn", body.path("response_format").path("json_schema").path("name").asText());
    }

    @Test
    void sparkOnePointThreeAnswersWhenTheOpenModelCannot() {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("""
                {"id":"o","model":"meta-models/muse-spark-open-1026","choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":300,"completion_tokens":1024}}"""));
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("""
                {"id":"o","model":"meta-models/muse-spark-open-1026","choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}],
                 "usage":{"prompt_tokens":300,"completion_tokens":1024}}"""));
        ParentTurn fallback = new ParentTurn("START_TRIP", null, 0.9, "none", "సరే.", "start");
        when(spark.completeJson(any(), eq(ParentTurn.class), any())).thenReturn(fallback);
        service.setSparkOpen(open(server.url("/v1").toString()));

        ParentTurn t = service.interpret("నా ప్రయాణం మొదలు", null);

        assertEquals("START_TRIP", t.intent());
        verify(spark).completeJson(any(), eq(ParentTurn.class), any());
    }
}
