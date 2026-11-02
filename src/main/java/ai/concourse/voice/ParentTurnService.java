package ai.concourse.voice;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.common.PhoneHasher;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import ai.concourse.journey.StageType;
import ai.concourse.safety.SafetyPolicy;
import ai.concourse.ops.ModelTape;
import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.stt.SttClient;
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
 * the safety check, tool execution, and sending. The journey-changing intents (start my trip, done, I'm here,
 * what next, where is the gate) are answered by {@link JourneyController} and templates, never by model prose.
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
    private final JourneyService journeys;
    private final JourneyController controller;
    private final ParentMessenger messenger;
    private final PhoneHasher hasher;
    private final EventLog eventLog;
    private final ObjectMapper mapper;

    public ParentTurnService(MessagingChannel channel, AudioTranscoder transcoder, SttClient stt, SparkClient spark,
                             PromptLoader prompts, JourneyService journeys, JourneyController controller,
                             ParentMessenger messenger, PhoneHasher hasher, EventLog eventLog, ObjectMapper mapper) {
        this.channel = channel;
        this.transcoder = transcoder;
        this.stt = stt;
        this.spark = spark;
        this.prompts = prompts;
        this.journeys = journeys;
        this.controller = controller;
        this.messenger = messenger;
        this.hasher = hasher;
        this.eventLog = eventLog;
        this.mapper = mapper;
    }

    /** What a turn produced: the outcome label for the log and the main Telugu line that was spoken. */
    public record Reply(String outcome, String telugu, String intent) {}

    private ModelTape tape; // optional: record/replay of her inputs
    private ai.concourse.spark.SparkOpen sparkOpen; // optional: open-weight classifier first, Spark 1.3 as the fallback

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTape(ModelTape tape) { this.tape = tape; }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSparkOpen(ai.concourse.spark.SparkOpen sparkOpen) { this.sparkOpen = sparkOpen; }

    /** Parent sent a voice note over WhatsApp. */
    public void handleVoiceNote(String parentWaId, String mediaId) {
        long t0 = System.currentTimeMillis();
        Optional<JourneyEntity> journey = journeys.latestForParent(hasher.hash(parentWaId));
        if (journey.isEmpty()) {
            messenger.speak(parentWaId, TeluguPhrases.NOT_SET_UP, null, null);
            return;
        }
        byte[] ogg = channel.downloadMedia(mediaId);
        long t1 = System.currentTimeMillis();
        byte[] wav = transcoder.toWav16kMono(ogg);
        long t2 = System.currentTimeMillis();
        handleAudio(journey.get(), wav, "whatsapp", t0, t1, t2);
    }

    /** The last resort when something on our side failed: "say it again", in Telugu, with her three buttons. */
    public void apologize(String parentWaId) {
        messenger.speak(parentWaId, TeluguPhrases.TRY_AGAIN, PARENT_BUTTONS, null);
    }

    /** Her voice from anywhere (WhatsApp note, the travel screen's microphone), already converted to 16 kHz WAV. */
    public Reply handleAudio(JourneyEntity j, byte[] wav, String source) {
        long now = System.currentTimeMillis();
        return handleAudio(j, wav, source, now, now, now);
    }

    private Reply handleAudio(JourneyEntity j, byte[] wav, String source, long t0, long t1, long t2) {
        if (tape != null && !"simulator".equals(source)) tape.saveInput("voice", wav, "wav"); // record mode only: her note becomes a sample input (a sample already is one)
        controller.onParentMessage(j);
        JourneyFacts facts = messenger.facts(j);
        String parentWaId = messenger.parentOf(j).map(c -> c.getE164()).orElse(null);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("source", source);
        SttClient.Transcript transcript;
        try {
            transcript = stt.transcribeWav(wav, VoiceNoteService.PARENT_LANGUAGE_BIAS);
        } catch (RuntimeException e) {
            // Voice Transcribe is down or refused the audio: she hears a Telugu "say it again" with the buttons, never silence
            log.warn("transcription failed ({}); asking her to say it again", e.getMessage());
            event.put("error", "stt: " + e.getMessage());
            if (parentWaId != null) messenger.speak(parentWaId, TeluguPhrases.TRY_AGAIN, PARENT_BUTTONS, facts);
            event.put("outcome", "retry");
            eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "voice_turn", event);
            return new Reply("retry", TeluguPhrases.TRY_AGAIN, "ERROR");
        }
        long t3 = System.currentTimeMillis();

        ParentTurn turn = null;
        if (!transcript.isEmpty()) {
            try {
                turn = interpret(transcript.text(), facts);
            } catch (RuntimeException e) {
                log.warn("intent classification failed ({}); asking her to say it again", e.getMessage());
                event.put("error", "spark: " + e.getMessage());
            }
        }
        long t4 = System.currentTimeMillis();

        event.put("audio_ms", transcript.audioDurationMs());
        event.put("transcript_chars", transcript.text().length());
        Reply reply;
        if (turn == null || turn.needsClarification()) {
            if (turn != null) { event.put("intent", turn.intent()); event.put("confidence", turn.confidence()); }
            if (parentWaId != null) messenger.speak(parentWaId, TeluguPhrases.CLARIFY, PARENT_BUTTONS, facts);
            reply = new Reply("clarify", TeluguPhrases.CLARIFY, turn == null ? "EMPTY" : turn.intent());
        } else {
            event.put("intent", turn.intent());
            event.put("confidence", turn.confidence());
            event.put("tool", turn.toolProposal());
            reply = respond(j, facts, parentWaId, turn);
        }
        event.put("outcome", reply.outcome());
        long t5 = System.currentTimeMillis();
        event.put("ms", Map.of("download", t1 - t0, "ffmpeg", t2 - t1, "stt", t3 - t2, "spark", t4 - t3, "tts_send", t5 - t4, "total", t5 - t0));
        eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "voice_turn", event);
        log.info("parent turn {} ms (dl {} / ffmpeg {} / stt {} / spark {} / tts+send {}) outcome={} source={}",
                t5 - t0, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, reply.outcome(), source);
        return reply;
    }

    /** Spark classified the intent; code answers. Every "where do I go" answer ends with the camera button. */
    private Reply respond(JourneyEntity j, JourneyFacts facts, String parentWaId, ParentTurn turn) {
        String intent = turn.intent();
        switch (intent) {
            case "START_TRIP" -> {
                boolean already = j.getConsentAt() != null;
                controller.recordConsent(j, "voice");
                return new Reply("consent", already ? StageText.ACK_THANKS : StageText.CONSENT_THANKS, intent);
            }
            case "I_AM_HERE" -> {
                boolean complete = controller.markHere(j, "voice");
                return new Reply("here", complete ? StageText.instruction(StageType.COMPLETE, facts, null, null) : TeluguPhrases.SON_TOLD, intent);
            }
            case "AT_COUNTER" -> {
                controller.acknowledge(j, "voice: " + turn.englishGloss());
                return new Reply("ack", StageText.ACK_THANKS, intent);
            }
            case "WHAT_NEXT", "LOST" -> {
                String text = controller.whatNext(j);
                if (j.getConsentAt() == null) {
                    messenger.speak(parentWaId, text, StageText.buttons(StageType.PRE_TRIP), facts);
                    return new Reply("not_started", text, intent);
                }
                Optional<StageEntity> current = controller.current(j);
                StageType type = current.map(StageEntity::stageType).orElse(null);
                if (type != null && type.showsGateCard()) messenger.sendGateCard(parentWaId, facts, current.get().getSegmentSeq());
                else if (type != null) messenger.sendSignCard(parentWaId, type, facts, current.get().getSegmentSeq());
                String spoken = "LOST".equals(intent) ? TeluguPhrases.LOST + " " + text : text;
                messenger.speak(parentWaId, spoken, type == null ? null : StageText.buttons(type), facts);
                messenger.sendCameraLink(j);
                if ("LOST".equals(intent)) {
                    messenger.notifyChild(j, "says she is lost", turn.englishGloss());
                }
                return new Reply("LOST".equals(intent) ? "lost" : "what_next", spoken, intent);
            }
            case "WHERE_IS_GATE" -> {
                messenger.sendGateCard(parentWaId, facts);
                String text = messenger.speak(parentWaId, TeluguPhrases.gateAnswer(facts), null, facts);
                messenger.sendCameraLink(j);
                return new Reply("gate", text, intent);
            }
            default -> {
                SafetyPolicy.Result checked = messenger.check(turn.replyTe(), facts);
                if (!checked.ok()) {
                    messenger.notifyChild(j, "safety fallback: " + String.join("; ", checked.violations()), turn.englishGloss());
                }
                executeTool(turn, j, facts, parentWaId);
                messenger.speak(parentWaId, checked.text(), null, facts);
                return new Reply(checked.ok() ? "replied" : "fallback", checked.text(), intent);
            }
        }
    }

    /** Parent tapped a button. Templated answers only — no model. */
    public void handleButton(String parentWaId, String buttonId) {
        Optional<JourneyEntity> journey = journeys.latestForParent(hasher.hash(parentWaId));
        if (journey.isEmpty()) {
            messenger.speak(parentWaId, TeluguPhrases.NOT_SET_UP, null, null);
            return;
        }
        JourneyEntity j = journey.get();
        JourneyFacts facts = messenger.facts(j);
        switch (buttonId) {
            case StageText.BTN_START -> controller.recordConsent(j, "button");
            case StageText.BTN_DONE -> controller.acknowledge(j, "button");
            case StageText.BTN_HERE -> controller.markHere(j, "button");
            case BTN_GATE -> {
                controller.onParentMessage(j);
                messenger.sendGateCard(parentWaId, facts);
                messenger.speak(parentWaId, TeluguPhrases.gateAnswer(facts), null, facts);
            }
            case BTN_HELP -> {
                controller.onParentMessage(j);
                messenger.notifyChild(j, "pressed HELP", null);
                channel.sendText(parentWaId, messenger.staffCard(j, facts));
                messenger.speak(parentWaId, TeluguPhrases.SHOW_CARD + " " + TeluguPhrases.SON_TOLD, null, facts);
            }
            case BTN_SON -> {
                controller.onParentMessage(j);
                messenger.notifyChild(j, "asked you to call", null);
                messenger.speak(parentWaId, TeluguPhrases.SON_TOLD, null, facts);
            }
            default -> log.info("unknown parent button {}", buttonId);
        }
        eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "button", Map.of("id", buttonId));
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
                new SparkRequest.JsonSchema("parent_turn", prompts.schema("parent_turn")), 0.2,
                ModelTape.sha256(transcript)); // the recording is tied to what she said, not to the journey's dates
        if (sparkOpen != null && sparkOpen.enabled()) {
            try {
                ParentTurn open = sparkOpen.client().orElseThrow().completeJson(req, ParentTurn.class, mapper);
                if (open != null && open.intent() != null && !open.intent().isBlank()) return open;
                log.warn("spark open returned no intent; falling back to {}", spark.model());
            } catch (RuntimeException e) {
                log.warn("spark open failed ({}); falling back to {}", e.getMessage(), spark.model());
            }
        }
        return spark.completeJson(req, ParentTurn.class, mapper);
    }

    /** Executes the tool Spark proposed — only the two a parent may trigger; the rest is the controller's business. */
    private void executeTool(ParentTurn turn, JourneyEntity journey, JourneyFacts facts, String parentWaId) {
        String tool = turn.toolProposal() == null ? "none" : turn.toolProposal();
        switch (tool) {
            case "request_family_help" -> messenger.notifyChild(journey, "asked for help", turn.englishGloss());
            case "create_staff_card" -> channel.sendText(parentWaId, messenger.staffCard(journey, facts));
            case "acknowledge_stage" -> controller.acknowledge(journey, "voice: " + turn.englishGloss());
            default -> { /* get_next_stage / none: the reply already carries the next verified step */ }
        }
    }

    /** English card the parent shows to staff (kept here for tests and the router). */
    String staffCard(JourneyEntity journey, JourneyFacts facts) {
        return messenger.staffCard(journey, facts);
    }
}
