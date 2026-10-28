package ai.concourse.voice;

import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.StageType;
import ai.concourse.whatsapp.WhatsAppClient;

import java.util.List;
import java.util.Map;

/**
 * What the parent hears at each stage: short Telugu sentences built from verified facts only. No model in the loop.
 * Never "you are at X" (time, not location, triggered the push); never a walking direction; sign words are given
 * in English because that is what the boards show. Every text here is checked by SafetyPolicy in tests.
 */
public final class StageText {
    private StageText() {}

    public static final String BTN_DONE = "p:done";
    public static final String BTN_HERE = "p:here";
    public static final String BTN_START = "p:start";

    public static final String DONE_TITLE = "అయ్యింది ✓";         // done
    public static final String HERE_TITLE = "నేను వచ్చాను";       // I'm here
    public static final String START_TITLE = "నా ప్రయాణం మొదలు";  // start my trip

    /** Telugu city names for the airports a traveler from Hyderabad is likely to pass through; the IATA code otherwise. */
    private static final Map<String, String> CITY_TE = Map.ofEntries(
            Map.entry("HYD", "హైదరాబాద్"), Map.entry("DOH", "దోహా"), Map.entry("DFW", "డల్లాస్"), Map.entry("DXB", "దుబాయ్"),
            Map.entry("AUH", "అబుదాబి"), Map.entry("FRA", "ఫ్రాంక్‌ఫర్ట్"), Map.entry("LHR", "లండన్"), Map.entry("JFK", "న్యూయార్క్"),
            Map.entry("EWR", "న్యూవార్క్"), Map.entry("SFO", "శాన్ ఫ్రాన్సిస్కో"), Map.entry("DEL", "ఢిల్లీ"), Map.entry("BOM", "ముంబై"),
            Map.entry("BLR", "బెంగళూరు"), Map.entry("MAA", "చెన్నై"), Map.entry("IAH", "హ్యూస్టన్"), Map.entry("ORD", "చికాగో"),
            Map.entry("SEA", "సియాటిల్"), Map.entry("LAX", "లాస్ ఏంజెలెస్"), Map.entry("IAD", "వాషింగ్టన్"), Map.entry("ATL", "అట్లాంటా"),
            Map.entry("BOS", "బోస్టన్"), Map.entry("CDG", "పారిస్"), Map.entry("AMS", "ఆమ్‌స్టర్‌డామ్"), Map.entry("SIN", "సింగపూర్"),
            Map.entry("MUC", "మ్యూనిక్"), Map.entry("ZRH", "జ్యూరిచ్"), Map.entry("IST", "ఇస్తాంబుల్"));

    public static String city(String iata) {
        if (iata == null) return "";
        return CITY_TE.getOrDefault(iata, iata);
    }

    /** The intro, sent once she is registered and the itinerary is confirmed. Ends with the consent request. */
    public static String intro(JourneyFacts facts) {
        JourneyFacts.Flight f = facts.firstFlight();
        JourneyFacts.Flight last = facts.flights().isEmpty() ? null : facts.flights().get(facts.flights().size() - 1);
        String name = facts.travelerFirstName() == null || facts.travelerFirstName().isBlank() ? "" : facts.travelerFirstName() + " గారు, ";
        String flight = f == null ? "మీ విమానం" : TeluguPhrases.yourFlight(f);
        String route = f == null ? "" : " " + city(f.from()) + " నుండి " + city(last.to()) + " వరకు";
        return "నమస్కారం. " + name + "నేను కాంకోర్స్. మీ కొడుకు మీ ప్రయాణం కోసం నన్ను ఏర్పాటు చేశారు. "
                + flight + route + ". "
                + "ప్రతి అడుగులో నేను వాయిస్ నోట్ పంపుతాను. మీరు మాట్లాడి పంపవచ్చు, లేదా బోర్డు ఫోటో తీసి పంపవచ్చు. "
                + "సిద్ధంగా ఉంటే, 'నా ప్రయాణం మొదలు' అని చెప్పి వాయిస్ నోట్ పంపండి, లేదా కింది బటన్ నొక్కండి.";
    }

    /** "Thank you. Your trip has started. I will tell you when it is time for each step." */
    public static final String CONSENT_THANKS = "ధన్యవాదాలు. మీ ప్రయాణం మొదలైంది. ప్రతి అడుగుకు సమయం వచ్చినప్పుడు నేను చెబుతాను. ఫోన్ దగ్గరే ఉంచుకోండి.";

    /** "Noted. I will tell you the next step when it is time." */
    public static final String ACK_THANKS = "సరే. తర్వాతి అడుగు సమయం వచ్చినప్పుడు చెబుతాను.";

    /** "Not started yet: say 'start my trip' or press the button." */
    public static final String NOT_STARTED = "మీ ప్రయాణం ఇంకా మొదలు కాలేదు. 'నా ప్రయాణం మొదలు' అని చెప్పి వాయిస్ నోట్ పంపండి, లేదా కింది బటన్ నొక్కండి.";

    /** The instruction for a stage, from verified facts only. */
    public static String instruction(StageType type, JourneyFacts facts, Integer segmentSeq, String airport) {
        JourneyFacts.Flight f = flight(facts, segmentSeq);
        String flight = f == null ? "మీ విమానం" : TeluguPhrases.yourFlight(f);
        String gate = f == null ? null : f.gate();
        String to = f == null ? "" : city(f.to());
        String here = city(airport);
        return switch (type) {
            case PRE_TRIP -> intro(facts);
            case CHECK_IN -> "ఇప్పుడు ఎయిర్‌పోర్ట్ లోపల " + airlineOrFlight(f) + " కౌంటర్ కి వెళ్ళండి. "
                    + "పాస్‌పోర్ట్, టికెట్ చూపించండి. పెద్ద బ్యాగ్ ఇవ్వండి. బోర్డింగ్ పాస్ తీసుకోండి. "
                    + "అయ్యాక 'అయ్యింది' బటన్ నొక్కండి.";
            case SECURITY -> "ఇప్పుడు సెక్యూరిటీ చెక్ కి వెళ్ళండి. 'Security' అని రాసిన బోర్డులను అనుసరించండి. "
                    + "చేతి బ్యాగ్, ఫోన్ ట్రేలో పెట్టండి. పాస్‌పోర్ట్, బోర్డింగ్ పాస్ చేతిలో ఉంచుకోండి. అయ్యాక 'అయ్యింది' నొక్కండి.";
            case WAITING_AT_ORIGIN, TRANSFER_WAITING -> gate == null
                    ? flight + " గేట్ ఇంకా ప్రకటించలేదు. పెద్ద స్క్రీన్ చూడండి, లేదా ఈ కార్డును సిబ్బందికి చూపించండి. గేట్ తెలిశాక నేను చెబుతాను."
                    : flight + " గేట్ " + gate + ". మళ్ళీ చెబుతున్నాను: గేట్ " + gate + ". గేట్ " + gate + " అని రాసిన బోర్డులను అనుసరించండి. కార్డు చూడండి. గేట్ దగ్గర కూర్చోండి.";
            case BOARDING_ORIGIN, TRANSFER_BOARDING -> (gate == null ? "బోర్డింగ్ త్వరలో మొదలవుతుంది. " : "గేట్ " + gate + " దగ్గర బోర్డింగ్ త్వరలో మొదలవుతుంది. ")
                    + "పాస్‌పోర్ట్, బోర్డింగ్ పాస్ చేతిలో ఉంచుకోండి. సిబ్బంది పిలిచినప్పుడు లైన్‌లో నిలబడండి.";
            case IN_FLIGHT -> flight + " బయలుదేరే సమయం. ఫోన్ ఫ్లైట్ మోడ్ లో పెట్టండి. "
                    + (to.isEmpty() ? "" : to + " లో దిగాక ఫోన్ ఆన్ చేయండి. ") + "అప్పుడు నేను మళ్ళీ చెబుతాను. మంచి ప్రయాణం.";
            case TRANSFER_ARRIVAL -> here + " లో దిగిన తర్వాత: తొందర లేదు. ఫోన్ ఆన్ చేయండి. "
                    + "'Transfer' లేదా 'Connections' అని రాసిన బోర్డులను అనుసరించండి. బయటకు వెళ్ళకండి. బ్యాగ్ తీసుకోవద్దు.";
            case TRANSFER_FIND_GATE -> gate == null
                    ? "మీ తర్వాతి విమానం " + airlineOrFlight(f) + ". గేట్ ఇంకా ప్రకటించలేదు. 'Transfer' బోర్డులను అనుసరించి పెద్ద స్క్రీన్ చూడండి, లేదా ఈ కార్డును సిబ్బందికి చూపించండి."
                    : "మీ తర్వాతి విమానం " + airlineOrFlight(f) + ". గేట్ " + gate + ". మళ్ళీ చెబుతున్నాను: గేట్ " + gate + ". 'Transfer' బోర్డులు, తర్వాత గేట్ " + gate + " బోర్డులను అనుసరించండి. కార్డు చూడండి.";
            case US_ARRIVAL -> here + " లో దిగిన తర్వాత: ఫోన్ ఆన్ చేయండి. 'Arrivals' లేదా 'Immigration' అని రాసిన బోర్డులను అనుసరించండి. "
                    + "ఆఫీసర్ ప్రశ్నలకు మీరే సమాధానం చెప్పండి. ఫోన్ అడిగితేనే చూపించండి.";
            case IMMIGRATION -> "ఇమ్మిగ్రేషన్ లైన్‌లో నిలబడండి. పాస్‌పోర్ట్ చేతిలో ఉంచుకోండి. ఆఫీసర్ అడిగినవి మీరే చెప్పండి. "
                    + "సహాయం కావాలంటే 'సహాయం కావాలి' నొక్కండి, కార్డు చూపించండి. అయ్యాక 'అయ్యింది' నొక్కండి.";
            case BAGGAGE -> "ఇప్పుడు 'Baggage Claim' అని రాసిన బోర్డులను అనుసరించండి. స్క్రీన్ మీద "
                    + (f == null ? "మీ విమానం నంబర్" : f.flight()) + " చూసి ఆ బెల్ట్ దగ్గర బ్యాగ్ తీసుకోండి. అయ్యాక 'అయ్యింది' నొక్కండి.";
            case EXIT -> "బ్యాగ్ తీసుకున్నాక 'Exit' అని రాసిన బోర్డులను అనుసరించండి. బయట మీ కొడుకు ఉంటారు. "
                    + "బయటకు వచ్చాక 'నేను వచ్చాను' నొక్కండి.";
            case FAMILY_HANDOFF -> "బయటకు వచ్చారా? 'నేను వచ్చాను' నొక్కండి. మీ కొడుకుకు నేను చెబుతాను. ఇంకా రాకపోతే 'కొడుకుకు చెప్పు' నొక్కండి.";
            case COMPLETE -> "మీ ప్రయాణం పూర్తయింది. మీ కొడుకుకు చెప్పాను. నమస్కారం.";
        };
    }

    /** One English line per stage for the child's phone. Describes the instruction, never her location. */
    public static String english(StageType type, JourneyFacts facts, Integer segmentSeq) {
        JourneyFacts.Flight f = flight(facts, segmentSeq);
        String gate = f == null || f.gate() == null ? "gate not announced yet" : "gate " + f.gate();
        String flight = f == null ? "her flight" : f.flight();
        return switch (type) {
            case PRE_TRIP -> "intro + consent request ('start my trip')";
            case CHECK_IN -> "check in at the " + (f == null || f.airline() == null ? "airline" : f.airline()) + " counter";
            case SECURITY -> "security check";
            case WAITING_AT_ORIGIN, TRANSFER_WAITING -> "go to the gate (" + gate + ") — card sent";
            case BOARDING_ORIGIN, TRANSFER_BOARDING -> "boarding soon (" + gate + ")";
            case IN_FLIGHT -> flight + " departing — phone in flight mode until landing";
            case TRANSFER_ARRIVAL -> "after landing: follow Transfer signs, do not exit";
            case TRANSFER_FIND_GATE -> "next flight " + flight + " (" + gate + ") — card sent";
            case US_ARRIVAL -> "after landing: Arrivals / Immigration signs; answer the officer herself";
            case IMMIGRATION -> "immigration line; help button shows the staff card";
            case BAGGAGE -> "baggage claim (" + flight + " belt)";
            case EXIT -> "follow Exit signs; press 'I'm here' outside";
            case FAMILY_HANDOFF -> "waiting for her 'I'm here'";
            case COMPLETE -> "journey complete";
        };
    }

    /** Buttons offered with a stage push (WhatsApp allows three). */
    public static List<WhatsAppClient.Button> buttons(StageType type) {
        return switch (type) {
            case PRE_TRIP -> List.of(new WhatsAppClient.Button(BTN_START, START_TITLE),
                    new WhatsAppClient.Button(ParentTurnService.BTN_SON, TeluguPhrases.BTN_SON));
            case EXIT, FAMILY_HANDOFF -> List.of(new WhatsAppClient.Button(BTN_HERE, HERE_TITLE),
                    new WhatsAppClient.Button(ParentTurnService.BTN_HELP, TeluguPhrases.BTN_HELP),
                    new WhatsAppClient.Button(ParentTurnService.BTN_SON, TeluguPhrases.BTN_SON));
            case IN_FLIGHT, COMPLETE -> List.of();
            default -> List.of(new WhatsAppClient.Button(BTN_DONE, DONE_TITLE),
                    new WhatsAppClient.Button(ParentTurnService.BTN_GATE, TeluguPhrases.BTN_GATE),
                    new WhatsAppClient.Button(ParentTurnService.BTN_HELP, TeluguPhrases.BTN_HELP));
        };
    }

    private static JourneyFacts.Flight flight(JourneyFacts facts, Integer segmentSeq) {
        if (facts == null || facts.flights().isEmpty()) return null;
        if (segmentSeq == null) return facts.firstFlight();
        return facts.flights().stream().filter(x -> x.seq() == segmentSeq).findFirst().orElse(facts.firstFlight());
    }

    private static String airlineOrFlight(JourneyFacts.Flight f) {
        if (f == null) return "మీ ఎయిర్‌లైన్";
        return Airlines.teluguName(f.airline(), f.flight()).orElse(f.flight());
    }
}
