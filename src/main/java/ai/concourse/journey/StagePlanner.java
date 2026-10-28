package ai.concourse.journey;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static ai.concourse.journey.StageType.*;

/**
 * Flight times → when each stage is pushed (docs/PLAN.md timeline). Pure. Times come from verified segments only;
 * a missing arrival time means the post-landing stages for that airport are not scheduled (reported, never guessed).
 */
public final class StagePlanner {
    private StagePlanner() {}

    public record Leg(int seq, String origin, String destination, OffsetDateTime departure, OffsetDateTime arrival) {}

    public record PlannedStage(int seq, StageType type, String airport, Integer segmentSeq, OffsetDateTime scheduledAt) {}

    public record Plan(List<PlannedStage> stages, List<String> warnings) {}

    public static Plan plan(List<Leg> legs) {
        if (legs == null || legs.isEmpty()) throw new IllegalArgumentException("no flights");
        List<PlannedStage> out = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Leg first = legs.get(0);
        out.add(new PlannedStage(0, PRE_TRIP, first.origin(), 1, null)); // sent at onboarding, not by the clock
        OffsetDateTime d = first.departure();
        add(out, CHECK_IN, first.origin(), 1, d.minusMinutes(180));
        add(out, SECURITY, first.origin(), 1, d.minusMinutes(120));
        add(out, WAITING_AT_ORIGIN, first.origin(), 1, d.minusMinutes(60));
        add(out, BOARDING_ORIGIN, first.origin(), 1, d.minusMinutes(40));
        add(out, IN_FLIGHT, first.origin(), 1, d);

        for (int i = 1; i < legs.size(); i++) {
            Leg prev = legs.get(i - 1), leg = legs.get(i);
            OffsetDateTime dep = leg.departure();
            OffsetDateTime landed = prev.arrival();
            if (landed == null) {
                warnings.add("Flight " + prev.seq() + " has no arrival time; transfer steps at " + leg.origin() + " are timed from the next departure.");
                landed = dep.minusMinutes(90);
            }
            add(out, TRANSFER_ARRIVAL, leg.origin(), leg.seq(), landed.plusMinutes(5));
            add(out, TRANSFER_FIND_GATE, leg.origin(), leg.seq(), landed.plusMinutes(15));
            add(out, TRANSFER_WAITING, leg.origin(), leg.seq(), dep.minusMinutes(60));
            add(out, TRANSFER_BOARDING, leg.origin(), leg.seq(), dep.minusMinutes(40));
            add(out, IN_FLIGHT, leg.origin(), leg.seq(), dep);
        }

        Leg last = legs.get(legs.size() - 1);
        if (last.arrival() == null) {
            warnings.add("Flight " + last.seq() + " has no arrival time; the steps after landing at " + last.destination()
                    + " are not scheduled. Fix the itinerary to add it.");
        } else {
            OffsetDateTime a = last.arrival();
            add(out, US_ARRIVAL, last.destination(), last.seq(), a.plusMinutes(10));
            add(out, IMMIGRATION, last.destination(), last.seq(), a.plusMinutes(20));
            add(out, BAGGAGE, last.destination(), last.seq(), a.plusMinutes(45));
            add(out, EXIT, last.destination(), last.seq(), a.plusMinutes(65));
            add(out, FAMILY_HANDOFF, last.destination(), last.seq(), a.plusMinutes(75));
        }
        out.add(new PlannedStage(0, COMPLETE, last.destination(), last.seq(), null)); // hers to declare

        // Keep pushes strictly in plan order even on a tight connection: never let a later stage fire before an earlier one.
        OffsetDateTime floor = null;
        List<PlannedStage> ordered = new ArrayList<>();
        int seq = 1;
        for (PlannedStage s : out) {
            OffsetDateTime at = s.scheduledAt();
            if (at != null && floor != null && !at.isAfter(floor)) at = floor.plus(Duration.ofMinutes(1));
            if (at != null) floor = at;
            ordered.add(new PlannedStage(seq++, s.type(), s.airport(), s.segmentSeq(), at));
        }
        return new Plan(List.copyOf(ordered), List.copyOf(warnings));
    }

    private static void add(List<PlannedStage> out, StageType type, String airport, int segmentSeq, OffsetDateTime at) {
        out.add(new PlannedStage(0, type, airport, segmentSeq, at));
    }
}
