package ai.concourse.spark;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * One Muse Spark call. Attachments are inlined as data: URLs (images as {@code image_url}, PDFs as {@code file});
 * {@code schema} switches on constrained JSON output ({@code response_format: json_schema}).
 */
public record SparkRequest(
        String system,
        String user,
        List<Attachment> attachments,
        JsonSchema schema,
        Double temperature
) {
    public record Attachment(String mimeType, byte[] bytes, String filename) {
        public boolean isPdf() { return "application/pdf".equalsIgnoreCase(mimeType); }
    }

    public record JsonSchema(String name, JsonNode schema) {}

    public static SparkRequest text(String system, String user) {
        return new SparkRequest(system, user, List.of(), null, 0.2);
    }

    public SparkRequest withSchema(JsonSchema s) {
        return new SparkRequest(system, user, attachments, s, temperature);
    }

    public SparkRequest withAttachments(List<Attachment> a) {
        return new SparkRequest(system, user, a, schema, temperature);
    }

    public SparkRequest withTemperature(double t) {
        return new SparkRequest(system, user, attachments, schema, t);
    }
}
