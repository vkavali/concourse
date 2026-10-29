package ai.concourse.voice;

import ai.concourse.card.CardRenderer;
import ai.concourse.itinerary.Airports;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.JourneyService;
import ai.concourse.safety.SafetyPolicy;
import ai.concourse.tts.TtsClient;
import ai.concourse.whatsapp.MessagingChannel;
import ai.concourse.whatsapp.WhatsAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one place that talks to the two phones: every Telugu message to the parent passes {@link SafetyPolicy} and goes
 * out as a voice note first, then as text (with buttons when offered); every message to the child is plain English and
 * never claims where she is. Shared by the parent-turn handler and the journey controller.
 */
@Service
public class ParentMessenger {
    private static final Logger log = LoggerFactory.getLogger(ParentMessenger.class);

    private final MessagingChannel channel;
    private final TtsClient tts;
    private final SafetyPolicy safety;
    private final CardRenderer cards;
    private final ContactService contacts;
    private final JourneyService journeys;
    private final Airports airports;
    private final ExecutorService warmer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tts-prewarm");
        t.setDaemon(true);
        return t;
    });

    public ParentMessenger(MessagingChannel channel, TtsClient tts, SafetyPolicy safety, CardRenderer cards,
                           ContactService contacts, JourneyService journeys, Airports airports) {
        this.channel = channel;
        this.tts = tts;
        this.safety = safety;
        this.cards = cards;
        this.contacts = contacts;
        this.journeys = journeys;
        this.airports = airports;
    }

    /** Voice note first (the thing she can use), then the same text and optional buttons. Returns the text actually sent. */
    public String speak(String to, String textTe, List<WhatsAppClient.Button> buttons, JourneyFacts facts) {
        SafetyPolicy.Result checked = safety.check(textTe, facts);
        String text = checked.text();
        if (tts.isConfigured()) {
            try {
                Map<String, String> names = Airlines.spokenNames(facts == null ? null : facts.flights());
                channel.sendVoiceNote(to, tts.synthesizeOggOpus(TeluguSpeech.spokenForm(text, names)));
            } catch (Exception e) {
                log.warn("tts failed, sending text only: {}", e.getMessage());
            }
        }
        if (buttons != null && !buttons.isEmpty()) channel.sendButtons(to, text, buttons);
        else channel.sendText(to, text);
        return text;
    }

    /**
     * Synthesises these texts in the background so the voice note is already cached when its stage fires — the
     * difference between a push that lands in a second and one that lands in ten.
     */
    public void prewarm(List<String> texts, JourneyFacts facts) {
        if (!tts.isConfigured() || texts.isEmpty()) return;
        Map<String, String> names = Airlines.spokenNames(facts == null ? null : facts.flights());
        warmer.submit(() -> {
            for (String t : texts) {
                try {
                    tts.synthesizeOggOpus(TeluguSpeech.spokenForm(safety.check(t, facts).text(), names));
                } catch (Exception e) {
                    log.debug("prewarm skipped: {}", e.getMessage());
                }
            }
            log.info("tts prewarm done: {} texts", texts.size());
        });
    }

    /** Whether the last reply had to be replaced by the safe fallback. */
    public SafetyPolicy.Result check(String textTe, JourneyFacts facts) {
        return safety.check(textTe, facts);
    }

    /** The gate card: verified facts only, large print. Sent before the voice so it is on screen while she listens. */
    public void sendGateCard(String to, JourneyFacts facts, Integer segmentSeq) {
        JourneyFacts.Flight f = flight(facts, segmentSeq);
        if (f == null) return;
        try {
            String caption = f.gate() == null ? "గేట్ ఇంకా ప్రకటించలేదు · " + f.flight() : "గేట్ " + f.gate() + " · " + f.flight();
            channel.sendImage(to, cards.gateCard(f), caption);
        } catch (Exception e) {
            log.warn("gate card not sent: {}", e.getMessage());
        }
    }

    public void sendGateCard(String to, JourneyFacts facts) {
        sendGateCard(to, facts, null);
    }

    /** Plain English to the child. Never a location claim. */
    public void notifyChild(JourneyEntity journey, String what, String gloss) {
        Optional<ContactEntity> child = contactByHash(journey.getChildPhoneHash());
        if (child.isEmpty()) return;
        String parentNumber = contactByHash(journey.getParentPhoneHash()).map(c -> "+" + c.getE164()).orElse("her number");
        String msg = "Concourse: the traveler " + what + (gloss == null || gloss.isBlank() ? "" : " — she said: \"" + gloss + "\"")
                + ".\nLast stage sent: " + journey.getStatus() + ". I do not know her physical location.\nCall her: " + parentNumber;
        channel.sendText(child.get().getE164(), msg);
    }

    /** A short English line to the child (status updates from the journey engine). */
    public void tellChild(JourneyEntity journey, String text) {
        contactByHash(journey.getChildPhoneHash()).ifPresent(c -> channel.sendText(c.getE164(), text));
    }

    public void sendTextToParent(JourneyEntity journey, String text) {
        contactByHash(journey.getParentPhoneHash()).ifPresent(c -> channel.sendText(c.getE164(), text));
    }

    /** English card the parent shows to staff. Verified facts only. */
    public String staffCard(JourneyEntity journey, JourneyFacts facts) {
        JourneyFacts.Flight f = facts.firstFlight();
        String son = contactByHash(journey.getChildPhoneHash()).map(c -> "+" + c.getE164()).orElse("(not set)");
        StringBuilder sb = new StringBuilder("PLEASE HELP — I speak Telugu, not English.\n");
        if (journey.getPassengerFirstName() != null) sb.append("Traveler: ").append(journey.getPassengerFirstName()).append('\n');
        if (f != null) {
            sb.append("Flight ").append(f.flight()).append(" to ").append(f.to()).append(", departs ").append(f.departureLocal()).append('\n');
            sb.append(f.gate() != null ? "Gate " + f.gate() : "Gate: not yet assigned");
            if (f.terminal() != null) sb.append(" · Terminal ").append(f.terminal());
            sb.append('\n');
        }
        sb.append("Family contact (WhatsApp): ").append(son);
        return sb.toString();
    }

    public JourneyFacts facts(JourneyEntity j) {
        return JourneyFacts.of(j, journeys.segments(j.getId()), airports);
    }

    public Optional<ContactEntity> parentOf(JourneyEntity j) {
        return contactByHash(j.getParentPhoneHash());
    }

    public Optional<ContactEntity> contactByHash(String hash) {
        return hash == null ? Optional.empty() : contacts.findByHash(hash);
    }

    private static JourneyFacts.Flight flight(JourneyFacts facts, Integer segmentSeq) {
        if (facts == null || facts.flights().isEmpty()) return null;
        if (segmentSeq == null) return facts.firstFlight();
        return facts.flights().stream().filter(x -> x.seq() == segmentSeq).findFirst().orElse(facts.firstFlight());
    }
}
