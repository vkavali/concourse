package ai.concourse.journey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FlightSegmentRepository extends JpaRepository<FlightSegmentEntity, UUID> {
    List<FlightSegmentEntity> findByJourneyIdOrderBySeq(UUID journeyId);
}
