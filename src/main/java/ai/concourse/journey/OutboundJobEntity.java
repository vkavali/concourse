package ai.concourse.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A timed send, polled by {@code StageScheduler}. One row per planned stage push. */
@Entity
@Table(name = "outbound_job")
public class OutboundJobEntity implements Persistable<UUID> {
    public static final String PENDING = "PENDING", SENT = "SENT", FAILED = "FAILED", CANCELLED = "CANCELLED";
    public static final String KIND_STAGE_PUSH = "STAGE_PUSH";

    @Id
    private UUID id;

    @Column(name = "journey_id", nullable = false)
    private UUID journeyId;

    @Column(name = "stage_id")
    private UUID stageId;

    @Column(name = "run_at", nullable = false)
    private OffsetDateTime runAt;

    @Column(nullable = false)
    private String kind = KIND_STAGE_PUSH;

    @Column(nullable = false)
    private String payload = "{}";

    @Column(nullable = false)
    private String status = PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() { return isNew; }

    @PostPersist
    @PostLoad
    void markNotNew() { this.isNew = false; }

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (status == null) status = PENDING;
        if (payload == null) payload = "{}";
        if (kind == null) kind = KIND_STAGE_PUSH;
    }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getJourneyId() { return journeyId; }
    public void setJourneyId(UUID journeyId) { this.journeyId = journeyId; }
    public UUID getStageId() { return stageId; }
    public void setStageId(UUID stageId) { this.stageId = stageId; }
    public OffsetDateTime getRunAt() { return runAt; }
    public void setRunAt(OffsetDateTime runAt) { this.runAt = runAt; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
