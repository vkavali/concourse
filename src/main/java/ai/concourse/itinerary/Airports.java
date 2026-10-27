package ai.concourse.itinerary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * IATA code → airport (name, city, country, time zone). Source: OpenFlights airports database (ODbL), filtered to
 * airports with an IATA code; rows with no IANA zone carry a fixed UTC offset. Loaded once from {@code data/airports.csv}.
 * This table is "verified fact" infrastructure: it turns printed local times into absolute instants, so Spark never has to.
 */
@Component
public class Airports {
    private static final Logger log = LoggerFactory.getLogger(Airports.class);

    public record Airport(String iata, String name, String city, String country, ZoneId zone) {}

    private final Map<String, Airport> byIata = new HashMap<>();

    public Airports() {
        this("data/airports.csv");
    }

    Airports(String classpathCsv) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource(classpathCsv).getInputStream(), StandardCharsets.UTF_8))) {
            String line = r.readLine(); // header
            int skipped = 0;
            while ((line = r.readLine()) != null) {
                String[] f = line.split(",", -1);
                if (f.length != 5) { skipped++; continue; }
                try {
                    byIata.put(f[0], new Airport(f[0], f[1], f[2], f[3], ZoneId.of(f[4])));
                } catch (Exception e) {
                    skipped++;
                }
            }
            log.info("airports loaded: {} (skipped {})", byIata.size(), skipped);
        } catch (IOException e) {
            throw new IllegalStateException("cannot load " + classpathCsv, e);
        }
    }

    public Optional<Airport> find(String iata) {
        if (iata == null) return Optional.empty();
        return Optional.ofNullable(byIata.get(iata.trim().toUpperCase(Locale.ROOT)));
    }

    public boolean exists(String iata) {
        return find(iata).isPresent();
    }

    public int size() {
        return byIata.size();
    }
}
