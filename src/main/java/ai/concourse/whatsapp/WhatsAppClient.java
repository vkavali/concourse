package ai.concourse.whatsapp;

import ai.concourse.config.ConcourseProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the WhatsApp Business Cloud API (Graph API).
 * Outbound: text, interactive buttons (max 3), media upload, audio as voice note, image, template.
 * Inbound media: resolve media id -> URL -> bytes (bearer required on the download too).
 */
@Component
public class WhatsAppClient {
    private final WebClient graph;
    private final String phoneNumberId;
    private final String token;

    public WhatsAppClient(ConcourseProperties props, WebClient.Builder builder) {
        this(props.whatsapp().graphBaseUrl(), props.whatsapp().phoneNumberId(), props.whatsapp().token(), builder);
    }

    WhatsAppClient(String graphBaseUrl, String phoneNumberId, String token, WebClient.Builder builder) {
        this.phoneNumberId = phoneNumberId;
        this.token = token;
        this.graph = builder.baseUrl(graphBaseUrl)
                .defaultHeader("Authorization", "Bearer " + token)
                .build();
    }

    public String sendText(String to, String body) {
        return send(Map.of("messaging_product", "whatsapp", "to", to, "type", "text",
                "text", Map.of("preview_url", false, "body", body)));
    }

    /** Up to 3 quick-reply buttons; ids are what come back in interactive.button_reply.id. */
    public String sendButtons(String to, String body, List<Button> buttons) {
        if (buttons.isEmpty() || buttons.size() > 3) throw new IllegalArgumentException("1..3 buttons");
        List<Map<String, Object>> btns = buttons.stream()
                .map(b -> Map.<String, Object>of("type", "reply", "reply", Map.of("id", b.id(), "title", b.title())))
                .toList();
        return send(Map.of("messaging_product", "whatsapp", "to", to, "type", "interactive",
                "interactive", Map.of("type", "button", "body", Map.of("text", body), "action", Map.of("buttons", btns))));
    }

    /** Uploads media and returns the media id. mimeType e.g. "audio/ogg; codecs=opus", "image/png". */
    public String uploadMedia(byte[] bytes, String mimeType, String filename) {
        MultipartBodyBuilder mb = new MultipartBodyBuilder();
        mb.part("messaging_product", "whatsapp");
        mb.part("type", mimeType);
        mb.part("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        }).contentType(MediaType.parseMediaType(mimeType));
        JsonNode res = graph.post().uri("/{id}/media", phoneNumberId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(mb.build()))
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(30));
        return res.path("id").asText();
    }

    /** Sends an uploaded OGG/Opus mono file as a voice note (voice:true renders mic icon + autoplay-on-open). */
    public String sendVoiceNote(String to, String mediaId) {
        return send(Map.of("messaging_product", "whatsapp", "to", to, "type", "audio",
                "audio", Map.of("id", mediaId, "voice", true)));
    }

    public String sendImage(String to, String mediaId, String caption) {
        Map<String, Object> image = caption == null ? Map.of("id", mediaId) : Map.of("id", mediaId, "caption", caption);
        return send(Map.of("messaging_product", "whatsapp", "to", to, "type", "image", "image", image));
    }

    /** Pre-approved template, e.g. journey_update with one body parameter. Required outside the 24-h window. */
    public String sendTemplate(String to, String templateName, String languageCode, List<String> bodyParams) {
        List<Map<String, Object>> params = bodyParams.stream()
                .map(p -> Map.<String, Object>of("type", "text", "text", p)).toList();
        return send(Map.of("messaging_product", "whatsapp", "to", to, "type", "template",
                "template", Map.of("name", templateName, "language", Map.of("code", languageCode),
                        "components", List.of(Map.of("type", "body", "parameters", params)))));
    }

    public void markRead(String messageId) {
        graph.post().uri("/{id}/messages", phoneNumberId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("messaging_product", "whatsapp", "status", "read", "message_id", messageId))
                .retrieve().toBodilessEntity().block(Duration.ofSeconds(10));
    }

    /** Inbound media: id -> temporary URL -> bytes. The download URL also requires the bearer token. */
    public byte[] downloadMedia(String mediaId) {
        JsonNode meta = graph.get().uri("/{id}", mediaId).retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(15));
        String url = meta.path("url").asText();
        return WebClient.create().get().uri(url)
                .header("Authorization", "Bearer " + token)
                .retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(60));
    }

    private String send(Map<String, Object> payload) {
        JsonNode res = graph.post().uri("/{id}/messages", phoneNumberId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(20));
        return res.path("messages").path(0).path("id").asText();
    }

    public record Button(String id, String title) {}
}
