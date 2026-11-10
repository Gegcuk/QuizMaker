package uk.gegc.quizmaker.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/** Hibernate compatibility fixtures still need the actual Flyway-owned trigger implementation. */
@TestConfiguration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
public class LegacyAnswerMembershipTestConfig {
    @Bean
    @DependsOn("entityManagerFactory")
    InitializingBean legacyAnswerMembershipTriggers(DataSource dataSource) {
        return () -> {
            String url;
            try (var connection = dataSource.getConnection()) { url = connection.getMetaData().getURL(); }
            var admin = new DriverManagerDataSource(url,
                    System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root"),
                    System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root"));
            // Only this helper's history is reset. db-serial contexts recreate the mapped
            // tables (and therefore their triggers), but do not drop unmapped history tables.
            new JdbcTemplate(admin).execute("DROP TABLE IF EXISTS legacy_answer_membership_test_history");
            Flyway.configure().dataSource(admin).table("legacy_answer_membership_test_history")
                    .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("107").target("108")
                    .load().migrate();
        };
    }
}
