package ai.concourse.journey;

import ai.concourse.itinerary.Itinerary;
import ai.concourse.itinerary.ItineraryValidator;
import ai.concourse.itinerary.ValidatedItinerary;
import ai.concourse.whatsapp.InboundMessageHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Real Flyway schema (V1+V2) on H2 in PostgreSQL mode; the entities must round-trip through it. */
@SpringBootTest
@ActiveProfiles("test")
class JourneyServiceTest {
    @Autowired JourneyService journeys;
    @Autowired ItineraryValidator validator;
    @Autowired EventRepository events;
    @Autowired ContactService contacts;
    @MockBean InboundMessageHandler handler;

    private ValidatedItinerary demoItinerary() {
        Itinerary raw = new Itinerary(true, "e-ticket", "Lakshmi", null, List.of(
                new Itinerary.Segment("Qatar Airways", "QR4793", "HYD", "DOH", "2026-11-28T03:45", "2026-11-28T06:05", null, null, 1.0, ""),
                new Itinerary.Segment("Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28T08:40", null, "D", null, 1.0, "")));
        ValidatedItinerary v = validator.validate(raw);
        assertFalse(v.hasErrors(), v.issues().toString());
        return v;
    }

    @Test
    void createsJourneyWithSegmentsAndEvent() {
        ValidatedItinerary v = demoItinerary();
        JourneyEntity j = journeys.createFromItinerary("child-hash-1", v);

        assertNotNull(j.getId());
        assertEquals("PRE_TRIP", j.getStatus());
        assertEquals("Lakshmi", j.getPassengerFirstName());
        assertNotNull(j.getCreatedAt());
        // no arrival on the last leg → expiry is computed from its departure + 7 days
        assertEquals(Duration.ofDays(7), Duration.between(v.segments().get(1).departure(), j.getExpiresAt()));

        List<FlightSegmentEntity> segs = journeys.segments(j.getId());
        assertEquals(2, segs.size());
        assertEquals("QR4793", segs.get(0).getFlightNumber());
        assertEquals("HYD", segs.get(0).getOrigin());
        assertEquals(v.segments().get(0).departure().toInstant(), segs.get(0).getScheduledDeparture().toInstant());
        assertNull(segs.get(1).getScheduledArrival());
        assertEquals("D", segs.get(1).getTerminal());
        assertTrue(segs.get(1).getUnverifiedFields().contains("arrival"));
        assertEquals("ticket", segs.get(1).getSource());

        List<EventEntity> log = events.findByJourneyIdOrderByTs(j.getId());
        assertEquals(1, log.size());
        assertEquals("ticket_ingested", log.get(0).getType());
        assertTrue(log.get(0).getSanitizedPayload().contains("HYD>DOH"));
        assertFalse(log.get(0).getSanitizedPayload().contains("Lakshmi"), "names never reach the event log");

        assertEquals(j.getId(), journeys.latestForChild("child-hash-1").orElseThrow().getId());
    }

    @Test
    void confirmIsScopedToTheChildWhoCreatedIt() {
        JourneyEntity j = journeys.createFromItinerary("child-hash-2", demoItinerary());
        assertTrue(journeys.confirmItinerary(j.getId(), "someone-else").isEmpty());
        assertNull(journeys.find(j.getId()).orElseThrow().getItineraryConfirmedAt());

        Optional<JourneyEntity> confirmed = journeys.confirmItinerary(j.getId(), "child-hash-2");
        assertTrue(confirmed.isPresent());
        assertNotNull(journeys.find(j.getId()).orElseThrow().getItineraryConfirmedAt());
    }

    @Test
    void verifiedFlightUpdateIsTheOnlyGateWriter() {
        JourneyEntity j = journeys.createFromItinerary("child-hash-3", demoItinerary());
        assertNull(journeys.segments(j.getId()).get(0).getGate());
        FlightSegmentEntity updated = journeys.applyVerifiedFlightUpdate(j.getId(), 1, "b12", "demo operations event").orElseThrow();
        assertEquals("B12", updated.getGate());
        assertEquals("demo operations event", updated.getSource());
        assertEquals("B12", journeys.segments(j.getId()).get(0).getGate());
        assertTrue(journeys.applyVerifiedFlightUpdate(j.getId(), 9, "C1", "x").isEmpty(), "unknown segment");
        List<EventEntity> log = events.findByJourneyIdOrderByTs(j.getId());
        assertTrue(log.stream().anyMatch(e -> "gate_update".equals(e.getType()) && e.getSanitizedPayload().contains("B12")));
    }

    @Test
    void contactsDefaultToChildUntilRegistered() {
        assertEquals(ContactEntity.Role.CHILD, contacts.roleOf("919876543210"));
        contacts.register("+91 98765 43210", ContactEntity.Role.PARENT, "te");
        assertEquals(ContactEntity.Role.PARENT, contacts.roleOf("919876543210"));
        assertEquals("919876543210", contacts.find("919876543210").orElseThrow().getE164());
    }
}
