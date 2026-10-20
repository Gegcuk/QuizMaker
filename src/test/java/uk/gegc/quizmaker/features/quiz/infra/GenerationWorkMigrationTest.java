package uk.gegc.quizmaker.features.quiz.infra;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@JdbcTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.flyway.enabled=false")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("db-serial")
class GenerationWorkMigrationTest {
    @Autowired JdbcTemplate jdbc;

    @Test void additiveMigrationPreservesLegacyRowsAndEnforcesIdentityLimitsAndCascade() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String docs = "source_" + suffix, jobs = "jobs_" + suffix, work = "quiz_work_" + suffix;
        try {
            jdbc.execute("CREATE TABLE " + docs + " (id BINARY(16) PRIMARY KEY, marker VARCHAR(32)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE " + jobs + " (id BINARY(16) PRIMARY KEY, status VARCHAR(16)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO " + docs + " VALUES (UUID_TO_BIN(?), 'unchanged')", UUID.randomUUID().toString());
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO " + jobs + " VALUES (UUID_TO_BIN(?), 'PROCESSING')", id);
            // Execute the actual migration under unique table/constraint names so this test
            // cannot drop tables owned by another shared-schema migration fixture.
            String sql = new ClassPathResource("db/migration/V79__create_durable_generation_work.sql")
                    .getContentAsString(StandardCharsets.UTF_8)
                    .replace("documents", docs).replace("quiz_generation_jobs", jobs)
                    .replace("generation_work", "work_" + suffix);
            try (var connection = jdbc.getDataSource().getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
            assertThat(jdbc.queryForObject("SELECT content_revision FROM " + docs, Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT marker FROM " + docs, String.class)).isEqualTo("unchanged");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + work, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM " + jobs, String.class)).isEqualTo("PROCESSING");
            jdbc.update("INSERT INTO " + work + " (job_id,source_revision,state,created_at) VALUES (UUID_TO_BIN(?),0,'READY',NOW(6))", id);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO " + work + " (job_id,source_revision,state,created_at) VALUES (UUID_TO_BIN(?),0,'READY',NOW(6))", id))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE " + work + " SET executions=3"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            jdbc.update("DELETE FROM " + jobs);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + work, Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + work);
            jdbc.execute("DROP TABLE IF EXISTS " + jobs);
            jdbc.execute("DROP TABLE IF EXISTS " + docs);
        }
    }
}
