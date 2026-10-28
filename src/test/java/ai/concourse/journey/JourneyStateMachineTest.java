package ai.concourse.journey;

import ai.concourse.journey.JourneyStateMachine.Trigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.concourse.journey.StageType.*;
import static org.junit.jupiter.api.Assertions.*;

class JourneyStateMachineTest {
    /** The transition table of docs/ARCHITECTURE.md: from → allowed next stages. */
    private static final Map<StageType, Set<StageType>> TABLE = Map.ofEntries(
            Map.entry(PRE_TRIP, Set.of(CHECK_IN)), Map.entry(CHECK_IN, Set.of(SECURITY)), Map.entry(SECURITY, Set.of(WAITING_AT_ORIGIN)),
            Map.entry(WAITING_AT_ORIGIN, Set.of(BOARDING_ORIGIN)), Map.entry(BOARDING_ORIGIN, Set.of(IN_FLIGHT)),
            Map.entry(IN_FLIGHT, Set.of(TRANSFER_ARRIVAL, US_ARRIVAL)), Map.entry(TRANSFER_ARRIVAL, Set.of(TRANSFER_FIND_GATE)),
            Map.entry(TRANSFER_FIND_GATE, Set.of(TRANSFER_WAITING)), Map.entry(TRANSFER_WAITING, Set.of(TRANSFER_BOARDING)),
            Map.entry(TRANSFER_BOARDING, Set.of(IN_FLIGHT)), Map.entry(US_ARRIVAL, Set.of(IMMIGRATION)), Map.entry(IMMIGRATION, Set.of(BAGGAGE)),
            Map.entry(BAGGAGE, Set.of(EXIT)), Map.entry(EXIT, Set.of(FAMILY_HANDOFF)), Map.entry(FAMILY_HANDOFF, Set.of(COMPLETE)),
            Map.entry(COMPLETE, Set.of()));

    @Test
    void everyPairOfStagesAgainstTheTable() {
        for (StageType from : StageType.values()) {
            assertEquals(TABLE.get(from), JourneyStateMachine.successors(from), "successors of " + from);
            for (StageType to : StageType.values()) {
                boolean listed = TABLE.get(from).contains(to);
                boolean time = JourneyStateMachine.canAdvance(from, to, Trigger.TIME, true);
                boolean ack = JourneyStateMachine.canAdvance(from, to, Trigger.PARENT_ACK, true);
                boolean ops = JourneyStateMachine.canAdvance(from, to, Trigger.OPS_EVENT, true);
                assertEquals(listed && to != COMPLETE, time, "TIME " + from + "→" + to);
                assertEquals(listed && (from == EXIT || from == FAMILY_HANDOFF), ack, "PARENT_ACK " + from + "→" + to);
                assertFalse(ops, "OPS_EVENT never advances: " + from + "→" + to);
                assertFalse(JourneyStateMachine.canAdvance(from, to, Trigger.TIME, false), "nothing moves without consent: " + from + "→" + to);
                assertFalse(JourneyStateMachine.canAdvance(from, to, Trigger.PARENT_ACK, false));
            }
        }
    }

    @Test
    void plansHaveOneInFlightPerLeg() {
        List<StageType> one = JourneyStateMachine.plan(1);
        assertEquals(List.of(PRE_TRIP, CHECK_IN, SECURITY, WAITING_AT_ORIGIN, BOARDING_ORIGIN, IN_FLIGHT,
                US_ARRIVAL, IMMIGRATION, BAGGAGE, EXIT, FAMILY_HANDOFF, COMPLETE), one);
        List<StageType> two = JourneyStateMachine.plan(2);
        assertEquals(17, two.size());
        assertEquals(2, two.stream().filter(s -> s == IN_FLIGHT).count());
        assertEquals(List.of(TRANSFER_ARRIVAL, TRANSFER_FIND_GATE, TRANSFER_WAITING, TRANSFER_BOARDING, IN_FLIGHT), two.subList(6, 11));
        assertThrows(IllegalArgumentException.class, () -> JourneyStateMachine.plan(0));
    }

    @Test
    void catchingUpSkipsThroughValidHopsOnly() {
        List<StageType> plan = JourneyStateMachine.plan(2);
        // PRE_TRIP (0) straight to TRANSFER_FIND_GATE (7): skips 6 stages, all valid TIME hops
        List<StageType> skipped = JourneyStateMachine.path(plan, 0, 7, Trigger.TIME, true);
        assertEquals(List.of(CHECK_IN, SECURITY, WAITING_AT_ORIGIN, BOARDING_ORIGIN, IN_FLIGHT, TRANSFER_ARRIVAL), skipped);
        assertNull(JourneyStateMachine.path(plan, 0, 7, Trigger.TIME, false), "no consent, no movement");
        assertNull(JourneyStateMachine.path(plan, 7, 0, Trigger.TIME, true), "never backwards");
        assertNull(JourneyStateMachine.path(plan, 0, 16, Trigger.TIME, true), "time never completes a journey");
        assertEquals(List.of(), JourneyStateMachine.path(plan, 15, 16, Trigger.PARENT_ACK, true), "'I'm here' completes it");
        assertEquals(List.of(FAMILY_HANDOFF), JourneyStateMachine.path(plan, 14, 16, Trigger.PARENT_ACK, true), "'I'm here' from EXIT");
        assertNull(JourneyStateMachine.path(plan, 2, 4, Trigger.PARENT_ACK, true), "an ack at security does not jump stages");
    }
}
