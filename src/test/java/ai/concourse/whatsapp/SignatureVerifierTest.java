package ai.concourse.whatsapp;

import ai.concourse.config.ConcourseProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SignatureVerifierTest {
    private final SignatureVerifier verifier = new SignatureVerifier(new ConcourseProperties(
            "http://localhost", "admin", "salt",
            new ConcourseProperties.WhatsApp("tok", "123", "verify", "secret", "http://graph"),
            new ConcourseProperties.Meta("k", "http://meta", "spark", "stt", true, 120),
            null, null, null));

    @Test
    void acceptsCorrectSignature() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = "sha256=" + verifier.hmacHex(body);
        assertTrue(verifier.isValid(sig, body));
    }

    @Test
    void rejectsWrongSignature() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertFalse(verifier.isValid("sha256=deadbeef", body));
        assertFalse(verifier.isValid(null, body));
    }
}
