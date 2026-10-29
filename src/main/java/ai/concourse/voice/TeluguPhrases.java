package ai.concourse.voice;

import ai.concourse.journey.JourneyFacts;

/**
 * Fixed Telugu sentences composed from verified facts only (no model in the loop). Used for button replies,
 * clarifications and the safety fallback. Spoken register, short, one idea per sentence.
 */
public final class TeluguPhrases {
    private TeluguPhrases() {}

    /** "Your journey is not set up yet. Please ask your son." */
    public static final String NOT_SET_UP = "మీ ప్రయాణం వివరాలు నా దగ్గర ఇంకా లేవు. మీ అబ్బాయిని అడగండి.";

    /** "Sorry, I could not hear that properly. Please say it again, or press a button below." */
    public static final String CLARIFY = "క్షమించండి, సరిగ్గా వినపడలేదు. మళ్ళీ ఒకసారి చెప్పండి, లేదా కింద బటన్ నొక్కండి.";

    /** "I have told your son. He will call you soon." */
    public static final String SON_TOLD = "మీ అబ్బాయికి చెప్పాను. ఆయన మీకు ఫోన్ చేస్తారు.";

    /** "Show this card to a staff member. They will help you." */
    public static final String SHOW_CARD = "ఈ కార్డు అక్కడి సిబ్బందికి చూపించండి. వాళ్ళు సహాయం చేస్తారు.";

    // Button titles (WhatsApp limit: 20 characters)
    public static final String BTN_GATE = "గేట్ ఎక్కడ?";      // Where is the gate?
    public static final String BTN_HELP = "సహాయం కావాలి";     // I need help
    public static final String BTN_SON = "అబ్బాయికి చెప్పు";    // Tell my son

    /** "Your Air India flight" when the airline is known; otherwise "your flight AI173" (the voice spells the code). */
    public static String yourFlight(JourneyFacts.Flight f) {
        return Airlines.teluguName(f.airline(), f.flight())
                .map(name -> "మీ " + name + " విమానం")
                .orElse("మీ విమానం " + f.flight());
    }

    /** "Attention: your flight's gate has changed. New gate: X. Again: gate X. Follow the boards that say gate X. See the card." */
    public static String gateChanged(JourneyFacts.Flight f) {
        if (f.gate() == null) {
            return "జాగ్రత్త: " + yourFlight(f) + " గేట్ మారింది. కొత్త గేట్ ఇంకా చెప్పలేదు. పెద్ద స్క్రీన్ మీద చూడండి, లేదా ఈ కార్డు సిబ్బందికి చూపించండి. గేట్ తెలియగానే నేను చెబుతాను.";
        }
        return "జాగ్రత్త: " + yourFlight(f) + " గేట్ మారింది. కొత్త గేట్ " + f.gate() + ". "
                + "మళ్ళీ చెబుతున్నాను: గేట్ " + f.gate() + ". కార్డు చూడండి. " + f.gate() + " అని ఉన్న బోర్డులు చూస్తూ వెళ్ళండి.";
    }

    /**
     * The gate answer, from verified facts only — a fixed template, never model prose: "Your Air India flight leaves
     * from gate B12. Again: gate B12. Follow the boards that say gate B12. See the card." Or, with no gate yet:
     * "not announced yet; terminal 3; look at the big screen or show the card to staff".
     */
    public static String gateAnswer(JourneyFacts facts) {
        JourneyFacts.Flight f = facts == null ? null : facts.firstFlight();
        if (f == null) return NOT_SET_UP;
        if (f.gate() != null) {
            // The gate is said twice, slowly, the way staff repeat it; the card shows it in large print.
            return yourFlight(f) + " గేట్ " + f.gate() + " నుంచి బయలుదేరుతుంది. "
                    + "మళ్ళీ చెబుతున్నాను: గేట్ " + f.gate() + ". "
                    + "కార్డు చూడండి. " + f.gate() + " అని ఉన్న బోర్డులు చూస్తూ వెళ్ళండి.";
        }
        String terminal = f.terminal() == null ? "" : " టెర్మినల్ " + f.terminal() + ".";
        return yourFlight(f) + " గేట్ ఇంకా చెప్పలేదు." + terminal
                + " పెద్ద స్క్రీన్ మీద చూడండి, లేదా ఈ కార్డు సిబ్బందికి చూపించండి. గేట్ తెలియగానే నేను చెబుతాను.";
    }
}
