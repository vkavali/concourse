package ai.concourse.journey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JourneyRepository extends JpaRepository<JourneyEntity, UUID> {
    Optional<JourneyEntity> findFirstByChildPhoneHashOrderByCreatedAtDesc(String childPhoneHash);
    Optional<JourneyEntity> findFirstByParentPhoneHashAndStatusNotOrderByCreatedAtDesc(String parentPhoneHash, String status);
}
