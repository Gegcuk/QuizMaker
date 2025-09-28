package uk.gegc.quizmaker.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy(DataSource dataSource) {
        return (Flyway flyway) -> {
            // Pre-flight: ensure critical tables exist to allow older migrations to run safely
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                log.info("Ensuring base table 'quiz_generation_jobs' exists before migration...");
                String sql = "CREATE TABLE IF NOT EXISTS quiz_generation_jobs (" +
                        "id BINARY(16) NOT NULL, " +
                        "document_id BINARY(16) NOT NULL, " +
                        "username VARCHAR(50) NOT NULL, " +
                        "status ENUM('PENDING','PROCESSING','COMPLETED','FAILED','CANCELLED') NOT NULL DEFAULT 'PENDING', " +
                        "started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (id)" +
                        ") ENGINE=InnoDB";
                st.execute(sql);
            } catch (SQLException e) {
                log.warn("Pre-flight DDL for 'quiz_generation_jobs' skipped: {}", e.getMessage());
            }

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
