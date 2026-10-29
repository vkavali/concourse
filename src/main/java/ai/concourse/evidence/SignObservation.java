package ai.concourse.evidence;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * What Spark saw in her photo — printed text, gate codes, at most one arrow — and nothing about where she is:
 * {@code location_claim} is typed null in the schema, and the service drops the observation if it is ever not null.
 */
public record SignObservation(
        @JsonProperty("is_airport_sign") boolean isAirportSign,
        @JsonProperty("visible_text") List<String> visibleText,
        @JsonProperty("gate_codes") List<String> gateCodes,
        @JsonProperty("explicit_arrow") String explicitArrow,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("location_claim") Object locationClaim
) {
    public SignObservation {
        visibleText = visibleText == null ? List.of() : List.copyOf(visibleText);
        gateCodes = gateCodes == null ? List.of() : List.copyOf(gateCodes);
    }

    public boolean readable() {
        return confidence >= 0.4 && (!visibleText.isEmpty() || !gateCodes.isEmpty());
    }
}
