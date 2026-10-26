package ai.concourse.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebhookPayloadParserTest {
    private final WebhookPayloadParser parser = new WebhookPayloadParser(new ObjectMapper());

    @Test
    void parsesTextAudioImageAndButton() throws Exception {
        String payload = """
            {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":{
              "messaging_product":"whatsapp","metadata":{"display_phone_number":"1","phone_number_id":"123"},
              "messages":[
                {"from":"919876543210","id":"wamid.1","timestamp":"1700000000","type":"text","text":{"body":"hello"}},
                {"from":"919876543210","id":"wamid.2","timestamp":"1700000001","type":"audio","audio":{"id":"m1","mime_type":"audio/ogg; codecs=opus","voice":true}},
                {"from":"919876543210","id":"wamid.3","timestamp":"1700000002","type":"image","image":{"id":"m2","mime_type":"image/jpeg"}},
                {"from":"919876543210","id":"wamid.4","timestamp":"1700000003","type":"interactive","interactive":{"type":"button_reply","button_reply":{"id":"ack_stage_1","title":"Done"}}}
              ]}, "field":"messages"}]}]}
            """;
        List<InboundMessage> msgs = parser.parse(payload.getBytes(StandardCharsets.UTF_8));
        assertEquals(4, msgs.size());
        assertEquals(InboundMessage.Type.TEXT, msgs.get(0).type());
        assertEquals("hello", msgs.get(0).text());
        assertEquals(InboundMessage.Type.AUDIO, msgs.get(1).type());
        assertEquals("m1", msgs.get(1).mediaId());
        assertEquals(InboundMessage.Type.IMAGE, msgs.get(2).type());
        assertEquals(InboundMessage.Type.BUTTON_REPLY, msgs.get(3).type());
        assertEquals("ack_stage_1", msgs.get(3).interactiveReplyId());
    }

    @Test
    void ignoresStatusOnlyPayloads() throws Exception {
        String payload = """
            {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"value":{
              "messaging_product":"whatsapp","statuses":[{"id":"wamid.9","status":"delivered"}]},"field":"messages"}]}]}
            """;
        assertTrue(parser.parse(payload.getBytes(StandardCharsets.UTF_8)).isEmpty());
    }
}
