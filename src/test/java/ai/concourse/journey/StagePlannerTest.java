package ai.concourse.journey;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static ai.concourse.journey.StageType.*;
import static org.junit.jupiter.api.Assertions.*;

class StagePlannerTest {
    private static final OffsetDateTime DEP1 = OffsetDateTime.of(2026, 11, 28, 3, 45, 0, 0, ZoneOffset.ofHoursMinutes(5, 30)); // HYD
    private static final OffsetDateTime ARR1 = OffsetDateTime.of(2026, 11, 28, 6, 5, 0, 0, ZoneOffset.ofHours(3));             // DOH
    private static final OffsetDateTime DEP2 = OffsetDateTime.of(2026, 11, 28, 8, 40, 0, 0, ZoneOffset.ofHours(3));
    private static final OffsetDateTime ARR2 = OffsetDateTime.of(2026, 11, 28, 15, 50, 0, 0, ZoneOffset.ofHours(-6));          // DFW

    @Test
    void twoLegJourneyFollowsTheTimeline() {
        StagePlanner.Plan plan = StagePlanner.plan(List.of(
                new StagePlanner.Leg(1, "HYD", "DOH", DEP1, ARR1),
                new StagePlanner.Leg(2, "DOH", "DFW", DEP2, ARR2)));
        assertTrue(plan.warnings().isEmpty(), plan.warnings().toString());
        List<StagePlanner.PlannedStage> s = plan.stages();
        assertEquals(JourneyStateMachine.plan(2), s.stream().map(StagePlanner.PlannedStage::type).toList());
        assertEquals(PRE_TRIP, s.get(0).type()); assertNull(s.get(0).scheduledAt());
        assertEquals(DEP1.minusMinutes(180), s.get(1).scheduledAt()); assertEquals("HYD", s.get(1).airport());
        assertEquals(DEP1.minusMinutes(40), at(s, BOARDING_ORIGIN));
        assertEquals(DEP1, at(s, IN_FLIGHT));
        assertEquals(ARR1.plusMinutes(5), at(s, TRANSFER_ARRIVAL)); assertEquals("DOH", find(s, TRANSFER_ARRIVAL).airport());
        assertEquals(2, find(s, TRANSFER_FIND_GATE).segmentSeq());
        assertEquals(DEP2.minusMinutes(60), at(s, TRANSFER_WAITING));
        assertEquals(ARR2.plusMinutes(10), at(s, US_ARRIVAL)); assertEquals("DFW", find(s, US_ARRIVAL).airport());
        assertEquals(ARR2.plusMinutes(75), at(s, FAMILY_HANDOFF));
        assertNull(find(s, COMPLETE).scheduledAt());
        for (int i = 1; i < s.size(); i++) assertEquals(i + 1, s.get(i).seq());
        // strictly increasing pushes
        OffsetDateTime prev = null;
        for (StagePlanner.PlannedStage st : s) {
            if (st.scheduledAt() == null) continue;
            if (prev != null) assertTrue(st.scheduledAt().isAfter(prev), st.type() + " fires after the stage before it");
            prev = st.scheduledAt();
        }
    }

    @Test
    void tightConnectionNeverReordersStages() {
        OffsetDateTime dep2 = ARR1.plusMinutes(50); // 50-minute connection: dep−60 would be before landing
        StagePlanner.Plan plan = StagePlanner.plan(List.of(
                new StagePlanner.Leg(1, "HYD", "DOH", DEP1, ARR1),
                new StagePlanner.Leg(2, "DOH", "DFW", dep2, ARR2)));
        List<StagePlanner.PlannedStage> s = plan.stages();
        assertTrue(at(s, TRANSFER_WAITING).isAfter(at(s, TRANSFER_FIND_GATE)));
        assertTrue(at(s, TRANSFER_BOARDING).isAfter(at(s, TRANSFER_WAITING)));
        assertTrue(at(s, IN_FLIGHT, 2).isAfter(at(s, TRANSFER_BOARDING)));
    }

    @Test
    void missingArrivalMeansNoGuessedLandingStages() {
        StagePlanner.Plan plan = StagePlanner.plan(List.of(new StagePlanner.Leg(1, "DEL", "SFO", DEP1, null)));
        assertEquals(1, plan.warnings().size());
        assertTrue(plan.warnings().get(0).contains("not scheduled"));
        List<StageType> types = plan.stages().stream().map(StagePlanner.PlannedStage::type).toList();
        assertEquals(List.of(PRE_TRIP, CHECK_IN, SECURITY, WAITING_AT_ORIGIN, BOARDING_ORIGIN, IN_FLIGHT, COMPLETE), types);
    }

    private static StagePlanner.PlannedStage find(List<StagePlanner.PlannedStage> s, StageType t) {
        return s.stream().filter(x -> x.type() == t).findFirst().orElseThrow();
    }

    private static OffsetDateTime at(List<StagePlanner.PlannedStage> s, StageType t) { return find(s, t).scheduledAt(); }

    private static OffsetDateTime at(List<StagePlanner.PlannedStage> s, StageType t, int nth) {
        return s.stream().filter(x -> x.type() == t).skip(nth - 1).findFirst().orElseThrow().scheduledAt();
    }
}
