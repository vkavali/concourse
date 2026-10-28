package ai.concourse.journey;

/** The states of a journey, in the order of docs/ARCHITECTURE.md. IN_FLIGHT recurs once per leg. */
public enum StageType {
    PRE_TRIP, CHECK_IN, SECURITY, WAITING_AT_ORIGIN, BOARDING_ORIGIN, IN_FLIGHT,
    TRANSFER_ARRIVAL, TRANSFER_FIND_GATE, TRANSFER_WAITING, TRANSFER_BOARDING,
    US_ARRIVAL, IMMIGRATION, BAGGAGE, EXIT, FAMILY_HANDOFF, COMPLETE;

    /** Stages whose push carries the gate card. */
    public boolean showsGateCard() {
        return this == WAITING_AT_ORIGIN || this == BOARDING_ORIGIN || this == TRANSFER_FIND_GATE
                || this == TRANSFER_WAITING || this == TRANSFER_BOARDING;
    }

    /** Stages whose push offers a "done" acknowledgement. */
    public boolean asksForAck() {
        return this != PRE_TRIP && this != IN_FLIGHT && this != COMPLETE;
    }
}
