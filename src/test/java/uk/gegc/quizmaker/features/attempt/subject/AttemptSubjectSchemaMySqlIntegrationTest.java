package uk.gegc.quizmaker.features.attempt.subject;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptSubjectServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectEntity;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubjectRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAttemptSubjectRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Each scenario owns a new catalog: no shared-schema create/drop or prior Spring context. */
@Tag("db-serial")
class AttemptSubjectSchemaMySqlIntegrationTest {
    @Test
    void fullFlywayV86SchemaValidatesRealMappingsWithoutRecreatingLegacyTables() {
        try (var schema = new OwnedSchema()) {
            Flyway.configure().dataSource(schema.source).locations("classpath:db/migration")
                    .target("86").load().migrate();
            assertThat(schema.sql.queryForObject("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("86");
            assertThat(foreignKeyCount(schema.sql, "fk_quiz_creator")).isEqualTo(1);
            assertThat(foreignKeyCount(schema.sql, "fk_attempt_subject_user")).isEqualTo(1);
            var before = schema.sql.queryForMap("SHOW CREATE TABLE users");
            var precision = userTimePrecision(schema.sql);
            var factory = factory(schema.source, "validate", true);
            try {
                // These are the real application mappings, not fixture entity substitutes.
                assertThat(factory.getObject().getMetamodel().getEntities()).hasSize(4);
                assertThat(schema.sql.queryForMap("SHOW CREATE TABLE users")).isEqualTo(before);
                assertThat(userTimePrecision(schema.sql)).isEqualTo(precision);
                assertThat(foreignKeyCount(schema.sql, "fk_quiz_creator")).isEqualTo(1);
            } finally { factory.destroy(); }
        }
    }

    @Test
    void controlledCreateDropRecreatesOwnedRowsAndRetainedFkThenDropsBothTables() {
        try (var schema = new OwnedSchema()) {
            prepareRetainedBinding(schema);
            var factory = factory(schema.source, "create-drop", true);
            try {
                assertThat(userTimePrecision(schema.sql)).isEqualTo(6);
                assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isZero();
                assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class)).isZero();
                assertThat(foreignKeyCount(schema.sql, "fk_attempt_subject_user")).isEqualTo(1);
                UUID user = insertUser(schema.sql);
                var binding = AttemptSubject.authenticated(UUID.randomUUID(), user);
                var transactions = new JpaTransactionManager(factory.getObject());
                transactions.setDataSource(schema.source);
                var proxy = new ProxyFactory(new JdbcAttemptSubjectRepository(new NamedParameterJdbcTemplate(schema.source)));
                proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
                var metrics = new SimpleMeterRegistry();
                try {
                    var service = new AttemptSubjectServiceImpl((AttemptSubjectRepository) proxy.getProxy(), transactions, metrics);
                    assertThat(service.bindOrReplay(user, binding)).isEqualTo(binding);
                    assertThat(service.read(binding.attemptId())).isEqualTo(new AttemptSubjectLookup.Bound(binding));
                } finally { metrics.close(); }
            } finally { factory.destroy(); }
            assertThat(schema.sql.queryForObject("""
                    SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()
                    AND table_name IN ('users','attempt_participant_subject')
                    """, Integer.class)).isZero();
        }
    }

    @Test
    void controlledCreateDropFailsIfTheRetainedSubjectFkIsNotMapped() {
        try (var schema = new OwnedSchema()) {
            prepareRetainedBinding(schema);
            // The negative control makes silent Hibernate DDL warnings a test failure.
            assertThatThrownBy(() -> factory(schema.source, "create-drop", false))
                    .isInstanceOf(RuntimeException.class).hasStackTraceContaining("fk_attempt_subject_user");
        }
    }

    private static void prepareRetainedBinding(OwnedSchema schema) {
        var userFactory = factory(schema.source, "create-only", false);
        userFactory.destroy(); // No preliminary drop in a new catalog; retain the prepared schema.
        Flyway.configure().dataSource(schema.source).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("85").target("86").load().migrate();
        UUID user = insertUser(schema.sql);
        schema.sql.update("INSERT INTO attempt_participant_subject VALUES (UUID_TO_BIN(?),1,'AUTHENTICATED_USER',UUID_TO_BIN(?))",
                UUID.randomUUID().toString(), user.toString());
        assertThat(foreignKeyCount(schema.sql, "fk_attempt_subject_user")).isEqualTo(1);
        assertThat(schema.sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class)).isEqualTo(1);
    }

    private static LocalContainerEntityManagerFactoryBean factory(DriverManagerDataSource source, String action, boolean withSubject) {
        var names = new ArrayList<>(List.of(User.class.getName(), Role.class.getName(), Permission.class.getName()));
        if (withSubject) names.add(AttemptSubjectEntity.class.getName());
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(names, List.of()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", action, "hibernate.hbm2ddl.halt_on_error", "true"));
        try { factory.afterPropertiesSet(); return factory; }
        catch (RuntimeException failure) { factory.destroy(); throw failure; }
    }

    private static UUID insertUser(JdbcTemplate sql) {
        UUID id = UUID.randomUUID();
        var time = java.sql.Timestamp.valueOf("2026-01-02 12:00:00");
        sql.update("""
                INSERT INTO users (user_id,username,email,password,is_active,is_deleted,
                    email_verified,created_at,password_changed_at,version)
                VALUES (UUID_TO_BIN(?),?,?,?,TRUE,FALSE,FALSE,?,?,0)
                """, id.toString(), "subject-" + id, id + "@example.invalid", "synthetic-unused", time, time);
        return id;
    }

    private static int foreignKeyCount(JdbcTemplate sql, String name) {
        return sql.queryForObject("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND constraint_name=?",
                Integer.class, name);
    }

    private static int userTimePrecision(JdbcTemplate sql) {
        return sql.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='password_changed_at'", Integer.class);
    }

    /** Administrative credentials belong only to disposable local/CI MySQL, never the app. */
    private static final class OwnedSchema implements AutoCloseable {
        private final JdbcTemplate admin;
        private final String name = "quizmaker_535_schema_" + UUID.randomUUID().toString().replace("-", "");
        private final DriverManagerDataSource source;
        private final JdbcTemplate sql;

        OwnedSchema() {
            String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
            if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
            String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
            String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
            String base = "jdbc:mysql://127.0.0.1:" + port + "/";
            String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
            // The generated identifier is test-owned; CREATE must fail rather than reuse a catalog.
            admin.execute("CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            source = new DriverManagerDataSource(base + name + options, user, password);
            sql = new JdbcTemplate(source);
        }

        @Override
        public void close() { admin.execute("DROP DATABASE " + name); }
    }
}
