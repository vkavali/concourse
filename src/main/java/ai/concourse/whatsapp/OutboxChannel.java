package ai.concourse.whatsapp;

import ai.concourse.journey.ContactService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The messaging channel the app actually uses: records every outbound message (by role, never by number) in a small
 * in-memory ring, then delegates to WhatsApp. The simulator page reads the ring to show "both phones" side by side,
 * and plays the voice notes and cards from {@link #media(long)}. When WhatsApp is not configured (a judge running
 * the repo, a laptop without a Business account) nothing is delegated and the simulator is the only phone.
 */
@Primary
@Component
public class OutboxChannel implements MessagingChannel {
    private static final Logger log = LoggerFactory.getLogger(OutboxChannel.class);

    public record Entry(long seq, OffsetDateTime at, String role, String kind, String text, List<String> buttons, int bytes,
                        String mediaType) {}

    public record Media(String contentType, byte[] bytes) {}

    private static final int KEEP = 300;
    private static final int KEEP_MEDIA = 60;
    private final WhatsAppClient delegate;
    private final ContactService contacts;
    private final Deque<Entry> ring = new ArrayDeque<>();
    private final Map<Long, Media> media = new LinkedHashMap<>();
    private long seq;
    private boolean warnedOffline;

    public OutboxChannel(WhatsAppClient delegate, ContactService contacts) {
        this.delegate = delegate;
        this.contacts = contacts;
    }

    /** Whether messages leave for WhatsApp at all. */
    public boolean delivering() {
        return delegate.isConfigured();
    }

    @Override
    public String sendText(String to, String body) {
        long id = record(to, "text", body, List.of(), null);
        return delivering() ? delegate.sendText(to, body) : offline(id);
    }

    @Override
    public String sendButtons(String to, String body, List<WhatsAppClient.Button> buttons) {
        long id = record(to, "buttons", body, buttons.stream().map(WhatsAppClient.Button::title).toList(), null);
        return delivering() ? delegate.sendButtons(to, body, buttons) : offline(id);
    }

    @Override
    public String sendVoiceNote(String to, byte[] oggOpus) {
        long id = record(to, "voice", "", List.of(), oggOpus == null ? null : new Media("audio/ogg", oggOpus));
        return delivering() ? delegate.sendVoiceNote(to, oggOpus) : offline(id);
    }

    @Override
    public String sendImage(String to, byte[] png, String caption) {
        long id = record(to, "image", caption == null ? "" : caption, List.of(), png == null ? null : new Media("image/png", png));
        return delivering() ? delegate.sendImage(to, png, caption) : offline(id);
    }

    @Override
    public String sendLinkButton(String to, String body, String buttonText, String url) {
        long id = record(to, "link", body + "\n" + url, List.of(buttonText), null);
        return delivering() ? delegate.sendLinkButton(to, body, buttonText, url) : offline(id);
    }

    @Override
    public String sendTemplate(String to, String templateName, String languageCode, List<String> bodyParams) {
        long id = record(to, "template", templateName, List.of(), null);
        return delivering() ? delegate.sendTemplate(to, templateName, languageCode, bodyParams) : offline(id);
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

    /** The voice note or card behind an entry, while it is still held (the last {@value KEEP_MEDIA}). */
    public synchronized Optional<Media> media(long seq) {
        return Optional.ofNullable(media.get(seq));
    }

    private synchronized long record(String to, String kind, String text, List<String> buttons, Media m) {
        String role;
        try {
            role = contacts.roleOf(to).name().toLowerCase();
        } catch (RuntimeException e) {
            role = "unknown";
        }
        long id = ++seq;
        ring.addLast(new Entry(id, OffsetDateTime.now(), role, kind, text, buttons, m == null ? 0 : m.bytes().length,
                m == null ? null : m.contentType()));
        while (ring.size() > KEEP) ring.pollFirst();
        if (m != null) {
            media.put(id, m);
            while (media.size() > KEEP_MEDIA) media.remove(media.keySet().iterator().next());
        }
        return id;
    }

    private String offline(long id) {
        if (!warnedOffline) {
            warnedOffline = true;
            log.warn("WhatsApp is not configured (WHATSAPP_TOKEN / WHATSAPP_PHONE_NUMBER_ID): messages are recorded in the outbox only — open /sim.html");
        }
        return "outbox-" + id;
    }
}
