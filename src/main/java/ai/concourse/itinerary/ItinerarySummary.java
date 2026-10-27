package ai.concourse.itinerary;

import ai.concourse.journey.FlightSegmentEntity;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** English summaries for the child (the parent never sees these). Plain text, fits a WhatsApp interactive body (≤ 1024 chars). */
public final class ItinerarySummary {
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);

    private ItinerarySummary() {}

    public static String forChild(ValidatedItinerary v) {
        StringBuilder sb = new StringBuilder();
        String name = v.raw().passengerFirstName();
        sb.append("Itinerary").append(name == null || name.isBlank() ? "" : " for " + name.strip())
                .append(" — ").append(v.segments().size()).append(v.segments().size() == 1 ? " flight" : " flights").append('\n');
        for (ValidatedItinerary.Segment s : v.segments()) {
            sb.append(line(s.seq(), s.flightNumber(), s.origin(), s.destination(), s.departure(), s.arrival(), s.terminal()));
        }
        List<ValidatedItinerary.Issue> warnings = v.warnings();
        if (!warnings.isEmpty()) {
            sb.append("\nPlease check:\n");
            for (ValidatedItinerary.Issue w : warnings) {
                sb.append("• ").append(w.segmentSeq() > 0 ? "Flight " + w.segmentSeq() + ": " : "").append(w.message()).append('\n');
            }
        }
        sb.append("\nIs this right?");
        return clip(sb.toString());
    }

    public static String forChild(String passengerFirstName, List<FlightSegmentEntity> segments) {
        StringBuilder sb = new StringBuilder();
        sb.append("Current itinerary").append(passengerFirstName == null || passengerFirstName.isBlank() ? "" : " for " + passengerFirstName)
                .append('\n');
        for (FlightSegmentEntity f : segments) {
            sb.append(line(f.getSeq(), f.getFlightNumber(), f.getOrigin(), f.getDestination(),
                    f.getScheduledDeparture(), f.getScheduledArrival(), f.getTerminal()));
        }
        return clip(sb.toString());
    }

    public static String errorsForChild(ValidatedItinerary v) {
        StringBuilder sb = new StringBuilder("I could not build a journey from that document:\n");
        for (ValidatedItinerary.Issue e : v.errors()) {
            sb.append("• ").append(e.segmentSeq() > 0 ? "Flight " + e.segmentSeq() + ": " : "").append(e.message()).append('\n');
        }
        sb.append("\nPlease send the e-ticket PDF or a clear photo of the full itinerary.");
        return clip(sb.toString());
    }

    private static String line(int seq, String flight, String origin, String destination,
                               OffsetDateTime dep, OffsetDateTime arr, String terminal) {
        StringBuilder sb = new StringBuilder();
        sb.append(seq).append(". ").append(flight).append("  ").append(origin).append(" → ").append(destination).append('\n');
        sb.append("   Dep ").append(dep == null ? "?" : DAY_TIME.format(dep)).append(" (").append(origin).append(" local)");
        sb.append(" · Arr ").append(arr == null ? "not printed" : DAY_TIME.format(arr) + " (" + destination + " local)");
        if (terminal != null && !terminal.isBlank()) sb.append(" · T").append(terminal.replaceFirst("(?i)^terminal\\s*", ""));
        sb.append('\n');
        return sb.toString();
    }

    private static String clip(String s) {
        return s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
