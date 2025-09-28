package uk.gegc.quizmaker.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return (Flyway flyway) -> {
            try {
                log.info("Running Flyway repair to clear failed migrations if present...");
                flyway.repair();
            } catch (Exception e) {
                log.warn("Flyway repair encountered an issue: {}", e.getMessage());
            }

            log.info("Running Flyway migrations...");
            flyway.migrate();
        };
    }
}

