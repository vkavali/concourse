package ai.concourse.journey;

import ai.concourse.common.PhoneHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Who is talking: a registered parent, or (by default) a child configuring the service. */
@Service
public class ContactService {
    private final ContactRepository contacts;
    private final PhoneHasher hasher;

    public ContactService(ContactRepository contacts, PhoneHasher hasher) {
        this.contacts = contacts;
        this.hasher = hasher;
    }

    @Transactional(readOnly = true)
    public ContactEntity.Role roleOf(String waId) {
        return contacts.findById(hasher.hash(waId)).map(ContactEntity::role).orElse(ContactEntity.Role.CHILD);
    }

    @Transactional(readOnly = true)
    public Optional<ContactEntity> find(String waId) {
        return contacts.findById(hasher.hash(waId));
    }

    @Transactional
    public ContactEntity register(String waId, ContactEntity.Role role, String language) {
        String hash = hasher.hash(waId);
        ContactEntity c = contacts.findById(hash).orElseGet(ContactEntity::new);
        c.setPhoneHash(hash);
        c.setE164(PhoneHasher.normalize(waId));
        c.setRole(role.name());
        c.setLanguage(language == null ? "te" : language);
        return contacts.save(c);
    }
}
