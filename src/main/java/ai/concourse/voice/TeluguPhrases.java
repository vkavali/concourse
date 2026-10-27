package ai.concourse.voice;

import ai.concourse.journey.JourneyFacts;

/**
 * Fixed Telugu sentences composed from verified facts only (no model in the loop). Used for button replies,
 * clarifications and the safety fallback. Spoken register, short, one idea per sentence.
 */
public final class TeluguPhrases {
    private TeluguPhrases() {}

    /** "Your journey is not set up yet. Please ask your son." */
    public static final String NOT_SET_UP = "మీ ప్రయాణం ఇంకా సిద్ధం కాలేదు. దయచేసి మీ కొడుకును అడగండి.";

    /** "Sorry, I could not hear that properly. Please say it again, or press a button below." */
    public static final String CLARIFY = "క్షమించండి, నాకు సరిగ్గా వినిపించలేదు. దయచేసి మళ్ళీ చెప్పండి, లేదా కింద ఉన్న బటన్ నొక్కండి.";

    /** "I have told your son. He will call you soon." */
    public static final String SON_TOLD = "నేను మీ కొడుకుకు చెప్పాను. అతను త్వరలో మీకు కాల్ చేస్తాడు.";

    /** "Show this card to a staff member. They will help you." */
    public static final String SHOW_CARD = "ఈ కార్డును సిబ్బందికి చూపించండి. వారు మీకు సహాయం చేస్తారు.";

    // Button titles (WhatsApp limit: 20 characters)
    public static final String BTN_GATE = "గేట్ ఎక్కడ?";      // Where is the gate?
    public static final String BTN_HELP = "సహాయం కావాలి";     // I need help
    public static final String BTN_SON = "కొడుకుకు చెప్పు";    // Tell my son

    /** "Attention: your gate has changed. New gate: X. Again: gate X. Follow the boards that say gate X. See the card." */
    public static String gateChanged(JourneyFacts.Flight f) {
        if (f.gate() == null) {
            return "గమనించండి: మీ విమానం " + f.flight() + " గేట్ మారింది. కొత్త గేట్ ఇంకా ప్రకటించలేదు. దయచేసి పెద్ద స్క్రీన్ చూడండి, లేదా ఈ కార్డును సిబ్బందికి చూపించండి.";
        }
        return "గమనించండి: మీ విమానం " + f.flight() + " గేట్ మారింది. కొత్త గేట్ " + f.gate() + ". "
                + "మళ్ళీ చెబుతున్నాను: గేట్ " + f.gate() + ". గేట్ " + f.gate() + " అని రాసిన బోర్డులను అనుసరించండి. కార్డు చూడండి.";
    }

    /** Gate answer from verified facts: known gate, or "not announced yet, look at the big screen / show the card". */
    public static String gateAnswer(JourneyFacts facts) {
        JourneyFacts.Flight f = facts == null ? null : facts.firstFlight();
        if (f == null) return NOT_SET_UP;
        if (f.gate() != null) {
            // The gate is said twice, slowly, the way staff repeat it; the card shows it in large print.
            return "మీ విమానం " + f.flight() + " గేట్ " + f.gate() + " నుండి బయలుదేరుతుంది. "
                    + "మళ్ళీ చెబుతున్నాను: గేట్ " + f.gate() + ". "
                    + "గేట్ " + f.gate() + " అని రాసిన బోర్డులను అనుసరించండి. కార్డు చూడండి.";
        }
        String terminal = f.terminal() == null ? "" : " టెర్మినల్ " + f.terminal() + ".";
        return "మీ విమానం " + f.flight() + " గేట్ ఇంకా ప్రకటించలేదు." + terminal
                + " దయచేసి పెద్ద స్క్రీన్ చూడండి, లేదా ఈ కార్డును సిబ్బందికి చూపించండి.";
    }
}
