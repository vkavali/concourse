package ai.concourse.spark;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One Muse Spark call. Attachments are inlined as data: URLs (images as {@code image_url}, PDFs as {@code file});
 * {@code schema} switches on constrained JSON output ({@code response_format: json_schema}).
 * <p>
 * {@code tapeKey} names the input this call is about (a hash of the photo, the audio's transcript, the ticket
 * bytes) so a recording of the answer can be replayed for the same input on another day, when the journey's dates
 * in the prompt differ. Null = the whole request is the key.
 */
public record SparkRequest(
        String system,
        String user,
        List<Attachment> attachments,
        JsonSchema schema,
        Double temperature,
        String tapeKey
) {
    public SparkRequest(String system, String user, List<Attachment> attachments, JsonSchema schema, Double temperature) {
        this(system, user, attachments, schema, temperature, null);
    }

    public record Attachment(String mimeType, byte[] bytes, String filename) {
        public boolean isPdf() { return "application/pdf".equalsIgnoreCase(mimeType); }
    }

    public record JsonSchema(String name, JsonNode schema) {}

    public static SparkRequest text(String system, String user) {
        return new SparkRequest(system, user, List.of(), null, 0.2, null);
    }

    public SparkRequest withSchema(JsonSchema s) {
        return new SparkRequest(system, user, attachments, s, temperature, tapeKey);
    }

    public SparkRequest withAttachments(List<Attachment> a) {
        return new SparkRequest(system, user, a, schema, temperature, tapeKey);
    }

    public SparkRequest withTemperature(double t) {
        return new SparkRequest(system, user, attachments, schema, t, tapeKey);
    }

    public SparkRequest withTapeKey(String k) {
        return new SparkRequest(system, user, attachments, schema, temperature, k);
    }

    /** The operation name for logs: the schema name, else "chat". */
    public String op() {
        return schema == null ? "chat" : schema.name();
    }
}
