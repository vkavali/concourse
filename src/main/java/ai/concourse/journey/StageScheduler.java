package ai.concourse.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 30 s: let the controller push whatever the clock says is due. Single instance; the DB rows are the queue. */
@Component
public class StageScheduler {
    private static final Logger log = LoggerFactory.getLogger(StageScheduler.class);
    private final JourneyController controller;

    public StageScheduler(JourneyController controller) {
        this.controller = controller;
    }

    @Scheduled(fixedDelayString = "${concourse.scheduler.delay-ms:30000}", initialDelayString = "${concourse.scheduler.initial-delay-ms:15000}")
    public void tick() {
        try {
            controller.tick();
        } catch (RuntimeException e) {
            log.error("scheduler tick failed", e);
        }
    }
}
