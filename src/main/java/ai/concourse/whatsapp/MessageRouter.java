package ai.concourse.whatsapp;

import ai.concourse.common.PhoneHasher;
import ai.concourse.evidence.SignEvidenceService;
import ai.concourse.itinerary.ItinerarySummary;
import ai.concourse.itinerary.TicketIngestService;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import ai.concourse.stt.SttClient;
import ai.concourse.tts.TtsClient;
import ai.concourse.voice.ParentTurnService;
import ai.concourse.voice.TeluguSpeech;
import ai.concourse.voice.VoiceNoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Routes each inbound message by sender role and message type. Runs async so the webhook returns 200 immediately.
 * <ul>
 *   <li>Child (default role): ticket PDF/photo → itinerary; Confirm/Fix; a phone number → registers the traveler;
 *       "status"; dev commands "say …", "mode parent|child"; voice notes are transcribed and echoed (voice gate).</li>
 *   <li>Parent: voice notes and buttons go to {@link ParentTurnService}, photos to {@link SignEvidenceService}; typed text gets a 🎙 nudge.</li>
 * </ul>
 */
@Component
public class MessageRouter implements InboundMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);
    /** "gate B12", "gate none", "gate 2 C7" (second flight). */
    private static final Pattern GATE_CMD = Pattern.compile("^gate\\s+(?:(\\d)\\s+)?([A-Za-z]{1,2}\\s?-?\\d{1,3}[A-Za-z]?|none)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("^(?:parent|traveler|traveller|mom|amma|dad)?\\s*\\+?[\\d\\s().-]{8,20}$", Pattern.CASE_INSENSITIVE);

    private final MessagingChannel whatsApp;
    private final ContactService contacts;
    private final TicketIngestService tickets;
    private final VoiceNoteService voiceNotes;
    private final ParentTurnService parentTurns;
    private final JourneyService journeys;
    private final JourneyController controller;
    private final SignEvidenceService signs;
    private final PhoneHasher hasher;
    private final TtsClient tts;

    public MessageRouter(MessagingChannel whatsApp, ContactService contacts, TicketIngestService tickets,
                         VoiceNoteService voiceNotes, ParentTurnService parentTurns, JourneyService journeys,
                         JourneyController controller, SignEvidenceService signs, PhoneHasher hasher, TtsClient tts) {
        this.whatsApp = whatsApp;
        this.contacts = contacts;
        this.tickets = tickets;
        this.voiceNotes = voiceNotes;
        this.parentTurns = parentTurns;
        this.journeys = journeys;
        this.controller = controller;
        this.signs = signs;
        this.hasher = hasher;
        this.tts = tts;
    }

    @Async
    @Override
    public void handle(InboundMessage m) {
        String from = m.fromWaId();
        try {
            ContactEntity.Role role = contacts.roleOf(from);
            switch (m.type()) {
                case DOCUMENT, IMAGE -> {
                    if (role == ContactEntity.Role.PARENT) {
                        signs.handlePhoto(from, m.mediaId(), m.mimeType()); // a sign she photographed
                    } else {
                        tickets.ingest(from, m.mediaId(), m.mimeType(), m.text());
                    }
                }
                case AUDIO -> {
                    if (role == ContactEntity.Role.PARENT) {
                        parentTurns.handleVoiceNote(from, m.mediaId());
                    } else {
                        SttClient.Transcript t = voiceNotes.transcribe(m.mediaId());
                        whatsApp.sendText(from, t.isEmpty() ? "I could not hear anything in that voice note." : "🎙 " + t.text());
                    }
                }
                case BUTTON_REPLY -> handleButton(from, m.interactiveReplyId());
                case TEXT -> handleText(from, role, m.text());
                default -> log.info("ignored {} from {}", m.type(), PhoneHasher.redact(from));
            }
        } catch (Exception e) {
            log.error("handling {} from {} failed", m.type(), PhoneHasher.redact(from), e);
            try {
                whatsApp.sendText(from, "Something went wrong on my side. Please try again in a minute.");
            } catch (Exception ignored) {
            }
        }
    }

    private void handleButton(String from, String id) {
        if (id == null) return;
        if (id.startsWith("p:")) {
            parentTurns.handleButton(from, id);
        } else if (id.startsWith(TicketIngestService.BUTTON_CONFIRM)) {
            tickets.confirm(from, id.substring(TicketIngestService.BUTTON_CONFIRM.length()));
        } else if (id.startsWith(TicketIngestService.BUTTON_FIX)) {
            tickets.fix(from, id.substring(TicketIngestService.BUTTON_FIX.length()));
        } else {
            log.info("unknown button {} from {}", id, PhoneHasher.redact(from));
        }
    }

    private void handleText(String from, ContactEntity.Role role, String text) {
        String raw = text == null ? "" : text.strip();
        String t = raw.toLowerCase(Locale.ROOT);
        if (role == ContactEntity.Role.PARENT) {
            if (t.equals("mode child")) { // dev: one phone plays both roles
                contacts.register(from, ContactEntity.Role.CHILD, "en");
                whatsApp.sendText(from, "This number is the child again.");
                return;
            }
            whatsApp.sendText(from, "🎙"); // parents are never asked to type
            return;
        }
        if (t.equals("mode parent")) { // dev: one phone plays both roles
            Optional<JourneyEntity> j = journeys.attachParent(hasher.hash(from), hasher.hash(from));
            if (j.isEmpty()) {
                whatsApp.sendText(from, "No journey yet — send the ticket first, then 'mode parent'.");
                return;
            }
            contacts.register(from, ContactEntity.Role.PARENT, "te");
            whatsApp.sendText(from, "This number is now the traveler. Send a Telugu voice note. Type 'mode child' to switch back.");
            controller.onJourneyReady(j.get().getId());
            return;
        }
        if (t.equals("next") || t.equals("plan") || t.startsWith("clock")) { // demo controls
            Optional<JourneyEntity> j = journeys.latestForChild(hasher.hash(from));
            if (j.isEmpty()) {
                whatsApp.sendText(from, "No journey yet. Send the ticket first.");
                return;
            }
            if (t.equals("plan")) {
                whatsApp.sendText(from, controller.describePlan(j.get()));
            } else if (t.equals("next")) {
                Optional<StageEntity> fired = controller.fireNext(j.get());
                whatsApp.sendText(from, fired.map(st -> "Clock moved to its time; stage " + st.getSeq() + " " + st.getType() + " pushed to her.")
                        .orElse(j.get().getConsentAt() == null
                                ? "She has not said 'start my trip' yet — nothing is pushed until she does (switch to 'mode parent' and tap the button or say it)."
                                : "No planned stage left to fire."));
            } else {
                if (t.equals("clock reset")) controller.clock().reset();
                whatsApp.sendText(from, controller.clock().isTravelling()
                        ? "Simulated clock: " + controller.clock().now() + " (offset " + controller.clock().offset().toMinutes() + " min). 'clock reset' returns to real time."
                        : "Clock: real time (" + controller.clock().now() + "). 'next' jumps to the next planned stage.");
            }
            return;
        }
        if (t.startsWith("say ") && raw.length() > 4) {
            // Dev/test: "say <Telugu text>" → Telugu voice note (MMS-TTS on Modal). Used for the intelligibility test.
            if (!tts.isConfigured()) {
                whatsApp.sendText(from, "TTS is not configured (TTS_BASE_URL).");
                return;
            }
            long t0 = System.currentTimeMillis();
            byte[] ogg = tts.synthesizeOggOpus(TeluguSpeech.spokenForm(raw.substring(4).strip()));
            whatsApp.sendVoiceNote(from, ogg);
            log.info("say: {} chars → {} bytes in {} ms", raw.length() - 4, ogg.length, System.currentTimeMillis() - t0);
            return;
        }
        java.util.regex.Matcher gate = GATE_CMD.matcher(raw);
        if (gate.matches()) { // demo: a labeled simulated operations event — the only way a gate changes
            Optional<JourneyEntity> j = journeys.latestForChild(hasher.hash(from));
            if (j.isEmpty()) {
                whatsApp.sendText(from, "No journey yet. Send the ticket first.");
                return;
            }
            String newGate = gate.group(2).equalsIgnoreCase("none") ? null : gate.group(2).replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
            int seg = gate.group(1) == null ? 1 : Integer.parseInt(gate.group(1));
            whatsApp.sendText(from, "Simulated operations event: flight " + seg + " gate → " + (newGate == null ? "cleared" : newGate) + ". Notifying both phones.");
            if (!controller.applyGateChange(j.get(), seg, newGate, JourneyController.DEMO_SOURCE)) {
                whatsApp.sendText(from, "No flight segment " + seg + " to update.");
            }
            return;
        }
        if (PHONE.matcher(raw).matches() && PhoneHasher.normalize(raw).length() >= 10) {
            registerParent(from, PhoneHasher.normalize(raw));
            return;
        }
        if (t.equals("status") || t.contains("where is") || t.contains("where's")) {
            Optional<JourneyEntity> j = journeys.latestForChild(hasher.hash(from));
            if (j.isEmpty()) {
                whatsApp.sendText(from, "No journey yet. Send the ticket (PDF or photo) to start.");
            } else {
                JourneyEntity je = j.get();
                String confirmed = je.getItineraryConfirmedAt() == null ? "not confirmed yet" : "confirmed";
                String parent = je.getParentPhoneHash() == null ? "traveler's number not set — send it to me" : "traveler registered";
                String consent = je.getConsentAt() == null ? "not started (waiting for her 'start my trip')" : "started";
                whatsApp.sendText(from, ItinerarySummary.forChild(je.getPassengerFirstName(), journeys.segments(je.getId()))
                        + "\nStage: " + je.getStatus() + " (" + confirmed + "; " + parent + "; " + consent + ")"
                        + "\nI do not know her physical location — only the last stage she confirmed. Type 'plan' for the timeline.");
            }
            return;
        }
        whatsApp.sendText(from, "Concourse: send the traveler's ticket (PDF or photo) and I will build the journey. "
                + "Then send her WhatsApp number (e.g. +91 98765 43210). Commands: 'status', 'plan', 'next' (fire the next stage now), "
                + "'gate B12' (simulated gate change), 'clock reset'.");
    }

    private void registerParent(String childWaId, String parentDigits) {
        Optional<JourneyEntity> j = journeys.attachParent(hasher.hash(childWaId), hasher.hash(parentDigits));
        if (j.isEmpty()) {
            whatsApp.sendText(childWaId, "Send the ticket first, then the traveler's number.");
            return;
        }
        contacts.register(parentDigits, ContactEntity.Role.PARENT, "te");
        whatsApp.sendText(childWaId, "Registered +" + parentDigits + " as the traveler. She can send Telugu voice notes to this number now."
                + " (Test number: she must also be a verified recipient in the Meta console.)");
        controller.onJourneyReady(j.get().getId());
    }
}
