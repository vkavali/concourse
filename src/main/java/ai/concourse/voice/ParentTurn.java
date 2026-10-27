package ai.concourse.voice;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Spark's interpretation of one parent voice note (schema: prompts/parent_turn.schema.json). Code decides what happens. */
public record ParentTurn(
        @JsonProperty("intent") String intent,
        @JsonProperty("entity") String entity,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("tool_proposal") String toolProposal,
        @JsonProperty("reply_te") String replyTe,
        @JsonProperty("english_gloss") String englishGloss
) {
    public static final double CONFIDENCE_GATE = 0.7;

    public boolean needsClarification() {
        return confidence < CONFIDENCE_GATE || "UNCLEAR".equals(intent) || replyTe == null || replyTe.isBlank();
    }
}
