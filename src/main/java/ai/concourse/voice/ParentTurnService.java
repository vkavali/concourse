package ai.concourse.voice;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.card.CardRenderer;
import ai.concourse.common.PhoneHasher;
import ai.concourse.itinerary.Airports;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.JourneyService;
import ai.concourse.safety.SafetyPolicy;
import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.stt.SttClient;
import ai.concourse.tts.TtsClient;
import ai.concourse.whatsapp.MessagingChannel;
import ai.concourse.whatsapp.WhatsAppClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One parent turn: voice note → transcript → Spark interpretation → code decides → Telugu voice note back.
 * <p>
 * Spark proposes {intent, confidence, tool, reply}; this class owns what actually happens: the confidence gate,
 * the safety check, tool execution, and sending. Every reply (model-written or templated) passes {@link SafetyPolicy}.
 * Timings per hop are logged on one line so the < 10 s gate can be measured.
 */
@Service
public class ParentTurnService {
    private static final Logger log = LoggerFactory.getLogger(ParentTurnService.class);

    public static final String BTN_GATE = "p:gate";
    public static final String BTN_HELP = "p:help";
    public static final String BTN_SON = "p:son";
    private static final List<WhatsAppClient.Button> PARENT_BUTTONS = List.of(
            new WhatsAppClient.Button(BTN_GATE, TeluguPhrases.BTN_GATE),
            new WhatsAppClient.Button(BTN_HELP, TeluguPhrases.BTN_HELP),
            new WhatsAppClient.Button(BTN_SON, TeluguPhrases.BTN_SON));

    private final MessagingChannel channel;
    private final AudioTranscoder transcoder;
    private final SttClient stt;
    private final SparkClient spark;
    private final PromptLoader prompts;
    private final TtsClient tts;
    private final SafetyPolicy safety;
    private final JourneyService journeys;
    private final ContactService contacts;
    private final Airports airports;
    private final PhoneHasher hasher;
    private final EventLog eventLog;
    private final ObjectMapper mapper;
    private final CardRenderer cards;

    public ParentTurnService(MessagingChannel channel, AudioTranscoder transcoder, SttClient stt, SparkClient spark,
                             PromptLoader prompts, TtsClient tts, SafetyPolicy safety, JourneyService journeys,
                             ContactService contacts, Airports airports, PhoneHasher hasher, EventLog eventLog,
                             ObjectMapper mapper, CardRenderer cards) {
        this.cards = cards;
        this.channel = channel;
        this.transcoder = transcoder;
        this.stt = stt;
        this.spark = spark;
        this.prompts = prompts;
        this.tts = tts;
        this.safety = safety;
        this.journeys = journeys;
        this.contacts = contacts;
        this.airports = airports;
        this.hasher = hasher;
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    /** Parent sent a voice note. */
    public void handleVoiceNote(String parentWaId, String mediaId) {
        long t0 = System.currentTimeMillis();
        Optional<JourneyEntity> journey = journeys.latestForParent(hasher.hash(parentWaId));
        if (journey.isEmpty()) {
            speak(parentWaId, TeluguPhrases.NOT_SET_UP, null, null);
            return;
        }
        JourneyFacts facts = facts(journey.get());

        byte[] ogg = channel.downloadMedia(mediaId);
        long t1 = System.currentTimeMillis();
        byte[] wav = transcoder.toWav16kMono(ogg);
        long t2 = System.currentTimeMillis();
        SttClient.Transcript transcript = stt.transcribeWav(wav, VoiceNoteService.PARENT_LANGUAGE_BIAS);
        long t3 = System.currentTimeMillis();

        ParentTurn turn = null;
        if (!transcript.isEmpty()) {
            turn = interpret(transcript.text(), facts);
        }
        long t4 = System.currentTimeMillis();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("audio_ms", transcript.audioDurationMs());
        event.put("transcript_chars", transcript.text().length());
        if (turn == null || turn.needsClarification()) {
            event.put("outcome", "clarify");
            if (turn != null) { event.put("intent", turn.intent()); event.put("confidence", turn.confidence()); }
            speak(parentWaId, TeluguPhrases.CLARIFY, PARENT_BUTTONS, facts);
        } else {
            event.put("intent", turn.intent());
            event.put("confidence", turn.confidence());
            event.put("tool", turn.toolProposal());
            // Spark classifies the intent; the gate itself is answered by a fixed template from verified facts, so the
            // most important sentence of the journey never depends on model prose (or on how a model spells a code).
            boolean gateQuestion = "WHERE_IS_GATE".equals(turn.intent()) || "WHAT_NEXT".equals(turn.intent());
            String reply = gateQuestion ? TeluguPhrases.gateAnswer(facts) : turn.replyTe();
            SafetyPolicy.Result checked = safety.check(reply, facts);
            if (!checked.ok()) {
                event.put("safety_violations", checked.violations());
                notifyChild(journey.get(), "safety fallback: " + String.join("; ", checked.violations()), turn.englishGloss());
            }
            executeTool(turn, journey.get(), facts, parentWaId);
            if (gateQuestion) sendGateCard(parentWaId, facts);
            speak(parentWaId, checked.text(), null, facts);
            event.put("outcome", checked.ok() ? "replied" : "fallback");
        }
        long t5 = System.currentTimeMillis();
        event.put("ms", Map.of("download", t1 - t0, "ffmpeg", t2 - t1, "stt", t3 - t2, "spark", t4 - t3, "tts_send", t5 - t4, "total", t5 - t0));
        eventLog.record(journey.get().getId(), EventLog.ACTOR_PARENT, "voice_turn", event);
        log.info("parent turn {} ms (dl {} / ffmpeg {} / stt {} / spark {} / tts+send {}) outcome={}",
                t5 - t0, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, event.get("outcome"));
    }

    /** Parent tapped one of the three buttons. Templated answers only — no model. */
    public void handleButton(String parentWaId, String buttonId) {
        Optional<JourneyEntity> journey = journeys.latestForParent(hasher.hash(parentWaId));
        if (journey.isEmpty()) {
            speak(parentWaId, TeluguPhrases.NOT_SET_UP, null, null);
            return;
        }
        JourneyFacts facts = facts(journey.get());
        switch (buttonId) {
            case BTN_GATE -> {
                sendGateCard(parentWaId, facts);
                speak(parentWaId, TeluguPhrases.gateAnswer(facts), null, facts);
            }
            case BTN_HELP -> {
                notifyChild(journey.get(), "pressed HELP", null);
                channel.sendText(parentWaId, staffCard(journey.get(), facts));
                speak(parentWaId, TeluguPhrases.SHOW_CARD + " " + TeluguPhrases.SON_TOLD, null, facts);
            }
            case BTN_SON -> {
                notifyChild(journey.get(), "asked you to call", null);
                speak(parentWaId, TeluguPhrases.SON_TOLD, null, facts);
            }
            default -> log.info("unknown parent button {}", buttonId);
        }
        eventLog.record(journey.get().getId(), EventLog.ACTOR_PARENT, "button", Map.of("id", buttonId));
    }

    /** A verified gate change reached the journey: card + voice to the parent, English text to the child. */
    public void announceGateChange(JourneyEntity journey, int seq, String source) {
        JourneyFacts facts = facts(journey);
        JourneyFacts.Flight f = facts.flights().stream().filter(x -> x.seq() == seq).findFirst().orElse(facts.firstFlight());
        if (f == null) return;
        Optional<ContactEntity> parent = contactByHash(journey.getParentPhoneHash());
        if (parent.isPresent()) {
            String to = parent.get().getE164();
            try {
                channel.sendImage(to, cards.gateCard(f), "గేట్ మారింది · " + f.flight());
            } catch (Exception e) {
                log.warn("gate card not sent: {}", e.getMessage());
            }
            speak(to, TeluguPhrases.gateChanged(f), null, facts);
        }
        contactByHash(journey.getChildPhoneHash()).ifPresent(c -> channel.sendText(c.getE164(),
                "Gate update for flight " + f.flight() + ": " + (f.gate() == null ? "gate cleared" : "gate " + f.gate())
                        + " (source: " + source + "). " + (parent.isPresent() ? "She has the new card and a voice note." : "Traveler not registered yet.")));
        eventLog.record(journey.getId(), EventLog.ACTOR_SYSTEM, "gate_change_announced", Map.of("seq", seq, "parent_notified", parent.isPresent()));
    }

    // ---- pieces -----------------------------------------------------------------------------------------------

    ParentTurn interpret(String transcript, JourneyFacts facts) {
        String user;
        try {
            user = "transcript: " + transcript + "\njourney: " + mapper.writeValueAsString(facts);
        } catch (Exception e) {
            user = "transcript: " + transcript + "\njourney: {}";
        }
        SparkRequest req = new SparkRequest(prompts.prompt("parent_turn"), user, List.of(),
                new SparkRequest.JsonSchema("parent_turn", prompts.schema("parent_turn")), 0.2);
        return spark.completeJson(req, ParentTurn.class, mapper);
    }

    private void executeTool(ParentTurn turn, JourneyEntity journey, JourneyFacts facts, String parentWaId) {
        String tool = turn.toolProposal() == null ? "none" : turn.toolProposal();
        switch (tool) {
            case "request_family_help" -> notifyChild(journey, "asked for help", turn.englishGloss());
            case "create_staff_card" -> channel.sendText(parentWaId, staffCard(journey, facts));
            default -> { /* get_next_stage / acknowledge_stage: the state machine arrives on Day 3; the reply already carries the next verified step */ }
        }
    }

    /** The gate card: verified facts only, large print, Telugu + English. Sent before the voice so it is on screen while she listens. */
    private void sendGateCard(String to, JourneyFacts facts) {
        JourneyFacts.Flight f = facts == null ? null : facts.firstFlight();
        if (f == null) return;
        try {
            String caption = f.gate() == null ? "గేట్ ఇంకా ప్రకటించలేదు · " + f.flight() : "గేట్ " + f.gate() + " · " + f.flight();
            channel.sendImage(to, cards.gateCard(f), caption);
        } catch (Exception e) {
            log.warn("gate card not sent: {}", e.getMessage());
        }
    }

    /** Voice note first (the thing she can use), then the same text and optional buttons. */
    private void speak(String to, String textTe, List<WhatsAppClient.Button> buttons, JourneyFacts facts) {
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
    }

    private void notifyChild(JourneyEntity journey, String what, String gloss) {
        Optional<ContactEntity> child = contactByHash(journey.getChildPhoneHash());
        if (child.isEmpty()) return;
        String parentNumber = contactByHash(journey.getParentPhoneHash()).map(c -> "+" + c.getE164()).orElse("her number");
        String msg = "Concourse: the traveler " + what + (gloss == null || gloss.isBlank() ? "" : " — she said: \"" + gloss + "\"")
                + ".\nLast confirmed stage: " + journey.getStatus() + ". I do not know her physical location.\nCall her: " + parentNumber;
        channel.sendText(child.get().getE164(), msg);
    }

    /** English card the parent shows to staff. Verified facts only. */
    String staffCard(JourneyEntity journey, JourneyFacts facts) {
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

    private JourneyFacts facts(JourneyEntity j) {
        return JourneyFacts.of(j, journeys.segments(j.getId()), airports);
    }

    private Optional<ContactEntity> contactByHash(String hash) {
        return hash == null ? Optional.empty() : contacts.findByHash(hash);
    }
}
