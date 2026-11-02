package ai.concourse.evidence;

import ai.concourse.config.ConcourseProperties;
import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>SAM 3.1</b> on the Meta Model API ({@code POST /v1/responses}, model {@code sam-3.1}): given a short noun phrase
 * and the photo, it returns one box and one mask per matching object as special-token text
 * ({@code <0f>0 <|box;x1=..;y1=..;x2=..;y2=..;w=..;h=..;c=..|> <|mask;...|>, ...}). Concourse asks it for the
 * <i>sign</i> in her frame so the travel screen can outline the board she should be reading. The box is a highlight,
 * never a direction: Spark still reads the whole photo and the reconciler still decides.
 * <p>
 * Failures never reach her: a call that errors or times out yields no box, and after three failures in a row the
 * client stays quiet for ten minutes so a broken model cannot slow every frame.
 */
@Component
public class SamClient {
    private static final Logger log = LoggerFactory.getLogger(SamClient.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {};
    private static final Pattern BOX = Pattern.compile("<\\|box;([^|>]*)\\|>");

    /** One detection in source pixels, with the frame size it was measured in. */
    public record Box(int x1, int y1, int x2, int y2, int frameW, int frameH, Double confidence) {
        public int area() { return Math.max(0, x2 - x1) * Math.max(0, y2 - y1); }
        /** Normalised to 0..1 of the frame: {x, y, w, h}. */
        public Map<String, Double> normalised() {
            Map<String, Double> m = new LinkedHashMap<>();
            m.put("x", round(x1 / (double) frameW));
            m.put("y", round(y1 / (double) frameH));
            m.put("w", round((x2 - x1) / (double) frameW));
            m.put("h", round((y2 - y1) / (double) frameH));
            if (confidence != null) m.put("c", round(confidence));
            return m;
        }
        private static double round(double v) { return Math.round(v * 1000) / 1000.0; }
    }

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String model;
    private final String concept;
    private final boolean configured;
    private final Duration timeout;
    private ModelCallLog callLog; // optional
    private ModelTape tape;       // optional
    private int failures;
    private volatile long quietUntil;

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public SamClient(ConcourseProperties props, WebClient.Builder builder, ObjectMapper mapper) {
        this(props.meta().baseUrl(), props.meta().apiKey(), props.meta().samModel(), props.meta().samConcept(),
                props.meta().samEnabled(), Duration.ofSeconds(20), builder, mapper);
    }

    public SamClient(String baseUrl, String apiKey, String model, String concept, boolean enabled, Duration timeout,
                     WebClient.Builder builder, ObjectMapper mapper) {
        this.mapper = mapper;
        this.model = model == null || model.isBlank() ? "sam-3.1" : model;
        this.concept = concept == null || concept.isBlank() ? "sign" : concept;
        this.configured = enabled && apiKey != null && !apiKey.isBlank();
        this.timeout = timeout;
        this.client = builder.baseUrl(baseUrl == null ? "http://localhost:0" : baseUrl)
                .defaultHeader("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .exchangeStrategies(ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)).build())
                .build();
    }

    @Autowired(required = false)
    public void setCallLog(ModelCallLog callLog) { this.callLog = callLog; }

    @Autowired(required = false)
    public void setTape(ModelTape tape) { this.tape = tape; }

    public boolean isConfigured() { return configured; }

    public String model() { return model; }

    /** The most likely sign in the photo, or nothing — never an exception. */
    public Optional<Box> locateSign(byte[] image, String mimeType) {
        return locate(image, mimeType, concept);
    }

    public Optional<Box> locate(byte[] image, String mimeType, String what) {
        if (image == null || image.length == 0) return Optional.empty();
        String key = tape != null && tape.mode() != ModelTape.Mode.OFF ? ModelTape.sha256(what + "|" + ModelTape.sha256(image)) : null;
        if (tape != null && tape.replaying()) {
            Optional<ModelTape.Recording> hit = tape.recording("sam", key);
            if (hit.isPresent()) {
                record(0, true, "replayed from tape");
                return best(parse(hit.get().output()));
            }
            if (!configured) return Optional.empty();
        }
        if (!configured || System.currentTimeMillis() < quietUntil) return Optional.empty();
        long t0 = System.currentTimeMillis();
        try {
            String text = call(image, mimeType == null ? "image/jpeg" : mimeType, what);
            List<Box> boxes = parse(text);
            long ms = System.currentTimeMillis() - t0;
            failures = 0;
            record(ms, true, boxes.size() + " " + what + (boxes.size() == 1 ? "" : "s") + " found");
            if (tape != null && tape.recording()) {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("model", model);
                meta.put("op", "segment");
                meta.put("ms", ms);
                meta.put("boxes", boxes.size());
                tape.save("sam", key, meta, text);
            }
            return best(boxes);
        } catch (RuntimeException e) {
            long ms = System.currentTimeMillis() - t0;
            record(ms, false, e.getMessage());
            if (++failures >= 3) {
                quietUntil = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
                failures = 0;
                log.warn("sam failed three times ({}); not asking again for ten minutes", e.getMessage());
            } else {
                log.warn("sam call failed ({}); no box this time", e.getMessage());
            }
            return Optional.empty();
        }
    }

    /** Highest confidence when the model reports it, else the largest box. */
    static Optional<Box> best(List<Box> boxes) {
        if (boxes.isEmpty()) return Optional.empty();
        boolean scored = boxes.stream().allMatch(b -> b.confidence() != null);
        return boxes.stream().max(scored
                ? Comparator.comparingDouble((Box b) -> b.confidence()).thenComparingInt(Box::area)
                : Comparator.comparingInt(Box::area));
    }

    /** Every {@code <|box;…|>} token in the first frame's line, by key — never by position. */
    static List<Box> parse(String text) {
        List<Box> out = new ArrayList<>();
        if (text == null) return out;
        String first = text;
        int nextFrame = text.indexOf("<1f>");
        if (nextFrame >= 0) first = text.substring(0, nextFrame);
        Matcher m = BOX.matcher(first);
        while (m.find()) {
            Map<String, String> kv = new LinkedHashMap<>();
            for (String part : m.group(1).split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) kv.put(part.substring(0, eq).strip(), part.substring(eq + 1).strip());
            }
            try {
                int x1 = Integer.parseInt(kv.get("x1")), y1 = Integer.parseInt(kv.get("y1"));
                int x2 = Integer.parseInt(kv.get("x2")), y2 = Integer.parseInt(kv.get("y2"));
                int w = Integer.parseInt(kv.get("w")), h = Integer.parseInt(kv.get("h"));
                Double c = kv.containsKey("c") ? Double.parseDouble(kv.get("c")) : null;
                if (w > 0 && h > 0 && x2 > x1 && y2 > y1) out.add(new Box(x1, y1, x2, y2, w, h, c));
            } catch (RuntimeException ignored) {
                // a malformed token is skipped, never a failure
            }
        }
        return out;
    }

    // ---- transport: Responses API, streamed special-token text ----------------------------------------------------

    private String call(byte[] image, String mimeType, String what) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        ObjectNode meta = body.putObject("metadata");
        meta.put("include_confidence", "true");
        meta.put("mask_encoding", "one_bit");
        ArrayNode input = body.putArray("input");
        ObjectNode msg = input.addObject();
        msg.put("type", "message");
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        content.addObject().put("type", "input_text").put("text", what);
        content.addObject().put("type", "input_image").put("image_url", "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(image));
        StringBuilder text = new StringBuilder();
        String[] done = {null};
        try {
            client.post().uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.error(new IllegalStateException("SAM HTTP " + r.statusCode().value() + ": " + b))))
                    .bodyToFlux(SSE)
                    .doOnNext(ev -> {
                        String data = ev.data();
                        if (data == null || data.isBlank() || "[DONE]".equals(data.strip())) return;
                        try {
                            JsonNode n = mapper.readTree(data);
                            String type = n.path("type").asText("");
                            if (type.endsWith("output_text.delta") && n.path("delta").isTextual()) text.append(n.path("delta").asText());
                            else if (type.endsWith("output_text.done") && n.path("text").isTextual()) done[0] = n.path("text").asText();
                            else if (n.path("output").isArray()) done[0] = outputText(n); // a non-streamed body
                        } catch (Exception e) {
                            log.debug("unparseable SAM event ignored: {}", data);
                        }
                    })
                    .blockLast(timeout.plusSeconds(5));
        } catch (RuntimeException e) {
            throw new IllegalStateException("sam call failed: " + e.getMessage(), e);
        }
        return done[0] != null ? done[0] : text.toString();
    }

    private static String outputText(JsonNode response) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            for (JsonNode c : item.path("content")) {
                if (c.path("text").isTextual()) sb.append(c.path("text").asText());
            }
        }
        return sb.toString();
    }

    private void record(long ms, boolean ok, String note) {
        if (callLog != null) callLog.record(model, "segment " + concept, ms, ok, note);
    }
}
