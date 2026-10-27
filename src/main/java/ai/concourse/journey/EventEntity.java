package ai.concourse.journey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only event log. Payloads are sanitized before they get here (no names, numbers, transcripts). */
@Entity
@Table(name = "event")
public class EventEntity {
    @Id
    private UUID id;

    @Column(name = "journey_id")
    private UUID journeyId;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String type;

    @Column(name = "sanitized_payload")
    private String sanitizedPayload;

    @Column(nullable = false)
    private OffsetDateTime ts;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (ts == null) ts = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getJourneyId() { return journeyId; }
    public void setJourneyId(UUID journeyId) { this.journeyId = journeyId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSanitizedPayload() { return sanitizedPayload; }
    public void setSanitizedPayload(String sanitizedPayload) { this.sanitizedPayload = sanitizedPayload; }
    public OffsetDateTime getTs() { return ts; }
    public void setTs(OffsetDateTime ts) { this.ts = ts; }
}
