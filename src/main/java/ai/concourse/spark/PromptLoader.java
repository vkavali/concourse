package ai.concourse.spark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Prompts and JSON schemas live as versioned files under {@code src/main/resources/prompts/}. */
@Component
public class PromptLoader {
    private final ObjectMapper mapper;
    private final Map<String, String> texts = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> schemas = new ConcurrentHashMap<>();

    public PromptLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** {@code prompt("ticket_extract")} → contents of {@code prompts/ticket_extract.md}. */
    public String prompt(String name) {
        return texts.computeIfAbsent(name, n -> read("prompts/" + n + ".md"));
    }

    /** {@code schema("ticket_extract")} → parsed {@code prompts/ticket_extract.schema.json}. */
    public JsonNode schema(String name) {
        return schemas.computeIfAbsent(name, n -> {
            try {
                return mapper.readTree(read("prompts/" + n + ".schema.json"));
            } catch (IOException e) {
                throw new IllegalStateException("invalid schema " + n, e);
            }
        });
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("missing resource " + path, e);
        }
    }
}
