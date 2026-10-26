package ai.concourse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All runtime configuration. Values come from environment variables (see .env.example). */
@ConfigurationProperties(prefix = "concourse")
public record ConcourseProperties(
        String publicBaseUrl,
        String adminToken,
        String phoneHashSalt,
        WhatsApp whatsapp,
        Meta meta
) {
    public record WhatsApp(
            String token,
            String phoneNumberId,
            String verifyToken,
            String appSecret,
            String graphBaseUrl
    ) {}

    public record Meta(
            String apiKey,
            String baseUrl,
            String sparkModel,
            String sttModel
    ) {}
}
