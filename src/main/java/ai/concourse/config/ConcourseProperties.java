package ai.concourse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All runtime configuration. Values come from environment variables (see .env.example and application.yml). */
@ConfigurationProperties(prefix = "concourse")
public record ConcourseProperties(
        String publicBaseUrl,
        String adminToken,
        String phoneHashSalt,
        @DefaultValue WhatsApp whatsapp,
        @DefaultValue Meta meta,
        @DefaultValue Tts tts,
        @DefaultValue Audio audio,
        @DefaultValue Itinerary itinerary
) {
    /** WhatsApp Business Cloud API (Graph API). */
    public record WhatsApp(
            String token,
            String phoneNumberId,
            String verifyToken,
            String appSecret,
            @DefaultValue("https://graph.facebook.com/v25.0") String graphBaseUrl
    ) {}

    /** Meta Model API: Muse Spark (chat completions) and Muse Voice Transcribe (/asr/transcribe). */
    public record Meta(
            String apiKey,
            @DefaultValue("https://api.meta.ai/v1") String baseUrl,
            @DefaultValue("muse-spark-1.3") String sparkModel,
            @DefaultValue("muse-voice-transcribe-1.0") String sttModel,
            /** Stream chat completions (SSE) and aggregate; avoids gateway timeouts on long generations. */
            @DefaultValue("true") boolean stream,
            @DefaultValue("120") int timeoutSeconds
    ) {}

    /** Text-to-speech endpoint: facebook/mms-tts-tel served on Modal (see modal/mms_tts.py). */
    public record Tts(
            String baseUrl,
            String token,
            @DefaultValue("data/tts-cache") String cacheDir
    ) {}

    public record Audio(@DefaultValue("ffmpeg") String ffmpegPath) {}

    /** Itinerary validation knobs. allowPastDates=true lets old tickets through for evals and demos. */
    public record Itinerary(
            @DefaultValue("false") boolean allowPastDates,
            @DefaultValue("60") int maxDaysAhead
    ) {}
}
