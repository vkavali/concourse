package ai.concourse.ops;

import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.itinerary.Itinerary;
import ai.concourse.itinerary.ItineraryValidator;
import ai.concourse.itinerary.ValidatedItinerary;
import ai.concourse.journey.ConcourseClock;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.EventEntity;
import ai.concourse.journey.EventRepository;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import ai.concourse.travel.TravelLinks;
import ai.concourse.whatsapp.OutboxChannel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operations endpoints (header {@code X-Admin-Token}), also what the simulator page ({@code /sim.html}) drives:
 * the only way a gate changes (a "simulated operations event", labelled as such on both phones), the time-travel
 * clock, a demo journey built from the sample itinerary without a ticket, her consent/ack/"I'm here" as if she had
 * tapped them, and a read-only view of the latest journey plus the outbox of what each phone was sent.
 */
@RestController
@RequestMapping("/ops")
public class OpsController {
    private final ConcourseProperties props;
    private final JourneyController controller;
    private final JourneyService journeys;
    private final ConcourseClock clock;
    private final ItineraryValidator validator;
    private final ContactService contacts;
    private final PhoneHasher hasher;
    private final EventRepository events;
    private final OutboxChannel outbox;
    private final TravelLinks travelLinks;

    public OpsController(ConcourseProperties props, JourneyController controller, JourneyService journeys, ConcourseClock clock,
                         ItineraryValidator validator, ContactService contacts, PhoneHasher hasher, EventRepository events,
                         OutboxChannel outbox, TravelLinks travelLinks) {
        this.travelLinks = travelLinks;
        this.props = props;
        this.controller = controller;
        this.journeys = journeys;
        this.clock = clock;
        this.validator = validator;
        this.contacts = contacts;
        this.hasher = hasher;
        this.events = events;
        this.outbox = outbox;
    }

    public record OpsEvent(String journey_id, String type, Integer segment, String new_gate, String source) {}

    public record ClockRequest(String now, Integer shift_minutes, Boolean reset) {}

    public record DemoRequest(String child, String parent, Integer days_ahead) {}

    // ---- the gate change --------------------------------------------------------------------------------------

    @PostMapping("/events")
    public ResponseEntity<?> event(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody OpsEvent e) {
        if (!authorized(token)) return forbidden();
        if (!"GATE_CHANGE".equalsIgnoreCase(e.type())) return ResponseEntity.badRequest().body(Map.of("error", "type must be GATE_CHANGE"));
        Optional<JourneyEntity> j = e.journey_id() == null ? journeys.latest() : journeys.find(UUID.fromString(e.journey_id()));
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        String source = e.source() == null || e.source().isBlank() ? JourneyController.DEMO_SOURCE : e.source();
        boolean ok = controller.applyGateChange(j.get(), e.segment() == null ? 1 : e.segment(), e.new_gate(), source);
        return ok ? ResponseEntity.ok(Map.of("journey_id", j.get().getId().toString(), "applied", true, "source", source))
                : ResponseEntity.badRequest().body(Map.of("error", "no such flight segment"));
    }

    // ---- the clock ----------------------------------------------------------------------------------------------

    @GetMapping("/clock")
    public Map<String, Object> clock() {
        return Map.of("now", clock.now().toString(), "offset_minutes", clock.offset().toMinutes(), "travelling", clock.isTravelling());
    }

    @PostMapping("/clock")
    public ResponseEntity<?> setClock(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody ClockRequest r) {
        if (!authorized(token)) return forbidden();
        if (Boolean.TRUE.equals(r.reset())) clock.reset();
        else if (r.now() != null) clock.travelTo(OffsetDateTime.parse(r.now()).toInstant());
        else if (r.shift_minutes() != null) clock.shift(Duration.ofMinutes(r.shift_minutes()));
        controller.tick();
        return ResponseEntity.ok(clock());
    }

    // ---- demo journey without a ticket -----------------------------------------------------------------------

    /**
     * Builds the sample HYD→DOH→DFW journey dated {@code days_ahead} days from now (default 2), registers the two
     * numbers, confirms the itinerary and sends the intro — the same path a real ticket takes, minus Spark.
     * Any other open journey of the same traveler is abandoned so only one journey ever speaks to her.
     */
    @PostMapping("/demo")
    public ResponseEntity<?> demo(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody DemoRequest r) {
        if (!authorized(token)) return forbidden();
        String child = PhoneHasher.normalize(r.child() == null ? "" : r.child());
        String parent = PhoneHasher.normalize(r.parent() == null || r.parent().isBlank() ? child : r.parent());
        if (child.length() < 10) return ResponseEntity.badRequest().body(Map.of("error", "child must be a WhatsApp number with country code"));
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(r.days_ahead() == null ? 2 : Math.max(1, r.days_ahead()));
        String d = day.toString();
        Itinerary raw = new Itinerary(true, "sample itinerary", "Lakshmi", "DEMO42", List.of(
                new Itinerary.Segment("Qatar Airways", "QR4793", "HYD", "DOH", d + "T03:45", d + "T06:05", null, null, 1.0, ""),
                new Itinerary.Segment("Qatar Airways", "QR729", "DOH", "DFW", d + "T08:40", d + "T15:50", "D", null, 1.0, "")));
        ValidatedItinerary v = validator.validate(raw);
        if (v.hasErrors()) return ResponseEntity.badRequest().body(Map.of("error", v.errors().toString()));
        clock.reset();
        contacts.register(child, ContactEntity.Role.CHILD, "en");
        contacts.register(parent, ContactEntity.Role.PARENT, "te");
        JourneyEntity j = journeys.createFromItinerary(hasher.hash(child), v);
        journeys.confirmItinerary(j.getId(), hasher.hash(child));
        journeys.attachParent(hasher.hash(child), hasher.hash(parent));
        for (JourneyEntity old : journeys.abandonOthers(hasher.hash(parent), j.getId())) controller.cancelPendingJobs(old.getId(), "abandoned for demo " + j.getId());
        controller.onJourneyReady(j.getId());
        return ResponseEntity.ok(Map.of("journey_id", j.getId().toString(), "date", d, "parent_is_child", parent.equals(child)));
    }

    // ---- acting for her (the simulator's buttons) --------------------------------------------------------------

    @PostMapping("/journeys/latest/{action}")
    public ResponseEntity<?> act(@RequestHeader(value = "X-Admin-Token", required = false) String token, @PathVariable String action) {
        if (!authorized(token)) return forbidden();
        Optional<JourneyEntity> j = journeys.latest();
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        JourneyEntity je = j.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey_id", je.getId().toString());
        switch (action) {
            case "consent" -> controller.recordConsent(je, "simulator");
            case "ack" -> controller.acknowledge(je, "simulator");
            case "here" -> out.put("complete", controller.markHere(je, "simulator"));
            case "next" -> out.put("fired", controller.fireNext(je).map(StageEntity::getType).orElse("nothing planned"));
            case "tick" -> controller.tick();
            default -> { return ResponseEntity.badRequest().body(Map.of("error", "unknown action " + action)); }
        }
        out.put("status", journeys.find(je.getId()).map(JourneyEntity::getStatus).orElse("?"));
        return ResponseEntity.ok(out);
    }

    // ---- read side ---------------------------------------------------------------------------------------------

    @GetMapping("/journeys/latest")
    public ResponseEntity<?> latest(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) return forbidden();
        Optional<JourneyEntity> j = journeys.latest();
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        JourneyEntity je = j.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey_id", je.getId().toString());
        out.put("traveler", je.getPassengerFirstName());
        out.put("status", je.getStatus());
        out.put("confirmed", je.getItineraryConfirmedAt() != null);
        out.put("parent_registered", je.getParentPhoneHash() != null);
        out.put("intro_sent", je.getIntroSentAt() != null);
        out.put("consent_at", je.getConsentAt() == null ? null : je.getConsentAt().toString());
        out.put("current_stage_id", je.getCurrentStageId() == null ? null : je.getCurrentStageId().toString());
        out.put("travel_url", travelLinks.url(je.getId()));
        out.put("flights", journeys.segments(je.getId()).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq()); m.put("flight", s.getFlightNumber()); m.put("from", s.getOrigin()); m.put("to", s.getDestination());
            m.put("departure", String.valueOf(s.getScheduledDeparture())); m.put("arrival", String.valueOf(s.getScheduledArrival()));
            m.put("terminal", s.getTerminal()); m.put("gate", s.getGate()); m.put("source", s.getSource());
            return m;
        }).toList());
        out.put("stages", controller.stages(je.getId()).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId().toString()); m.put("seq", s.getSeq()); m.put("type", s.getType()); m.put("airport", s.getAirport());
            m.put("scheduled_at", s.getScheduledAt() == null ? null : s.getScheduledAt().toString()); m.put("status", s.getStatus());
            m.put("sent_at", s.getSentAt() == null ? null : s.getSentAt().toString());
            m.put("acknowledged_at", s.getAcknowledgedAt() == null ? null : s.getAcknowledgedAt().toString());
            return m;
        }).toList());
        List<EventEntity> log = events.findByJourneyIdOrderByTs(je.getId());
        out.put("events", log.subList(Math.max(0, log.size() - 60), log.size()).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", String.valueOf(e.getTs())); m.put("actor", e.getActor()); m.put("type", e.getType()); m.put("payload", e.getSanitizedPayload());
            return m;
        }).toList());
        out.put("clock", clock());
        return ResponseEntity.ok(out);
    }

    /** Everything sent to either phone since {@code after} (outbox sequence number), by role — never by number. */
    @GetMapping("/outbox")
    public ResponseEntity<?> outbox(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @RequestParam(value = "after", defaultValue = "0") long after) {
        if (!authorized(token)) return forbidden();
        return ResponseEntity.ok(outbox.since(after));
    }

    private boolean authorized(String token) {
        String expected = props.adminToken();
        return expected != null && !expected.isBlank() && !"change-me".equals(expected) && expected.equals(token);
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "bad or missing X-Admin-Token (set ADMIN_TOKEN in .env)"));
    }
}
