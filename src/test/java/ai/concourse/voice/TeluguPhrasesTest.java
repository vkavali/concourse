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
        assertEquals("మీ ఎయిర్ ఇండియా విమానం గేట్ B12 నుంచి బయలుదేరుతుంది. మళ్ళీ చెబుతున్నాను: గేట్ B12. కార్డు చూడండి. B12 అని ఉన్న బోర్డులు చూస్తూ వెళ్ళండి.", text);
        assertEquals("మీ ఎయిర్ ఇండియా విమానం గేట్, బీ పన్నెండు, నుంచి బయలుదేరుతుంది. మళ్ళీ చెబుతున్నాను: గేట్, బీ పన్నెండు. కార్డు చూడండి. బీ పన్నెండు, అని ఉన్న బోర్డులు చూస్తూ వెళ్ళండి.",
                TeluguSpeech.spokenForm(text));
    }

    @Test
    void unknownGateSaysSoAndNamesTheTerminal() {
        String text = TeluguPhrases.gateAnswer(facts(new JourneyFacts.Flight(1, "Air India", "AI173", "DEL", "SFO", "2026-06-06 00:05", null, "3", null, false)));
        assertEquals("మీ ఎయిర్ ఇండియా విమానం గేట్ ఇంకా చెప్పలేదు. టెర్మినల్ 3. పెద్ద స్క్రీన్ మీద చూడండి, లేదా ఈ కార్డు సిబ్బందికి చూపించండి. గేట్ తెలియగానే నేను చెబుతాను.", text);
        assertTrue(TeluguSpeech.spokenForm(text).contains("టెర్మినల్ మూడు."));
    }

    @Test
    void unknownAirlineFallsBackToTheSpelledCode() {
        JourneyFacts.Flight f = new JourneyFacts.Flight(1, "Mystery Air", "ZZ12", "DEL", "SFO", "2026-06-06 00:05", null, null, "C7", true);
        assertTrue(TeluguPhrases.gateAnswer(facts(f)).startsWith("మీ విమానం ZZ12 గేట్ C7 నుంచి"));
        assertTrue(TeluguPhrases.gateChanged(f).startsWith("జాగ్రత్త: మీ విమానం ZZ12 గేట్ మారింది. కొత్త గేట్ C7."));
        assertTrue(TeluguSpeech.spokenForm(TeluguPhrases.gateChanged(f)).contains("కొత్త గేట్, సీ ఏడు."));
    }
}
