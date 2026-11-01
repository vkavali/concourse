package ai.concourse.spark;

import ai.concourse.config.ConcourseProperties;
import ai.concourse.ops.ModelCallLog;
import ai.concourse.ops.ModelTape;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Optional;

/**
 * Meta's open-weight <b>Muse Spark Open</b> through partner inference (Baseten, OpenAI-compatible), used for the
 * small, frequent call — classifying what she said — with Spark 1.3 answering whenever it fails. Off until
 * {@code SPARK_OPEN_BASE_URL} is set. Blocking calls (no SSE), a short reasoning budget and a fixed
 * {@code max_tokens}: on this deployment reasoning tokens count against the output limit.
 */
@Component
public class SparkOpen {
    private static final Logger log = LoggerFactory.getLogger(SparkOpen.class);
    private final SparkClient client; // null when off
    private final String model;

    public SparkOpen(ConcourseProperties props, WebClient.Builder builder, ObjectMapper mapper,
                     ObjectProvider<ModelCallLog> calls, ObjectProvider<ModelTape> tape) {
        ConcourseProperties.Meta m = props.meta();
        this.model = m.openModel();
        if (m.openBaseUrl() == null || m.openBaseUrl().isBlank()) {
            this.client = null;
            return;
        }
        SparkClient c = new SparkClient(m.openBaseUrl(), m.openApiKey() == null ? "" : m.openApiKey(), m.openModel(), false,
                Duration.ofSeconds(60), builder, mapper).withBodyDefaults(m.openMaxTokens(), m.openReasoningEffort());
        calls.ifAvailable(c::setCallLog);
        tape.ifAvailable(c::setTape);
        this.client = c;
        log.info("spark open on: {} at {}", m.openModel(), m.openBaseUrl());
    }

    public boolean enabled() { return client != null; }

    public String model() { return model; }

    public Optional<SparkClient> client() { return Optional.ofNullable(client); }
}
