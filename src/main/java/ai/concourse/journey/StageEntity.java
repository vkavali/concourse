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

/** One planned step of a journey: what to tell her, where, and when. Written only by {@code JourneyController}. */
@Entity
@Table(name = "stage")
public class StageEntity implements Persistable<UUID> {
    public static final String PLANNED = "PLANNED", SENT = "SENT", ACKED = "ACKED", SKIPPED = "SKIPPED";

    @Id
    private UUID id;

    @Column(name = "journey_id", nullable = false)
    private UUID journeyId;

    /** Position in the plan (1-based). */
    @Column(nullable = false)
    private int seq;

    /** {@link StageType} name. */
    @Column(nullable = false)
    private String type;

    /** IATA code of the airport this stage happens at. */
    private String airport;

    /** The flight segment this stage belongs to (1-based), for gate lookups. */
    @Column(name = "segment_seq")
    private Integer segmentSeq;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(nullable = false)
    private String status = PLANNED;

    @Column(name = "instruction_version", nullable = false)
    private int instructionVersion = 1;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "acknowledged_at")
    private OffsetDateTime acknowledgedAt;

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
        if (status == null) status = PLANNED;
    }

    public StageType stageType() { return StageType.valueOf(type); }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getJourneyId() { return journeyId; }
    public void setJourneyId(UUID journeyId) { this.journeyId = journeyId; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getAirport() { return airport; }
    public void setAirport(String airport) { this.airport = airport; }
    public Integer getSegmentSeq() { return segmentSeq; }
    public void setSegmentSeq(Integer segmentSeq) { this.segmentSeq = segmentSeq; }
    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getInstructionVersion() { return instructionVersion; }
    public void setInstructionVersion(int instructionVersion) { this.instructionVersion = instructionVersion; }
    public OffsetDateTime getSentAt() { return sentAt; }
    public void setSentAt(OffsetDateTime sentAt) { this.sentAt = sentAt; }
    public OffsetDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(OffsetDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
}
