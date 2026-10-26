package ai.concourse.common;

import ai.concourse.config.ConcourseProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Phone numbers are never stored or logged in clear text; this produces a stable salted hash. */
@Component
public class PhoneHasher {
    private final byte[] salt;

    public PhoneHasher(ConcourseProperties props) {
        this.salt = props.phoneHashSalt().getBytes(StandardCharsets.UTF_8);
    }

    public String hash(String e164) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(normalize(e164).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Keep digits only; WhatsApp delivers "wa_id" without '+'. */
    public static String normalize(String phone) {
        return phone.replaceAll("[^0-9]", "");
    }

    /** For logs: last 3 digits only. */
    public static String redact(String phone) {
        String n = normalize(phone);
        return n.length() <= 3 ? "***" : "***" + n.substring(n.length() - 3);
    }
}
