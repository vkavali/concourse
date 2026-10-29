package ai.concourse.card;

import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.StageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/**
 * The sign card: the English word the airport prints on its overhead boards, huge and in the same shapes, with the
 * standard pictogram next to it. She cannot read "TRANSFER", but she can match it — letter shapes are patterns, and
 * matching a pattern on a board is how she finds her way without reading a word. One Telugu line says what to do
 * with it; one English line lets staff help. Rendered from the stage type and verified facts only.
 */
@Component
public class SignCardRenderer {
    private static final Logger log = LoggerFactory.getLogger(SignCardRenderer.class);
    private static final int W = 1080, H = 1080, MAX_TEXT_W = 1000;
    private static final Color NAVY = new Color(0x0B, 0x3D, 0x91);
    private static final Color INK = new Color(0x11, 0x11, 0x11);
    private static final Color GREY = new Color(0x55, 0x55, 0x55);
    private static final Color YELLOW = new Color(0xF2, 0xC2, 0x1E);

    /** What goes on the card for a stage. */
    public record Spec(String icon, String word, List<String> alsoSeenAs, String ask) {}

    private final Font telugu;
    private final Font latinBold;
    private final Font latin;

    public SignCardRenderer() {
        this.telugu = load("fonts/NotoSansTelugu-Variable.ttf");
        this.latinBold = load("fonts/DejaVuSans-Bold.ttf");
        this.latin = load("fonts/DejaVuSans.ttf");
    }

    /** The sign words airports use for each stage; null when the stage has no board to look for. */
    public static Spec specFor(StageType type, JourneyFacts.Flight f) {
        String airline = f == null || f.airline() == null ? "" : f.airline().toUpperCase(Locale.ROOT);
        return switch (type) {
            case CHECK_IN -> new Spec("counter", "CHECK-IN", airline.isEmpty() ? List.of("DEPARTURES") : List.of(airline, "DEPARTURES"), "Please point me to check-in");
            case SECURITY -> new Spec("tray", "SECURITY", List.of("SECURITY CHECK", "DEPARTURES"), "Please point me to security");
            case TRANSFER_ARRIVAL -> new Spec("transfer", "TRANSFER", List.of("CONNECTIONS", "FLIGHT CONNECTIONS"), "I am connecting — please point me to transfers");
            case US_ARRIVAL -> new Spec("arrivals", "ARRIVALS", List.of("IMMIGRATION", "PASSPORT CONTROL"), "Please point me to immigration");
            case IMMIGRATION -> new Spec("passport", "PASSPORT CONTROL", List.of("IMMIGRATION", "VISITORS"), "Please point me to passport control");
            case BAGGAGE -> new Spec("suitcase", "BAGGAGE CLAIM", f == null ? List.of("BAGGAGE RECLAIM") : List.of("BAGGAGE RECLAIM", "FLIGHT " + f.flight()), "Please point me to baggage claim");
            case EXIT, FAMILY_HANDOFF -> new Spec("exit", "EXIT", List.of("WAY OUT", "ARRIVALS HALL"), "Please point me to the exit");
            default -> null;
        };
    }

    /** PNG for the stage, or null when the stage has no sign to look for (gate stages use the gate card). */
    public byte[] signCard(StageType type, JourneyFacts.Flight f) {
        Spec spec = specFor(type, f);
        if (spec == null) return null;
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, W, H);

        // The board itself: navy, like the overhead signs; pictogram left, word right — the way they are printed.
        g.setColor(NAVY);
        g.fillRect(0, 0, W, 560);
        g.setColor(Color.WHITE);
        drawIcon(g, spec.icon(), 60, 110, 300);
        String[] lines = spec.word().contains(" ") && spec.word().length() > 9 ? spec.word().split(" ", 2) : new String[]{spec.word()};
        if (lines.length == 1) {
            fit(g, lines[0], latinBold, lines[0].length() <= 8 ? 190f : 150f, 400, 1040, 320);
        } else {
            fit(g, lines[0], latinBold, 150f, 400, 1040, 250);
            fit(g, lines[1], latinBold, 150f, 400, 1040, 410);
        }

        g.setColor(GREY);
        g.setFont(latin.deriveFont(38f));
        g.drawString("Also written as:", 60, 640);
        g.setColor(INK);
        int y = 700;
        for (String alt : spec.alsoSeenAs()) {
            fitLeft(g, alt, latinBold, 54f, 60, 1000, y);
            y += 68;
        }
        g.setColor(INK);
        fitLeft(g, "ఈ పదం ఉన్న బోర్డు కోసం చూడండి.", telugu, 46f, 60, 1000, 900);        // look for a board with this word
        fitLeft(g, "కనపడకపోతే ఈ కార్డు సిబ్బందికి చూపించండి.", telugu, 46f, 60, 1000, 960); // if you cannot see one, show this card to staff
        g.setColor(GREY);
        fitLeft(g, spec.ask() + ". I speak Telugu, not English.", latin, 36f, 60, 1000, 1030);
        g.dispose();

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(80_000);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("sign card render failed", e);
        }
    }

    /** The pictograms airports use, drawn as simple shapes (no generative images). {@code s} is the box size. */
    private void drawIcon(Graphics2D g, String icon, int x, int y, int s) {
        g.setStroke(new BasicStroke(s / 14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (icon) {
            case "arrivals", "transfer" -> plane(g, x, y, s, icon.equals("arrivals") ? -28 : 0);
            case "counter" -> {
                g.fill(new RoundRectangle2D.Float(x, y + s * 0.55f, s, s * 0.35f, s * 0.08f, s * 0.08f));
                g.fill(new Ellipse2D.Float(x + s * 0.33f, y + s * 0.05f, s * 0.26f, s * 0.26f));
                g.fill(new RoundRectangle2D.Float(x + s * 0.26f, y + s * 0.32f, s * 0.4f, s * 0.26f, s * 0.1f, s * 0.1f));
            }
            case "tray" -> {
                g.fill(new RoundRectangle2D.Float(x, y + s * 0.5f, s, s * 0.32f, s * 0.1f, s * 0.1f));
                g.fill(new RoundRectangle2D.Float(x + s * 0.1f, y + s * 0.2f, s * 0.42f, s * 0.26f, s * 0.06f, s * 0.06f)); // bag
                g.fill(new RoundRectangle2D.Float(x + s * 0.62f, y + s * 0.1f, s * 0.2f, s * 0.36f, s * 0.05f, s * 0.05f)); // phone
            }
            case "passport" -> {
                g.fill(new RoundRectangle2D.Float(x + s * 0.2f, y, s * 0.6f, s * 0.9f, s * 0.08f, s * 0.08f));
                g.setColor(NAVY);
                g.fill(new Ellipse2D.Float(x + s * 0.36f, y + s * 0.22f, s * 0.28f, s * 0.28f));
                g.fill(new RoundRectangle2D.Float(x + s * 0.3f, y + s * 0.62f, s * 0.4f, s * 0.06f, s * 0.03f, s * 0.03f));
                g.setColor(Color.WHITE);
            }
            case "suitcase" -> {
                g.fill(new RoundRectangle2D.Float(x + s * 0.1f, y + s * 0.3f, s * 0.8f, s * 0.6f, s * 0.1f, s * 0.1f));
                g.draw(new RoundRectangle2D.Float(x + s * 0.35f, y + s * 0.12f, s * 0.3f, s * 0.2f, s * 0.08f, s * 0.08f));
                g.setColor(NAVY);
                g.fill(new RoundRectangle2D.Float(x + s * 0.2f, y + s * 0.55f, s * 0.6f, s * 0.08f, s * 0.04f, s * 0.04f));
                g.setColor(Color.WHITE);
            }
            case "exit" -> {
                g.draw(new RoundRectangle2D.Float(x + s * 0.05f, y, s * 0.5f, s * 0.9f, s * 0.06f, s * 0.06f));
                Path2D.Float arrow = new Path2D.Float();
                arrow.moveTo(x + s * 0.3f, y + s * 0.45f); arrow.lineTo(x + s * 0.8f, y + s * 0.45f);
                g.draw(arrow);
                Path2D.Float head = new Path2D.Float();
                head.moveTo(x + s * 0.62f, y + s * 0.25f); head.lineTo(x + s * 0.95f, y + s * 0.45f); head.lineTo(x + s * 0.62f, y + s * 0.65f);
                g.draw(head);
            }
            default -> plane(g, x, y, s, 0);
        }
    }

    private void plane(Graphics2D g, int x, int y, int s, double tiltDegrees) {
        Path2D.Float p = new Path2D.Float();
        p.moveTo(0.05, 0.5); p.lineTo(0.35, 0.42); p.lineTo(0.55, 0.12); p.lineTo(0.64, 0.14); p.lineTo(0.5, 0.44);
        p.lineTo(0.78, 0.44); p.lineTo(0.92, 0.5); p.lineTo(0.78, 0.56); p.lineTo(0.5, 0.56); p.lineTo(0.64, 0.86);
        p.lineTo(0.55, 0.88); p.lineTo(0.35, 0.58); p.lineTo(0.05, 0.5); p.closePath();
        java.awt.geom.AffineTransform t = new java.awt.geom.AffineTransform();
        t.translate(x, y);
        t.scale(s, s);
        t.rotate(Math.toRadians(tiltDegrees), 0.5, 0.5);
        g.fill(t.createTransformedShape(p));
    }

    private void fit(Graphics2D g, String s, Font font, float size, int left, int right, int baseline) {
        int max = right - left;
        Font f = font.deriveFont(size);
        g.setFont(f);
        while (g.getFontMetrics().stringWidth(s) > max && size > 16f) {
            size -= 2f;
            f = font.deriveFont(size);
            g.setFont(f);
        }
        int w = g.getFontMetrics().stringWidth(s);
        g.drawString(s, left + (max - w) / 2, baseline);
    }

    private void fitLeft(Graphics2D g, String s, Font font, float size, int left, int maxWidth, int baseline) {
        Font f = font.deriveFont(size);
        g.setFont(f);
        while (g.getFontMetrics().stringWidth(s) > maxWidth && size > 16f) {
            size -= 2f;
            f = font.deriveFont(size);
            g.setFont(f);
        }
        g.drawString(s, left, baseline);
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
