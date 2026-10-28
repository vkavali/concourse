package ai.concourse.journey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboundJobRepository extends JpaRepository<OutboundJobEntity, UUID> {
    List<OutboundJobEntity> findByStatusAndRunAtLessThanEqualOrderByRunAt(String status, OffsetDateTime runAt);
    List<OutboundJobEntity> findByJourneyIdAndStatus(UUID journeyId, String status);
    List<OutboundJobEntity> findByJourneyIdOrderByRunAt(UUID journeyId);
}
