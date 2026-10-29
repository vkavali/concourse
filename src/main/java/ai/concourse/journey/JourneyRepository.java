package ai.concourse.journey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JourneyRepository extends JpaRepository<JourneyEntity, UUID> {
    Optional<JourneyEntity> findFirstByChildPhoneHashOrderByCreatedAtDesc(String childPhoneHash);
    Optional<JourneyEntity> findFirstByParentPhoneHashAndStatusNotInOrderByCreatedAtDesc(String parentPhoneHash, java.util.Collection<String> statuses);
    List<JourneyEntity> findByParentPhoneHashAndStatusNotIn(String parentPhoneHash, java.util.Collection<String> statuses);
    Optional<JourneyEntity> findFirstByOrderByCreatedAtDesc();
}
