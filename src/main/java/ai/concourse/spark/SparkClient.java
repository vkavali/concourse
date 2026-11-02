package ai.concourse.spark;

import ai.concourse.config.ConcourseProperties;
import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Muse Spark via the OpenAI-compatible Chat Completions endpoint ({@code POST {base}/chat/completions}).
 * <ul>
 *   <li>Images: {@code {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}}</li>
 *   <li>PDFs: {@code {"type":"file","file":{"filename":"t.pdf","file_data":"data:application/pdf;base64,..."}}}
 *       (first 100 pages as text, first 50 as page images)</li>
 *   <li>Structured output: {@code response_format: {type: json_schema, json_schema: {name, schema}}} — constrained decoding,
 *       so the content always parses as JSON matching the schema</li>
 *   <li>Streaming (default): SSE deltas are aggregated; falls back to a blocking call if the stream fails</li>
 * </ul>
 * Standard tier model id only ({@code muse-spark-1.3}); never the {@code -contributor} tier (tickets must not train models).
 */
@Component
public class SparkClient {
    private static final Logger log = LoggerFactory.getLogger(SparkClient.class);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() {};

    public record SparkResponse(String content, long promptTokens, long completionTokens, String model) {}

    private final WebClient client;
    private final ObjectMapper mapper;
    private final String model;
    private final boolean stream;
    private final Duration timeout;
    private final boolean configured;
    private Integer maxTokens;          // optional body default (open-weight deployments count reasoning tokens here)
    private String reasoningEffort;     // optional body default
    private ModelCallLog callLog;   // optional
    private ModelTape tape;         // optional

    @Autowired // two constructors exist (the other is for tests); Spring needs to be told which one
    public SparkClient(ConcourseProperties props, WebClient.Builder builder, ObjectMapper mapper) {
        this(props.meta().baseUrl(), props.meta().apiKey(), props.meta().sparkModel(), props.meta().stream(),
                Duration.ofSeconds(props.meta().timeoutSeconds()), builder, mapper);
    }

    public SparkClient(String baseUrl, String apiKey, String model, boolean stream, Duration timeout,
                WebClient.Builder builder, ObjectMapper mapper) {
        this.mapper = mapper;
        this.model = model;
        this.stream = stream;
        this.timeout = timeout;
        this.configured = apiKey != null && !apiKey.isBlank();
        this.client = builder.baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout)))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)).build())
                .build();
    }

    public String model() { return model; }

    public boolean isConfigured() { return configured; }

    /** Body defaults sent with every request: {@code max_tokens} and {@code reasoning_effort} (null = omitted). */
    public SparkClient withBodyDefaults(Integer maxTokens, String reasoningEffort) {
        this.maxTokens = maxTokens;
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort;
        return this;
    }

    @Autowired(required = false)
    public void setCallLog(ModelCallLog callLog) { this.callLog = callLog; }

    @Autowired(required = false)
    public void setTape(ModelTape tape) { this.tape = tape; }

    /**
     * Runs the request; one retry on 429/5xx/transport errors. With the tape replaying, a recorded answer for the
     * same input is returned instead (and logged as a replay); with it recording, the live answer is saved.
     */
    public SparkResponse complete(SparkRequest req) {
        String key = tape != null && tape.mode() != ModelTape.Mode.OFF ? tapeKey(req) : null;
        if (tape != null && tape.replaying()) {
            Optional<ModelTape.Recording> hit = tape.recording("spark", key);
            if (hit.isPresent()) {
                note(req.op(), 0, true, "replayed from tape (" + hit.get().model() + ")");
                return new SparkResponse(hit.get().output(), 0, 0, hit.get().model() + " (recording)");
            }
            if (!configured) {
                note(req.op(), 0, false, "not on tape and no MODEL_API_KEY");
                throw new SparkException(0, "no MODEL_API_KEY and no recording on the tape for this input", false);
            }
        }
        if (!configured) {
            note(req.op(), 0, false, "no MODEL_API_KEY");
            throw new SparkException(0, "no MODEL_API_KEY", false);
        }
        long t0 = System.currentTimeMillis();
        SparkResponse res;
        try {
            res = live(req);
        } catch (SparkException e) {
            note(req.op(), System.currentTimeMillis() - t0, false, e.getMessage());
            throw e;
        }
        long ms = System.currentTimeMillis() - t0;
        note(req.op(), ms, true, res.promptTokens() + "+" + res.completionTokens() + " tokens");
        if (tape != null && tape.recording()) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("model", res.model());
            meta.put("op", req.op());
            meta.put("ms", ms);
            meta.put("prompt_tokens", res.promptTokens());
            meta.put("completion_tokens", res.completionTokens());
            tape.save("spark", key, meta, res.content());
        }
        return res;
    }

    private void note(String op, long ms, boolean ok, String note) {
        if (callLog != null) callLog.record(model, op, ms, ok, note);
    }

    /**
     * The recording key: the caller's input hash when given (per operation, not per model — so an answer recorded from
     * Spark Open replays even when the judge runs Spark 1.3 only), else the whole request. Never leaves the machine.
     */
    String tapeKey(SparkRequest req) {
        if (req.tapeKey() != null && !req.tapeKey().isBlank()) return ModelTape.sha256(req.op() + "|" + req.tapeKey());
        ObjectNode body = buildBody(req, false);
        body.remove("stream");
        body.remove("stream_options");
        return ModelTape.sha256(body.toString());
    }

    private SparkResponse live(SparkRequest req) {
        ObjectNode body = buildBody(req, stream);
        SparkException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                if (stream) {
                    try {
                        return completeStreaming(body);
                    } catch (SparkException e) {
                        if (e.status() == 400 || e.status() == 404 || e.status() == 415) {
                            log.warn("streaming rejected ({}); retrying blocking", e.status());
                            body.put("stream", false);
                            body.remove("stream_options");
                            return completeBlocking(body);
                        }
                        throw e;
                    }
                }
                return completeBlocking(body);
            } catch (SparkException e) {
                last = e;
                if (!e.retryable() || attempt == 2) throw e;
                log.warn("spark attempt {} failed ({}); retrying", attempt, e.getMessage());
                sleep(1500L * attempt);
            }
        }
        throw last;
    }

    /** Structured call: the content is parsed into {@code type} with the given mapper (lets callers use snake_case). */
    public <T> T completeJson(SparkRequest req, Class<T> type, ObjectMapper jsonMapper) {
        if (req.schema() == null) throw new IllegalArgumentException("completeJson needs a schema");
        SparkResponse res = complete(req);
        try {
            return jsonMapper.readValue(res.content(), type);
        } catch (Exception e) {
            throw new SparkException(0, "model output did not match " + type.getSimpleName() + ": " + e.getMessage(), false);
        }
    }

    public <T> T completeJson(SparkRequest req, TypeReference<T> type, ObjectMapper jsonMapper) {
        if (req.schema() == null) throw new IllegalArgumentException("completeJson needs a schema");
        SparkResponse res = complete(req);
        try {
            return jsonMapper.readValue(res.content(), type);
        } catch (Exception e) {
            throw new SparkException(0, "model output did not match " + type.getType() + ": " + e.getMessage(), false);
        }
    }

    // ---- request construction -------------------------------------------------------------------------------------

    ObjectNode buildBody(SparkRequest req, boolean streaming) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        if (req.temperature() != null) body.put("temperature", req.temperature());
        if (maxTokens != null) body.put("max_tokens", maxTokens);
        if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort);
        body.put("stream", streaming);
        if (streaming) body.putObject("stream_options").put("include_usage", true);

        ArrayNode messages = body.putArray("messages");
        if (req.system() != null && !req.system().isBlank()) {
            messages.addObject().put("role", "system").put("content", req.system());
        }
        ObjectNode user = messages.addObject().put("role", "user");
        ArrayNode parts = user.putArray("content");
        parts.addObject().put("type", "text").put("text", req.user() == null ? "" : req.user());
        List<SparkRequest.Attachment> attachments = req.attachments() == null ? List.of() : req.attachments();
        for (SparkRequest.Attachment a : attachments) {
            String dataUrl = "data:" + a.mimeType() + ";base64," + Base64.getEncoder().encodeToString(a.bytes());
            if (a.isPdf()) {
                ObjectNode f = parts.addObject().put("type", "file").putObject("file");
                f.put("filename", a.filename() == null ? "document.pdf" : a.filename());
                f.put("file_data", dataUrl);
            } else {
                parts.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl);
            }
        }
        if (req.schema() != null) {
            ObjectNode rf = body.putObject("response_format");
            rf.put("type", "json_schema");
            ObjectNode js = rf.putObject("json_schema");
            js.put("name", req.schema().name());
            js.set("schema", req.schema().schema());
        }
        return body;
    }

    // ---- transport ------------------------------------------------------------------------------------------------

    private SparkResponse completeBlocking(ObjectNode body) {
        try {
            JsonNode res = client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.error(new SparkException(r.statusCode().value(), b, true))))
                    .bodyToMono(JsonNode.class)
                    .block(timeout.plusSeconds(5));
            if (res == null) throw new SparkException(0, "empty response", true);
            JsonNode choice = res.path("choices").path(0);
            String content = choice.path("message").path("content").asText("");
            if (content.isEmpty() && choice.path("message").path("refusal").isTextual()) {
                throw new SparkException(0, "model refused: " + choice.path("message").path("refusal").asText(), false);
            }
            JsonNode usage = res.path("usage");
            return new SparkResponse(content, usage.path("prompt_tokens").asLong(0),
                    usage.path("completion_tokens").asLong(0), res.path("model").asText(model));
        } catch (SparkException e) {
            throw e;
        } catch (WebClientRequestException e) {
            throw new SparkException(0, "request failed: " + e.getMessage(), true);
        } catch (RuntimeException e) {
            throw new SparkException(0, "call failed: " + e.getMessage(), true);
        }
    }

    private SparkResponse completeStreaming(ObjectNode body) {
        StringBuilder content = new StringBuilder();
        long[] usage = new long[2];
        String[] seenModel = {model};
        try {
            client.post().uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, r -> r.bodyToMono(String.class).defaultIfEmpty("")
                            .flatMap(b -> Mono.error(new SparkException(r.statusCode().value(), b, true))))
                    .bodyToFlux(SSE)
                    .doOnNext(ev -> {
                        String data = ev.data();
                        if (data == null || data.isBlank() || "[DONE]".equals(data.strip())) return;
                        try {
                            JsonNode n = mapper.readTree(data);
                            JsonNode delta = n.path("choices").path(0).path("delta").path("content");
                            if (delta.isTextual()) content.append(delta.asText());
                            if (n.path("usage").isObject()) {
                                usage[0] = n.path("usage").path("prompt_tokens").asLong(usage[0]);
                                usage[1] = n.path("usage").path("completion_tokens").asLong(usage[1]);
                            }
                            if (n.path("model").isTextual()) seenModel[0] = n.path("model").asText();
                        } catch (Exception e) {
                            log.debug("unparseable SSE chunk ignored: {}", data);
                        }
                    })
                    .blockLast(timeout.plusSeconds(5));
        } catch (SparkException e) {
            throw e;
        } catch (WebClientRequestException e) {
            throw new SparkException(0, "request failed: " + e.getMessage(), true);
        } catch (RuntimeException e) {
            throw new SparkException(0, "stream failed: " + e.getMessage(), true);
        }
        if (content.isEmpty()) throw new SparkException(0, "empty streamed response", true);
        return new SparkResponse(content.toString(), usage[0], usage[1], seenModel[0]);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static class SparkException extends RuntimeException {
        private final int status;
        private final boolean retryable;

        public SparkException(int status, String message, boolean transportLevel) {
            super("Spark HTTP " + status + ": " + message);
            this.status = status;
            this.retryable = transportLevel && (status == 0 || status == 429 || status >= 500);
        }

        public int status() { return status; }
        public boolean retryable() { return retryable; }
    }
}
