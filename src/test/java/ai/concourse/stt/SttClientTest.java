package ai.concourse.stt;

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

class SttClientTest {
    private MockWebServer server;
    private SttClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new SttClient(server.url("/v1").toString(), "key-1", "muse-voice-transcribe-1.0",
                Duration.ofSeconds(10), WebClient.builder());
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void sendsMultipartRequestAndAudioParts() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody("""
                {"sessionId":"s-1","transcript":"నా గేట్ ఎక్కడ ఉంది","audioDurationMs":3200,"turns":[]}"""));
        byte[] wav = "RIFF....WAVEfmt ".getBytes(StandardCharsets.US_ASCII);

        SttClient.Transcript t = client.transcribeWav(wav, List.of("Telugu", "English"));

        assertEquals("s-1", t.sessionId());
        assertEquals("నా గేట్ ఎక్కడ ఉంది", t.text());
        assertEquals(3200, t.audioDurationMs());
        assertFalse(t.isEmpty());

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertTrue(req.getPath().startsWith("/v1/asr/transcribe?sessionId="), req.getPath());
        assertEquals("Bearer key-1", req.getHeader("Authorization"));
        assertTrue(req.getHeader("Content-Type").startsWith("multipart/form-data"));
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("name=\"request\""), "request part");
        assertTrue(body.contains("\"model\":\"muse-voice-transcribe-1.0\""));
        assertTrue(body.contains("\"audioEncoding\":\"WAV\""));
        assertTrue(body.contains("\"languageBias\":[\"Telugu\",\"English\"]"));
        assertTrue(body.contains("\"keywords\":["));
        assertTrue(body.contains("name=\"audio\""), "audio part");
        assertTrue(body.contains("filename=\"audio.wav\""));
        assertTrue(body.contains("RIFF....WAVE"));
    }

    @Test
    void surfacesApiErrorsWithStatus() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("{\"error\":\"audio exceeds 10 minutes\"}"));
        SttClient.SttException e = assertThrows(SttClient.SttException.class,
                () -> client.transcribeWav(new byte[100], List.of("Telugu")));
        assertEquals(400, e.status());
        assertFalse(e.retryable());
        assertTrue(e.getMessage().contains("10 minutes"));
    }

    @Test
    void emptyTranscriptIsEmpty() {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"sessionId\":\"s\",\"transcript\":\"\",\"audioDurationMs\":10}"));
        assertTrue(client.transcribeWav(new byte[100], List.of()).isEmpty());
    }
}
