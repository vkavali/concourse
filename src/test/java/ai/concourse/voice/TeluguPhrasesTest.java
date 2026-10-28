package ai.concourse.voice;

import ai.concourse.journey.JourneyFacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TeluguPhrasesTest {
    private static JourneyFacts facts(JourneyFacts.Flight f) {
        return new JourneyFacts("PRE_TRIP", "Lakshmi", List.of(f), "x", null, null);
    }

    @Test
    void gateAnswerNamesTheAirlineAndRepeatsTheGate() {
        String text = TeluguPhrases.gateAnswer(facts(new JourneyFacts.Flight(1, "Air India", "AI173", "DEL", "SFO", "2026-06-06 00:05", null, "3", "B12", true)));
        assertEquals("మీ ఎయిర్ ఇండియా విమానం గేట్ B12 నుండి బయలుదేరుతుంది. మళ్ళీ చెబుతున్నాను: గేట్ B12. గేట్ B12 అని రాసిన బోర్డులను అనుసరించండి. కార్డు చూడండి.", text);
        assertEquals("మీ ఎయిర్ ఇండియా విమానం గేట్, బీ పన్నెండు, నుండి బయలుదేరుతుంది. మళ్ళీ చెబుతున్నాను: గేట్, బీ పన్నెండు. గేట్, బీ పన్నెండు, అని రాసిన బోర్డులను అనుసరించండి. కార్డు చూడండి.",
                TeluguSpeech.spokenForm(text));
    }

    @Test
    void unknownGateSaysSoAndNamesTheTerminal() {
        String text = TeluguPhrases.gateAnswer(facts(new JourneyFacts.Flight(1, "Air India", "AI173", "DEL", "SFO", "2026-06-06 00:05", null, "3", null, false)));
        assertEquals("మీ ఎయిర్ ఇండియా విమానం గేట్ ఇంకా ప్రకటించలేదు. టెర్మినల్ 3. దయచేసి పెద్ద స్క్రీన్ చూడండి, లేదా ఈ కార్డును సిబ్బందికి చూపించండి.", text);
        assertTrue(TeluguSpeech.spokenForm(text).contains("టెర్మినల్ మూడు."));
    }

    @Test
    void unknownAirlineFallsBackToTheSpelledCode() {
        JourneyFacts.Flight f = new JourneyFacts.Flight(1, "Mystery Air", "ZZ12", "DEL", "SFO", "2026-06-06 00:05", null, null, "C7", true);
        assertTrue(TeluguPhrases.gateAnswer(facts(f)).startsWith("మీ విమానం ZZ12 గేట్ C7 నుండి"));
        assertTrue(TeluguPhrases.gateChanged(f).startsWith("గమనించండి: మీ విమానం ZZ12 గేట్ మారింది. కొత్త గేట్ C7."));
        assertTrue(TeluguSpeech.spokenForm(TeluguPhrases.gateChanged(f)).contains("కొత్త గేట్, సీ ఏడు."));
    }
}
