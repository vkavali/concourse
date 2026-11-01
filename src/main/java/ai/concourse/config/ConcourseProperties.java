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
        @DefaultValue Tape tape,
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

    /**
     * Meta Model API: Muse Spark (chat completions) and Muse Voice Transcribe (/asr/transcribe) — plus, optionally,
     * Meta's open-weight Muse Spark Open through partner inference (Baseten) for the small intent call.
     */
    public record Meta(
            String apiKey,
            @DefaultValue("https://api.meta.ai/v1") String baseUrl,
            @DefaultValue("muse-spark-1.3") String sparkModel,
            @DefaultValue("muse-voice-transcribe-1.0") String sttModel,
            /** Stream chat completions (SSE) and aggregate; avoids gateway timeouts on long generations. */
            @DefaultValue("true") boolean stream,
            @DefaultValue("120") int timeoutSeconds,
            /** OpenAI-compatible base URL of the open-weight deployment (e.g. https://inference.baseten.co/v1); empty = off. */
            String openBaseUrl,
            String openApiKey,
            @DefaultValue("meta-models/muse-spark-open-1026") String openModel,
            /** Reasoning tokens count against max_tokens on Spark Open: keep room for the JSON answer. */
            @DefaultValue("1024") int openMaxTokens,
            /** "low" keeps the open model's thinking short for a classification; empty = omit the field. */
            @DefaultValue("low") String openReasoningEffort
    ) {}

    /**
     * Text-to-speech: Meta's open-weight voices served from {@code modal/} — SeamlessM4T v2 (200 speakers) or
     * MMS-TTS Telugu — behind one HTTP contract ({@code POST /tts}, {@code GET /health}). No other voice provider
     * exists in this codebase: every model Concourse calls at runtime is a Meta model.
     */
    public record Tts(
            String baseUrl,
            String token,
            /** Notes and phrases cached here; the default folder is per speaker so changing the voice never replays an old one. */
            @DefaultValue("data/tts-cache") String cacheDir,
            /** SeamlessM4T vocoder speaker (0-199); ignored by MMS-TTS. */
            @DefaultValue("0") int speaker,
            /** Label used until the server's /health reports the real model id. */
            @DefaultValue("facebook/seamless-m4t-v2-large") String model
    ) {}

    /**
     * The tape: {@code record} saves every model input and output of a real run under {@code dir}; {@code replay}
     * serves those recordings so a judge without API keys sees the same run (the judge path). {@code off} otherwise.
     */
    public record Tape(
            @DefaultValue("off") String mode,
            @DefaultValue("eval/sample-run") String dir
    ) {}

    public record Audio(@DefaultValue("ffmpeg") String ffmpegPath) {}

    /** Itinerary validation knobs. allowPastDates=true lets old tickets through for evals and demos. */
    public record Itinerary(
            @DefaultValue("false") boolean allowPastDates,
            @DefaultValue("60") int maxDaysAhead
    ) {}
}
