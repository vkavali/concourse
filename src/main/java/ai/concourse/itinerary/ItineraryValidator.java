package ai.concourse.itinerary;

import ai.concourse.config.ConcourseProperties;
import ai.concourse.itinerary.ValidatedItinerary.Issue;
import ai.concourse.itinerary.ValidatedItinerary.Segment;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Turns a raw {@link Itinerary} into verified journey facts, or says exactly why it can't.
 * Code, not the model, owns temporal truth: printed local times + airport zone → absolute instants.
 */
@Component
public class ItineraryValidator {
    private static final Pattern FLIGHT_NUMBER = Pattern.compile("^[A-Z0-9]{2}[0-9]{1,4}[A-Z]?$");
    private static final Pattern IATA = Pattern.compile("^[A-Z]{3}$");
    private static final List<DateTimeFormatter> LOCAL_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    private static final Duration MIN_CONNECTION = Duration.ofMinutes(30);
    private static final Duration MAX_CONNECTION = Duration.ofHours(24);
    private static final Duration MAX_LEG = Duration.ofHours(20);
    private static final double MIN_CONFIDENCE = 0.8;

    private final Airports airports;
    private final boolean allowPastDates;
    private final int maxDaysAhead;
    private final Clock clock;

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public ItineraryValidator(Airports airports, ConcourseProperties props, Clock clock) {
        this(airports, props.itinerary().allowPastDates(), props.itinerary().maxDaysAhead(), clock);
    }

    public ItineraryValidator(Airports airports, boolean allowPastDates, int maxDaysAhead, Clock clock) {
        this.airports = airports;
        this.allowPastDates = allowPastDates;
        this.maxDaysAhead = maxDaysAhead;
        this.clock = clock;
    }

    public ValidatedItinerary validate(Itinerary raw) {
        List<Issue> issues = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();

        if (!raw.isTicket()) {
            issues.add(Issue.error(0, "document", "This does not look like a flight ticket: " + nz(raw.reason())));
            return new ValidatedItinerary(raw, segments, issues);
        }
        if (raw.segmentsOrEmpty().isEmpty()) {
            issues.add(Issue.error(0, "segments", "No flights found on the document."));
            return new ValidatedItinerary(raw, segments, issues);
        }

        int seq = 0;
        for (Itinerary.Segment s : raw.segmentsOrEmpty()) {
            seq++;
            Set<String> unverified = new LinkedHashSet<>();

            String origin = code(s.origin());
            String destination = code(s.destination());
            Optional<Airports.Airport> from = airports.find(origin);
            Optional<Airports.Airport> to = airports.find(destination);
            if (!IATA.matcher(origin).matches() || from.isEmpty()) {
                issues.add(Issue.error(seq, "origin", "Unknown departure airport code '" + nz(s.origin()) + "'."));
            }
            if (!IATA.matcher(destination).matches() || to.isEmpty()) {
                issues.add(Issue.error(seq, "destination", "Unknown arrival airport code '" + nz(s.destination()) + "'."));
            }
            if (from.isPresent() && to.isPresent() && origin.equals(destination)) {
                issues.add(Issue.error(seq, "destination", "Departure and arrival airport are the same (" + origin + ")."));
            }

            String flightNumber = nz(s.flightNumber()).replace(" ", "").toUpperCase(Locale.ROOT);
            if (!FLIGHT_NUMBER.matcher(flightNumber).matches()) {
                issues.add(Issue.warn(seq, "flight_number", "Flight number '" + nz(s.flightNumber()) + "' looks unusual."));
                unverified.add("flight_number");
            }

            OffsetDateTime departure = null;
            OffsetDateTime arrival = null;
            if (from.isPresent()) {
                Optional<LocalDateTime> dep = parseLocal(s.departureLocal());
                if (dep.isEmpty()) {
                    issues.add(Issue.error(seq, "departure", "Departure time '" + nz(s.departureLocal()) + "' is missing or unreadable."));
                } else {
                    departure = dep.get().atZone(from.get().zone()).toOffsetDateTime();
                }
            }
            if (to.isPresent()) {
                if (s.arrivalLocal() == null || s.arrivalLocal().isBlank()) {
                    issues.add(Issue.warn(seq, "arrival", "Arrival time is not printed; please confirm it."));
                    unverified.add("arrival");
                } else {
                    Optional<LocalDateTime> arr = parseLocal(s.arrivalLocal());
                    if (arr.isEmpty()) {
                        issues.add(Issue.warn(seq, "arrival", "Arrival time '" + s.arrivalLocal() + "' is unreadable."));
                        unverified.add("arrival");
                    } else {
                        arrival = arr.get().atZone(to.get().zone()).toOffsetDateTime();
                    }
                }
            }
            if (departure != null && arrival != null) {
                if (!arrival.isAfter(departure)) {
                    issues.add(Issue.error(seq, "arrival", "Arrival is not after departure (check the dates and the +1 day marker)."));
                } else if (Duration.between(departure, arrival).compareTo(MAX_LEG) > 0) {
                    issues.add(Issue.warn(seq, "arrival", "This leg is longer than 20 hours; please confirm the arrival date."));
                    unverified.add("arrival");
                }
            }
            if (departure != null) {
                OffsetDateTime now = OffsetDateTime.now(clock);
                if (departure.isBefore(now)) {
                    if (allowPastDates) {
                        issues.add(Issue.warn(seq, "departure", "Departure is in the past (allowed in test mode)."));
                    } else {
                        issues.add(Issue.error(seq, "departure", "Departure " + departure.toLocalDate() + " is in the past."));
                    }
                } else if (departure.isAfter(now.plusDays(maxDaysAhead))) {
                    issues.add(Issue.warn(seq, "departure", "Departure is more than " + maxDaysAhead + " days away; please confirm the date."));
                    unverified.add("departure");
                }
            }
            if (s.confidence() < MIN_CONFIDENCE) {
                issues.add(Issue.warn(seq, "segment", "The reader was not sure about this flight" +
                        (nz(s.notes()).isBlank() ? "." : ": " + s.notes().strip())));
                unverified.add("segment");
            }

            segments.add(new Segment(seq, blankToNull(s.airline()), flightNumber, origin, destination, departure, arrival,
                    normalizeTerminal(s.terminal()), blankToNull(s.gate()), s.confidence(), unverified));
        }

        // Cross-segment checks only make sense when every departure resolved.
        if (segments.stream().allMatch(x -> x.departure() != null)) {
            segments.sort(Comparator.comparing(Segment::departure));
            for (int i = 0; i < segments.size(); i++) {
                Segment cur = segments.get(i);
                if (cur.seq() != i + 1) {
                    segments.set(i, withSeq(cur, i + 1));
                    cur = segments.get(i);
                }
                if (i == 0) continue;
                Segment prev = segments.get(i - 1);
                if (!prev.destination().equals(cur.origin())) {
                    issues.add(Issue.warn(cur.seq(), "origin", "Flight " + cur.seq() + " leaves from " + cur.origin()
                            + " but flight " + prev.seq() + " arrives at " + prev.destination() + "."));
                }
                if (prev.arrival() != null) {
                    Duration connection = Duration.between(prev.arrival(), cur.departure());
                    if (connection.isNegative()) {
                        issues.add(Issue.error(cur.seq(), "departure", "Flight " + cur.seq() + " departs before flight " + prev.seq() + " arrives."));
                    } else if (connection.compareTo(MIN_CONNECTION) < 0) {
                        issues.add(Issue.warn(cur.seq(), "departure", "Only " + connection.toMinutes() + " minutes between flights; please confirm."));
                    } else if (connection.compareTo(MAX_CONNECTION) > 0) {
                        issues.add(Issue.warn(cur.seq(), "departure", "Layover is longer than 24 hours; please confirm the dates."));
                    }
                }
            }
        }
        return new ValidatedItinerary(raw, List.copyOf(segments), List.copyOf(issues));
    }

    private static Segment withSeq(Segment s, int seq) {
        return new Segment(seq, s.airline(), s.flightNumber(), s.origin(), s.destination(), s.departure(), s.arrival(),
                s.terminal(), s.gate(), s.confidence(), s.unverifiedFields());
    }

    static Optional<LocalDateTime> parseLocal(String text) {
        if (text == null) return Optional.empty();
        String t = text.strip();
        if (t.isEmpty()) return Optional.empty();
        for (DateTimeFormatter f : LOCAL_FORMATS) {
            try {
                return Optional.of(LocalDateTime.parse(t, f));
            } catch (DateTimeParseException ignored) {
            }
        }
        return Optional.empty();
    }

    private static String code(String s) {
        return nz(s).strip().toUpperCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** "T3", "Terminal 3", "T-3" → "3"; "D" stays "D". The card and the voice say "టెర్మినల్ 3", never "టెర్మినల్ టీ మూడు". */
    static String normalizeTerminal(String terminal) {
        return Terminals.normalize(terminal);
    }
}
