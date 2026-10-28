package ai.concourse.ops;

import ai.concourse.config.ConcourseProperties;
import ai.concourse.journey.ConcourseClock;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operations endpoints (header {@code X-Admin-Token}). The only way a gate changes — the demo's "simulated operations
 * event", labelled as such on both phones — plus the time-travel clock and a read-only view of the latest journey.
 */
@RestController
@RequestMapping("/ops")
public class OpsController {
    private final ConcourseProperties props;
    private final JourneyController controller;
    private final JourneyService journeys;
    private final ConcourseClock clock;

    public OpsController(ConcourseProperties props, JourneyController controller, JourneyService journeys, ConcourseClock clock) {
        this.props = props;
        this.controller = controller;
        this.journeys = journeys;
        this.clock = clock;
    }

    public record OpsEvent(String journey_id, String type, Integer segment, String new_gate, String source) {}

    public record ClockRequest(String now, Integer shift_minutes, Boolean reset) {}

    @PostMapping("/events")
    public ResponseEntity<?> event(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody OpsEvent e) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "bad or missing X-Admin-Token (set ADMIN_TOKEN)"));
        if (!"GATE_CHANGE".equalsIgnoreCase(e.type())) return ResponseEntity.badRequest().body(Map.of("error", "type must be GATE_CHANGE"));
        Optional<JourneyEntity> j = e.journey_id() == null ? journeys.latest() : journeys.find(UUID.fromString(e.journey_id()));
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        String source = e.source() == null || e.source().isBlank() ? JourneyController.DEMO_SOURCE : e.source();
        boolean ok = controller.applyGateChange(j.get(), e.segment() == null ? 1 : e.segment(), e.new_gate(), source);
        return ok ? ResponseEntity.ok(Map.of("journey_id", j.get().getId().toString(), "applied", true, "source", source))
                : ResponseEntity.badRequest().body(Map.of("error", "no such flight segment"));
    }

    @GetMapping("/clock")
    public Map<String, Object> clock() {
        return Map.of("now", clock.now().toString(), "offset_minutes", clock.offset().toMinutes(), "travelling", clock.isTravelling());
    }

    @PostMapping("/clock")
    public ResponseEntity<?> setClock(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody ClockRequest r) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "bad or missing X-Admin-Token (set ADMIN_TOKEN)"));
        if (Boolean.TRUE.equals(r.reset())) clock.reset();
        else if (r.now() != null) clock.travelTo(OffsetDateTime.parse(r.now()).toInstant());
        else if (r.shift_minutes() != null) clock.shift(Duration.ofMinutes(r.shift_minutes()));
        controller.tick();
        return ResponseEntity.ok(clock());
    }

    @GetMapping("/journeys/latest")
    public ResponseEntity<?> latest(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "bad or missing X-Admin-Token (set ADMIN_TOKEN)"));
        Optional<JourneyEntity> j = journeys.latest();
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        JourneyEntity je = j.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey_id", je.getId().toString());
        out.put("status", je.getStatus());
        out.put("consent_at", String.valueOf(je.getConsentAt()));
        out.put("intro_sent_at", String.valueOf(je.getIntroSentAt()));
        List<Map<String, Object>> stages = controller.stages(je.getId()).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq()); m.put("type", s.getType()); m.put("airport", s.getAirport());
            m.put("scheduled_at", String.valueOf(s.getScheduledAt())); m.put("status", s.getStatus());
            m.put("sent_at", String.valueOf(s.getSentAt())); m.put("acknowledged_at", String.valueOf(s.getAcknowledgedAt()));
            return m;
        }).toList();
        out.put("stages", stages);
        out.put("clock", clock());
        return ResponseEntity.ok(out);
    }

    private boolean authorized(String token) {
        String expected = props.adminToken();
        return expected != null && !expected.isBlank() && !"change-me".equals(expected) && expected.equals(token);
    }
}
