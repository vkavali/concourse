package ai.concourse.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hosts (Railway, Heroku, Render, Fly's Postgres) hand the app one {@code DATABASE_URL} like
 * {@code postgresql://user:pass@host:5432/db}. Spring wants a JDBC URL plus separate credentials, so this turns the
 * former into {@code spring.datasource.url/username/password}. A {@code jdbc:} URL is left alone. Runs after the
 * .env loader so a {@code DATABASE_URL} from {@code .env} is converted too.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String url = env.getProperty("DATABASE_URL");
        if (url == null || url.isBlank() || url.startsWith("jdbc:")) return;
        Map<String, Object> converted = convert(url.strip());
        if (converted.isEmpty()) return;
        env.getPropertySources().addFirst(new MapPropertySource("databaseUrl", converted));
        System.out.println("concourse: DATABASE_URL converted to " + converted.get("spring.datasource.url"));
    }

    /** {@code postgres://u:p@h:5432/db?sslmode=require} → url/username/password; empty map if it is not a Postgres URL. */
    static Map<String, Object> convert(String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return out;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("postgres") || scheme.equals("postgresql"))) return out;
        if (uri.getHost() == null) return out;
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        out.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + path + query);
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            String user = colon < 0 ? userInfo : userInfo.substring(0, colon);
            out.put("spring.datasource.username", URLDecoder.decode(user, StandardCharsets.UTF_8));
            if (colon >= 0) out.put("spring.datasource.password", URLDecoder.decode(userInfo.substring(colon + 1), StandardCharsets.UTF_8));
        }
        return out;
    }
}
