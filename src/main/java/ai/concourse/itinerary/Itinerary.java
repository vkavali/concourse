package ai.concourse.itinerary;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Raw extraction result from Spark (snake_case JSON in {@code prompts/ticket_extract.schema.json}).
 * Times are naive local strings exactly as printed; {@link ItineraryValidator} resolves them against airport time zones.
 */
public record Itinerary(
        @JsonProperty("is_ticket") boolean isTicket,
        @JsonProperty("reason") String reason,
        @JsonProperty("passenger_first_name") String passengerFirstName,
        @JsonProperty("booking_reference") String bookingReference,
        @JsonProperty("segments") List<Segment> segments
) {
    public record Segment(
            @JsonProperty("airline") String airline,
            @JsonProperty("flight_number") String flightNumber,
            @JsonProperty("origin") String origin,
            @JsonProperty("destination") String destination,
            @JsonProperty("departure_local") String departureLocal,
            @JsonProperty("arrival_local") String arrivalLocal,
            @JsonProperty("terminal") String terminal,
            @JsonProperty("gate") String gate,
            @JsonProperty("confidence") double confidence,
            @JsonProperty("notes") String notes
    ) {}

    public List<Segment> segmentsOrEmpty() {
        return segments == null ? List.of() : segments;
    }
}
