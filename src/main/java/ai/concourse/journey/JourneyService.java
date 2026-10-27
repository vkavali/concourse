package ai.concourse.journey;

import ai.concourse.itinerary.ValidatedItinerary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The only writer of journey and flight_segment rows. Day 1: create from a validated ticket, confirm, read back. */
@Service
public class JourneyService {
    private static final Logger log = LoggerFactory.getLogger(JourneyService.class);
    private static final int EXPIRE_DAYS_AFTER_ARRIVAL = 7;

    private final JourneyRepository journeys;
    private final FlightSegmentRepository segments;
    private final EventLog eventLog;
    private final ObjectMapper mapper;

    public JourneyService(JourneyRepository journeys, FlightSegmentRepository segments, EventLog eventLog, ObjectMapper mapper) {
        this.journeys = journeys;
        this.segments = segments;
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    @Transactional
    public JourneyEntity createFromItinerary(String childPhoneHash, ValidatedItinerary v) {
        if (v.hasErrors()) throw new IllegalArgumentException("itinerary has errors: " + v.errors());
        JourneyEntity j = new JourneyEntity();
        j.setId(UUID.randomUUID());
        j.setChildPhoneHash(childPhoneHash);
        j.setStatus("PRE_TRIP");
        j.setPassengerFirstName(v.raw().passengerFirstName());
        OffsetDateTime last = v.lastArrivalOrDeparture();
        if (last != null) j.setExpiresAt(last.plusDays(EXPIRE_DAYS_AFTER_ARRIVAL));
        journeys.save(j);

        for (ValidatedItinerary.Segment s : v.segments()) {
            FlightSegmentEntity f = new FlightSegmentEntity();
            f.setId(UUID.randomUUID());
            f.setJourneyId(j.getId());
            f.setSeq(s.seq());
            f.setAirline(s.airline());
            f.setFlightNumber(s.flightNumber());
            f.setOrigin(s.origin());
            f.setDestination(s.destination());
            f.setScheduledDeparture(s.departure());
            f.setScheduledArrival(s.arrival());
            f.setTerminal(s.terminal());
            f.setGate(s.gate());
            f.setSource("ticket");
            f.setLastVerifiedAt(OffsetDateTime.now());
            f.setUnverifiedFields(toJson(s.unverifiedFields()));
            segments.save(f);
        }
        eventLog.record(j.getId(), EventLog.ACTOR_CHILD, "ticket_ingested", Map.of(
                "segments", v.segments().size(),
                "route", v.segments().stream().map(s -> s.origin() + ">" + s.destination()).toList(),
                "warnings", v.warnings().size()));
        log.info("journey {} created with {} segments", j.getId(), v.segments().size());
        return j;
    }

    @Transactional
    public Optional<JourneyEntity> confirmItinerary(UUID journeyId, String childPhoneHash) {
        Optional<JourneyEntity> j = journeys.findById(journeyId).filter(x -> x.getChildPhoneHash().equals(childPhoneHash));
        j.ifPresent(x -> {
            x.setItineraryConfirmedAt(OffsetDateTime.now());
            journeys.save(x);
            eventLog.record(x.getId(), EventLog.ACTOR_CHILD, "itinerary_confirmed", Map.of());
        });
        return j;
    }

    @Transactional(readOnly = true)
    public Optional<JourneyEntity> latestForChild(String childPhoneHash) {
        return journeys.findFirstByChildPhoneHashOrderByCreatedAtDesc(childPhoneHash);
    }

    @Transactional(readOnly = true)
    public Optional<JourneyEntity> latestForParent(String parentPhoneHash) {
        return journeys.findFirstByParentPhoneHashAndStatusNotOrderByCreatedAtDesc(parentPhoneHash, "COMPLETE");
    }

    /** Links the traveler's number to the child's latest journey. Returns empty if the child has no journey yet. */
    @Transactional
    public Optional<JourneyEntity> attachParent(String childPhoneHash, String parentPhoneHash) {
        Optional<JourneyEntity> j = journeys.findFirstByChildPhoneHashOrderByCreatedAtDesc(childPhoneHash);
        j.ifPresent(x -> {
            x.setParentPhoneHash(parentPhoneHash);
            journeys.save(x);
            eventLog.record(x.getId(), EventLog.ACTOR_CHILD, "parent_attached", Map.of());
        });
        return j;
    }

    /**
     * The only way a gate changes. Source is recorded ("demo operations event", "aeroapi", ...). Never called from
     * parent input or sign photos.
     */
    @Transactional
    public Optional<FlightSegmentEntity> applyVerifiedFlightUpdate(UUID journeyId, int seq, String newGate, String source) {
        Optional<FlightSegmentEntity> seg = segments.findByJourneyIdOrderBySeq(journeyId).stream()
                .filter(x -> x.getSeq() == seq).findFirst();
        seg.ifPresent(x -> {
            String old = x.getGate();
            x.setGate(newGate == null || newGate.isBlank() ? null : newGate.strip().toUpperCase());
            x.setSource(source);
            x.setLastVerifiedAt(OffsetDateTime.now());
            segments.save(x);
            eventLog.record(journeyId, EventLog.ACTOR_OPS, "gate_update",
                    Map.of("seq", seq, "from", old == null ? "" : old, "to", x.getGate() == null ? "" : x.getGate(), "source", source));
        });
        return seg;
    }

    @Transactional(readOnly = true)
    public Optional<JourneyEntity> find(UUID id) {
        return journeys.findById(id);
    }

    @Transactional(readOnly = true)
    public List<FlightSegmentEntity> segments(UUID journeyId) {
        return segments.findByJourneyIdOrderBySeq(journeyId);
    }

    private String toJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }
}
