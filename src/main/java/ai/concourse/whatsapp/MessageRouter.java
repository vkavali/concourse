package ai.concourse.whatsapp;

import ai.concourse.common.PhoneHasher;
import ai.concourse.itinerary.ItinerarySummary;
import ai.concourse.itinerary.TicketIngestService;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.stt.SttClient;
import ai.concourse.tts.TtsClient;
import ai.concourse.voice.VoiceNoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Routes each inbound message by sender role and message type. Runs async so the webhook returns 200 immediately.
 * Day 1 scope: child sends a ticket → itinerary; CONFIRM/FIX buttons; voice notes are transcribed and echoed
 * (voice gate). The parent-side journey conversation arrives on Day 2–3 and plugs in here.
 */
@Component
public class MessageRouter implements InboundMessageHandler {
    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final MessagingChannel whatsApp;
    private final ContactService contacts;
    private final TicketIngestService tickets;
    private final VoiceNoteService voiceNotes;
    private final JourneyService journeys;
    private final PhoneHasher hasher;
    private final TtsClient tts;

    public MessageRouter(MessagingChannel whatsApp, ContactService contacts, TicketIngestService tickets,
                         VoiceNoteService voiceNotes, JourneyService journeys, PhoneHasher hasher, TtsClient tts) {
        this.whatsApp = whatsApp;
        this.contacts = contacts;
        this.tickets = tickets;
        this.voiceNotes = voiceNotes;
        this.journeys = journeys;
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
                        whatsApp.sendText(from, "Photo received. Sign reading arrives on Day 4."); // placeholder until SignEvidenceService
                    } else {
                        tickets.ingest(from, m.mediaId(), m.mimeType(), m.text());
                    }
                }
                case AUDIO -> {
                    SttClient.Transcript t = voiceNotes.transcribe(m.mediaId());
                    whatsApp.sendText(from, t.isEmpty() ? "I could not hear anything in that voice note." : "🎙 " + t.text());
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
        if (id.startsWith(TicketIngestService.BUTTON_CONFIRM)) {
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
            whatsApp.sendText(from, "🎙"); // parents are never asked to type; Day 2 answers with a voice note
            return;
        }
        if (t.startsWith("say ") && raw.length() > 4) {
            // Dev/test command: "say <Telugu text>" → Telugu voice note (MMS-TTS on Modal). Used for the intelligibility test.
            if (!tts.isConfigured()) {
                whatsApp.sendText(from, "TTS is not configured (TTS_BASE_URL).");
                return;
            }
            long t0 = System.currentTimeMillis();
            byte[] ogg = tts.synthesizeOggOpus(raw.substring(4).strip());
            whatsApp.sendVoiceNote(from, ogg);
            log.info("say: {} chars → {} bytes in {} ms", raw.length() - 4, ogg.length, System.currentTimeMillis() - t0);
            return;
        }
        if (t.equals("status")) {
            Optional<JourneyEntity> j = journeys.latestForChild(hasher.hash(from));
            if (j.isEmpty()) {
                whatsApp.sendText(from, "No journey yet. Send the ticket (PDF or photo) to start.");
            } else {
                JourneyEntity je = j.get();
                String confirmed = je.getItineraryConfirmedAt() == null ? "not confirmed yet" : "confirmed";
                whatsApp.sendText(from, ItinerarySummary.forChild(je.getPassengerFirstName(), journeys.segments(je.getId()))
                        + "\nStatus: " + je.getStatus() + " (" + confirmed + ")");
            }
            return;
        }
        whatsApp.sendText(from, "Concourse: send the traveler's ticket (PDF or photo) and I will build the journey. "
                + "Type 'status' to see the current itinerary.");
    }
}
