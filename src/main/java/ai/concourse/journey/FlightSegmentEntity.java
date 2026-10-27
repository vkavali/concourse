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

/**
 * A verified flight leg. {@code gate} and times change only through {@code apply_verified_flight_update}
 * (ops/flight-status source), never from a parent message or a sign photo.
 */
@Entity
@Table(name = "flight_segment")
public class FlightSegmentEntity implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(name = "journey_id", nullable = false)
    private UUID journeyId;

    @Column(nullable = false)
    private int seq;

    private String airline;

    @Column(name = "flight_number", nullable = false)
    private String flightNumber;

    @Column(nullable = false, length = 3)
    private String origin;

    @Column(nullable = false, length = 3)
    private String destination;

    @Column(name = "scheduled_departure", nullable = false)
    private OffsetDateTime scheduledDeparture;

    @Column(name = "scheduled_arrival")
    private OffsetDateTime scheduledArrival;

    private String terminal;

    private String gate;

    @Column(nullable = false)
    private String source = "ticket";

    @Column(name = "last_verified_at")
    private OffsetDateTime lastVerifiedAt;

    /** JSON array of field names the child still has to confirm, e.g. ["arrival"]. */
    @Column(name = "unverified_fields")
    private String unverifiedFields;

    /** Ids are assigned by code, so Spring Data cannot infer "new" from a null id; without this it would merge() (SELECT + copy) instead of persist(). */
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
        if (source == null) source = "ticket";
    }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getJourneyId() { return journeyId; }
    public void setJourneyId(UUID journeyId) { this.journeyId = journeyId; }
    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }
    public String getAirline() { return airline; }
    public void setAirline(String airline) { this.airline = airline; }
    public String getFlightNumber() { return flightNumber; }
    public void setFlightNumber(String flightNumber) { this.flightNumber = flightNumber; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public OffsetDateTime getScheduledDeparture() { return scheduledDeparture; }
    public void setScheduledDeparture(OffsetDateTime scheduledDeparture) { this.scheduledDeparture = scheduledDeparture; }
    public OffsetDateTime getScheduledArrival() { return scheduledArrival; }
    public void setScheduledArrival(OffsetDateTime scheduledArrival) { this.scheduledArrival = scheduledArrival; }
    public String getTerminal() { return terminal; }
    public void setTerminal(String terminal) { this.terminal = terminal; }
    public String getGate() { return gate; }
    public void setGate(String gate) { this.gate = gate; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public OffsetDateTime getLastVerifiedAt() { return lastVerifiedAt; }
    public void setLastVerifiedAt(OffsetDateTime lastVerifiedAt) { this.lastVerifiedAt = lastVerifiedAt; }
    public String getUnverifiedFields() { return unverifiedFields; }
    public void setUnverifiedFields(String unverifiedFields) { this.unverifiedFields = unverifiedFields; }
}
