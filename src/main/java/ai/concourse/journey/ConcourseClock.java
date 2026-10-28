package ai.concourse.journey;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The clock the journey engine runs on. Real time by default; "time travel" shifts it so a whole journey can be
 * rehearsed in ten minutes (ops endpoint, the child's {@code next} command, or {@code CONCOURSE_CLOCK=<instant>} at
 * start-up). Only stage timing follows this clock — WhatsApp's 24-hour window is always measured in real time.
 */
@Component
public class ConcourseClock extends Clock {
    private volatile Duration offset = Duration.ZERO;

    public ConcourseClock() {
        String start = System.getenv("CONCOURSE_CLOCK");
        if (start != null && !start.isBlank()) {
            try {
                travelTo(OffsetDateTime.parse(start.strip()).toInstant());
            } catch (RuntimeException e) {
                System.out.println("concourse: ignoring CONCOURSE_CLOCK=" + start + " (" + e.getMessage() + ")");
            }
        }
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.now().plus(offset); }

    public OffsetDateTime now() { return OffsetDateTime.now(this); }

    public void travelTo(Instant target) { offset = Duration.between(Instant.now(), target); }
    public void shift(Duration by) { offset = offset.plus(by); }
    public void reset() { offset = Duration.ZERO; }
    public Duration offset() { return offset; }
    public boolean isTravelling() { return !offset.isZero(); }
}
