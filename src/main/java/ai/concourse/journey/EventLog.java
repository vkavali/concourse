package ai.concourse.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Writes the append-only event log. Callers pass already-sanitized payloads (counts, codes, durations — never names,
 * phone numbers, or transcripts). Logging never fails the business operation.
 */
@Component
public class EventLog {
    private static final Logger log = LoggerFactory.getLogger(EventLog.class);

    public static final String ACTOR_PARENT = "parent";
    public static final String ACTOR_CHILD = "child";
    public static final String ACTOR_SYSTEM = "system";
    public static final String ACTOR_OPS = "ops";

    private final EventRepository events;
    private final ObjectMapper mapper;

    public EventLog(EventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional // joins the caller's transaction: the journey row it references may not be committed yet
    public void record(UUID journeyId, String actor, String type, Map<String, ?> payload) {
        try {
            EventEntity e = new EventEntity();
            e.setJourneyId(journeyId);
            e.setActor(actor);
            e.setType(type);
            e.setSanitizedPayload(payload == null ? null : mapper.writeValueAsString(payload));
            events.save(e);
        } catch (Exception ex) {
            log.warn("event not recorded type={} journey={}: {}", type, journeyId, ex.getMessage());
        }
    }
}
