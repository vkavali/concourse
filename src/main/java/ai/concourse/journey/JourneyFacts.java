package ai.concourse.journey;

import ai.concourse.itinerary.Airports;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The verified facts Spark is allowed to speak from, and that {@code SafetyPolicy} checks replies against.
 * Built from journey rows only; never from a transcript or a photo. Serialised as JSON into the parent_turn prompt.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record JourneyFacts(
        @JsonProperty("status") String status,
        @JsonProperty("traveler_first_name") String travelerFirstName,
        @JsonProperty("flights") List<Flight> flights,
        @JsonProperty("next_verified_step") String nextVerifiedStep,
        @JsonProperty("last_acknowledged_stage") String lastAcknowledgedStage,
        @JsonProperty("observed_sign_arrow") String observedSignArrow
) {
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public record Flight(
            @JsonProperty("seq") int seq,
            @JsonProperty("flight") String flight,
            @JsonProperty("from") String from,
            @JsonProperty("to") String to,
            @JsonProperty("departure_local") String departureLocal,
            @JsonProperty("arrival_local") String arrivalLocal,
            @JsonProperty("terminal") String terminal,
            @JsonProperty("gate") String gate,
            @JsonProperty("gate_verified") boolean gateVerified
    ) {}

    public static JourneyFacts of(JourneyEntity j, List<FlightSegmentEntity> segments, Airports airports) {
        List<Flight> flights = new ArrayList<>();
        for (FlightSegmentEntity s : segments) {
            flights.add(new Flight(s.getSeq(), s.getFlightNumber(), s.getOrigin(), s.getDestination(),
                    local(s.getScheduledDeparture(), s.getOrigin(), airports),
                    local(s.getScheduledArrival(), s.getDestination(), airports),
                    s.getTerminal(), s.getGate(), s.getGate() != null && s.getLastVerifiedAt() != null));
        }
        String next = flights.isEmpty() ? "No flights on file." : nextStep(flights.get(0));
        return new JourneyFacts(j.getStatus(), j.getPassengerFirstName(), flights, next, null, null);
    }

    private static String nextStep(Flight f) {
        String gate = f.gate() == null ? "gate not announced yet" : "gate " + f.gate();
        String terminal = f.terminal() == null ? "" : ", terminal " + f.terminal();
        return "Flight " + f.flight() + " from " + f.from() + " to " + f.to() + " departs " + f.departureLocal()
                + " local" + terminal + ", " + gate + ".";
    }

    private static String local(OffsetDateTime t, String iata, Airports airports) {
        if (t == null) return null;
        ZoneId zone = airports.find(iata).map(Airports.Airport::zone).orElse(t.getOffset());
        return LOCAL.format(t.atZoneSameInstant(zone));
    }

    /** Gate codes, terminals and flight numbers that may be spoken, upper-cased without spaces. */
    public Set<String> allowedCodes() {
        Set<String> codes = new TreeSet<>();
        for (Flight f : flights) {
            if (f.flight() != null) codes.add(norm(f.flight()));
            if (f.gate() != null) codes.add(norm(f.gate()));
            if (f.terminal() != null) codes.add(norm(f.terminal()));
            codes.add(norm(f.from()));
            codes.add(norm(f.to()));
        }
        return codes;
    }

    public static String norm(String s) {
        return s.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    public Flight firstFlight() {
        return flights.isEmpty() ? null : flights.get(0);
    }
}
