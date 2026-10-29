package ai.concourse.evidence;

import ai.concourse.card.SignCardRenderer;
import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.StageType;
import ai.concourse.voice.TeluguPhrases;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares what the photo shows with her next verified step and says one of four things — in a template, never in
 * model prose: MATCH ("this board has your word / your gate; follow it"), MISMATCH ("this board shows other gates;
 * yours is B12"), IRRELEVANT ("this board is not about your next step"), UNREADABLE ("take it closer").
 * A direction is spoken only when the observation carries an explicit arrow, and only as "follow the arrow".
 * Pure: no clock, no model, no database.
 */
public final class EvidenceReconciler {
    private EvidenceReconciler() {}

    public enum Kind { MATCH, MISMATCH, IRRELEVANT, UNREADABLE }

    /** The result: what to say (Telugu, safety-checked by the caller) and what to log. */
    public record Verdict(Kind kind, String matched, String arrow, String telugu, String english) {}

    private static final Pattern GATE_RANGE = Pattern.compile("^([A-Z])\\s?(\\d{1,3})\\s?[-–]\\s?(?:[A-Z]\\s?)?(\\d{1,3})$");
    private static final Pattern GATE_ONE = Pattern.compile("^([A-Z])\\s?-?(\\d{1,3})[A-Z]?$");

    /** What the photo should show for this stage: a gate (letter + number) or a sign word with its variants. */
    public record Target(StageType stage, String gate, List<String> words) {
        public boolean isGate() { return gate != null; }
    }

    public static Target targetFor(StageType stage, JourneyFacts.Flight f) {
        if (stage.showsGateCard()) {
            String gate = f == null || f.gate() == null ? null : JourneyFacts.norm(f.gate());
            List<String> words = new ArrayList<>(List.of("GATE", "GATES", "DEPARTURES"));
            if (gate != null) words.add(0, gate);
            return new Target(stage, gate, words);
        }
        SignCardRenderer.Spec spec = SignCardRenderer.specFor(stage, f);
        if (spec == null) return new Target(stage, null, List.of());
        List<String> words = new ArrayList<>();
        words.add(spec.word());
        words.addAll(spec.alsoSeenAs());
        return new Target(stage, null, words);
    }

    public static Verdict reconcile(SignObservation o, Target target) {
        if (o == null || !o.readable()) {
            return new Verdict(Kind.UNREADABLE, null, null,
                    "ఈ ఫోటోలో బోర్డు సరిగ్గా కనిపించడం లేదు. బోర్డుకు కొంచెం దగ్గరగా వెళ్ళి, మళ్ళీ ఫోటో తీసి పంపండి.",
                    "photo unreadable; asked for a closer one");
        }
        String arrow = o.explicitArrow() == null || o.explicitArrow().isBlank() ? null : o.explicitArrow().toLowerCase(Locale.ROOT);
        List<String> text = o.visibleText().stream().map(t -> t.toUpperCase(Locale.ROOT)).toList();
        String joined = String.join(" | ", text);

        // 1. Her exact gate, or a range that contains it.
        if (target.isGate()) {
            Matcher g = GATE_ONE.matcher(target.gate());
            String letter = g.matches() ? g.group(1) : null;
            int number = g.matches() ? Integer.parseInt(g.group(2)) : -1;
            boolean exact = false, range = false, letterOnly = false;
            List<String> otherLetters = new ArrayList<>();
            for (String code : o.gateCodes()) {
                String c = JourneyFacts.norm(code);
                if (c.equals(target.gate())) { exact = true; continue; }
                Matcher r = GATE_RANGE.matcher(code.toUpperCase(Locale.ROOT).strip());
                if (r.matches() && r.group(1).equals(letter)) {
                    int lo = Integer.parseInt(r.group(2)), hi = Integer.parseInt(r.group(3));
                    if (number >= Math.min(lo, hi) && number <= Math.max(lo, hi)) { range = true; continue; }
                }
                if (c.equals(letter) || (c.length() > 1 && c.startsWith(letter) && letter != null)) { letterOnly = true; continue; }
                if (!c.isEmpty() && !otherLetters.contains(c.substring(0, 1))) otherLetters.add(c.substring(0, 1));
            }
            if (exact || range) {
                return new Verdict(Kind.MATCH, target.gate(), arrow,
                        "ఈ బోర్డు మీద మీ గేట్ " + target.gate() + " ఉంది. " + follow(arrow) + " మళ్ళీ చెబుతున్నాను: గేట్ " + target.gate() + ".",
                        "sign shows her gate " + target.gate() + (arrow == null ? "" : ", arrow " + arrow));
            }
            if (letterOnly) {
                return new Verdict(Kind.MATCH, letter, arrow,
                        "ఈ బోర్డు " + spellLetter(letter) + " గేట్ల వైపు చూపిస్తోంది. మీ గేట్ " + target.gate() + ". " + follow(arrow) + " మళ్ళీ చెబుతున్నాను: గేట్ " + target.gate() + ".",
                        "sign shows " + letter + " gates; her gate is " + target.gate() + (arrow == null ? "" : ", arrow " + arrow));
            }
            if (!otherLetters.isEmpty()) {
                return new Verdict(Kind.MISMATCH, String.join(",", otherLetters), null,
                        "ఈ బోర్డు మీద మీ గేట్ లేదు — ఇవి వేరే గేట్లు. మీ గేట్ " + target.gate() + ". "
                                + spellLetter(letter) + " అక్షరం ఉన్న బోర్డు కోసం చూడండి. తెలియకపోతే ఈ కార్డు సిబ్బందికి చూపించండి.",
                        "sign shows other gates (" + String.join(",", otherLetters) + "); her gate is " + target.gate());
            }
        }

        // 2. The sign word for this stage (TRANSFER, BAGGAGE CLAIM, ...).
        for (String w : target.words()) {
            String want = w.toUpperCase(Locale.ROOT);
            if (want.length() < 3) continue;
            for (String line : text) {
                if (line.contains(want) || (want.contains(" ") && line.contains(want.split(" ")[0]))) {
                    return new Verdict(Kind.MATCH, want, arrow,
                            "ఈ బోర్డు మీద మీకు కావలసిన పదం ఉంది. " + follow(arrow) + " ఇదే బోర్డులను చూస్తూ వెళ్ళండి.",
                            "sign shows " + want + (arrow == null ? "" : ", arrow " + arrow));
                }
            }
        }

        // 3. Readable, but not about her next step.
        String summary = joined.length() > 60 ? joined.substring(0, 60) + "…" : joined;
        if (!o.isAirportSign()) {
            return new Verdict(Kind.IRRELEVANT, null, null,
                    "ఇది బోర్డు ఫోటో కాదు. పైన ఉన్న పెద్ద బోర్డు ఫోటో తీసి పంపండి, లేదా ఈ కార్డు సిబ్బందికి చూపించండి.",
                    "not an airport sign (" + summary + ")");
        }
        return new Verdict(Kind.IRRELEVANT, null, null,
                "ఈ బోర్డు మీ తర్వాతి పని గురించి కాదు. కార్డులో ఉన్న పదం ఉన్న బోర్డు కోసం చూడండి, లేదా ఈ కార్డు సిబ్బందికి చూపించండి.",
                "sign not relevant to the next step (" + summary + ")");
    }

    /** "Go the way the arrow points: left." Only ever spoken for an arrow that was observed. */
    static String follow(String arrow) {
        if (arrow == null) return "ఆ బోర్డు చూపించే వైపు వెళ్ళండి.";
        return switch (arrow) {
            case "left" -> "బాణం ఎడమ వైపు చూపిస్తోంది. ఎడమ వైపు వెళ్ళండి.";
            case "right" -> "బాణం కుడి వైపు చూపిస్తోంది. కుడి వైపు వెళ్ళండి.";
            case "up", "straight" -> "బాణం ముందుకు చూపిస్తోంది. నేరుగా వెళ్ళండి.";
            case "down" -> "బాణం కిందకి చూపిస్తోంది. మెట్లు లేదా లిఫ్ట్ తో కిందకి వెళ్ళండి.";
            default -> "ఆ బోర్డు చూపించే వైపు వెళ్ళండి.";
        };
    }

    private static String spellLetter(String letter) {
        return letter == null ? "" : letter;
    }

    static String gateLetter(String gate) {
        Matcher g = GATE_ONE.matcher(gate == null ? "" : gate);
        return g.matches() ? g.group(1) : null;
    }
}
