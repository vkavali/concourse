package ai.concourse.journey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StageRepository extends JpaRepository<StageEntity, UUID> {
    List<StageEntity> findByJourneyIdOrderBySeq(UUID journeyId);
    List<StageEntity> findByJourneyIdAndStatusOrderBySeq(UUID journeyId, String status);
}
