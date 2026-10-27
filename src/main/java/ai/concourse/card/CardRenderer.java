package ai.concourse.card;

import ai.concourse.itinerary.Airports;
import ai.concourse.journey.JourneyFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * The "simple card": a PNG the parent can read at a glance and show to staff. Rendered from verified facts only —
 * no model in the loop — so it can never show a gate that is not on file. Telugu for her, English for staff.
 */
@Component
public class CardRenderer {
    private static final Logger log = LoggerFactory.getLogger(CardRenderer.class);
    private static final int W = 1080, H = 1080;
    private static final Color NAVY = new Color(0x0B, 0x3D, 0x91);
    private static final Color INK = new Color(0x11, 0x11, 0x11);
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    private static final Color AMBER = new Color(0xB4, 0x5F, 0x06);

    /** Telugu city names for the airports a Hyderabad→US traveler is likely to pass through; Latin fallback otherwise. */
    private static final Map<String, String> CITY_TE = Map.ofEntries(
            Map.entry("HYD", "హైదరాబాద్"), Map.entry("DOH", "దోహా"), Map.entry("DFW", "డల్లాస్"), Map.entry("DXB", "దుబాయ్"),
            Map.entry("AUH", "అబుదాబి"), Map.entry("FRA", "ఫ్రాంక్‌ఫర్ట్"), Map.entry("LHR", "లండన్"), Map.entry("JFK", "న్యూయార్క్"),
            Map.entry("EWR", "న్యూవార్క్"), Map.entry("SFO", "శాన్ ఫ్రాన్సిస్కో"), Map.entry("DEL", "ఢిల్లీ"), Map.entry("BOM", "ముంబై"),
            Map.entry("BLR", "బెంగళూరు"), Map.entry("MAA", "చెన్నై"), Map.entry("IAH", "హ్యూస్టన్"), Map.entry("ORD", "చికాగో"),
            Map.entry("SEA", "సియాటిల్"), Map.entry("LAX", "లాస్ ఏంజెలెస్"), Map.entry("IAD", "వాషింగ్టన్"), Map.entry("ATL", "అట్లాంటా"),
            Map.entry("BOS", "బోస్టన్"), Map.entry("CDG", "పారిస్"), Map.entry("AMS", "ఆమ్‌స్టర్‌డామ్"), Map.entry("SIN", "సింగపూర్"),
            Map.entry("MUC", "మ్యూనిక్"), Map.entry("ZRH", "జ్యూరిచ్"), Map.entry("IST", "ఇస్తాంబుల్"));

    private final Font telugu;
    private final Font latinBold;
    private final Font latin;
    private final Airports airports;

    public CardRenderer(Airports airports) {
        this.airports = airports;
        this.telugu = load("fonts/NotoSansTelugu-Variable.ttf");
        this.latinBold = load("fonts/DejaVuSans-Bold.ttf");
        this.latin = load("fonts/DejaVuSans.ttf");
    }

    /** Gate card for the given flight: huge gate code, Telugu label, flight + route, staff line. */
    public byte[] gateCard(JourneyFacts.Flight f) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);

        boolean known = f.gate() != null && !f.gate().isBlank();
        g.setColor(known ? NAVY : AMBER);
        g.fillRect(0, 0, W, 250);
        g.setColor(Color.WHITE);
        g.setFont(telugu.deriveFont(140f));
        center(g, "గేట్", 185);

        if (known) {
            g.setColor(INK);
            String code = spaced(f.gate());
            float size = code.length() <= 4 ? 420f : code.length() <= 6 ? 300f : 220f;
            g.setFont(latinBold.deriveFont(size));
            center(g, code, 690);
        } else {
            g.setColor(INK);
            g.setFont(latinBold.deriveFont(360f));
            center(g, "?", 640);
            g.setFont(telugu.deriveFont(70f));
            g.setColor(AMBER);
            center(g, "గేట్ ఇంకా ప్రకటించలేదు", 760); // gate not announced yet
        }

        g.setColor(GREY);
        g.setFont(telugu.deriveFont(60f));
        center(g, "విమానం " + f.flight() + "   " + city(f.from()) + " నుండి " + city(f.to()) + " కు", 860);
        g.setFont(latinBold.deriveFont(42f));
        String gateLine = known ? "GATE " + f.gate() : "GATE: not yet assigned — check the screens";
        center(g, gateLine + " · Flight " + f.flight() + " " + f.from() + " → " + f.to(), 935);
        g.setFont(latin.deriveFont(40f));
        center(g, "I speak Telugu, not English — please help me", 1005);
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(80_000);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("card render failed", e);
        }
    }

    private String city(String iata) {
        if (iata == null) return "";
        String te = CITY_TE.get(iata);
        if (te != null) return te;
        return airports.find(iata).map(Airports.Airport::city).orElse(iata);
    }

    /** "B12" → "B 12" so the letter and the number read as two things. */
    static String spaced(String gate) {
        return gate.strip().toUpperCase().replaceAll("([A-Z])(\\d)", "$1 $2").replaceAll("(\\d)([A-Z])", "$1 $2");
    }

    private void center(Graphics2D g, String s, int baseline) {
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, (W - w) / 2, baseline);
    }

    private static Font load(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            log.warn("font {} not loadable ({}); falling back to the JVM default", path, e.getMessage());
            return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
    }
}
