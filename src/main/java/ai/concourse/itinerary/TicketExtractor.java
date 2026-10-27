package ai.concourse.itinerary;

import ai.concourse.spark.PromptLoader;
import ai.concourse.spark.SparkClient;
import ai.concourse.spark.SparkRequest;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Ticket PDF/image → {@link Itinerary} through Muse Spark with constrained JSON output. Nothing is stored here. */
@Component
public class TicketExtractor {
    private static final Logger log = LoggerFactory.getLogger(TicketExtractor.class);
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif");
    private static final String USER_TEXT = "Extract the flight itinerary from the attached document.";

    private final SparkClient spark;
    private final PromptLoader prompts;
    private final ObjectMapper snakeCase;

    public TicketExtractor(SparkClient spark, PromptLoader prompts, ObjectMapper mapper) {
        this.spark = spark;
        this.prompts = prompts;
        this.snakeCase = mapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    public boolean supports(String mimeType) {
        String mt = normalize(mimeType);
        return "application/pdf".equals(mt) || IMAGE_TYPES.contains(mt);
    }

    public Itinerary extract(byte[] bytes, String mimeType, String filename) {
        String mt = normalize(mimeType);
        if (!supports(mt)) throw new IllegalArgumentException("unsupported ticket type: " + mimeType);
        SparkRequest req = new SparkRequest(
                prompts.prompt("ticket_extract"),
                USER_TEXT,
                List.of(new SparkRequest.Attachment(mt, bytes, filename == null ? defaultName(mt) : filename)),
                new SparkRequest.JsonSchema("itinerary", prompts.schema("ticket_extract")),
                0.0);
        Itinerary it = spark.completeJson(req, Itinerary.class, snakeCase);
        log.info("ticket extracted: isTicket={} segments={}", it.isTicket(), it.segmentsOrEmpty().size());
        return it;
    }

    private static String normalize(String mimeType) {
        if (mimeType == null) return "";
        String mt = mimeType.strip().toLowerCase(Locale.ROOT);
        int semi = mt.indexOf(';');
        return semi > 0 ? mt.substring(0, semi).strip() : mt;
    }

    private static String defaultName(String mt) {
        return "application/pdf".equals(mt) ? "ticket.pdf" : "ticket." + mt.substring(mt.indexOf('/') + 1);
    }
}
