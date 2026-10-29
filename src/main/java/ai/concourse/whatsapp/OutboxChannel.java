package ai.concourse.whatsapp;

import ai.concourse.journey.ContactService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The messaging channel the app actually uses: records every outbound message (by role, never by number) in a small
 * in-memory ring, then delegates to WhatsApp. The simulator page reads the ring to show "both phones" side by side.
 */
@Primary
@Component
public class OutboxChannel implements MessagingChannel {
    public record Entry(long seq, OffsetDateTime at, String role, String kind, String text, List<String> buttons, int bytes) {}

    private static final int KEEP = 300;
    private final MessagingChannel delegate;
    private final ContactService contacts;
    private final Deque<Entry> ring = new ArrayDeque<>();
    private long seq;

    public OutboxChannel(WhatsAppClient delegate, ContactService contacts) {
        this.delegate = delegate;
        this.contacts = contacts;
    }

    @Override
    public String sendText(String to, String body) {
        record(to, "text", body, List.of(), 0);
        return delegate.sendText(to, body);
    }

    @Override
    public String sendButtons(String to, String body, List<WhatsAppClient.Button> buttons) {
        record(to, "buttons", body, buttons.stream().map(WhatsAppClient.Button::title).toList(), 0);
        return delegate.sendButtons(to, body, buttons);
    }

    @Override
    public String sendVoiceNote(String to, byte[] oggOpus) {
        record(to, "voice", "", List.of(), oggOpus == null ? 0 : oggOpus.length);
        return delegate.sendVoiceNote(to, oggOpus);
    }

    @Override
    public String sendImage(String to, byte[] png, String caption) {
        record(to, "image", caption == null ? "" : caption, List.of(), png == null ? 0 : png.length);
        return delegate.sendImage(to, png, caption);
    }

    @Override
    public String sendLinkButton(String to, String body, String buttonText, String url) {
        record(to, "link", body, List.of(buttonText), 0);
        return delegate.sendLinkButton(to, body, buttonText, url);
    }

    @Override
    public String sendTemplate(String to, String templateName, String languageCode, List<String> bodyParams) {
        record(to, "template", templateName, List.of(), 0);
        return delegate.sendTemplate(to, templateName, languageCode, bodyParams);
    }

    @Override
    public byte[] downloadMedia(String mediaId) {
        return delegate.downloadMedia(mediaId);
    }

    /** Entries after {@code afterSeq}, oldest first. */
    public synchronized List<Entry> since(long afterSeq) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ring) if (e.seq() > afterSeq) out.add(e);
        return out;
    }

    private synchronized void record(String to, String kind, String text, List<String> buttons, int bytes) {
        String role;
        try {
            role = contacts.roleOf(to).name().toLowerCase();
        } catch (RuntimeException e) {
            role = "unknown";
        }
        ring.addLast(new Entry(++seq, OffsetDateTime.now(), role, kind, text, buttons, bytes));
        while (ring.size() > KEEP) ring.pollFirst();
    }
}
