package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningSessionServiceImpl;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.infra.repository.JdbcLearningSessionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.shared.config.TransactionConfig;
import uk.gegc.quizmaker.shared.transaction.SafeJpaTransactionManager;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.learning.LearningSessionTest.rejected;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Production service, JDBC, UserRepository and JPA transaction proxies. Only content authority
 * is a fixture; real Learning source eligibility/public activation remains #578/#692/#667. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = LearningSessionSpringMySqlIntegrationTest.SchemaCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@Import({LearningSessionServiceImpl.class, JdbcLearningSessionRepository.class, AuthenticatedUserLookupImpl.class,
        TransactionConfig.class, LearningSessionSpringMySqlIntegrationTest.Config.class})
class LearningSessionSpringMySqlIntegrationTest {
    private static final String SCHEMA = "learning_session_spring_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PORT = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
    private static final String ADMIN = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
    private static final String PASSWORD = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final UUID LEARNER = UUID.randomUUID(), QUIZ = UUID.randomUUID(), REVISION = UUID.randomUUID();
    private static final Instant TIME = Instant.parse("2026-11-19T09:00:00.123456Z");

    static String server() {
        if (!PORT.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        return "jdbc:mysql://127.0.0.1:" + PORT + "/";
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> server() + SCHEMA + OPTIONS + "&createDatabaseIfNotExist=true");
        properties.add("spring.datasource.username", () -> ADMIN);
        properties.add("spring.datasource.password", () -> PASSWORD);
    }
    public static class SchemaCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext ignored) {
            new JdbcTemplate(new DriverManagerDataSource(server() + OPTIONS, ADMIN, PASSWORD))
                    .execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean Clock clock() { return Clock.fixed(TIME, ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean LearningSessionSourceVerifier sourceVerifier() {
            return (learner, source) -> learner.equals(LEARNER) && source.quizId().equals(QUIZ);
        }
    }

    @Autowired LearningSessionService service;
    @Autowired AuthenticatedUserLookup users;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate sql;
    @MockitoSpyBean JdbcLearningSessionRepository repository;

    private JdbcLearningSessionRepository target() { return AopTestUtils.getUltimateTargetObject(repository); }

    @BeforeEach void fixture() {
        sql.update("""
                INSERT IGNORE INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version)
                VALUES(UUID_TO_BIN(?),'anna','anna@example.invalid','not-a-login',1,0,0,'2026-01-01','2026-01-01',0)
                """, LEARNER.toString());
        sql.update("""
                INSERT IGNORE INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1,'Learning source',?)
                """, REVISION.toString(), QUIZ.toString(), LEARNER.toString(), java.sql.Timestamp.from(TIME));
        var principal = User.withUsername("anna").password("unused").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @AfterEach void clean() {
        SecurityContextHolder.clearContext();
        sql.update("DELETE FROM learning_session_create_receipt");
        sql.update("DELETE FROM learning_session");
    }

    @Test void productionProxiesShareActualRcAndRestorePoolIsolation() {
        assertThat(manager).isInstanceOf(SafeJpaTransactionManager.class);
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        assertThat(AopUtils.isAopProxy(repository)).isTrue();
        assertThat(AopUtils.isAopProxy(users)).isTrue();
        var isolation = new AtomicBoolean();
        doAnswer(call -> {
            isolation.set("READ-COMMITTED".equals(sql.queryForObject("SELECT @@transaction_isolation", String.class)));
            return call.callRealMethod();
        }).when(target()).recordCreation(any());
        var command = command();
        var saved = service.create(command).session();
        assertThat(isolation).isTrue();
        assertThat(service.read(saved.id())).isEqualTo(saved);
        assertThat(service.create(command).replayed()).isTrue();
        assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
    }

    @Test void participatingJpaRollbackRemovesBothAuthoritativeWrites() {
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            var saved = service.create(command()).session();
            assertThat(service.read(saved.id())).isEqualTo(saved);
            status.setRollbackOnly();
        });
        assertEmpty();
        assertThat(service.create(command()).replayed()).isFalse();
    }

    @Test void reachedRepositoryFailureRollsBackUnderRealJpaManagerThenRetryWorks() {
        var reached = new AtomicBoolean();
        doAnswer(call -> {
            call.callRealMethod();
            reached.set(true);
            throw new IllegalStateException("private-diagnostic");
        }).when(target()).recordCreation(any());
        var command = command();
        rejected(() -> service.create(command), PERSISTENCE_UNAVAILABLE);
        assertThat(reached).isTrue();
        assertEmpty();
        doCallRealMethod().when(target()).recordCreation(any());
        assertThat(service.create(command).replayed()).isFalse();
    }

    private static LearningSessionService.Create command() {
        return new LearningSessionService.Create(1, UUID.randomUUID(), new LearningSession.Source(1, QUIZ, REVISION, 1), FsrsManifest.approved());
    }
    private void assertEmpty() {
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session_create_receipt", Integer.class)).isZero();
    }
}
