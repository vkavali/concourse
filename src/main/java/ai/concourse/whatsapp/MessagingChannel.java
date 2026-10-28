package ai.concourse.whatsapp;

import java.util.List;

/**
 * What the journey logic needs from a messaging transport. {@link WhatsAppClient} (Meta Cloud API) is the primary
 * implementation; a Twilio WhatsApp Sandbox adapter can stand in if the Meta business account is locked.
 */
public interface MessagingChannel {
    String sendText(String to, String body);

    /** 1..3 quick-reply buttons; the reply comes back as {@code InboundMessage.interactiveReplyId}. */
    String sendButtons(String to, String body, List<WhatsAppClient.Button> buttons);

    /** OGG/Opus mono bytes → voice note (mic icon, autoplay on open). */
    String sendVoiceNote(String to, byte[] oggOpus);

    String sendImage(String to, byte[] png, String caption);

    /** An approved template (the only thing WhatsApp lets through outside the 24-hour window). */
    String sendTemplate(String to, String templateName, String languageCode, List<String> bodyParams);

    /** Inbound media id → bytes. */
    byte[] downloadMedia(String mediaId);
}
