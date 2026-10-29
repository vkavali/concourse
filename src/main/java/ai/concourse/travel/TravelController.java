package ai.concourse.travel;

import ai.concourse.audio.AudioTranscoder;
import ai.concourse.card.CardRenderer;
import ai.concourse.card.SignCardRenderer;
import ai.concourse.evidence.EvidenceReconciler;
import ai.concourse.evidence.SignEvidenceService;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import ai.concourse.journey.StageType;
import ai.concourse.safety.SafetyPolicy;
import ai.concourse.tts.TtsClient;
import ai.concourse.voice.Airlines;
import ai.concourse.voice.ParentMessenger;
import ai.concourse.voice.ParentTurnService;
import ai.concourse.voice.StageText;
import ai.concourse.voice.TeluguPhrases;
import ai.concourse.voice.TeluguSpeech;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Travel Mode's API: one screen on her phone with the current card, its voice, a live camera that reads the boards
 * she points at, and the same four buttons as WhatsApp. Every call carries the journey's link token. Replies come
 * from the same templates and the same safety check as WhatsApp; the camera path is the WhatsApp photo path.
 */
@RestController
@RequestMapping("/travel/api/{id}")
public class TravelController {
    private static final Logger log = LoggerFactory.getLogger(TravelController.class);
    private static final long FRAME_GAP_MS = 1500;

    private final TravelLinks links;
    private final JourneyService journeys;
    private final JourneyController controller;
    private final ParentMessenger messenger;
    private final SignEvidenceService signs;
    private final SafetyPolicy safety;
    private final TtsClient tts;
    private final AudioTranscoder transcoder;
    private final CardRenderer cards;
    private final SignCardRenderer signCards;
    private final ParentTurnService parentTurns;

    private final Map<String, String> clips = new ConcurrentHashMap<>();       // clip id → Telugu text (verdicts)
    private final Map<String, byte[]> mp3 = new ConcurrentHashMap<>();         // text hash → mp3
    private final Map<UUID, Long> lastFrame = new ConcurrentHashMap<>();

    public TravelController(TravelLinks links, JourneyService journeys, JourneyController controller, ParentMessenger messenger,
                            SignEvidenceService signs, SafetyPolicy safety, TtsClient tts, AudioTranscoder transcoder,
                            CardRenderer cards, SignCardRenderer signCards, ParentTurnService parentTurns) {
        this.parentTurns = parentTurns;
        this.links = links;
        this.journeys = journeys;
        this.controller = controller;
        this.messenger = messenger;
        this.signs = signs;
        this.safety = safety;
        this.tts = tts;
        this.transcoder = transcoder;
        this.cards = cards;
        this.signCards = signCards;
    }

    // ---- what to show ----------------------------------------------------------------------------------------

    @GetMapping("/state")
    public ResponseEntity<?> state(@PathVariable String id, @RequestParam("t") String token) {
        Optional<JourneyEntity> j = open(id, token);
        if (j.isEmpty()) return forbidden();
        JourneyEntity je = j.get();
        JourneyFacts facts = messenger.facts(je);
        Optional<StageEntity> current = controller.current(je);
        StageType type = current.map(StageEntity::stageType).orElse(StageType.PRE_TRIP);
        Integer seg = current.map(StageEntity::getSegmentSeq).orElse(1);
        JourneyFacts.Flight flight = flight(facts, seg);
        String telugu = je.getConsentAt() == null && type == StageType.PRE_TRIP ? StageText.intro(facts)
                : je.getConsentAt() == null ? StageText.NOT_STARTED
                : StageText.instruction(type, facts, seg, current.map(StageEntity::getAirport).orElse(null));
        telugu = safety.check(telugu, facts).text();
        SignCardRenderer.Spec spec = SignCardRenderer.specFor(type, flight);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("traveler", facts.travelerFirstName());
        out.put("status", je.getStatus());
        out.put("consented", je.getConsentAt() != null);
        out.put("complete", StageType.COMPLETE.name().equals(je.getStatus()));
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("seq", current.map(StageEntity::getSeq).orElse(1));
        stage.put("type", type.name());
        stage.put("airport", current.map(StageEntity::getAirport).orElse(flight == null ? null : flight.from()));
        stage.put("telugu", telugu);
        stage.put("english", StageText.english(type, facts, seg));
        stage.put("gate", flight == null ? null : flight.gate());
        stage.put("flight", flight == null ? null : flight.flight());
        stage.put("airline", flight == null ? null : flight.airline());
        stage.put("shows_gate_card", type.showsGateCard());
        stage.put("sign_word", spec == null ? null : spec.word());
        stage.put("sign_alts", spec == null ? List.of() : spec.alsoSeenAs());
        stage.put("asks_here", type == StageType.EXIT || type == StageType.FAMILY_HANDOFF);
        stage.put("asks_done", type.asksForAck() && type != StageType.EXIT && type != StageType.FAMILY_HANDOFF);
        out.put("stage", stage);
        out.put("version", (current.map(s -> s.getId().toString()).orElse("none")) + "|" + je.getStatus() + "|" + (je.getConsentAt() != null)
                + "|" + (flight == null ? "" : flight.gate()));
        out.put("audio", tts.isConfigured() ? "/travel/api/" + id + "/audio/stage?t=" + token + "&v=" + hash(telugu).substring(0, 8) : null);
        out.put("card", "/travel/api/" + id + "/card?t=" + token + "&v=" + hash(type + "|" + (flight == null ? "" : flight.gate())).substring(0, 8));
        out.put("staff_card", messenger.staffCard(je, facts));
        out.put("suggest", messenger.navigationAskedRecently(je) ? "camera" : null);
        return ResponseEntity.ok(out);
    }

    // ---- hold to talk --------------------------------------------------------------------------------------------

    /** Her voice from the screen's microphone: any browser recording → WAV → the same turn as a WhatsApp voice note. */
    @PostMapping(value = "/voice", consumes = {"audio/*", MediaType.APPLICATION_OCTET_STREAM_VALUE, "video/webm"})
    public ResponseEntity<?> voice(@PathVariable String id, @RequestParam("t") String token, @RequestBody byte[] audio) {
        Optional<JourneyEntity> j = open(id, token);
        if (j.isEmpty()) return forbidden();
        if (audio == null || audio.length < 200) return ResponseEntity.badRequest().body(Map.of("error", "empty recording"));
        long now = System.currentTimeMillis();
        Long last = lastFrame.get(j.get().getId());
        if (last != null && now - last < FRAME_GAP_MS) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("retry_ms", FRAME_GAP_MS - (now - last)));
        lastFrame.put(j.get().getId(), now);
        byte[] wav = transcoder.toWav16kMono(audio);
        ParentTurnService.Reply reply = parentTurns.handleAudio(j.get(), wav, "travel");
        String clipId = hash(reply.telugu()).substring(0, 16);
        clips.put(clipId, reply.telugu());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", reply.outcome());
        out.put("intent", reply.intent());
        out.put("telugu", reply.telugu());
        out.put("audio", tts.isConfigured() ? "/travel/api/" + id + "/audio/" + clipId + "?t=" + token : null);
        out.put("camera", reply.intent() != null && (reply.intent().equals("LOST") || reply.intent().equals("WHERE_IS_GATE") || reply.intent().equals("WHAT_NEXT")));
        return ResponseEntity.ok(out);
    }

    @GetMapping(value = "/card", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> card(@PathVariable String id, @RequestParam("t") String token) {
        Optional<JourneyEntity> j = open(id, token);
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        JourneyFacts facts = messenger.facts(j.get());
        Optional<StageEntity> current = controller.current(j.get());
        StageType type = current.map(StageEntity::stageType).orElse(StageType.PRE_TRIP);
        JourneyFacts.Flight flight = flight(facts, current.map(StageEntity::getSegmentSeq).orElse(1));
        if (flight == null) return ResponseEntity.notFound().build();
        byte[] png = type.showsGateCard() || SignCardRenderer.specFor(type, flight) == null ? cards.gateCard(flight) : signCards.signCard(type, flight);
        return ResponseEntity.ok().header("Cache-Control", "private, max-age=60").body(png);
    }

    @GetMapping(value = "/audio/stage", produces = "audio/mpeg")
    public ResponseEntity<byte[]> stageAudio(@PathVariable String id, @RequestParam("t") String token) {
        Optional<JourneyEntity> j = open(id, token);
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        if (!tts.isConfigured()) return ResponseEntity.noContent().build();
        JourneyEntity je = j.get();
        JourneyFacts facts = messenger.facts(je);
        Optional<StageEntity> current = controller.current(je);
        StageType type = current.map(StageEntity::stageType).orElse(StageType.PRE_TRIP);
        String telugu = je.getConsentAt() == null && type == StageType.PRE_TRIP ? StageText.intro(facts)
                : je.getConsentAt() == null ? StageText.NOT_STARTED
                : StageText.instruction(type, facts, current.map(StageEntity::getSegmentSeq).orElse(1), current.map(StageEntity::getAirport).orElse(null));
        return ResponseEntity.ok().header("Cache-Control", "private, max-age=300").body(mp3(safety.check(telugu, facts).text(), facts));
    }

    @GetMapping(value = "/audio/{clip}", produces = "audio/mpeg")
    public ResponseEntity<byte[]> clipAudio(@PathVariable String id, @PathVariable String clip, @RequestParam("t") String token) {
        Optional<JourneyEntity> j = open(id, token);
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        String text = clips.get(clip);
        if (text == null || !tts.isConfigured()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "private, max-age=300").body(mp3(text, messenger.facts(j.get())));
    }

    // ---- the camera -------------------------------------------------------------------------------------------

    /** One camera frame (JPEG body). Same reader, same reconciler, same safety check as a WhatsApp photo. */
    @PostMapping(value = "/frame", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    public ResponseEntity<?> frame(@PathVariable String id, @RequestParam("t") String token,
                                   @RequestHeader(value = "Content-Type", required = false) String contentType, @RequestBody byte[] image) {
        Optional<JourneyEntity> j = open(id, token);
        if (j.isEmpty()) return forbidden();
        if (image == null || image.length < 500) return ResponseEntity.badRequest().body(Map.of("error", "empty frame"));
        long now = System.currentTimeMillis();
        Long last = lastFrame.get(j.get().getId());
        if (last != null && now - last < FRAME_GAP_MS) return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("retry_ms", FRAME_GAP_MS - (now - last)));
        lastFrame.put(j.get().getId(), now);
        controller.onParentMessage(j.get());
        SignEvidenceService.Assessment a = signs.assess(j.get(), image, contentType == null ? "image/jpeg" : contentType, "camera");
        String telugu = a.safeTelugu(safety);
        String clipId = hash(telugu).substring(0, 16);
        clips.put(clipId, telugu);
        if (clips.size() > 400) clips.clear();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", a.verdict().kind().name());
        out.put("matched", a.verdict().matched());
        out.put("arrow", a.verdict().kind() == EvidenceReconciler.Kind.MATCH ? a.verdict().arrow() : null);
        out.put("telugu", telugu);
        out.put("english", a.verdict().english());
        out.put("visible_text", a.seen().visibleText());
        out.put("gate_codes", a.seen().gateCodes());
        out.put("confidence", a.seen().confidence());
        out.put("stage", a.stage().name());
        out.put("audio", tts.isConfigured() ? "/travel/api/" + id + "/audio/" + clipId + "?t=" + token : null);
        out.put("spark_ms", a.sparkMs());
        if (a.verdict().kind() == EvidenceReconciler.Kind.MISMATCH) {
            messenger.tellChild(j.get(), "Camera: she pointed at a sign that does not show her gate (" + a.verdict().english() + "). The screen told her the verified gate.");
        }
        return ResponseEntity.ok(out);
    }

    // ---- the buttons ------------------------------------------------------------------------------------------

    @PostMapping("/action/{what}")
    public ResponseEntity<?> action(@PathVariable String id, @PathVariable String what, @RequestParam("t") String token) {
        Optional<JourneyEntity> j = open(id, token);
        if (j.isEmpty()) return forbidden();
        JourneyEntity je = j.get();
        Map<String, Object> out = new LinkedHashMap<>();
        switch (what) {
            case "start" -> controller.recordConsent(je, "travel mode");
            case "done" -> controller.acknowledge(je, "travel mode");
            case "here" -> out.put("complete", controller.markHere(je, "travel mode"));
            case "help" -> {
                controller.onParentMessage(je);
                messenger.notifyChild(je, "pressed HELP on the travel screen", null);
                out.put("staff_card", messenger.staffCard(je, messenger.facts(je)));
            }
            case "son" -> {
                controller.onParentMessage(je);
                messenger.notifyChild(je, "asked you to call (travel screen)", null);
                out.put("telugu", TeluguPhrases.SON_TOLD);
            }
            default -> { return ResponseEntity.badRequest().body(Map.of("error", "unknown action")); }
        }
        out.put("status", journeys.find(je.getId()).map(JourneyEntity::getStatus).orElse(je.getStatus()));
        return ResponseEntity.ok(out);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Optional<JourneyEntity> open(String id, String token) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!links.valid(uuid, token)) return Optional.empty();
        return journeys.find(uuid);
    }

    private byte[] mp3(String telugu, JourneyFacts facts) {
        String key = hash(telugu);
        byte[] cached = mp3.get(key);
        if (cached != null) return cached;
        byte[] ogg = tts.synthesizeOggOpus(TeluguSpeech.spokenForm(telugu, Airlines.spokenNames(facts == null ? null : facts.flights())));
        byte[] out = transcoder.toMp3(ogg);
        if (mp3.size() > 200) mp3.clear();
        mp3.put(key, out);
        return out;
    }

    private static JourneyFacts.Flight flight(JourneyFacts facts, Integer seg) {
        if (facts == null || facts.flights().isEmpty()) return null;
        int s = seg == null ? 1 : seg;
        return facts.flights().stream().filter(f -> f.seq() == s).findFirst().orElse(facts.firstFlight());
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "this link is not valid"));
    }
}
