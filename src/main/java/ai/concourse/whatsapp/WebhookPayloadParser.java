package ai.concourse.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Parses the WhatsApp Cloud API webhook envelope into {@link InboundMessage}s. Status updates are ignored. */
@Component
public class WebhookPayloadParser {
    private final ObjectMapper mapper;

    public WebhookPayloadParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<InboundMessage> parse(byte[] body) throws java.io.IOException {
        JsonNode root = mapper.readTree(body);
        List<InboundMessage> out = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                JsonNode value = change.path("value");
                for (JsonNode m : value.path("messages")) {
                    out.add(toMessage(m));
                }
            }
        }
        return out;
    }

    private InboundMessage toMessage(JsonNode m) {
        String id = m.path("id").asText();
        String from = m.path("from").asText();
        long ts = m.path("timestamp").asLong(0);
        String type = m.path("type").asText("");
        return switch (type) {
            case "text" -> new InboundMessage(id, from, InboundMessage.Type.TEXT,
                    m.path("text").path("body").asText(), null, null, null, ts);
            case "audio" -> new InboundMessage(id, from, InboundMessage.Type.AUDIO, null,
                    m.path("audio").path("id").asText(), m.path("audio").path("mime_type").asText(), null, ts);
            case "image" -> new InboundMessage(id, from, InboundMessage.Type.IMAGE,
                    m.path("image").path("caption").asText(null),
                    m.path("image").path("id").asText(), m.path("image").path("mime_type").asText(), null, ts);
            case "document" -> new InboundMessage(id, from, InboundMessage.Type.DOCUMENT,
                    m.path("document").path("filename").asText(null),
                    m.path("document").path("id").asText(), m.path("document").path("mime_type").asText(), null, ts);
            case "interactive" -> {
                JsonNode br = m.path("interactive").path("button_reply");
                yield new InboundMessage(id, from, InboundMessage.Type.BUTTON_REPLY,
                        br.path("title").asText(null), null, null, br.path("id").asText(null), ts);
            }
            default -> new InboundMessage(id, from, InboundMessage.Type.OTHER, null, null, null, null, ts);
        };
    }
}
