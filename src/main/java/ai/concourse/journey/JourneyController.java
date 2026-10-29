package ai.concourse.journey;

import ai.concourse.itinerary.Airports;
import ai.concourse.journey.JourneyStateMachine.Trigger;
import ai.concourse.voice.ParentMessenger;
import ai.concourse.voice.StageText;
import ai.concourse.voice.TeluguPhrases;
import ai.concourse.whatsapp.MessagingChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns journey state and time. Plans stages from verified flight times, pushes each one when the clock says so,
 * records consent and acknowledgements, rebuilds the plan on a verified flight update, and completes the journey on
 * her "I'm here". Every state change is checked against {@link JourneyStateMachine} first; no model is involved.
 * <p>
 * A stage is marked SENT before its messages go out, so a failure mid-send is reported, never retried into a
 * double-send. Outside WhatsApp's 24-hour window the stage is HELD: the approved template goes out, and the voice
 * note follows the moment she replies ({@link #onParentMessage}).
 */
@Service
public class JourneyController {
    private static final Logger log = LoggerFactory.getLogger(JourneyController.class);
    public static final String TEMPLATE_NAME = "journey_update";
    public static final String DEMO_SOURCE = "demo operations event";
    static final String HELD = "HELD";
    private static final Duration WINDOW = Duration.ofHours(23).plusMinutes(50);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_HHMM = DateTimeFormatter.ofPattern("dd MMM HH:mm");

    private final JourneyService journeys;
    private final StageRepository stages;
    private final OutboundJobRepository jobs;
    private final ParentMessenger messenger;
    private final MessagingChannel channel;
    private final EventLog eventLog;
    private final ConcourseClock clock;
    private final Airports airports;

    public JourneyController(JourneyService journeys, StageRepository stages, OutboundJobRepository jobs, ParentMessenger messenger,
                             MessagingChannel channel, EventLog eventLog, ConcourseClock clock, Airports airports) {
        this.journeys = journeys;
        this.stages = stages;
        this.jobs = jobs;
        this.messenger = messenger;
        this.channel = channel;
        this.eventLog = eventLog;
        this.clock = clock;
        this.airports = airports;
    }

    // ---- onboarding ------------------------------------------------------------------------------------------

    /** Called after the itinerary is confirmed and after the traveler is registered: once both are true, plan + intro. */
    @Transactional
    public void onJourneyReady(UUID journeyId) {
        JourneyEntity j = journeys.find(journeyId).orElse(null);
        if (j == null || j.getItineraryConfirmedAt() == null || j.getParentPhoneHash() == null || j.getIntroSentAt() != null) return;
        if (messenger.parentOf(j).isEmpty()) return;
        StagePlanner.Plan plan = planStages(j);
        JourneyFacts facts = messenger.facts(j);
        StageEntity preTrip = stages.findByJourneyIdOrderBySeq(j.getId()).stream()
                .filter(s -> s.stageType() == StageType.PRE_TRIP).findFirst().orElse(null);
        String parent = messenger.parentOf(j).orElseThrow().getE164();
        messenger.speak(parent, StageText.intro(facts), StageText.buttons(StageType.PRE_TRIP), facts);
        OffsetDateTime now = OffsetDateTime.now();
        j.setIntroSentAt(now);
        if (preTrip != null) {
            preTrip.setStatus(StageEntity.SENT);
            preTrip.setSentAt(now);
            stages.save(preTrip);
            j.setCurrentStageId(preTrip.getId());
        }
        journeys.save(j);
        eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "intro_sent", Map.of("stages", plan.stages().size()));
        StringBuilder sb = new StringBuilder("Intro sent to the traveler. She is asked to say 'start my trip' (or tap the button) — nothing is pushed until she does.\n")
                .append("Plan: ").append(plan.stages().size()).append(" steps");
        plan.stages().stream().filter(s -> s.scheduledAt() != null).findFirst()
                .ifPresent(s -> sb.append(", first (").append(s.type()).append(") at ").append(local(s.scheduledAt(), s.airport())));
        sb.append(". Type 'plan' to see them.");
        for (String w : plan.warnings()) sb.append("\n⚠ ").append(w);
        OffsetDateTime lastTimed = plan.stages().stream().map(StagePlanner.PlannedStage::scheduledAt).filter(t -> t != null)
                .max(OffsetDateTime::compareTo).orElse(null);
        if (lastTimed != null && lastTimed.isBefore(clock.now())) {
            sb.append("\n⚠ Every stage of this itinerary is already in the past, so they cannot fire in order — use a future-dated ticket (the sample itinerary in the repo) or the simulator.");
        }
        messenger.tellChild(j, sb.toString());
        messenger.sendTravelLink(j);
        prewarm(j, facts);
    }

    /** Warms the voice cache for everything this journey may say, so stage pushes land fast. */
    private void prewarm(JourneyEntity j, JourneyFacts facts) {
        List<String> texts = new ArrayList<>();
        texts.add(StageText.CONSENT_THANKS);
        texts.add(StageText.ACK_THANKS);
        texts.add(TeluguPhrases.gateAnswer(facts));
        for (StageEntity s : stages.findByJourneyIdOrderBySeq(j.getId())) {
            if (s.stageType() == StageType.PRE_TRIP) continue;
            texts.add(StageText.instruction(s.stageType(), facts, s.getSegmentSeq(), s.getAirport()));
        }
        messenger.prewarm(texts, facts);
    }

    /** Closes every pending job of a journey (it was abandoned or completed). */
    @Transactional
    public void cancelPendingJobs(UUID journeyId, String why) {
        for (OutboundJobEntity job : jobs.findByJourneyIdAndStatus(journeyId, OutboundJobEntity.PENDING)) {
            job.setStatus(OutboundJobEntity.CANCELLED);
            job.setLastError(why);
            jobs.save(job);
        }
    }

    /** (Re)builds the PLANNED stages and their jobs from the current verified segments; sent/acked rows stay. */
    @Transactional
    public StagePlanner.Plan planStages(JourneyEntity j) {
        List<StagePlanner.Leg> legs = journeys.segments(j.getId()).stream()
                .map(s -> new StagePlanner.Leg(s.getSeq(), s.getOrigin(), s.getDestination(), s.getScheduledDeparture(), s.getScheduledArrival()))
                .toList();
        StagePlanner.Plan plan = StagePlanner.plan(legs);
        List<StageEntity> existing = stages.findByJourneyIdOrderBySeq(j.getId());
        int keepUpTo = existing.stream().filter(s -> !s.getStatus().equals(StageEntity.PLANNED) && !s.getStatus().equals(HELD))
                .mapToInt(StageEntity::getSeq).max().orElse(0);
        for (StageEntity s : existing) {
            if (s.getSeq() > keepUpTo) {
                for (OutboundJobEntity job : jobs.findByJourneyIdOrderByRunAt(j.getId())) {
                    if (s.getId().equals(job.getStageId()) && OutboundJobEntity.PENDING.equals(job.getStatus())) {
                        job.setStatus(OutboundJobEntity.CANCELLED);
                        jobs.save(job);
                    }
                }
                stages.delete(s);
            }
        }
        for (StagePlanner.PlannedStage p : plan.stages()) {
            if (p.seq() <= keepUpTo) continue;
            StageEntity s = new StageEntity();
            s.setId(UUID.randomUUID());
            s.setJourneyId(j.getId());
            s.setSeq(p.seq());
            s.setType(p.type().name());
            s.setAirport(p.airport());
            s.setSegmentSeq(p.segmentSeq());
            s.setScheduledAt(p.scheduledAt());
            s.setStatus(StageEntity.PLANNED);
            stages.save(s);
            if (p.scheduledAt() != null) {
                OutboundJobEntity job = new OutboundJobEntity();
                job.setId(UUID.randomUUID());
                job.setJourneyId(j.getId());
                job.setStageId(s.getId());
                job.setRunAt(p.scheduledAt());
                job.setKind(OutboundJobEntity.KIND_STAGE_PUSH);
                job.setPayload("{\"stage\":\"" + p.type() + "\"}");
                jobs.save(job);
            }
        }
        eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "stages_planned", Map.of("stages", plan.stages().size(), "kept", keepUpTo, "warnings", plan.warnings().size()));
        return plan;
    }

    // ---- consent and parent activity -------------------------------------------------------------------------

    /** Her "start my trip" (voice or button). Opens the journey; pushes a stage that is already due. */
    @Transactional
    public void recordConsent(JourneyEntity j, String how) {
        touch(j);
        if (j.getConsentAt() != null) {
            messenger.speak(parentNumber(j), StageText.ACK_THANKS, null, messenger.facts(j));
            return;
        }
        j.setConsentAt(OffsetDateTime.now());
        journeys.save(j);
        eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "consent", Map.of("how", how));
        messenger.speak(parentNumber(j), StageText.CONSENT_THANKS, null, messenger.facts(j));
        if (!"travel mode".equals(how)) messenger.sendTravelLink(j);
        messenger.tellChild(j, "She said 'start my trip' (" + how + "). Consent recorded; stage pushes are armed. Her travel screen: " + messenger.travelUrl(j));
        advanceDue(j);
    }

    /** Any inbound parent message: the 24-hour window is open again, so a HELD stage can go out now. */
    @Transactional
    public void onParentMessage(JourneyEntity j) {
        touch(j);
        advanceDue(j);
    }

    private void touch(JourneyEntity j) {
        j.setLastParentMessageAt(OffsetDateTime.now());
        journeys.save(j);
    }

    // ---- the clock ------------------------------------------------------------------------------------------

    /** Scheduler entry point: every journey with a due job gets one chance to advance. */
    public void tick() {
        List<OutboundJobEntity> due = jobs.findByStatusAndRunAtLessThanEqualOrderByRunAt(OutboundJobEntity.PENDING, clock.now());
        List<UUID> seen = new ArrayList<>();
        for (OutboundJobEntity job : due) {
            if (seen.contains(job.getJourneyId())) continue;
            seen.add(job.getJourneyId());
            journeys.find(job.getJourneyId()).ifPresent(j -> {
                try {
                    advanceDue(j);
                } catch (RuntimeException e) {
                    log.error("journey {} tick failed", j.getId(), e);
                }
            });
        }
    }

    /**
     * Pushes the latest due stage (earlier due ones are SKIPPED, never sent late in a burst). Nothing moves without
     * consent; the child is told once per stage that it is waiting. Returns the stage pushed, if any.
     */
    @Transactional
    public Optional<StageEntity> advanceDue(JourneyEntity j) {
        OffsetDateTime now = clock.now();
        List<StageEntity> all = stages.findByJourneyIdOrderBySeq(j.getId());
        List<StageEntity> due = all.stream()
                .filter(s -> (StageEntity.PLANNED.equals(s.getStatus()) || HELD.equals(s.getStatus()))
                        && s.getScheduledAt() != null && !s.getScheduledAt().isAfter(now))
                .toList();
        if (due.isEmpty()) return Optional.empty();
        if (j.getConsentAt() == null) {
            holdForConsent(j, due);
            return Optional.empty();
        }
        StageEntity target = due.get(due.size() - 1);
        List<StageType> plan = all.stream().map(StageEntity::stageType).toList();
        int fromIndex = currentIndex(j, all);
        List<StageType> skipped = JourneyStateMachine.path(plan, fromIndex, target.getSeq() - 1, Trigger.TIME, true);
        if (skipped == null) {
            eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "transition_refused",
                    Map.of("from", j.getStatus(), "to", target.getType(), "trigger", "TIME"));
            log.warn("journey {} refused TIME transition {} → {}", j.getId(), j.getStatus(), target.getType());
            finishJob(target, OutboundJobEntity.FAILED, "transition refused");
            return Optional.empty();
        }
        for (StageEntity s : due) {
            if (s == target) break;
            s.setStatus(StageEntity.SKIPPED);
            stages.save(s);
            finishJob(s, OutboundJobEntity.CANCELLED, "superseded by " + target.getType());
            eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "stage_skipped", Map.of("stage", s.getType()));
        }
        pushStage(j, target);
        return Optional.of(target);
    }

    /** Due but no consent: the jobs are flagged and the child is told once per tick — one line, not one per stage. */
    private void holdForConsent(JourneyEntity j, List<StageEntity> due) {
        List<OutboundJobEntity> all = jobs.findByJourneyIdOrderByRunAt(j.getId());
        int newlyHeld = 0;
        for (StageEntity s : due) {
            for (OutboundJobEntity job : all) {
                if (s.getId().equals(job.getStageId()) && OutboundJobEntity.PENDING.equals(job.getStatus()) && job.getLastError() == null) {
                    job.setLastError("waiting for consent");
                    jobs.save(job);
                    newlyHeld++;
                }
            }
        }
        if (newlyHeld == 0) return;
        StageEntity latest = due.get(due.size() - 1);
        messenger.tellChild(j, (due.size() == 1 ? "Stage " + latest.getType() + " is due" : due.size() + " stages are due (latest " + latest.getType() + ")")
                + " but she has not said 'start my trip' yet. Only the latest goes out when she does. Ask her to send a voice note saying it, or to tap the button.");
        eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "stages_waiting_consent", Map.of("due", due.size(), "latest", latest.getType()));
    }

    private void pushStage(JourneyEntity j, StageEntity stage) {
        OffsetDateTime now = OffsetDateTime.now();
        String parent = parentNumber(j);
        StageType type = stage.stageType();
        JourneyFacts facts = messenger.facts(j);
        if (parent == null) {
            finishJob(stage, OutboundJobEntity.FAILED, "traveler not registered");
            return;
        }
        if (outsideWindow(j)) {
            stage.setStatus(HELD);
            stages.save(stage);
            finishJob(stage, OutboundJobEntity.FAILED, "outside 24h window: template sent, voice note waits for her reply");
            try {
                channel.sendTemplate(parent, TEMPLATE_NAME, "te", List.of());
                eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "stage_held_template_sent", Map.of("stage", type.name()));
                messenger.tellChild(j, "Stage " + type + " is due, but her WhatsApp window is closed (no message from her in 24 h). "
                        + "I sent the approved template; the voice note goes out the moment she replies with anything.");
            } catch (RuntimeException e) {
                eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "stage_held_template_failed", Map.of("stage", type.name(), "error", String.valueOf(e.getMessage())));
                messenger.tellChild(j, "Stage " + type + " is due, but her WhatsApp window is closed and the template could not be sent ("
                        + e.getMessage() + "). Ask her to send any voice note — then it goes out at once.");
            }
            return;
        }
        // Mark first, then send: a failure is reported, never retried into a double-send.
        stage.setStatus(StageEntity.SENT);
        stage.setSentAt(now);
        stages.save(stage);
        j.setStatus(type.name());
        j.setCurrentStageId(stage.getId());
        journeys.save(j);
        finishJob(stage, OutboundJobEntity.SENT, null);
        try {
            if (type.showsGateCard()) messenger.sendGateCard(parent, facts, stage.getSegmentSeq());
            else messenger.sendSignCard(parent, type, facts, stage.getSegmentSeq());
            String text = StageText.instruction(type, facts, stage.getSegmentSeq(), stage.getAirport());
            messenger.speak(parent, text, StageText.buttons(type), facts);
            eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "stage_sent", Map.of("stage", type.name(), "seq", stage.getSeq(),
                    "scheduled_at", String.valueOf(stage.getScheduledAt()), "clock_offset_min", clock.offset().toMinutes()));
            messenger.tellChild(j, "Sent stage " + stage.getSeq() + " " + type + " (" + local(stage.getScheduledAt(), stage.getAirport()) + "): "
                    + StageText.english(type, facts, stage.getSegmentSeq()) + ".");
        } catch (RuntimeException e) {
            log.error("journey {} stage {} send failed", j.getId(), type, e);
            eventLog.record(j.getId(), EventLog.ACTOR_SYSTEM, "stage_send_failed", Map.of("stage", type.name(), "error", String.valueOf(e.getMessage())));
            messenger.tellChild(j, "Could not deliver stage " + type + " to her (" + e.getMessage() + "). She can tap 'what next' or send a voice note to get it.");
        }
    }

    /** Demo: move the clock to the next planned stage and push it now. Returns what was pushed. */
    @Transactional
    public Optional<StageEntity> fireNext(JourneyEntity j) {
        Optional<StageEntity> next = stages.findByJourneyIdOrderBySeq(j.getId()).stream()
                .filter(s -> (StageEntity.PLANNED.equals(s.getStatus()) || HELD.equals(s.getStatus())) && s.getScheduledAt() != null)
                .findFirst();
        if (next.isEmpty()) return Optional.empty();
        if (next.get().getScheduledAt().isAfter(clock.now())) clock.travelTo(next.get().getScheduledAt().toInstant());
        return advanceDue(j);
    }

    // ---- acknowledgements --------------------------------------------------------------------------------------

    /** "Done" (button or voice) for the stage she has: evidence, not a transition — the next stage fires on time. */
    @Transactional
    public void acknowledge(JourneyEntity j, String how) {
        touch(j);
        JourneyFacts facts = messenger.facts(j);
        StageEntity current = current(j).orElse(null);
        if (j.getConsentAt() == null) {
            messenger.speak(parentNumber(j), StageText.NOT_STARTED, StageText.buttons(StageType.PRE_TRIP), facts);
            return;
        }
        if (current != null && (current.stageType() == StageType.EXIT || current.stageType() == StageType.FAMILY_HANDOFF)) {
            markHere(j, how);
            return;
        }
        if (current != null && StageEntity.SENT.equals(current.getStatus()) && current.stageType() != StageType.PRE_TRIP) {
            current.setStatus(StageEntity.ACKED);
            current.setAcknowledgedAt(OffsetDateTime.now());
            stages.save(current);
            eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "stage_ack", Map.of("stage", current.getType(), "how", how));
            messenger.tellChild(j, "She confirmed stage " + current.getType() + " (" + how + "): " + StageText.english(current.stageType(), facts, current.getSegmentSeq()) + ".");
        }
        messenger.speak(parentNumber(j), StageText.ACK_THANKS, null, facts);
    }

    /** Her "I'm here": completes the journey from EXIT or FAMILY_HANDOFF; earlier than that it is just an ack. */
    @Transactional
    public boolean markHere(JourneyEntity j, String how) {
        touch(j);
        JourneyFacts facts = messenger.facts(j);
        List<StageEntity> all = stages.findByJourneyIdOrderBySeq(j.getId());
        StageEntity complete = all.stream().filter(s -> s.stageType() == StageType.COMPLETE).findFirst().orElse(null);
        int fromIndex = currentIndex(j, all);
        List<StageType> skipped = complete == null || j.getConsentAt() == null ? null
                : JourneyStateMachine.path(all.stream().map(StageEntity::stageType).toList(), fromIndex, complete.getSeq() - 1, Trigger.PARENT_ACK, true);
        if (skipped == null) {
            eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "here_pressed_early", Map.of("stage", j.getStatus(), "how", how));
            messenger.tellChild(j, "She pressed 'I'm here' (" + how + ") while the last stage sent was " + j.getStatus() + ". Not treating the journey as complete — call her if that is unexpected.");
            messenger.speak(parentNumber(j), TeluguPhrases.SON_TOLD, null, facts);
            return false;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (StageEntity s : all) {
            if (s.getSeq() == fromIndex + 1 && StageEntity.SENT.equals(s.getStatus())) {
                s.setStatus(StageEntity.ACKED);
                s.setAcknowledgedAt(now);
                stages.save(s);
            } else if (s.getSeq() > fromIndex + 1 && s.getSeq() < complete.getSeq() && !StageEntity.ACKED.equals(s.getStatus())) {
                s.setStatus(StageEntity.SKIPPED);
                stages.save(s);
                finishJob(s, OutboundJobEntity.CANCELLED, "journey complete");
            }
        }
        complete.setStatus(StageEntity.ACKED);
        complete.setSentAt(now);
        complete.setAcknowledgedAt(now);
        stages.save(complete);
        j.setStatus(StageType.COMPLETE.name());
        j.setCurrentStageId(complete.getId());
        journeys.save(j);
        eventLog.record(j.getId(), EventLog.ACTOR_PARENT, "journey_complete", Map.of("how", how));
        messenger.speak(parentNumber(j), StageText.instruction(StageType.COMPLETE, facts, null, null), null, facts);
        messenger.tellChild(j, "She pressed 'I'm here' (" + how + ") at " + HHMM.format(now.atZoneSameInstant(ZoneId.systemDefault())) + ". Journey complete.");
        return true;
    }

    /** Telugu answer to "what do I do now?" from the plan — never from a model. */
    public String whatNext(JourneyEntity j) {
        if (j.getConsentAt() == null) return StageText.NOT_STARTED;
        JourneyFacts facts = messenger.facts(j);
        Optional<StageEntity> current = current(j);
        if (current.isPresent() && current.get().stageType() != StageType.PRE_TRIP) {
            StageEntity s = current.get();
            return StageText.instruction(s.stageType(), facts, s.getSegmentSeq(), s.getAirport());
        }
        return StageText.ACK_THANKS;
    }

    // ---- verified operations events --------------------------------------------------------------------------

    /** The demo's gate change: the only gate writer, then the remaining plan is rebuilt and both phones are told. */
    @Transactional
    public boolean applyGateChange(JourneyEntity j, int segmentSeq, String newGate, String source) {
        if (journeys.applyVerifiedFlightUpdate(j.getId(), segmentSeq, newGate, source).isEmpty()) return false;
        JourneyEntity fresh = journeys.find(j.getId()).orElse(j);
        if (fresh.getIntroSentAt() != null) {
            planStages(fresh);
            prewarm(fresh, messenger.facts(fresh));
        }
        announceGateChange(fresh, segmentSeq, source);
        return true;
    }

    /** Card + voice to the parent, English text to the child. */
    public void announceGateChange(JourneyEntity journey, int seq, String source) {
        JourneyFacts facts = messenger.facts(journey);
        JourneyFacts.Flight f = facts.flights().stream().filter(x -> x.seq() == seq).findFirst().orElse(facts.firstFlight());
        if (f == null) return;
        String parent = parentNumber(journey);
        if (parent != null) {
            messenger.sendGateCard(parent, facts, seq);
            messenger.speak(parent, TeluguPhrases.gateChanged(f), null, facts);
        }
        messenger.tellChild(journey, "Gate update for flight " + f.flight() + ": " + (f.gate() == null ? "gate cleared" : "gate " + f.gate())
                + " (source: " + source + "). " + (parent != null ? "She has the new card and a voice note." : "Traveler not registered yet."));
        eventLog.record(journey.getId(), EventLog.ACTOR_SYSTEM, "gate_change_announced", Map.of("seq", seq, "parent_notified", parent != null));
    }

    // ---- read side -------------------------------------------------------------------------------------------

    public List<StageEntity> stages(UUID journeyId) {
        return stages.findByJourneyIdOrderBySeq(journeyId);
    }

    public Optional<StageEntity> current(JourneyEntity j) {
        return j.getCurrentStageId() == null ? Optional.empty() : stages.findById(j.getCurrentStageId());
    }

    /** English plan listing for the child's phone. */
    public String describePlan(JourneyEntity j) {
        List<StageEntity> all = stages(j.getId());
        if (all.isEmpty()) return "No plan yet — the plan is built when the itinerary is confirmed and the traveler is registered.";
        StringBuilder sb = new StringBuilder("Plan (" + (j.getConsentAt() == null ? "not started — waiting for her 'start my trip'" : "started") + "):");
        for (StageEntity s : all) {
            String mark = switch (s.getStatus()) {
                case StageEntity.SENT -> "▶";
                case StageEntity.ACKED -> "✓";
                case StageEntity.SKIPPED -> "–";
                case HELD -> "⏸";
                default -> "·";
            };
            sb.append('\n').append(mark).append(' ').append(s.getSeq()).append(' ').append(s.getType());
            if (s.getScheduledAt() != null) sb.append("  ").append(local(s.getScheduledAt(), s.getAirport()));
        }
        sb.append("\nClock: ").append(clock.isTravelling() ? "simulated, " + DAY_HHMM.format(clock.now().atZoneSameInstant(ZoneId.of("UTC"))) + " UTC" : "real time");
        sb.append("\nI do not know her physical location — only the last stage she confirmed.");
        return sb.toString();
    }

    public ConcourseClock clock() {
        return clock;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private int currentIndex(JourneyEntity j, List<StageEntity> all) {
        if (j.getCurrentStageId() != null) {
            for (StageEntity s : all) if (s.getId().equals(j.getCurrentStageId())) return s.getSeq() - 1;
        }
        return 0; // PRE_TRIP
    }

    private boolean outsideWindow(JourneyEntity j) {
        OffsetDateTime last = j.getLastParentMessageAt();
        return last == null || Duration.between(last, OffsetDateTime.now()).compareTo(WINDOW) > 0;
    }

    private void finishJob(StageEntity stage, String status, String error) {
        for (OutboundJobEntity job : jobs.findByJourneyIdOrderByRunAt(stage.getJourneyId())) {
            if (stage.getId().equals(job.getStageId()) && OutboundJobEntity.PENDING.equals(job.getStatus())) {
                job.setStatus(status);
                job.setAttempts(job.getAttempts() + 1);
                job.setLastError(error);
                jobs.save(job);
            }
        }
    }

    private String parentNumber(JourneyEntity j) {
        return messenger.parentOf(j).map(ContactEntity::getE164).orElse(null);
    }

    /** "28 Nov 00:45 IST"-style local time at an airport. */
    String local(OffsetDateTime t, String iata) {
        if (t == null) return "unscheduled";
        ZoneId zone = iata == null ? ZoneId.of("UTC") : airports.find(iata).map(Airports.Airport::zone).orElse(t.getOffset());
        return DAY_HHMM.format(t.atZoneSameInstant(zone)) + " " + (iata == null ? "UTC" : iata + " time");
    }
}
