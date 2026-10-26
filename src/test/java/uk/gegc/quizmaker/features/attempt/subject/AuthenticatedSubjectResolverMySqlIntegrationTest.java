package uk.gegc.quizmaker.features.attempt.subject;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.application.impl.AuthenticatedSubjectResolverImpl;
import uk.gegc.quizmaker.features.auth.infra.security.UserDetailsServiceImpl;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException.Reason.INVALID;
import static uk.gegc.quizmaker.features.attempt.subject.AuthenticatedSubjectResolverTest.*;

/** Real user query, JPA transactions, current principal shape and SQL counts; no target start is claimed. */
@Tag("db-serial")
class AuthenticatedSubjectResolverMySqlIntegrationTest {
    private static JdbcTemplate admin;
    private static JdbcTemplate sql;
    private static String catalog;
    private static LocalContainerEntityManagerFactoryBean factory;
    private static UserRepository users;
    private static AuthenticatedUserLookup lookup;
    private static SessionFactory sessions;
    private SimpleMeterRegistry metrics;
    private AuthenticatedSubjectResolver resolver;

    @BeforeAll
    static void prepareOwnedCatalog() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        catalog = "quizmaker_536_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + catalog + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        var source = new DriverManagerDataSource(base + catalog + options, user, password);
        sql = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setManagedTypes(PersistenceManagedTypes.of(
                List.of(User.class.getName(), Role.class.getName(), Permission.class.getName()), List.of()));
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.generate_statistics", "true"));
        factory.afterPropertiesSet();
        var entityManagers = factory.getObject();
        sessions = entityManagers.unwrap(SessionFactory.class);
        users = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(entityManagers))
                .getRepository(UserRepository.class);
        var proxy = new ProxyFactory(new AuthenticatedUserLookupImpl(users));
        proxy.addAdvice(new TransactionInterceptor(new JpaTransactionManager(entityManagers), new AnnotationTransactionAttributeSource()));
        lookup = (AuthenticatedUserLookup) proxy.getProxy();
    }

    @AfterAll
    static void removeOwnedCatalog() {
        try { if (factory != null) factory.destroy(); }
        finally { if (admin != null && catalog != null) admin.execute("DROP DATABASE " + catalog); }
    }

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        resolver = new AuthenticatedSubjectResolverImpl(lookup, metrics);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        metrics.close();
    }

    @Test
    void realCurrentPrincipalResolvesAndNoAttemptOrBindingIsWritten() {
        var fixture = user(true, false);
        // This is the same principal producer called by JwtTokenService, not a mocked principal.
        var principal = new UserDetailsServiceImpl(users).loadUserByUsername(fixture.name());
        authenticate(token(principal));
        long attempts = count("attempts"), bindings = count("attempt_participant_subject");
        UUID attempt = UUID.randomUUID();
        var subject = resolver.resolve(attempt);
        assertThat(subject.attemptId()).isEqualTo(attempt);
        assertThat(subject.userId()).isEqualTo(fixture.id());
        assertThat(subject.schemaVersion()).isEqualTo(1);
        assertThat(count("attempts")).isEqualTo(attempts);
        assertThat(count("attempt_participant_subject")).isEqualTo(bindings);
    }

    @Test
    void everyResolutionRechecksCommittedAccountEligibilityEvenWithAnUnchangedPrincipal() {
        var fixture = user(true, false);
        authenticate(token(new UserDetailsServiceImpl(users).loadUserByUsername(fixture.name())));
        UUID attempt = UUID.randomUUID();
        assertThat(resolver.resolve(attempt).userId()).isEqualTo(fixture.id());
        sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", fixture.id().toString());
        rejected(() -> resolver.resolve(attempt), INVALID);
        sql.update("UPDATE users SET is_active=TRUE,is_deleted=TRUE WHERE user_id=UUID_TO_BIN(?)", fixture.id().toString());
        rejected(() -> resolver.resolve(attempt), INVALID);
        sql.update("UPDATE users SET is_deleted=FALSE WHERE user_id=UUID_TO_BIN(?)", fixture.id().toString());
        assertThat(resolver.resolve(attempt).userId()).isEqualTo(fixture.id());
        sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)", fixture.id().toString());
        sql.update("DELETE FROM users WHERE user_id=UUID_TO_BIN(?)", fixture.id().toString());
        rejected(() -> resolver.resolve(attempt), INVALID);
        assertThat(count("attempt_participant_subject")).isZero();
    }

    @Test
    void inactiveDeletedMissingAndReservedSentinelHaveNoEligibleIdentity() {
        var inactive = user(false, false);
        var deleted = user(true, true);
        for (String name : List.of(inactive.name(), deleted.name(), "missing-" + UUID.randomUUID())) {
            authenticate(token(principal(name)));
            rejected(() -> resolver.resolve(UUID.randomUUID()), INVALID);
            assertThat(lookup.findActiveUserId(name)).isEmpty();
        }
        if (sql.queryForObject("SELECT COUNT(*) FROM users WHERE username='anonymous'", Integer.class) == 0) {
            insertUser(UUID.randomUUID(), "anonymous", true, false);
        }
        authenticate(token(principal("anonymous")));
        rejected(() -> resolver.resolve(UUID.randomUUID()), INVALID);
        assertThat(lookup.findActiveUserId("anonymous")).isEmpty();
        var control = user(true, false);
        authenticate(token(principal(control.name())));
        assertThat(resolver.resolve(UUID.randomUUID()).userId()).isEqualTo(control.id());
    }

    @Test
    void multipleUsersWithRolesAndPermissionsRequireExactlyOneScalarQueryEach() {
        var fixtures = List.of(user(true, false), user(true, false), user(true, false));
        sessions.getStatistics().clear();
        for (var fixture : fixtures) {
            authenticate(token(principal(fixture.name())));
            assertThat(resolver.resolve(UUID.randomUUID()).userId()).isEqualTo(fixture.id());
        }
        var statistics = sessions.getStatistics();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(3);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(statistics.getCollectionLoadCount()).isZero();
        assertThat(statistics.getEntityInsertCount()).isZero();
        assertThat(statistics.getEntityUpdateCount()).isZero();
        assertThat(statistics.getEntityDeleteCount()).isZero();
        for (var fixture : fixtures) {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM user_roles WHERE user_id=UUID_TO_BIN(?)",
                    Integer.class, fixture.id().toString())).isEqualTo(2);
        }
        assertThat(sql.queryForMap("EXPLAIN SELECT user_id FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE AND username<>'anonymous'",
                fixtures.get(0).name()).get("type")).isEqualTo("const");
    }

    private static Fixture user(boolean active, boolean deleted) {
        UUID id = UUID.randomUUID();
        String name = "resolve-" + id;
        insertUser(id, name, active, deleted);
        for (int i = 0; i < 2; i++) {
            String role = "resolve-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            Long roleId = sql.queryForObject("SELECT role_id FROM roles WHERE role_name=?", Long.class, role);
            sql.update("INSERT INTO user_roles(user_id,role_id) VALUES(UUID_TO_BIN(?),?)", id.toString(), roleId);
            for (int j = 0; j < 2; j++) {
                String permission = "resolve-" + UUID.randomUUID();
                sql.update("INSERT INTO permissions(permission_name,resource,action) VALUES (?,'quiz','read')", permission);
                Long permissionId = sql.queryForObject("SELECT permission_id FROM permissions WHERE permission_name=?", Long.class, permission);
                sql.update("INSERT INTO role_permissions(role_id,permission_id) VALUES(?,?)", roleId, permissionId);
            }
        }
        return new Fixture(id, name);
    }

    private static void insertUser(UUID id, String name, boolean active, boolean deleted) {
        sql.update("""
                INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,
                    created_at,password_changed_at,version)
                VALUES(UUID_TO_BIN(?),?,?,?, ?,?,FALSE,'2026-01-02 12:00:00','2026-01-02 12:00:00',0)
                """, id.toString(), name, id + "@example.invalid", "synthetic-unused", active, deleted);
    }

    private static long count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private record Fixture(UUID id, String name) { }
}
