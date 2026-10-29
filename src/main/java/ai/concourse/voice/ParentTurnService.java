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

    /** Parent sent a voice note. */
    public void handleVoiceNote(String parentWaId, String mediaId) {
        long t0 = System.currentTimeMillis();
        Optional<JourneyEntity> journey = journeys.latestForParent(hasher.hash(parentWaId));
        if (journey.isEmpty()) {
            messenger.speak(parentWaId, TeluguPhrases.NOT_SET_UP, null, null);
            return;
        }
        JourneyEntity j = journey.get();
        controller.onParentMessage(j);
        JourneyFacts facts = messenger.facts(j);

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
            messenger.speak(parentWaId, TeluguPhrases.CLARIFY, PARENT_BUTTONS, facts);
        } else {
            event.put("intent", turn.intent());
            event.put("confidence", turn.confidence());
            event.put("tool", turn.toolProposal());
            event.put("outcome", respond(j, facts, parentWaId, turn));
        }
        long t5 = System.currentTimeMillis();
        event.put("ms", Map.of("download", t1 - t0, "ffmpeg", t2 - t1, "stt", t3 - t2, "spark", t4 - t3, "tts_send", t5 - t4, "total", t5 - t0));
        eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "voice_turn", event);
        log.info("parent turn {} ms (dl {} / ffmpeg {} / stt {} / spark {} / tts+send {}) outcome={}",
                t5 - t0, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, event.get("outcome"));
    }

    /** Spark classified the intent; code answers. Returns the outcome label for the event log. */
    private String respond(JourneyEntity j, JourneyFacts facts, String parentWaId, ParentTurn turn) {
        switch (turn.intent()) {
            case "START_TRIP" -> { controller.recordConsent(j, "voice"); return "consent"; }
            case "I_AM_HERE" -> { controller.markHere(j, "voice"); return "here"; }
            case "AT_COUNTER" -> { controller.acknowledge(j, "voice: " + turn.englishGloss()); return "ack"; }
            case "WHAT_NEXT" -> {
                String text = controller.whatNext(j);
                if (j.getConsentAt() == null) {
                    messenger.speak(parentWaId, text, StageText.buttons(StageType.PRE_TRIP), facts);
                } else {
                    Optional<StageEntity> current = controller.current(j);
                    StageType type = current.map(StageEntity::stageType).orElse(null);
                    if (type != null && type.showsGateCard()) messenger.sendGateCard(parentWaId, facts, current.get().getSegmentSeq());
                    else if (type != null) messenger.sendSignCard(parentWaId, type, facts, current.get().getSegmentSeq());
                    messenger.speak(parentWaId, text, type == null ? null : StageText.buttons(type), facts);
                }
                return "what_next";
            }
            case "WHERE_IS_GATE" -> {
                messenger.sendGateCard(parentWaId, facts);
                messenger.speak(parentWaId, TeluguPhrases.gateAnswer(facts), null, facts);
                return "gate";
            }
            default -> {
                SafetyPolicy.Result checked = messenger.check(turn.replyTe(), facts);
                if (!checked.ok()) {
                    messenger.notifyChild(j, "safety fallback: " + String.join("; ", checked.violations()), turn.englishGloss());
                }
                executeTool(turn, j, facts, parentWaId);
                messenger.speak(parentWaId, checked.text(), null, facts);
                return checked.ok() ? "replied" : "fallback";
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
                new SparkRequest.JsonSchema("parent_turn", prompts.schema("parent_turn")), 0.2);
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
