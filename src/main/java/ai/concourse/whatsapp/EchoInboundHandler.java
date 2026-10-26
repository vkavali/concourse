package ai.concourse.whatsapp;

import ai.concourse.common.PhoneHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Day-0 handler: proves the loop. Text in -> acknowledgement text out. Replaced by MessageRouter on Day 1.
 * Runs async so the webhook returns 200 immediately.
 */
@Component
public class EchoInboundHandler implements InboundMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(EchoInboundHandler.class);
    private final WhatsAppClient client;

    public EchoInboundHandler(WhatsAppClient client) {
        this.client = client;
    }

    @Async
    @Override
    public void handle(InboundMessage m) {
        try {
            switch (m.type()) {
                case TEXT -> client.sendText(m.fromWaId(), "Concourse is listening. (" + m.text().length() + " chars received)");
                case AUDIO -> client.sendText(m.fromWaId(), "Voice note received (" + m.mimeType() + "). Day 1 will understand it.");
                case IMAGE -> client.sendText(m.fromWaId(), "Photo received. Day 4 will read signs.");
                case DOCUMENT -> client.sendText(m.fromWaId(), "Document received. Day 1 will read tickets.");
                case BUTTON_REPLY -> client.sendText(m.fromWaId(), "Button: " + m.interactiveReplyId());
                default -> log.info("ignored message type {} from {}", m.type(), PhoneHasher.redact(m.fromWaId()));
            }
        } catch (Exception e) {
            log.error("echo failed for {}", PhoneHasher.redact(m.fromWaId()), e);
        }
    }
}
