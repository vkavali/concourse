package ai.concourse.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads {@code .env} from the working directory (or {@code CONCOURSE_ENV_FILE}) as the lowest-precedence property source,
 * so `./gradlew bootRun` and an IDE run both see the same values without exporting anything. Real environment
 * variables and JVM properties still win. Registered in META-INF/spring.factories.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        Path file = Path.of(System.getProperty("CONCOURSE_ENV_FILE", System.getenv().getOrDefault("CONCOURSE_ENV_FILE", ".env")));
        if (!Files.isRegularFile(file)) return;
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).strip();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isEmpty()) values.put(key, value);
            }
        } catch (IOException e) {
            return;
        }
        if (!values.isEmpty()) {
            env.getPropertySources().addLast(new MapPropertySource("dotenv", values));
            System.out.println("concourse: loaded " + values.size() + " values from " + file.toAbsolutePath());
        }
    }
}
