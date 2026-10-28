package ai.concourse.journey;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static ai.concourse.journey.StageType.*;

/**
 * The transitions of docs/ARCHITECTURE.md, as a table. Pure: no clock, no database, no model. The controller asks
 * {@link #canAdvance} before every state change; anything it refuses does not happen.
 * <ul>
 *   <li>TIME (the scheduler) moves the journey forward along the plan, one stage at a time, once she has consented.
 *       IN_FLIGHT forks: to a transfer, or to the final arrival.</li>
 *   <li>PARENT_ACK moves only the hand-off: EXIT → FAMILY_HANDOFF ("I'm here") and FAMILY_HANDOFF → COMPLETE.
 *       Every other ack is evidence, not a transition — the next stage still fires at its time.</li>
 *   <li>OPS_EVENT (a verified gate/time change) never changes the stage; it rebuilds what is still planned.</li>
 * </ul>
 */
public final class JourneyStateMachine {
    private JourneyStateMachine() {}

    public enum Trigger { TIME, PARENT_ACK, OPS_EVENT }

    private static final Set<StageType> ACK_ADVANCES_FROM = EnumSet.of(EXIT, FAMILY_HANDOFF);

    /** The stage sequence for a journey with {@code legs} flights (legs ≥ 1). */
    public static List<StageType> plan(int legs) {
        if (legs < 1) throw new IllegalArgumentException("a journey has at least one flight");
        List<StageType> plan = new ArrayList<>(List.of(PRE_TRIP, CHECK_IN, SECURITY, WAITING_AT_ORIGIN, BOARDING_ORIGIN, IN_FLIGHT));
        for (int i = 1; i < legs; i++) {
            plan.addAll(List.of(TRANSFER_ARRIVAL, TRANSFER_FIND_GATE, TRANSFER_WAITING, TRANSFER_BOARDING, IN_FLIGHT));
        }
        plan.addAll(List.of(US_ARRIVAL, IMMIGRATION, BAGGAGE, EXIT, FAMILY_HANDOFF, COMPLETE));
        return plan;
    }

    /** The stages that may directly follow {@code from}. */
    public static Set<StageType> successors(StageType from) {
        return switch (from) {
            case PRE_TRIP -> EnumSet.of(CHECK_IN);
            case CHECK_IN -> EnumSet.of(SECURITY);
            case SECURITY -> EnumSet.of(WAITING_AT_ORIGIN);
            case WAITING_AT_ORIGIN -> EnumSet.of(BOARDING_ORIGIN);
            case BOARDING_ORIGIN -> EnumSet.of(IN_FLIGHT);
            case IN_FLIGHT -> EnumSet.of(TRANSFER_ARRIVAL, US_ARRIVAL);
            case TRANSFER_ARRIVAL -> EnumSet.of(TRANSFER_FIND_GATE);
            case TRANSFER_FIND_GATE -> EnumSet.of(TRANSFER_WAITING);
            case TRANSFER_WAITING -> EnumSet.of(TRANSFER_BOARDING);
            case TRANSFER_BOARDING -> EnumSet.of(IN_FLIGHT);
            case US_ARRIVAL -> EnumSet.of(IMMIGRATION);
            case IMMIGRATION -> EnumSet.of(BAGGAGE);
            case BAGGAGE -> EnumSet.of(EXIT);
            case EXIT -> EnumSet.of(FAMILY_HANDOFF);
            case FAMILY_HANDOFF -> EnumSet.of(COMPLETE);
            case COMPLETE -> EnumSet.noneOf(StageType.class);
        };
    }

    /** One hop. */
    public static boolean canAdvance(StageType from, StageType to, Trigger trigger, boolean consented) {
        if (!consented || trigger == Trigger.OPS_EVENT || !successors(from).contains(to)) return false;
        return switch (trigger) {
            case TIME -> to != COMPLETE;                       // COMPLETE is hers to declare
            case PARENT_ACK -> ACK_ADVANCES_FROM.contains(from);
            case OPS_EVENT -> false;
        };
    }

    /**
     * Several hops along a concrete plan (e.g. the scheduler catching up after a late consent), by position in the
     * plan (IN_FLIGHT recurs, so names are ambiguous): every hop must be valid for {@code trigger}, so an ack can only
     * travel the hand-off path and time can never complete a journey. Returns the stages skipped, or null if invalid.
     */
    public static List<StageType> path(List<StageType> plan, int fromIndex, int toIndex, Trigger trigger, boolean consented) {
        if (fromIndex < 0 || toIndex >= plan.size() || toIndex <= fromIndex) return null;
        List<StageType> skipped = new ArrayList<>();
        StageType cur = plan.get(fromIndex);
        for (int k = fromIndex + 1; k <= toIndex; k++) {
            StageType next = plan.get(k);
            if (!canAdvance(cur, next, trigger, consented)) return null;
            if (k < toIndex) skipped.add(next);
            cur = next;
        }
        return skipped;
    }
}
