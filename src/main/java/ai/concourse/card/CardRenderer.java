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

/**
 * The "simple card": a PNG the parent shows to airport staff. English, because the people who read it are the
 * people at the airport — the gate code is drawn exactly as the signs print it, so she can match the shapes
 * "B 12" to "Gate B12" without reading a word. One small Telugu line at the bottom tells her what to do with it.
 * Rendered from verified facts only — no model in the loop — so it can never show a gate that is not on file.
 */
@Component
public class CardRenderer {
    private static final Logger log = LoggerFactory.getLogger(CardRenderer.class);
    private static final int W = 1080, H = 1080, MAX_TEXT_W = 1000;
    private static final Color NAVY = new Color(0x0B, 0x3D, 0x91);
    private static final Color INK = new Color(0x11, 0x11, 0x11);
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    private static final Color AMBER = new Color(0xB4, 0x5F, 0x06);

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

    /** Gate card for the given flight: GATE band, huge gate code, flight + route, the ask to staff. */
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
        g.fillRect(0, 0, W, 230);
        g.setColor(Color.WHITE);
        fit(g, "GATE", latinBold, 150f, 170);

        if (known) {
            g.setColor(INK);
            fit(g, spaced(f.gate()), latinBold, 440f, 640);
        } else {
            g.setColor(INK);
            fit(g, "?", latinBold, 330f, 600);
            g.setColor(AMBER);
            fit(g, "Not assigned yet — check the screens", latinBold, 56f, 720);
        }

        g.setColor(INK);
        fit(g, "Flight " + f.flight() + "  ·  " + f.from() + " → " + f.to(), latinBold, 64f, 830);
        g.setColor(GREY);
        fit(g, routeLine(f), latin, 46f, 900);
        g.setColor(INK);
        fit(g, "I speak Telugu, not English. Please help me find this gate.", latin, 42f, 975);
        g.setColor(GREY);
        fit(g, "ఈ కార్డును సిబ్బందికి చూపించండి", telugu, 36f, 1045); // "show this card to staff" — for her
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(80_000);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("card render failed", e);
        }
    }

    /** "Air India  ·  Delhi to San Francisco" (whatever is known). */
    private String routeLine(JourneyFacts.Flight f) {
        String airline = f.airline() == null ? "" : f.airline().strip();
        String route = city(f.from()) + " to " + city(f.to());
        return airline.isEmpty() ? route : airline + "  ·  " + route;
    }

    private String city(String iata) {
        if (iata == null) return "";
        return airports.find(iata).map(Airports.Airport::city).orElse(iata);
    }

    /** "B12" → "B 12" so the letter and the number read as two things. */
    static String spaced(String gate) {
        return gate.strip().toUpperCase().replaceAll("([A-Z])(\\d)", "$1 $2").replaceAll("(\\d)([A-Z])", "$1 $2");
    }

    /** Draws centred, shrinking the font until the line fits the card — nothing is ever cut off at the edges. */
    private void fit(Graphics2D g, String s, Font font, float size, int baseline) {
        Font f = font.deriveFont(size);
        g.setFont(f);
        while (g.getFontMetrics().stringWidth(s) > MAX_TEXT_W && size > 16f) {
            size -= 2f;
            f = font.deriveFont(size);
            g.setFont(f);
        }
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
