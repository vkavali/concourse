package ai.concourse.itinerary;

import ai.concourse.itinerary.ValidatedItinerary.Severity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ItineraryValidatorTest {
    private static final Airports AIRPORTS = new Airports();
    /** "Now" for the tests: 2026-11-01T00:00Z. Tickets below depart 2026-11-28. */
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);
    private final ItineraryValidator validator = new ItineraryValidator(AIRPORTS, false, 60, NOW);

    private static Itinerary.Segment seg(String flight, String from, String to, String dep, String arr, double conf) {
        return new Itinerary.Segment("Qatar Airways", flight, from, to, dep, arr, null, null, conf, "");
    }

    private static Itinerary ticket(Itinerary.Segment... segments) {
        return new Itinerary(true, "e-ticket", "Lakshmi", "ABC123", List.of(segments));
    }

    @Test
    void airportsTableKnowsTheDemoRoute() {
        assertEquals("Asia/Kolkata", AIRPORTS.find("HYD").orElseThrow().zone().getId());
        assertEquals("Asia/Qatar", AIRPORTS.find("DOH").orElseThrow().zone().getId());
        assertEquals("America/Chicago", AIRPORTS.find("DFW").orElseThrow().zone().getId());
        assertTrue(AIRPORTS.find("ZZZ").isEmpty());
        assertTrue(AIRPORTS.size() > 5000);
    }

    @Test
    void resolvesLocalTimesAgainstAirportZones() {
        ValidatedItinerary v = validator.validate(ticket(
                seg("QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", 1.0),
                seg("QR729", "DOH", "DFW", "2026-11-28T08:40", "2026-11-28T15:50", 1.0)));
        assertFalse(v.hasErrors(), v.issues().toString());
        assertEquals(2, v.segments().size());
        ValidatedItinerary.Segment first = v.segments().get(0);
        assertEquals("+05:30", first.departure().getOffset().getId());
        assertEquals("2026-11-27T22:15:00Z", first.departure().toInstant().toString());
        assertEquals("+03:00", first.arrival().getOffset().getId());
        ValidatedItinerary.Segment second = v.segments().get(1);
        assertEquals("+03:00", second.departure().getOffset().getId()); // departs Doha
        assertEquals("-06:00", second.arrival().getOffset().getId());   // DFW, CST in November
        assertTrue(v.warnings().isEmpty(), v.warnings().toString());
        assertTrue(first.unverifiedFields().isEmpty());
    }

    @Test
    void unknownAirportIsAnError() {
        ValidatedItinerary v = validator.validate(ticket(seg("QR1", "HYD", "QQQ", "2026-11-28T03:45", "2026-11-28T06:05", 1.0)));
        assertTrue(v.hasErrors());
        assertEquals("destination", v.errors().get(0).field());
    }

    @Test
    void arrivalBeforeDepartureIsAnError() {
        ValidatedItinerary v = validator.validate(ticket(seg("QR1", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T02:05", 1.0)));
        assertTrue(v.hasErrors());
        assertTrue(v.errors().get(0).message().contains("not after"));
    }

    @Test
    void missingArrivalIsUnverifiedNotFatal() {
        ValidatedItinerary v = validator.validate(ticket(seg("QR1", "HYD", "DOH", "2026-11-28T03:45", null, 1.0)));
        assertFalse(v.hasErrors());
        assertNull(v.segments().get(0).arrival());
        assertTrue(v.segments().get(0).unverifiedFields().contains("arrival"));
        assertEquals(Severity.WARN, v.issues().get(0).severity());
    }

    @Test
    void shortConnectionAndBrokenContinuityWarn() {
        ValidatedItinerary v = validator.validate(ticket(
                seg("QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", 1.0),
                seg("QR729", "DXB", "DFW", "2026-11-28T07:20", "2026-11-28T15:50", 1.0))); // 03:20Z, 15 min after 03:05Z
        assertFalse(v.hasErrors());
        assertTrue(v.warnings().stream().anyMatch(i -> i.message().contains("minutes between flights")));
        assertTrue(v.warnings().stream().anyMatch(i -> i.message().contains("leaves from DXB")));
    }

    @Test
    void secondFlightBeforeFirstArrivesIsAnError() {
        ValidatedItinerary v = validator.validate(ticket(
                seg("QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", 1.0),
                seg("QR729", "DOH", "DFW", "2026-11-28T05:00", "2026-11-28T15:50", 1.0)));
        assertTrue(v.hasErrors());
    }

    @Test
    void segmentsAreSortedChronologically() {
        ValidatedItinerary v = validator.validate(ticket(
                seg("QR729", "DOH", "DFW", "2026-11-28T08:40", "2026-11-28T15:50", 1.0),
                seg("QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", 1.0)));
        assertEquals("QR4793", v.segments().get(0).flightNumber());
        assertEquals(1, v.segments().get(0).seq());
        assertEquals(2, v.segments().get(1).seq());
    }

    @Test
    void pastDepartureIsErrorUnlessAllowed() {
        Itinerary old = ticket(seg("QR1", "HYD", "DOH", "2025-01-10T03:45", "2025-01-10T06:05", 1.0));
        assertTrue(validator.validate(old).hasErrors());
        ItineraryValidator lenient = new ItineraryValidator(AIRPORTS, true, 60, NOW);
        ValidatedItinerary v = lenient.validate(old);
        assertFalse(v.hasErrors());
        assertTrue(v.warnings().get(0).message().contains("in the past"));
    }

    @Test
    void lowConfidenceAndOddFlightNumberAreUnverified() {
        ValidatedItinerary v = validator.validate(ticket(
                new Itinerary.Segment(null, "QATAR 4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", "T2", null, 0.5, "blurry")));
        assertFalse(v.hasErrors());
        ValidatedItinerary.Segment s = v.segments().get(0);
        assertTrue(s.unverifiedFields().contains("flight_number"));
        assertTrue(s.unverifiedFields().contains("segment"));
        assertEquals("T2", s.terminal());
        assertNull(s.airline());
    }

    @Test
    void nonTicketIsRejected() {
        ValidatedItinerary v = validator.validate(new Itinerary(false, "hotel booking", null, null, List.of()));
        assertTrue(v.hasErrors());
        assertTrue(v.errors().get(0).message().contains("hotel booking"));
        assertTrue(validator.validate(new Itinerary(true, "ticket", "A", null, List.of())).hasErrors());
    }

    @Test
    void summaryForChildListsFlightsAndWarnings() {
        ValidatedItinerary v = validator.validate(ticket(seg("QR1", "HYD", "DOH", "2026-11-28T03:45", null, 1.0)));
        String s = ItinerarySummary.forChild(v);
        assertTrue(s.startsWith("Itinerary for Lakshmi — 1 flight"));
        assertTrue(s.contains("QR1  HYD → DOH"));
        assertTrue(s.contains("28 Nov 03:45 (HYD local)"));
        assertTrue(s.contains("not printed"));
        assertTrue(s.contains("Please check:"));
        assertTrue(s.length() <= 1000);
    }
}
