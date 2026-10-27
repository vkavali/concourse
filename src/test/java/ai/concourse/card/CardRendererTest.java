package ai.concourse.card;

import ai.concourse.itinerary.Airports;
import ai.concourse.journey.JourneyFacts;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class CardRendererTest {
    private final CardRenderer renderer = new CardRenderer(new Airports());

    @Test
    void rendersAGateCardPng() throws Exception {
        byte[] png = renderer.gateCard(new JourneyFacts.Flight(1, "QR729", "DOH", "DFW", "2026-11-28 08:40", null, "D", "B12", true));
        assertTrue(png.length > 10_000, "png bytes: " + png.length);
        assertEquals((byte) 0x89, png[0]);
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(1080, img.getWidth());
        assertEquals(1080, img.getHeight());
        // the big gate code is drawn in dark ink around the vertical centre
        assertTrue(darkPixels(img, 400, 700) > 20_000, "gate code should be visible");
    }

    @Test
    void unknownGateCardStillRenders() throws Exception {
        byte[] png = renderer.gateCard(new JourneyFacts.Flight(1, "AI173", "DEL", "SFO", "2026-06-06 00:05", null, null, null, false));
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img);
    }

    @Test
    void gateCodesAreSpacedForReading() {
        assertEquals("B 12", CardRenderer.spaced("b12"));
        assertEquals("D 40 A", CardRenderer.spaced("D40A"));
        assertEquals("T 2", CardRenderer.spaced("T2"));
    }

    private static int darkPixels(BufferedImage img, int fromY, int toY) {
        int n = 0;
        for (int y = fromY; y < toY; y += 2)
            for (int x = 0; x < img.getWidth(); x += 2)
                if ((img.getRGB(x, y) & 0xFF) < 0x40) n++;
        return n;
    }
}
