package ai.concourse.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseUrlEnvironmentPostProcessorTest {
    @Test
    void railwayStyleUrlBecomesJdbcPlusCredentials() {
        Map<String, Object> m = DatabaseUrlEnvironmentPostProcessor.convert("postgresql://postgres:p%40ss@shuttle.proxy.rlwy.net:31234/railway");
        assertEquals("jdbc:postgresql://shuttle.proxy.rlwy.net:31234/railway", m.get("spring.datasource.url"));
        assertEquals("postgres", m.get("spring.datasource.username"));
        assertEquals("p@ss", m.get("spring.datasource.password"));
    }

    @Test
    void defaultsPortAndKeepsQuery() {
        Map<String, Object> m = DatabaseUrlEnvironmentPostProcessor.convert("postgres://u:p@db.internal/concourse?sslmode=require");
        assertEquals("jdbc:postgresql://db.internal:5432/concourse?sslmode=require", m.get("spring.datasource.url"));
    }

    @Test
    void leavesOtherUrlsAlone() {
        assertTrue(DatabaseUrlEnvironmentPostProcessor.convert("jdbc:h2:file:./data/concourse").isEmpty());
        assertTrue(DatabaseUrlEnvironmentPostProcessor.convert("mysql://u:p@h/db").isEmpty());
        assertTrue(DatabaseUrlEnvironmentPostProcessor.convert("not a url").isEmpty());
    }
}
