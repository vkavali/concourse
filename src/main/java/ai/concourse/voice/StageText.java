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

    /** "దోహాలో", "డల్లాస్‌లో": the locative joins the city, with a non-joiner after a pollu so the script does not fuse. */
    static String in(String city) {
        if (city == null || city.isEmpty()) return "";
        return city + (city.endsWith("\u0C4D") ? "\u200C" : "") + "లో";
    }

    /** The intro, sent once she is registered and the itinerary is confirmed. Ends with the consent request. */
    public static String intro(JourneyFacts facts) {
        JourneyFacts.Flight f = facts.firstFlight();
        JourneyFacts.Flight last = facts.flights().isEmpty() ? null : facts.flights().get(facts.flights().size() - 1);
        String name = facts.travelerFirstName() == null || facts.travelerFirstName().isBlank() ? "" : " " + facts.travelerFirstName() + " గారు";
        String flight = f == null ? "మీ విమానం" : TeluguPhrases.yourFlight(f);
        String route = f == null ? "" : ", " + city(f.from()) + " నుంచి " + city(last.to()) + " వరకు";
        return "నమస్కారం" + name + ". నేను కాంకోర్స్. మీ అబ్బాయి మీ ప్రయాణం కోసం నన్ను పెట్టారు. "
                + flight + route + ". "
                + "ఈ ప్రయాణంలో ఎప్పుడు ఏం చేయాలో నేనే చెబుతాను. ఏదైనా అడగాలంటే మాట్లాడి పంపండి, లేదా బోర్డు ఫోటో తీసి పంపండి. "
                + "సిద్ధంగా ఉంటే 'నా ప్రయాణం మొదలు' అని చెప్పండి, లేదా కింద బటన్ నొక్కండి.";
    }

    /** "All right. Your trip has started. I will tell you what to do, when it is time. Keep the phone with you." */
    public static final String CONSENT_THANKS = "సరే. మీ ప్రయాణం మొదలైంది. ఎప్పుడు ఏం చేయాలో సమయానికి నేను చెబుతాను. ఫోన్ దగ్గరే ఉంచుకోండి.";

    /** "All right. I will tell you what to do next when it is time." */
    public static final String ACK_THANKS = "సరే. తర్వాత ఏం చేయాలో సమయం వచ్చినప్పుడు చెబుతాను.";

    /** "Your trip has not started yet: say 'start my trip', or press the button below." */
    public static final String NOT_STARTED = "మీ ప్రయాణం ఇంకా మొదలు కాలేదు. 'నా ప్రయాణం మొదలు' అని చెప్పండి, లేదా కింద బటన్ నొక్కండి.";

    /** "Look for boards with the word on the card as you go." */
    private static final String FOLLOW_CARD = "కార్డులో ఉన్న పదం ఉన్న బోర్డులు చూస్తూ వెళ్ళండి.";

    /** The instruction for a stage, from verified facts only. */
    public static String instruction(StageType type, JourneyFacts facts, Integer segmentSeq, String airport) {
        JourneyFacts.Flight f = flight(facts, segmentSeq);
        String flight = f == null ? "మీ విమానం" : TeluguPhrases.yourFlight(f);
        String gate = f == null ? null : f.gate();
        String to = f == null ? "" : city(f.to());
        String here = city(airport);
        return switch (type) {
            case PRE_TRIP -> intro(facts);
            case CHECK_IN -> "ఇప్పుడు ఎయిర్‌పోర్ట్ లోపల " + airlineOrFlight(f) + " కౌంటర్ దగ్గరకి వెళ్ళండి. " + FOLLOW_CARD + " "
                    + "కౌంటర్లో పాస్‌పోర్ట్, టికెట్ ఇవ్వండి. పెద్ద బ్యాగ్ అక్కడే ఇచ్చేయండి. బోర్డింగ్ పాస్ తీసుకోండి. "
                    + "అయ్యాక 'అయ్యింది' బటన్ నొక్కండి.";
            case SECURITY -> "ఇప్పుడు సెక్యూరిటీ చెక్\u200Cకి వెళ్ళండి. " + FOLLOW_CARD + " "
                    + "చేతి బ్యాగ్, ఫోన్ ట్రేలో పెట్టండి. పాస్‌పోర్ట్, బోర్డింగ్ పాస్ చేతిలోనే ఉంచుకోండి. అయ్యాక 'అయ్యింది' నొక్కండి.";
            case WAITING_AT_ORIGIN, TRANSFER_WAITING -> gate == null
                    ? flight + " గేట్ ఇంకా చెప్పలేదు. పెద్ద స్క్రీన్ మీద చూడండి, లేదా ఈ కార్డు సిబ్బందికి చూపించండి. గేట్ తెలియగానే నేను చెబుతాను."
                    : flight + " గేట్ " + gate + ". మళ్ళీ చెబుతున్నాను: గేట్ " + gate + ". కార్డు చూడండి. " + gate + " అని ఉన్న బోర్డులు చూస్తూ వెళ్ళండి. "
                    + "గేట్ దగ్గర కూర్చోండి. తెలియకపోతే కార్డు సిబ్బందికి చూపించండి.";
            case BOARDING_ORIGIN, TRANSFER_BOARDING -> (gate == null ? "బోర్డింగ్ ఇప్పుడు మొదలవుతుంది. " : "గేట్ " + gate + " దగ్గర బోర్డింగ్ ఇప్పుడు మొదలవుతుంది. ")
                    + "పాస్‌పోర్ట్, బోర్డింగ్ పాస్ చేతిలో ఉంచుకోండి. సిబ్బంది పిలిచినప్పుడు లైన్‌లో నిలబడండి.";
            case IN_FLIGHT -> flight + " బయలుదేరే సమయం అయింది. ఫోన్ ఫ్లైట్ మోడ్‌లో పెట్టండి. "
                    + (to.isEmpty() ? "" : in(to) + " దిగాక ఫోన్ ఆన్ చేయండి, ") + "అప్పుడు నేను మళ్ళీ చెబుతాను. ప్రయాణం బాగా జరగాలి.";
            case TRANSFER_ARRIVAL -> in(here) + " దిగాక: తొందర లేదు. ఫోన్ ఆన్ చేయండి. కార్డులో ఉన్న పదం, TRANSFER, ఉన్న బోర్డులు చూస్తూ వెళ్ళండి. "
                    + "బయటకి వెళ్ళకండి, బ్యాగ్ తీసుకోవద్దు. ఏ బోర్డు అయినా అర్థం కాకపోతే ఫోటో తీసి పంపండి.";
            case TRANSFER_FIND_GATE -> gate == null
                    ? "మీ తర్వాతి విమానం " + airlineOrFlight(f) + ". గేట్ ఇంకా చెప్పలేదు. TRANSFER బోర్డులు చూస్తూ వెళ్ళి పెద్ద స్క్రీన్ మీద చూడండి, లేదా ఈ కార్డు సిబ్బందికి చూపించండి."
                    : "మీ తర్వాతి విమానం " + airlineOrFlight(f) + ". గేట్ " + gate + ". మళ్ళీ చెబుతున్నాను: గేట్ " + gate + ". "
                    + "ముందు TRANSFER బోర్డులు, తర్వాత " + gate + " అని ఉన్న బోర్డులు చూస్తూ వెళ్ళండి. కార్డు చూడండి.";
            case US_ARRIVAL -> in(here) + " దిగాక: ఫోన్ ఆన్ చేయండి. కార్డులో ఉన్న పదాలు, ARRIVALS, IMMIGRATION, ఉన్న బోర్డులు చూస్తూ వెళ్ళండి. "
                    + "ఆఫీసర్ అడిగే ప్రశ్నలకు మీరే సమాధానం చెప్పండి. ఫోన్ అడిగితేనే చూపించండి.";
            case IMMIGRATION -> "ఇమ్మిగ్రేషన్ లైన్‌లో నిలబడండి. పాస్‌పోర్ట్ చేతిలో ఉంచుకోండి. ఆఫీసర్ అడిగినవి మీరే చెప్పండి. "
                    + "సహాయం కావాలంటే 'సహాయం కావాలి' నొక్కండి, కార్డు చూపించండి. అయ్యాక 'అయ్యింది' నొక్కండి.";
            case BAGGAGE -> "ఇప్పుడు BAGGAGE CLAIM బోర్డులు చూస్తూ వెళ్ళండి. స్క్రీన్ మీద "
                    + (f == null ? "మీ విమానం నంబర్" : f.flight()) + " చూసి, ఆ బెల్ట్ దగ్గర మీ బ్యాగ్ తీసుకోండి. అయ్యాక 'అయ్యింది' నొక్కండి.";
            case EXIT -> "బ్యాగ్ తీసుకున్నాక EXIT బోర్డులు చూస్తూ బయటకి రండి. బయట మీ అబ్బాయి ఉంటారు. "
                    + "బయటకి వచ్చాక 'నేను వచ్చాను' నొక్కండి.";
            case FAMILY_HANDOFF -> "బయటకి వచ్చారా? 'నేను వచ్చాను' నొక్కండి, మీ అబ్బాయికి చెబుతాను. ఆయన ఇంకా కనిపించకపోతే 'అబ్బాయికి చెప్పు' నొక్కండి.";
            case COMPLETE -> "మీ ప్రయాణం పూర్తయింది. మీ అబ్బాయికి చెప్పాను. నమస్కారం.";
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
