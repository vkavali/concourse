package ai.concourse.whatsapp;

import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Meta calls GET once to verify the webhook and POST for every message/status.
 * We validate the signature, parse, hand off asynchronously, and return 200 immediately
 * (Meta retries slow or failing webhooks).
 */
@RestController
@RequestMapping("/webhook/whatsapp")
public class WebhookController {
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ConcourseProperties props;
    private final SignatureVerifier signatureVerifier;
    private final WebhookPayloadParser parser;
    private final InboundMessageHandler handler;

    public WebhookController(ConcourseProperties props, SignatureVerifier signatureVerifier,
                             WebhookPayloadParser parser, InboundMessageHandler handler) {
        this.props = props;
        this.signatureVerifier = signatureVerifier;
        this.parser = parser;
        this.handler = handler;
    }

    @GetMapping
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if ("subscribe".equals(mode) && props.whatsapp().verifyToken().equals(token) && challenge != null) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("verification failed");
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
                                        @RequestBody byte[] body) {
        if (!signatureVerifier.isValid(signature, body)) {
            log.warn("webhook signature invalid");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            List<InboundMessage> messages = parser.parse(body);
            for (InboundMessage m : messages) {
                log.info("inbound {} from {} id={}", m.type(), PhoneHasher.redact(m.fromWaId()), m.messageId());
                handler.handle(m);
            }
        } catch (Exception e) {
            log.error("webhook parse/handle failed", e);
        }
        return ResponseEntity.ok().build();
    }
}
