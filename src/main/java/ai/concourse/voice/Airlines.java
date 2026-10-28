package ai.concourse.voice;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Airline names in Telugu script, so the voice says "మీ ఎయిర్ ఇండియా విమానం" instead of spelling "AI173" letter by
 * letter (which lands on the ear as a jumble). The flight code stays in the text, on the card and in the child's
 * messages, where staff can read it. Matched by the flight number's IATA prefix first, then by the airline name.
 */
public final class Airlines {
    private Airlines() {}

    private static final Map<String, String> BY_CODE = Map.ofEntries(
            Map.entry("AI", "ఎయిర్ ఇండియా"), Map.entry("IX", "ఎయిర్ ఇండియా ఎక్స్‌ప్రెస్"), Map.entry("6E", "ఇండిగో"),
            Map.entry("UK", "విస్తారా"), Map.entry("SG", "స్పైస్‌జెట్"), Map.entry("QP", "ఆకాశ ఎయిర్"),
            Map.entry("QR", "ఖతార్ ఎయిర్‌వేస్"), Map.entry("EK", "ఎమిరేట్స్"), Map.entry("EY", "ఎతిహాద్"),
            Map.entry("LH", "లుఫ్తాన్సా"), Map.entry("BA", "బ్రిటిష్ ఎయిర్‌వేస్"), Map.entry("AA", "అమెరికన్ ఎయిర్‌లైన్స్"),
            Map.entry("UA", "యునైటెడ్ ఎయిర్‌లైన్స్"), Map.entry("DL", "డెల్టా ఎయిర్‌లైన్స్"), Map.entry("SQ", "సింగపూర్ ఎయిర్‌లైన్స్"),
            Map.entry("CX", "క్యాథే పసిఫిక్"), Map.entry("TK", "టర్కిష్ ఎయిర్‌లైన్స్"), Map.entry("SV", "సౌదియా"),
            Map.entry("WY", "ఒమాన్ ఎయిర్"), Map.entry("G9", "ఎయిర్ అరేబియా"), Map.entry("FZ", "ఫ్లై దుబాయ్"),
            Map.entry("GF", "గల్ఫ్ ఎయిర్"), Map.entry("KU", "కువైట్ ఎయిర్‌వేస్"), Map.entry("MH", "మలేషియా ఎయిర్‌లైన్స్"),
            Map.entry("TG", "థాయ్ ఎయిర్‌వేస్"), Map.entry("JL", "జపాన్ ఎయిర్‌లైన్స్"), Map.entry("NH", "ఏ ఎన్ ఏ"),
            Map.entry("KE", "కొరియన్ ఎయిర్"), Map.entry("AC", "ఎయిర్ కెనడా"), Map.entry("AF", "ఎయిర్ ఫ్రాన్స్"),
            Map.entry("KL", "కే ఎల్ ఎమ్"), Map.entry("ET", "ఇథియోపియన్ ఎయిర్‌లైన్స్"), Map.entry("VS", "వర్జిన్ అట్లాంటిక్"),
            Map.entry("AY", "ఫిన్ ఎయిర్"), Map.entry("LX", "స్విస్"), Map.entry("OS", "ఆస్ట్రియన్"),
            Map.entry("UL", "శ్రీలంకన్ ఎయిర్‌లైన్స్"), Map.entry("QF", "క్వాంటాస్"), Map.entry("EI", "ఎయిర్ లింగస్"),
            Map.entry("WS", "వెస్ట్‌జెట్"), Map.entry("B6", "జెట్‌బ్లూ"), Map.entry("AS", "అలాస్కా ఎయిర్‌లైన్స్"),
            Map.entry("WN", "సౌత్‌వెస్ట్"), Map.entry("SK", "ఎస్ ఏ ఎస్"), Map.entry("IB", "ఐబీరియా"),
            Map.entry("AZ", "ఐటీఏ ఎయిర్‌వేస్"), Map.entry("MS", "ఈజిప్ట్ ఎయిర్"), Map.entry("RJ", "రాయల్ జోర్డానియన్"));

    /** Normalised English name (letters only, lower case) → Telugu. Longest keys are tried first. */
    private static final List<Map.Entry<String, String>> BY_NAME = Map.ofEntries(
            Map.entry("airindiaexpress", "ఎయిర్ ఇండియా ఎక్స్‌ప్రెస్"), Map.entry("airindia", "ఎయిర్ ఇండియా"),
            Map.entry("indigo", "ఇండిగో"), Map.entry("vistara", "విస్తారా"), Map.entry("spicejet", "స్పైస్‌జెట్"),
            Map.entry("akasa", "ఆకాశ ఎయిర్"), Map.entry("qatar", "ఖతార్ ఎయిర్‌వేస్"), Map.entry("emirates", "ఎమిరేట్స్"),
            Map.entry("etihad", "ఎతిహాద్"), Map.entry("lufthansa", "లుఫ్తాన్సా"), Map.entry("britishairways", "బ్రిటిష్ ఎయిర్‌వేస్"),
            Map.entry("americanairlines", "అమెరికన్ ఎయిర్‌లైన్స్"), Map.entry("unitedairlines", "యునైటెడ్ ఎయిర్‌లైన్స్"),
            Map.entry("united", "యునైటెడ్ ఎయిర్‌లైన్స్"), Map.entry("delta", "డెల్టా ఎయిర్‌లైన్స్"),
            Map.entry("singaporeairlines", "సింగపూర్ ఎయిర్‌లైన్స్"), Map.entry("cathay", "క్యాథే పసిఫిక్"),
            Map.entry("turkish", "టర్కిష్ ఎయిర్‌లైన్స్"), Map.entry("saudia", "సౌదియా"), Map.entry("omanair", "ఒమాన్ ఎయిర్"),
            Map.entry("airarabia", "ఎయిర్ అరేబియా"), Map.entry("flydubai", "ఫ్లై దుబాయ్"), Map.entry("gulfair", "గల్ఫ్ ఎయిర్"),
            Map.entry("kuwait", "కువైట్ ఎయిర్‌వేస్"), Map.entry("malaysia", "మలేషియా ఎయిర్‌లైన్స్"), Map.entry("thai", "థాయ్ ఎయిర్‌వేస్"),
            Map.entry("japanairlines", "జపాన్ ఎయిర్‌లైన్స్"), Map.entry("koreanair", "కొరియన్ ఎయిర్"), Map.entry("aircanada", "ఎయిర్ కెనడా"),
            Map.entry("airfrance", "ఎయిర్ ఫ్రాన్స్"), Map.entry("klm", "కే ఎల్ ఎమ్"), Map.entry("ethiopian", "ఇథియోపియన్ ఎయిర్‌లైన్స్"),
            Map.entry("virginatlantic", "వర్జిన్ అట్లాంటిక్"), Map.entry("finnair", "ఫిన్ ఎయిర్"), Map.entry("swiss", "స్విస్"),
            Map.entry("srilankan", "శ్రీలంకన్ ఎయిర్‌లైన్స్"), Map.entry("qantas", "క్వాంటాస్"), Map.entry("jetblue", "జెట్‌బ్లూ"),
            Map.entry("southwest", "సౌత్‌వెస్ట్"), Map.entry("alaska", "అలాస్కా ఎయిర్‌లైన్స్"))
            .entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed()).toList();

    /** Telugu airline name for a segment, from the flight number prefix ("AI173" → ఎయిర్ ఇండియా) or the airline name. */
    public static Optional<String> teluguName(String airline, String flightNumber) {
        if (flightNumber != null) {
            String code = flightNumber.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
            if (code.length() >= 3) {
                String prefix = code.substring(0, 2);
                if (BY_CODE.containsKey(prefix) && Character.isDigit(code.charAt(2))) return Optional.of(BY_CODE.get(prefix));
            }
        }
        if (airline != null) {
            String norm = airline.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            for (Map.Entry<String, String> e : BY_NAME) {
                if (norm.contains(e.getKey())) return Optional.of(e.getValue());
            }
        }
        return Optional.empty();
    }

    /** Flight code → Telugu airline name, for every flight the voice may mention. */
    public static Map<String, String> spokenNames(List<ai.concourse.journey.JourneyFacts.Flight> flights) {
        Map<String, String> names = new java.util.LinkedHashMap<>();
        if (flights == null) return names;
        for (ai.concourse.journey.JourneyFacts.Flight f : flights) {
            if (f.flight() == null) continue;
            teluguName(f.airline(), f.flight()).ifPresent(n -> names.put(f.flight(), n));
        }
        return names;
    }
}
