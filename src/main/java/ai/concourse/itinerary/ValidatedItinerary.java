package ai.concourse.itinerary;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Output of {@link ItineraryValidator}: segments with absolute times (resolved from airport zones), plus issues.
 * ERROR issues block journey creation; WARN issues mark fields as unverified and are shown to the child to confirm.
 */
public record ValidatedItinerary(Itinerary raw, List<Segment> segments, List<Issue> issues) {

    public record Segment(
            int seq,
            String airline,
            String flightNumber,
            String origin,
            String destination,
            OffsetDateTime departure,
            OffsetDateTime arrival,          // null when not printed (flagged as unverified)
            String terminal,
            String gate,
            double confidence,
            Set<String> unverifiedFields
    ) {}

    public enum Severity { ERROR, WARN }

    /** {@code segmentSeq} is 1-based; 0 means the whole itinerary. */
    public record Issue(Severity severity, int segmentSeq, String field, String message) {
        public static Issue error(int seq, String field, String message) { return new Issue(Severity.ERROR, seq, field, message); }
        public static Issue warn(int seq, String field, String message) { return new Issue(Severity.WARN, seq, field, message); }
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == Severity.ERROR);
    }

    public List<Issue> errors() {
        return issues.stream().filter(i -> i.severity() == Severity.ERROR).toList();
    }

    public List<Issue> warnings() {
        return issues.stream().filter(i -> i.severity() == Severity.WARN).toList();
    }

    public OffsetDateTime lastArrivalOrDeparture() {
        OffsetDateTime last = null;
        for (Segment s : segments) {
            OffsetDateTime t = s.arrival() != null ? s.arrival() : s.departure();
            if (t != null && (last == null || t.isAfter(last))) last = t;
        }
        return last;
    }
}
