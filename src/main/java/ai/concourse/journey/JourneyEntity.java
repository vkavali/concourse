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

/** One trip for one parent, configured by one child. Status values are the journey states in docs/ARCHITECTURE.md. */
@Entity
@Table(name = "journey")
public class JourneyEntity implements Persistable<UUID> {
    @Id
    private UUID id;

    @Column(name = "parent_phone_hash")
    private String parentPhoneHash;

    @Column(name = "child_phone_hash", nullable = false)
    private String childPhoneHash;

    @Column(name = "parent_language", nullable = false)
    private String parentLanguage = "te";

    @Column(nullable = false)
    private String status = "PRE_TRIP";

    @Column(name = "consent_at")
    private OffsetDateTime consentAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "passenger_first_name")
    private String passengerFirstName;

    @Column(name = "itinerary_confirmed_at")
    private OffsetDateTime itineraryConfirmedAt;

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
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "PRE_TRIP";
        if (parentLanguage == null) parentLanguage = "te";
    }

    @Override
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getParentPhoneHash() { return parentPhoneHash; }
    public void setParentPhoneHash(String parentPhoneHash) { this.parentPhoneHash = parentPhoneHash; }
    public String getChildPhoneHash() { return childPhoneHash; }
    public void setChildPhoneHash(String childPhoneHash) { this.childPhoneHash = childPhoneHash; }
    public String getParentLanguage() { return parentLanguage; }
    public void setParentLanguage(String parentLanguage) { this.parentLanguage = parentLanguage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public OffsetDateTime getConsentAt() { return consentAt; }
    public void setConsentAt(OffsetDateTime consentAt) { this.consentAt = consentAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public String getPassengerFirstName() { return passengerFirstName; }
    public void setPassengerFirstName(String passengerFirstName) { this.passengerFirstName = passengerFirstName; }
    public OffsetDateTime getItineraryConfirmedAt() { return itineraryConfirmedAt; }
    public void setItineraryConfirmedAt(OffsetDateTime itineraryConfirmedAt) { this.itineraryConfirmedAt = itineraryConfirmedAt; }
}
