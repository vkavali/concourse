package ai.concourse.whatsapp;

/**
 * A normalized inbound WhatsApp message. Exactly one of text / mediaId / interactiveReplyId is set
 * depending on {@link Type}. Phone numbers are raw here and must be hashed before persistence.
 */
public record InboundMessage(
        String messageId,
        String fromWaId,          // sender phone, digits only, as delivered by WhatsApp
        Type type,
        String text,              // TEXT
        String mediaId,           // AUDIO | IMAGE | DOCUMENT
        String mimeType,          // AUDIO | IMAGE | DOCUMENT
        String interactiveReplyId,// BUTTON_REPLY
        long timestampEpochSec
) {
    public enum Type { TEXT, AUDIO, IMAGE, DOCUMENT, BUTTON_REPLY, OTHER }
}
