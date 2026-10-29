package ai.concourse.card;

import ai.concourse.journey.JourneyFacts;
import ai.concourse.journey.StageType;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignCardRendererTest {
    private final SignCardRenderer renderer = new SignCardRenderer();
    private final JourneyFacts.Flight flight = new JourneyFacts.Flight(2, "Qatar Airways", "QR729", "DOH", "DFW", "2026-11-28 08:40", null, "D", "C7", true);

    @Test
    void everySignStageRendersACleanCardAndGateStagesHaveNone() throws Exception {
        EnumSet<StageType> withSign = EnumSet.of(StageType.CHECK_IN, StageType.SECURITY, StageType.TRANSFER_ARRIVAL, StageType.US_ARRIVAL,
                StageType.IMMIGRATION, StageType.BAGGAGE, StageType.EXIT, StageType.FAMILY_HANDOFF);
        for (StageType t : StageType.values()) {
            byte[] png = renderer.signCard(t, flight);
            if (!withSign.contains(t)) {
                assertNull(png, t + " has no sign card (gate stages use the gate card)");
                continue;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            assertEquals(1080, img.getWidth());
            for (int y = 570; y < img.getHeight(); y += 3) {
                for (int x : new int[]{0, 10, 20, img.getWidth() - 21, img.getWidth() - 11, img.getWidth() - 1}) {
                    assertEquals(0xFFFFFF, img.getRGB(x, y) & 0xFFFFFF, t + ": ink at the edge (" + x + "," + y + ")");
                }
            }
        }
    }

    @Test
    void wordsAreWhatTheBoardsPrint() {
        assertEquals("TRANSFER", SignCardRenderer.specFor(StageType.TRANSFER_ARRIVAL, flight).word());
        assertEquals(List.of("CONNECTIONS", "FLIGHT CONNECTIONS"), SignCardRenderer.specFor(StageType.TRANSFER_ARRIVAL, flight).alsoSeenAs());
        assertEquals(List.of("BAGGAGE RECLAIM", "FLIGHT QR729"), SignCardRenderer.specFor(StageType.BAGGAGE, flight).alsoSeenAs());
        assertEquals(List.of("QATAR AIRWAYS", "DEPARTURES"), SignCardRenderer.specFor(StageType.CHECK_IN, flight).alsoSeenAs());
        assertEquals("EXIT", SignCardRenderer.specFor(StageType.FAMILY_HANDOFF, flight).word());
        assertNull(SignCardRenderer.specFor(StageType.WAITING_AT_ORIGIN, flight));
        assertNotNull(SignCardRenderer.specFor(StageType.BAGGAGE, null), "no flight on file still yields a card");
    }
}
