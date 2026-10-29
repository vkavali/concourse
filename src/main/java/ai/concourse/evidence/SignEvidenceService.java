package ai.concourse.evidence;

import ai.concourse.common.PhoneHasher;
import ai.concourse.journey.EventLog;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import ai.concourse.journey.StageType;
import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import ai.concourse.voice.ParentMessenger;
import ai.concourse.voice.TeluguPhrases;
import ai.concourse.whatsapp.MessagingChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * She photographs a sign; Spark reports what is printed; code compares it with her next verified step and answers
 * from a template. The photo lives only in memory for this call. The observation can never change a gate or say
 * where she is: {@code location_claim} must be null (dropped and logged otherwise), and gate codes seen in the photo
 * are only ever compared, never written.
 */
@Service
public class SignEvidenceService {
    private static final Logger log = LoggerFactory.getLogger(SignEvidenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessagingChannel channel;
    private final SparkClient spark;
    private final PromptLoader prompts;
    private final JourneyService journeys;
    private final JourneyController controller;
    private final ParentMessenger messenger;
    private final PhoneHasher hasher;
    private final EventLog eventLog;

    public SignEvidenceService(MessagingChannel channel, SparkClient spark, PromptLoader prompts, JourneyService journeys,
                               JourneyController controller, ParentMessenger messenger, PhoneHasher hasher, EventLog eventLog) {
        this.channel = channel;
        this.spark = spark;
        this.prompts = prompts;
        this.journeys = journeys;
        this.controller = controller;
        this.messenger = messenger;
        this.hasher = hasher;
        this.eventLog = eventLog;
    }

    /** Parent sent a photo. */
    public void handlePhoto(String parentWaId, String mediaId, String mimeType) {
        long t0 = System.currentTimeMillis();
        Optional<JourneyEntity> journey = journeys.latestForParent(hasher.hash(parentWaId));
        if (journey.isEmpty()) {
            messenger.speak(parentWaId, TeluguPhrases.NOT_SET_UP, null, null);
            return;
        }
        JourneyEntity j = journey.get();
        controller.onParentMessage(j);
        JourneyFacts facts = messenger.facts(j);

        byte[] image = channel.downloadMedia(mediaId);
        long t1 = System.currentTimeMillis();
        SignObservation seen;
        try {
            seen = observe(image, mimeType == null ? "image/jpeg" : mimeType.split(";")[0].strip());
        } finally {
            java.util.Arrays.fill(image, (byte) 0); // the photo is never kept
        }
        long t2 = System.currentTimeMillis();

        if (seen.locationClaim() != null) {
            eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "location_claim_dropped", Map.of("claim", String.valueOf(seen.locationClaim())));
            seen = new SignObservation(seen.isAirportSign(), seen.visibleText(), seen.gateCodes(), seen.explicitArrow(), seen.confidence(), null);
        }

        Optional<StageEntity> current = controller.current(j);
        StageType stage = current.map(StageEntity::stageType).filter(t -> t != StageType.PRE_TRIP).orElse(StageType.WAITING_AT_ORIGIN);
        Integer segmentSeq = current.map(StageEntity::getSegmentSeq).orElse(1);
        JourneyFacts.Flight flight = facts.flights().stream().filter(f -> f.seq() == (segmentSeq == null ? 1 : segmentSeq)).findFirst().orElse(facts.firstFlight());
        EvidenceReconciler.Target target = EvidenceReconciler.targetFor(stage, flight);
        EvidenceReconciler.Verdict verdict = EvidenceReconciler.reconcile(seen, target);

        // The arrow she photographed is the only thing that ever licenses a direction word.
        JourneyFacts withArrow = new JourneyFacts(facts.status(), facts.travelerFirstName(), facts.flights(), facts.nextVerifiedStep(),
                facts.lastAcknowledgedStage(), verdict.kind() == EvidenceReconciler.Kind.MATCH ? verdict.arrow() : null);
        if (verdict.kind() != EvidenceReconciler.Kind.MATCH) {
            if (stage.showsGateCard()) messenger.sendGateCard(parentWaId, facts, segmentSeq);
            else messenger.sendSignCard(parentWaId, stage, facts, segmentSeq);
        }
        String sent = messenger.speak(parentWaId, verdict.telugu(), null, withArrow);
        long t3 = System.currentTimeMillis();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", stage.name());
        event.put("verdict", verdict.kind().name());
        event.put("matched", verdict.matched());
        event.put("arrow", verdict.arrow());
        event.put("confidence", seen.confidence());
        event.put("visible_text", seen.visibleText().size() > 12 ? seen.visibleText().subList(0, 12) : seen.visibleText());
        event.put("gate_codes", seen.gateCodes());
        event.put("fallback", !sent.equals(verdict.telugu()));
        event.put("ms", Map.of("download", t1 - t0, "spark", t2 - t1, "reply", t3 - t2, "total", t3 - t0));
        eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "sign_observed", event);
        log.info("sign turn {} ms (dl {} / spark {} / reply {}) verdict={} {}", t3 - t0, t1 - t0, t2 - t1, t3 - t2, verdict.kind(), verdict.english());
        if (verdict.kind() == EvidenceReconciler.Kind.MISMATCH) {
            messenger.tellChild(j, "She photographed a sign that does not show her gate (" + verdict.english() + "). I told her the verified gate and to ask staff if unsure.");
        }
    }

    SignObservation observe(byte[] image, String mimeType) {
        SparkRequest req = new SparkRequest(prompts.prompt("sign_observe"), "Report what is printed in this photo.",
                List.of(new SparkRequest.Attachment(mimeType, image, "sign.jpg")),
                new SparkRequest.JsonSchema("sign_observation", prompts.schema("sign_observe")), 0.0);
        return spark.completeJson(req, SignObservation.class, MAPPER);
    }
}
