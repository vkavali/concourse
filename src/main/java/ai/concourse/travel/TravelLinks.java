package ai.concourse.travel;

import ai.concourse.config.ConcourseProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The link she taps to open Travel Mode: {@code {public base}/travel.html#<journey>.<token>}. The token is an HMAC of
 * the journey id under the deployment salt, so a link cannot be guessed or edited to reach another journey. It
 * carries no phone number. Anyone holding the link sees that journey's verified facts and can press its buttons —
 * which is the point: it goes to her phone and nowhere else.
 */
@Component
public class TravelLinks {
    private final byte[] key;
    private final String baseUrl;

    public TravelLinks(ConcourseProperties props) {
        this(props.phoneHashSalt(), props.publicBaseUrl());
    }

    public TravelLinks(String salt, String baseUrl) {
        this.key = ("travel|" + (salt == null ? "" : salt)).getBytes(StandardCharsets.UTF_8);
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:8080" : baseUrl.replaceAll("/+$", "");
    }

    public String token(UUID journeyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(journeyId.toString().getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean valid(UUID journeyId, String token) {
        if (journeyId == null || token == null) return false;
        return MessageDigest.isEqual(token(journeyId).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    public String url(UUID journeyId) {
        return baseUrl + "/travel.html#" + journeyId + "." + token(journeyId);
    }
}
