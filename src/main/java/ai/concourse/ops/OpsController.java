package ai.concourse.ops;

import ai.concourse.common.PhoneHasher;
import ai.concourse.config.ConcourseProperties;
import ai.concourse.evidence.SamClient;
import ai.concourse.evidence.SignEvidenceService;
import ai.concourse.spark.SparkClient;
import ai.concourse.tts.TtsClient;
import ai.concourse.voice.ParentTurnService;
import ai.concourse.audio.AudioTranscoder;
import ai.concourse.itinerary.Itinerary;
import ai.concourse.itinerary.ItinerarySummary;
import ai.concourse.itinerary.TicketExtractor;
import ai.concourse.itinerary.ItineraryValidator;
import ai.concourse.itinerary.ValidatedItinerary;
import ai.concourse.journey.ConcourseClock;
import ai.concourse.journey.ContactEntity;
import ai.concourse.journey.ContactService;
import ai.concourse.journey.EventEntity;
import ai.concourse.journey.EventRepository;
import ai.concourse.journey.JourneyController;
import ai.concourse.journey.JourneyEntity;
import ai.concourse.journey.JourneyService;
import ai.concourse.journey.StageEntity;
import ai.concourse.travel.TravelLinks;
import ai.concourse.whatsapp.OutboxChannel;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operations endpoints (header {@code X-Admin-Token}), also what the simulator page ({@code /sim.html}) drives:
 * the only way a gate changes (a "simulated operations event", labelled as such on both phones), the time-travel
 * clock, a demo journey built from the sample itinerary without a ticket, her consent/ack/"I'm here" as if she had
 * tapped them, and a read-only view of the latest journey plus the outbox of what each phone was sent.
 */
@RestController
@RequestMapping("/ops")
public class OpsController {
    private final ConcourseProperties props;
    private final JourneyController controller;
    private final JourneyService journeys;
    private final ConcourseClock clock;
    private final ItineraryValidator validator;
    private final ContactService contacts;
    private final PhoneHasher hasher;
    private final EventRepository events;
    private final OutboxChannel outbox;
    private final TravelLinks travelLinks;
    private final ModelCallLog calls;
    private final ModelTape tape;
    private final ParentTurnService parentTurns;
    private final SignEvidenceService signs;
    private final TtsClient tts;
    private final SparkClient spark;
    private final SamClient sam;
    private final TicketExtractor extractor;
    private final AudioTranscoder transcoder;

    public OpsController(ConcourseProperties props, JourneyController controller, JourneyService journeys, ConcourseClock clock,
                         ItineraryValidator validator, ContactService contacts, PhoneHasher hasher, EventRepository events,
                         OutboxChannel outbox, TravelLinks travelLinks, ModelCallLog calls, ModelTape tape,
                         ParentTurnService parentTurns, SignEvidenceService signs, TtsClient tts, SparkClient spark, SamClient sam,
                         TicketExtractor extractor, AudioTranscoder transcoder) {
        this.extractor = extractor;
        this.transcoder = transcoder;
        this.sam = sam;
        this.calls = calls;
        this.tape = tape;
        this.parentTurns = parentTurns;
        this.signs = signs;
        this.tts = tts;
        this.spark = spark;
        this.travelLinks = travelLinks;
        this.props = props;
        this.controller = controller;
        this.journeys = journeys;
        this.clock = clock;
        this.validator = validator;
        this.contacts = contacts;
        this.hasher = hasher;
        this.events = events;
        this.outbox = outbox;
    }

    public record OpsEvent(String journey_id, String type, Integer segment, String new_gate, String source) {}

    public record ClockRequest(String now, Integer shift_minutes, Boolean reset) {}

    public record DemoRequest(String child, String parent, Integer days_ahead) {}

    /** Placeholder numbers for the simulator when WhatsApp is off: two, so both phone columns fill. Never dialled. */
    static final String SIM_CHILD = "10000000000", SIM_PARENT = "10000000001";

    /**
     * {child, parent} for a demo request, or null when no usable child number was given. Without WhatsApp the
     * simulator is the only phone, so blanks become the two placeholders; with WhatsApp a blank parent means one
     * real phone plays both roles.
     */
    static String[] phones(DemoRequest r, boolean sim) {
        String child = PhoneHasher.normalize(r.child() == null ? "" : r.child());
        if (child.length() < 10 && sim) child = SIM_CHILD;
        if (child.length() < 10) return null;
        String parent = PhoneHasher.normalize(r.parent() == null ? "" : r.parent());
        if (parent.length() < 10) parent = sim ? (child.equals(SIM_PARENT) ? SIM_CHILD : SIM_PARENT) : child;
        return new String[]{child, parent};
    }

    // ---- the gate change --------------------------------------------------------------------------------------

    @PostMapping("/events")
    public ResponseEntity<?> event(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody OpsEvent e) {
        if (!authorized(token)) return forbidden();
        if (!"GATE_CHANGE".equalsIgnoreCase(e.type())) return ResponseEntity.badRequest().body(Map.of("error", "type must be GATE_CHANGE"));
        Optional<JourneyEntity> j = e.journey_id() == null ? journeys.latest() : journeys.find(UUID.fromString(e.journey_id()));
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        String source = e.source() == null || e.source().isBlank() ? JourneyController.DEMO_SOURCE : e.source();
        boolean ok = controller.applyGateChange(j.get(), e.segment() == null ? 1 : e.segment(), e.new_gate(), source);
        return ok ? ResponseEntity.ok(Map.of("journey_id", j.get().getId().toString(), "applied", true, "source", source))
                : ResponseEntity.badRequest().body(Map.of("error", "no such flight segment"));
    }

    // ---- the clock ----------------------------------------------------------------------------------------------

    @GetMapping("/clock")
    public Map<String, Object> clock() {
        return Map.of("now", clock.now().toString(), "offset_minutes", clock.offset().toMinutes(), "travelling", clock.isTravelling());
    }

    @PostMapping("/clock")
    public ResponseEntity<?> setClock(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody ClockRequest r) {
        if (!authorized(token)) return forbidden();
        if (Boolean.TRUE.equals(r.reset())) clock.reset();
        else if (r.now() != null) clock.travelTo(OffsetDateTime.parse(r.now()).toInstant());
        else if (r.shift_minutes() != null) clock.shift(Duration.ofMinutes(r.shift_minutes()));
        controller.tick();
        return ResponseEntity.ok(clock());
    }

    // ---- demo journey without a ticket -----------------------------------------------------------------------

    /**
     * Builds the sample HYD→DOH→DFW journey dated {@code days_ahead} days from now (default 2), registers the two
     * numbers, confirms the itinerary and sends the intro — the same path a real ticket takes, minus Spark.
     * Any other open journey of the same traveler is abandoned so only one journey ever speaks to her.
     */
    @PostMapping("/demo")
    public ResponseEntity<?> demo(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody DemoRequest r) {
        if (!authorized(token)) return forbidden();
        String[] who = phones(r, !outbox.delivering());
        if (who == null) return ResponseEntity.badRequest().body(Map.of("error", "child must be a WhatsApp number with country code"));
        String child = who[0], parent = who[1];
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(r.days_ahead() == null ? 2 : Math.max(1, r.days_ahead()));
        String d = day.toString();
        Itinerary raw = new Itinerary(true, "sample itinerary", "Lakshmi", "DEMO42", List.of(
                new Itinerary.Segment("Qatar Airways", "QR4793", "HYD", "DOH", d + "T03:45", d + "T06:05", null, null, 1.0, ""),
                new Itinerary.Segment("Qatar Airways", "QR729", "DOH", "DFW", d + "T08:40", d + "T15:50", "D", null, 1.0, "")));
        ValidatedItinerary v = validator.validate(raw);
        if (v.hasErrors()) return ResponseEntity.badRequest().body(Map.of("error", v.errors().toString()));
        clock.reset();
        contacts.register(child, ContactEntity.Role.CHILD, "en");
        contacts.register(parent, ContactEntity.Role.PARENT, "te");
        JourneyEntity j = journeys.createFromItinerary(hasher.hash(child), v);
        journeys.confirmItinerary(j.getId(), hasher.hash(child));
        journeys.attachParent(hasher.hash(child), hasher.hash(parent));
        for (JourneyEntity old : journeys.abandonOthers(hasher.hash(parent), j.getId())) controller.cancelPendingJobs(old.getId(), "abandoned for demo " + j.getId());
        controller.onJourneyReady(j.getId());
        return ResponseEntity.ok(Map.of("journey_id", j.getId().toString(), "date", d, "parent_is_child", parent.equals(child)));
    }

    /**
     * The same journey, but read from the bundled sample ticket by Spark (the first model call of the story), then
     * re-dated so the first departure is {@code days_ahead} days away — labelled as such on both phones and in the log.
     */
    @PostMapping("/demo-ticket")
    public ResponseEntity<?> demoFromTicket(@RequestHeader(value = "X-Admin-Token", required = false) String token, @RequestBody DemoRequest r) {
        if (!authorized(token)) return forbidden();
        String[] who = phones(r, !outbox.delivering());
        if (who == null) return ResponseEntity.badRequest().body(Map.of("error", "child must be a WhatsApp number with country code"));
        String child = who[0], parent = who[1];
        Path ticket = Path.of(SAMPLE_TICKET);
        if (!Files.isRegularFile(ticket)) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no sample ticket at " + SAMPLE_TICKET));
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(ticket);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        int days = r.days_ahead() == null ? 2 : Math.max(1, r.days_ahead());
        Itinerary raw;
        try {
            raw = extractor.extract(bytes, "application/pdf", ticket.getFileName().toString());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Spark could not read the sample ticket: " + e.getMessage()));
        } finally {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
        if (!raw.isTicket() || raw.segmentsOrEmpty().isEmpty()) return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "not read as a ticket: " + raw.reason()));
        long shift = redateDays(raw, days);
        Itinerary dated = redate(raw, shift);
        ValidatedItinerary v = validator.validate(dated);
        if (v.hasErrors()) return ResponseEntity.badRequest().body(Map.of("error", v.errors().toString()));
        clock.reset();
        contacts.register(child, ContactEntity.Role.CHILD, "en");
        contacts.register(parent, ContactEntity.Role.PARENT, "te");
        JourneyEntity j = journeys.createFromItinerary(hasher.hash(child), v);
        journeys.confirmItinerary(j.getId(), hasher.hash(child));
        journeys.attachParent(hasher.hash(child), hasher.hash(parent));
        for (JourneyEntity old : journeys.abandonOthers(hasher.hash(parent), j.getId())) controller.cancelPendingJobs(old.getId(), "abandoned for demo " + j.getId());
        outbox.sendText(child, "Ticket read by " + spark.model() + " (sample ticket, re-dated +" + shift + " days for the demo):\n" + ItinerarySummary.forChild(v));
        controller.onJourneyReady(j.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey_id", j.getId().toString());
        out.put("traveler", raw.passengerFirstName());
        out.put("segments", raw.segmentsOrEmpty().size());
        out.put("redated_days", shift);
        out.put("parent_is_child", parent.equals(child));
        return ResponseEntity.ok(out);
    }

    static final String SAMPLE_TICKET = "eval/tickets/sample-itinerary-HYD-DOH-DFW.pdf";
    private static final java.time.format.DateTimeFormatter LOCAL = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** Days to add so the first departure lands {@code daysAhead} days from today (India time), keeping every time of day. */
    static long redateDays(Itinerary raw, int daysAhead) {
        try {
            java.time.LocalDateTime first = java.time.LocalDateTime.parse(raw.segmentsOrEmpty().get(0).departureLocal(), LOCAL);
            LocalDate target = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(daysAhead);
            return java.time.temporal.ChronoUnit.DAYS.between(first.toLocalDate(), target);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static Itinerary redate(Itinerary raw, long days) {
        if (days == 0) return raw;
        List<Itinerary.Segment> moved = raw.segmentsOrEmpty().stream().map(s -> new Itinerary.Segment(s.airline(), s.flightNumber(),
                s.origin(), s.destination(), shiftLocal(s.departureLocal(), days), shiftLocal(s.arrivalLocal(), days),
                s.terminal(), s.gate(), s.confidence(), s.notes())).toList();
        return new Itinerary(raw.isTicket(), raw.reason(), raw.passengerFirstName(), raw.bookingReference(), moved);
    }

    private static String shiftLocal(String local, long days) {
        if (local == null || local.isBlank()) return local;
        try {
            return java.time.LocalDateTime.parse(local, LOCAL).plusDays(days).format(LOCAL);
        } catch (RuntimeException e) {
            return local;
        }
    }

    // ---- acting for her (the simulator's buttons) --------------------------------------------------------------

    @PostMapping("/journeys/latest/{action}")
    public ResponseEntity<?> act(@RequestHeader(value = "X-Admin-Token", required = false) String token, @PathVariable String action) {
        if (!authorized(token)) return forbidden();
        Optional<JourneyEntity> j = journeys.latest();
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        JourneyEntity je = j.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey_id", je.getId().toString());
        switch (action) {
            case "consent" -> controller.recordConsent(je, "simulator");
            case "ack" -> controller.acknowledge(je, "simulator");
            case "here" -> out.put("complete", controller.markHere(je, "simulator"));
            case "next" -> out.put("fired", controller.fireNext(je).map(StageEntity::getType).orElse("nothing planned"));
            case "tick" -> controller.tick();
            default -> { return ResponseEntity.badRequest().body(Map.of("error", "unknown action " + action)); }
        }
        out.put("status", journeys.find(je.getId()).map(JourneyEntity::getStatus).orElse("?"));
        return ResponseEntity.ok(out);
    }

    // ---- read side ---------------------------------------------------------------------------------------------

    @GetMapping("/journeys/latest")
    public ResponseEntity<?> latest(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) return forbidden();
        Optional<JourneyEntity> j = journeys.latest();
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        JourneyEntity je = j.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("journey_id", je.getId().toString());
        out.put("traveler", je.getPassengerFirstName());
        out.put("status", je.getStatus());
        out.put("confirmed", je.getItineraryConfirmedAt() != null);
        out.put("parent_registered", je.getParentPhoneHash() != null);
        out.put("intro_sent", je.getIntroSentAt() != null);
        out.put("consent_at", je.getConsentAt() == null ? null : je.getConsentAt().toString());
        out.put("current_stage_id", je.getCurrentStageId() == null ? null : je.getCurrentStageId().toString());
        out.put("travel_url", travelLinks.url(je.getId()));
        out.put("flights", journeys.segments(je.getId()).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", s.getSeq()); m.put("flight", s.getFlightNumber()); m.put("from", s.getOrigin()); m.put("to", s.getDestination());
            m.put("departure", String.valueOf(s.getScheduledDeparture())); m.put("arrival", String.valueOf(s.getScheduledArrival()));
            m.put("terminal", s.getTerminal()); m.put("gate", s.getGate()); m.put("source", s.getSource());
            return m;
        }).toList());
        out.put("stages", controller.stages(je.getId()).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId().toString()); m.put("seq", s.getSeq()); m.put("type", s.getType()); m.put("airport", s.getAirport());
            m.put("scheduled_at", s.getScheduledAt() == null ? null : s.getScheduledAt().toString()); m.put("status", s.getStatus());
            m.put("sent_at", s.getSentAt() == null ? null : s.getSentAt().toString());
            m.put("acknowledged_at", s.getAcknowledgedAt() == null ? null : s.getAcknowledgedAt().toString());
            return m;
        }).toList());
        List<EventEntity> log = events.findByJourneyIdOrderByTs(je.getId());
        out.put("events", log.subList(Math.max(0, log.size() - 60), log.size()).stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", String.valueOf(e.getTs())); m.put("actor", e.getActor()); m.put("type", e.getType()); m.put("payload", e.getSanitizedPayload());
            return m;
        }).toList());
        out.put("clock", clock());
        return ResponseEntity.ok(out);
    }

    // ---- what is running (the simulator's banner) ---------------------------------------------------------------

    /** Which parts are live, replayed or absent — so the screen says plainly what a viewer is looking at. */
    @GetMapping("/status")
    public ResponseEntity<?> status(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) return forbidden();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("whatsapp", outbox.delivering() ? "delivering to the registered phones" : "not configured — outbox only");
        out.put("meta_api", spark.isConfigured() ? "live (" + spark.model() + ")" : "no MODEL_API_KEY");
        out.put("sam", sam.isConfigured() ? "live (" + sam.model() + ")" : "off");
        out.put("tts", tts.hasServer() ? "live (" + tts.modelId() + ")" : tts.isConfigured() ? "recorded notes only" : "no voice");
        out.put("tape", tape.mode().name().toLowerCase() + (tape.mode() == ModelTape.Mode.OFF ? "" : " at " + tape.dir()));
        out.put("samples", samples());
        return ResponseEntity.ok(out);
    }

    /** Every model call so far, newest last: model id, operation, milliseconds, ok, a short note. */
    @GetMapping("/models")
    public ResponseEntity<?> models(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @RequestParam(value = "after", defaultValue = "0") long after) {
        if (!authorized(token)) return forbidden();
        return ResponseEntity.ok(calls.since(after));
    }

    // ---- bundled sample inputs (her voice notes and sign photos from a recorded run) ------------------------------

    /** Names of the sample inputs under the tape folder: {@code *.wav} voice notes and {@code *.jpg/*.png} sign photos. */
    @GetMapping("/samples")
    public ResponseEntity<?> listSamples(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!authorized(token)) return forbidden();
        return ResponseEntity.ok(samples());
    }

    private List<String> samples() {
        Path dir = tape.inputsDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".wav") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png"))
                    .sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Sends a bundled sample as if she had sent it: a voice note goes through transcription and the turn, a photo through the sign reader. */
    @PostMapping("/journeys/latest/sample/{name}")
    public ResponseEntity<?> sample(@RequestHeader(value = "X-Admin-Token", required = false) String token, @PathVariable String name) {
        if (!authorized(token)) return forbidden();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return ResponseEntity.badRequest().body(Map.of("error", "bad name"));
        Path file = tape.inputsDir().resolve(name);
        if (!Files.isRegularFile(file)) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no such sample"));
        Optional<JourneyEntity> j = journeys.latest();
        if (j.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no journey"));
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sample", name);
        try {
            if (name.endsWith(".wav")) {
                ParentTurnService.Reply r = parentTurns.handleAudio(j.get(), bytes, "simulator");
                out.put("outcome", r.outcome()); out.put("intent", r.intent()); out.put("telugu", r.telugu());
            } else {
                String mime = name.endsWith(".png") ? "image/png" : "image/jpeg";
                SignEvidenceService.Assessment a = signs.handleImage(j.get(), bytes, mime, "simulator");
                out.put("verdict", a.verdict().kind().name()); out.put("english", a.verdict().english()); out.put("telugu", a.verdict().telugu());
            }
        } catch (RuntimeException e) {
            out.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(out);
        }
        return ResponseEntity.ok(out);
    }

    /** The voice note or card behind an outbox entry, for the simulator to play or show; {@code format=mp3} for Safari. */
    @GetMapping("/outbox/{seq}/media")
    public ResponseEntity<?> outboxMedia(@RequestHeader(value = "X-Admin-Token", required = false) String token, @PathVariable long seq,
                                         @RequestParam(value = "format", required = false) String format) {
        if (!authorized(token)) return forbidden();
        Optional<OutboxChannel.Media> media = outbox.media(seq);
        if (media.isEmpty()) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "no media for " + seq));
        OutboxChannel.Media m = media.get();
        if ("mp3".equalsIgnoreCase(format) && m.contentType().startsWith("audio/")) {
            try {
                return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg")).body(transcoder.toMp3(m.bytes()));
            } catch (RuntimeException e) {
                return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(Map.of("error", "cannot transcode to mp3 (is ffmpeg installed?): " + e.getMessage()));
            }
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(m.contentType())).body(m.bytes());
    }

    /** Everything sent to either phone since {@code after} (outbox sequence number), by role — never by number. */
    @GetMapping("/outbox")
    public ResponseEntity<?> outbox(@RequestHeader(value = "X-Admin-Token", required = false) String token,
                                    @RequestParam(value = "after", defaultValue = "0") long after) {
        if (!authorized(token)) return forbidden();
        return ResponseEntity.ok(outbox.since(after));
    }

    private boolean authorized(String token) {
        String expected = props.adminToken();
        return expected != null && !expected.isBlank() && !"change-me".equals(expected) && expected.equals(token);
    }

    private static ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "bad or missing X-Admin-Token (set ADMIN_TOKEN in .env)"));
    }
}
