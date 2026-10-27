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

/** Phone hash → sendable number and role. The only place a phone number is stored (encrypt at rest in production). */
@Entity
@Table(name = "contact")
public class ContactEntity implements Persistable<String> {
    public enum Role { PARENT, CHILD }

    @Id
    @Column(name = "phone_hash")
    private String phoneHash;

    @Column(nullable = false)
    private String e164;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String language = "te";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

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
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (language == null) language = "te";
    }

    @Override
    public String getId() { return phoneHash; }
    public String getPhoneHash() { return phoneHash; }
    public void setPhoneHash(String phoneHash) { this.phoneHash = phoneHash; }
    public String getE164() { return e164; }
    public void setE164(String e164) { this.e164 = e164; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Role role() { return Role.valueOf(role); }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
