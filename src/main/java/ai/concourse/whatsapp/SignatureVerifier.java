package ai.concourse.whatsapp;

import ai.concourse.config.ConcourseProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Validates the X-Hub-Signature-256 header Meta sends with every webhook POST (HMAC-SHA256 of the raw body with the app secret). */
@Component
public class SignatureVerifier {
    private final String appSecret;

    public SignatureVerifier(ConcourseProperties props) {
        this.appSecret = props.whatsapp().appSecret();
    }

    public boolean isValid(String signatureHeader, byte[] rawBody) {
        if (appSecret == null || appSecret.isBlank()) {
            // Day-0 convenience: allow unsigned when no secret configured. Never in production.
            return true;
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) return false;
        String expected = hmacHex(rawBody);
        String provided = signatureHeader.substring("sha256=".length());
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }

    String hmacHex(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}
