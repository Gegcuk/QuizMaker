package uk.gegc.quizmaker.features.attempt.access;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.media.application.SavedImageStorage;
import uk.gegc.quizmaker.features.media.application.impl.RetainedImageServiceImpl;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.*;
import uk.gegc.quizmaker.features.question.config.BaselineQuestionContractConfig;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizPracticeSourceServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionPublicationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.Command;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;
import uk.gegc.quizmaker.features.quiz.infra.repository.*;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import static org.assertj.core.api.Assertions.*;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.application.AttemptAccessService.Operation;
import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

/** Real publication and five-family bindings feed the real atomic start. Only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, PublicationContent.class, RetainedImageServiceImpl.class,
        AppPermissionEvaluator.class, QuestionHandlerFactory.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        QuestionContractRegistryImpl.class, QuestionRevisionContractServiceImpl.class, JdbcQuestionContractBindingRepository.class,
        PublishedQuestionContractServiceImpl.class, BaselineQuestionContractConfig.class,
        FixedFormMaterializerImpl.class, FormOptionOrderServiceImpl.class,
        JdbcFixedFormRepository.class, JdbcFormOptionOrderRepository.class,
        AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        AttemptAccessMySqlIntegrationTest.Config.class})
class AttemptAccessMySqlIntegrationTest {
    static final String SCHEMA = "attempt_access_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    static boolean schemaCreated;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true;
        p.add("spring.datasource.url", () -> base + SCHEMA + options);
        p.add("spring.datasource.username", () -> user); p.add("spring.datasource.password", () -> password);
    }
    @AfterAll static void dropDatabase() { if (schemaCreated) admin.execute("DROP DATABASE " + SCHEMA); }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaStorageProperties mediaProperties() { return new MediaStorageProperties(); }
        @Bean SavedImageStorage storage() {
            return new SavedImageStorage() {
                public Source inspect(String key) { throw new AssertionError("Text fixture must not call storage"); }
                public void retain(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
                public void verify(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
            };
        }
        @Bean static BeanPostProcessor measuredSql() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof AccessDataSource) ? new AccessDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired AttemptAccessService access;
    @Autowired PublishedQuestionContractService binding;
    @Autowired QuizRevisionPublicationService publications;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired AttemptStartService starts;
    @Autowired AuthenticatedSubjectResolver identity;
    @Autowired QuizPracticeSourceService sources;
    @Autowired AttemptSubjectService subjects;
    @Autowired AttemptLifecycleService lifecycle;
    @Autowired SimpleMeterRegistry metrics;
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    static final List<String> TARGET_TABLES = List.of("attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form", "attempt_policy_snapshot");
    AccessDataSource observer() { return (AccessDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
        username = "access-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username, true);
        grant(ownerId, "ATTEMPT_READ", "ATTEMPT_CREATE");
        quizId = newQuiz();
        authenticate(username);
    }

    @AfterEach void cleanup() {
        observer().reset(); SecurityContextHolder.clearContext();
        tx.executeWithoutResult(s -> {
            TARGET_TABLES.forEach(table -> sql.update("DELETE FROM " + table));
            for (String table : List.of("question_revision_contract_bindings", "quiz_publication_receipts", "quiz_publication_heads",
                    "quiz_revision_images", "quiz_publications", "quiz_revision_items", "question_option_revisions", "question_revisions",
                    "quiz_revisions", "quiz_questions", "question_tags", "question_source_confirmations", "questions", "quizzes",
                    "categories", "user_roles", "users")) sql.update("DELETE FROM " + table);
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'access-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'access-%'");
        });
    }


    @ParameterizedTest @EnumSource(Operation.class)
    void realStartProvidesScopedAccessAfterServiceRecreationWithoutAnyWrite(Operation operation) {
        var started = start();
        var before = targetSnapshot();
        var permitted = access.requireAccess(started.attemptId(), operation);
        assertThat(permitted.attemptId()).isEqualTo(started.attemptId());
        assertThat(permitted.operation()).isEqualTo(operation);
        assertThat(permitted.quizId()).isEqualTo(quizId);
        assertThat(permitted.quizRevisionId()).isEqualTo(started.quizRevisionId());
        assertThat(permitted.formId()).isEqualTo(started.formId());
        assertThat(permitted.stateVersion()).isEqualTo(1);
        assertThat(permitted.policySnapshotId()).isEqualTo(UUID.fromString(sql.queryForObject(
                "SELECT BIN_TO_UUID(policy_snapshot_id) FROM attempt_trace_stream", String.class)));
        assertThat(restarted().requireAccess(started.attemptId(), operation)).isEqualTo(permitted);
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(permitted.toString()).doesNotContain(started.attemptId().toString(), quizId.toString(), ownerId.toString());
        assertThat(metrics.get("quizmaker.attempt.access").tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("outcome", "allowed").timer().count()).isGreaterThanOrEqualTo(2);
        metrics.get("quizmaker.attempt.access").timers().forEach(timer ->
                assertThat(timer.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                        .containsExactlyInAnyOrder("operation", "outcome"));
    }

    @ParameterizedTest @ValueSource(strings = {"none", "read", "create", "both"})
    void operationPermissionsAreIndependentAndNeverGrantDisclosure(String permissions) {
        var started = start();
        revoke(ownerId, "ATTEMPT_READ"); revoke(ownerId, "ATTEMPT_CREATE");
        if (permissions.equals("read") || permissions.equals("both")) grant(ownerId, "ATTEMPT_READ");
        if (permissions.equals("create") || permissions.equals("both")) grant(ownerId, "ATTEMPT_CREATE");
        var before = targetSnapshot();
        for (var operation : Operation.values()) {
            boolean allowed = permissions.equals("both") || permissions.equals(operation == Operation.ANSWER ? "create" : "read");
            if (allowed) assertThat(access.requireAccess(started.attemptId(), operation).operation()).isEqualTo(operation);
            else denied(() -> guarded(started.attemptId(), operation), DENIED);
        }
        assertThat(targetSnapshot()).isEqualTo(before);
        // Access returns only scoped references, never an answer, grade, release or allowed-actions DTO.
        assertThat(Arrays.stream(AttemptAccessService.Access.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("attemptId", "operation", "quizId", "quizRevisionId", "formId", "policySnapshotId", "stateVersion",
                        "contentOwnerId", "lifecycle");
    }

    @ParameterizedTest @ValueSource(strings = {"QUESTION_ADMIN", "QUIZ_MODERATE"})
    void fullEditorCanAccessOnlyTheirOwnAttemptAndRevocationWinsEvenForPublicQuiz(String authority) {
        var ownerAttempt = start();
        String editor = "editor-" + UUID.randomUUID().toString().substring(0, 8);
        UUID editorId = user(editor, false);
        grant(editorId, "QUESTION_UPDATE", authority, "ATTEMPT_READ", "ATTEMPT_CREATE", "ATTEMPT_READ_ALL", "QUIZ_ADMIN");
        authenticate(editor);
        var own = starts.start(command(ownerAttempt.quizRevisionId(), AnswerOrder.AUTHORED, "editor"));
        for (var operation : Operation.values()) {
            assertThat(access.requireAccess(own.attemptId(), operation).operation()).isEqualTo(operation);
            denied(() -> guarded(ownerAttempt.attemptId(), operation), DENIED);
        }
        tx.executeWithoutResult(status -> sql.update("UPDATE quizzes SET visibility='PUBLIC',status='PUBLISHED' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString()));
        revoke(editorId, "QUESTION_UPDATE");
        for (var operation : Operation.values()) denied(() -> guarded(own.attemptId(), operation), DENIED);
        grant(editorId, "QUESTION_UPDATE");
        assertThat(access.requireAccess(own.attemptId(), Operation.VIEW).attemptId()).isEqualTo(own.attemptId());
        authenticate(username);
        denied(() -> guarded(own.attemptId(), Operation.VIEW), DENIED);
        assertThat(access.requireAccess(ownerAttempt.attemptId(), Operation.VIEW).attemptId()).isEqualTo(ownerAttempt.attemptId());
    }

    @Test void missingLegacyAndForeignBindingsDoNotEnumerateAndCallerIdentityCannotSelectAnOwner() {
        var started = start();
        String other = "other-" + UUID.randomUUID().toString().substring(0, 8);
        UUID otherId = user(other, false);
        grant(otherId, "ATTEMPT_READ", "ATTEMPT_CREATE", "ATTEMPT_READ_ALL", "QUESTION_UPDATE", "QUESTION_ADMIN");
        authenticate(other);
        var authentication = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        authentication.setDetails(Map.of("userId", ownerId, "subjectId", ownerId, "attemptId", started.attemptId()));
        var before = targetSnapshot();
        String deniedForeign = denialMessage(started.attemptId());
        assertThat(denialMessage(UUID.randomUUID())).isEqualTo(deniedForeign);
        UUID legacy = UUID.randomUUID();
        tx.executeWithoutResult(status -> sql.update("""
                INSERT INTO attempts(id,user_id,quiz_id,mode,status,started_at)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'ALL_AT_ONCE','IN_PROGRESS',NOW(6))
                """, legacy.toString(), otherId.toString(), quizId.toString()));
        assertThat(denialMessage(legacy)).isEqualTo(deniedForeign);
        assertThat(targetSnapshot()).isEqualTo(before);
        tx.executeWithoutResult(status -> sql.update("DELETE FROM attempts WHERE id=UUID_TO_BIN(?)", legacy.toString()));
        authenticate(username);
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
    }

    @Test void unauthenticatedUnsupportedAndMixedPrincipalsDoNotReachProtectedMetadata() {
        var started = start();
        SecurityContextHolder.clearContext(); observer().selects.clear();
        denied(() -> guarded(started.attemptId(), Operation.VIEW), UNAUTHENTICATED);
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "test-only", "anonymousUser", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        denied(() -> guarded(started.attemptId(), Operation.VIEW), UNAUTHENTICATED);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ownerId.toString(), null, List.of()));
        denied(() -> guarded(started.attemptId(), Operation.VIEW), DENIED);
        var principal = org.springframework.security.core.userdetails.User.withUsername(username).password("test-only").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()) {
            @Override public String getName() { return "contradictory-name"; }
        });
        denied(() -> guarded(started.attemptId(), Operation.VIEW), DENIED);
        assertThat(observer().selects).isEmpty();
        authenticate(username);
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
    }

    @ParameterizedTest @CsvSource({"1,permission", "2,permission", "1,deactivate", "2,deactivate",
            "1,delete-user", "2,delete-user", "1,rename", "2,rename", "1,delete-quiz", "2,delete-quiz"})
    void currentRevocationBeatsAnOldCallerSnapshot(int level, String change) {
        var started = start();
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(level == 1 ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        denied(() -> outer.executeWithoutResult(status -> {
            // Establish a real old snapshot with all previously allowed facts, before the external commit.
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=UUID_TO_BIN(?)", Integer.class, ownerId.toString())).isEqualTo(1);
            sql.queryForList("SELECT * FROM user_roles WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
            sql.queryForList("SELECT * FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
            independent.executeWithoutResult(s -> {
                switch (change) {
                    case "permission" -> revoke(ownerId, "ATTEMPT_READ");
                    case "deactivate" -> sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
                    case "delete-user" -> sql.update("UPDATE users SET is_deleted=TRUE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
                    case "rename" -> sql.update("UPDATE users SET username=CONCAT(username,'-new') WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
                    case "delete-quiz" -> sql.update("UPDATE quizzes SET is_deleted=TRUE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
                    default -> throw new AssertionError(change);
                }
            });
            guarded(started.attemptId(), Operation.VIEW);
        }), DENIED);
        // Restore the precise prerequisite and demonstrate success through the same supported path.
        tx.executeWithoutResult(status -> {
            sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE,username=? WHERE user_id=UUID_TO_BIN(?)", username, ownerId.toString());
            sql.update("UPDATE quizzes SET is_deleted=FALSE WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
        });
        if (change.equals("permission")) grant(ownerId, "ATTEMPT_READ");
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void targetCreatedAfterCallerSnapshotRemainsAccessible(int level) {
        UUID revision = prepared();
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(level == 1 ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class)).isZero();
            var separate = new TransactionTemplate(manager); separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            var started = separate.execute(s -> starts.start(command(revision, AnswerOrder.AUTHORED, "new-target")));
            assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
        });
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void revokedEditorAuthorityCannotBeRecoveredFromAnOldPermissionSnapshot(int level) {
        var original = start();
        String editor = "old-editor-" + UUID.randomUUID().toString().substring(0, 8);
        UUID editorId = user(editor, false);
        grant(editorId, "QUESTION_UPDATE", "QUESTION_ADMIN", "ATTEMPT_READ"); authenticate(editor);
        var own = starts.start(command(original.quizRevisionId(), AnswerOrder.AUTHORED, "old-editor"));
        var outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(level == 1 ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var separate = new TransactionTemplate(manager); separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        denied(() -> outer.executeWithoutResult(status -> {
            assertThat(sql.queryForList("SELECT p.permission_name FROM user_roles ur JOIN role_permissions rp ON rp.role_id=ur.role_id JOIN permissions p ON p.permission_id=rp.permission_id WHERE ur.user_id=UUID_TO_BIN(?)", String.class, editorId.toString()))
                    .contains("QUESTION_ADMIN", "QUESTION_UPDATE");
            separate.executeWithoutResult(s -> revoke(editorId, "QUESTION_ADMIN"));
            guarded(own.attemptId(), Operation.VIEW);
        }), DENIED);
        grant(editorId, "QUESTION_ADMIN");
        assertThat(access.requireAccess(own.attemptId(), Operation.VIEW).attemptId()).isEqualTo(own.attemptId());
    }

    @Test void successfulAccessDoesNotCommitTheCallersUnrelatedWrites() {
        var started = start();
        String title = tx.execute(s -> sql.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quizId.toString()));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            sql.update("UPDATE quizzes SET title='Uncommitted caller title' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
            assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
            throw new IllegalStateException("Reached outer rollback after successful access");
        })).hasMessage("Reached outer rollback after successful access");
        String persistedTitle = tx.execute(s -> sql.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quizId.toString()));
        assertThat(persistedTitle).isEqualTo(title);
    }

    @Test void authorizationLocksRemainUntilTheProtectedReadTransactionFinishes() throws Exception {
        var started = start();
        var allowed = new CountDownLatch(1); var release = new CountDownLatch(1); var revokeIssued = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        var reader = workers.submit(() -> {
            authenticate(username);
            try { tx.executeWithoutResult(status -> {
                assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
                allowed.countDown(); await(release);
            }); } finally { SecurityContextHolder.clearContext(); }
        });
        Future<?> revoker = null;
        try {
            assertThat(allowed.await(5, TimeUnit.SECONDS)).isTrue();
            observer().beforeSql = (query, connection) -> {
                if (query.startsWith("DELETE rp FROM role_permissions")) revokeIssued.countDown();
            };
            revoker = workers.submit(() -> revoke(ownerId, "ATTEMPT_READ"));
            assertThat(revokeIssued.await(5, TimeUnit.SECONDS)).isTrue();
            // Observe an actual InnoDB wait in this catalogue, not merely two started worker threads.
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < until) {
                waiting = admin.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA=?
                        """, Integer.class, SCHEMA) > 0;
                if (waiting) break;
                Thread.sleep(10);
            }
            assertThat(waiting).isTrue(); assertThat(revoker.isDone()).isFalse();
        } finally {
            release.countDown(); reader.get(10, TimeUnit.SECONDS);
            if (revoker != null) revoker.get(10, TimeUnit.SECONDS);
            workers.shutdownNow(); observer().reset();
        }
        denied(() -> guarded(started.attemptId(), Operation.VIEW), DENIED);
        grant(ownerId, "ATTEMPT_READ");
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
    }

    @Test void draftEditsAndNewPublicationDoNotRebindAccessToTheNewHead() {
        var started = start(); var before = access.requireAccess(started.attemptId(), Operation.VIEW);
        tx.executeWithoutResult(status -> questions.findAllByQuizId_IdOrderById(quizId)
                .forEach(question -> question.setQuestionText("Edited draft")));
        var questionIds = tx.execute(s -> questions.findAllByQuizId_IdOrderById(quizId).stream().map(Question::getId).toList());
        UUID next = publish(1, questionIds); binding.bind(quizId, next);
        assertThat(next).isNotEqualTo(started.quizRevisionId());
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"subject", "stream", "form", "policy", "revision", "lifecycle"})
    void unknownPersistedRootVersionFailsClosed(String root) {
        var started = start();
        Map<String, String> tables = Map.of("subject", "attempt_participant_subject", "stream", "attempt_trace_stream",
                "form", "attempt_form", "policy", "attempt_policy_snapshot", "revision", "quiz_revisions", "lifecycle", "attempt_lifecycle_state");
        String table = tables.get(root), column = root.equals("lifecycle") ? "lifecycle_version" : "schema_version";
        Map<String, String> constraints = Map.of("subject", "chk_attempt_subject_version", "stream", "chk_trace_stream",
                "form", "chk_form_version", "policy", "chk_policy_snapshot_version",
                "revision", "chk_quiz_revision_version", "lifecycle", "chk_attempt_lifecycle_version");
        // Future-version corruption fixture in the test-owned catalogue, following the existing trace/snapshot tests.
        String check = constraints.get(root);
        sql.execute("ALTER TABLE " + table + " ALTER CHECK " + check + " NOT ENFORCED");
        try {
            tx.executeWithoutResult(status -> sql.update("UPDATE " + table + " SET " + column + "=2"));
            denied(() -> guarded(started.attemptId(), Operation.VIEW), DENIED);
        } finally {
            tx.executeWithoutResult(status -> sql.update("UPDATE " + table + " SET " + column + "=1"));
            sql.execute("ALTER TABLE " + table + " ALTER CHECK " + check + " ENFORCED");
        }
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
    }

    @Test void partialTargetEvidenceCannotBeTreatedAsAnAttemptOrLegacyFallback() {
        UUID partial = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            subjects.bindOrReplay(ownerId, uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject.authenticated(partial, ownerId));
            lifecycle.initialize(partial);
        });
        denied(() -> guarded(partial, Operation.VIEW), DENIED);
        var started = start();
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
        tx.executeWithoutResult(status -> {
            sql.update("DELETE FROM attempt_trace_event WHERE attempt_id=UUID_TO_BIN(?)", started.attemptId().toString());
            sql.update("DELETE FROM attempt_trace_identity WHERE attempt_id=UUID_TO_BIN(?)", started.attemptId().toString());
            sql.update("DELETE FROM attempt_trace_stream WHERE attempt_id=UUID_TO_BIN(?)", started.attemptId().toString());
        });
        denied(() -> guarded(started.attemptId(), Operation.VIEW), DENIED);
    }

    @Test void databaseFailureIsSanitizedAndNeverRunsTheProtectedConsumer() {
        var started = start(); var before = targetSnapshot();
        var reached = new AtomicBoolean();
        observer().beforeSql = (query, connection) -> {
            if (query.contains("FROM attempt_lifecycle_state l")) {
                reached.set(true); throw new SQLException("synthetic private SQL diagnostic", "08006");
            }
        };
        try { denied(() -> guarded(started.attemptId(), Operation.VIEW), UNAVAILABLE); }
        finally { observer().reset(); }
        assertThat(reached).isTrue();
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void realLockTimeoutIsBoundedAndExplicitRetrySucceeds(int level) throws Exception {
        var started = start(); var before = targetSnapshot();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        var blocking = worker.submit(() -> tx.executeWithoutResult(status -> {
            sql.queryForObject("SELECT user_id FROM users WHERE user_id=UUID_TO_BIN(?) FOR UPDATE", byte[].class, ownerId.toString());
            locked.countDown(); await(release);
        }));
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var outer = new TransactionTemplate(manager);
            outer.setIsolationLevel(level == 1 ? TransactionDefinition.ISOLATION_READ_COMMITTED : TransactionDefinition.ISOLATION_REPEATABLE_READ);
            denied(() -> outer.executeWithoutResult(status -> {
                sql.execute("SET SESSION innodb_lock_wait_timeout=1");
                try { guarded(started.attemptId(), Operation.VIEW); }
                finally { sql.execute("SET SESSION innodb_lock_wait_timeout=50"); }
            }), IN_PROGRESS);
            assertThat(observer().sqlErrorCodes).contains(1205);
        } finally { release.countDown(); blocking.get(10, TimeUnit.SECONDS); worker.shutdownNow(); }
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(access.requireAccess(started.attemptId(), Operation.VIEW).attemptId()).isEqualTo(started.attemptId());
    }

    @ParameterizedTest @CsvSource({"2,attempt_participant_subject", "4,attempt_participant_subject",
            "2,attempt_lifecycle_state", "4,attempt_lifecycle_state", "2,attempt_trace_stream", "4,attempt_trace_stream"})
    void lockedForeignAttemptDoesNotEnumerateAndOwnContentionRemainsInProgress(int isolation, String table) throws Exception {
        var started = start();
        String ownerName = username;
        String otherName = "other-" + UUID.randomUUID().toString().substring(0, 8);
        UUID otherId = user(otherName, false);
        grant(otherId, "ATTEMPT_READ", "ATTEMPT_CREATE", "QUESTION_UPDATE", "QUESTION_ADMIN");
        authenticate(otherName);
        var own = starts.start(command(started.quizRevisionId(), AnswerOrder.AUTHORED, "other-access"));
        var before = targetSnapshot();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        var holder = worker.submit(() -> tx.executeWithoutResult(status -> {
            assertThat(sql.queryForObject("SELECT attempt_id FROM " + table + " WHERE attempt_id=UUID_TO_BIN(?) FOR UPDATE",
                    byte[].class, started.attemptId().toString())).isNotNull();
            locked.countDown(); await(release);
        }));
        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            for (var operation : Operation.values()) {
                var missing = rejectionInTransaction(UUID.randomUUID(), operation, isolation);
                assertThat(missing.reason()).isEqualTo(DENIED);
                observer().reset();
                var foreign = rejectionInTransaction(started.attemptId(), operation, isolation);
                assertThat(foreign.reason()).as("Actual SQL errors: %s", observer().sqlErrorCodes).isEqualTo(DENIED);
                assertThat(foreign.getMessage()).isEqualTo(missing.getMessage());
                assertThat(foreign.getCause()).isNull(); assertThat(foreign.getSuppressed()).isEmpty();
                assertThat(observer().sqlErrorCodes).isEmpty();
                assertThat(observer().selects).noneMatch(query -> query.contains("attempt_lifecycle_state")
                        || query.contains("attempt_trace_stream"));
                assertThat(access.requireAccess(own.attemptId(), operation).attemptId()).isEqualTo(own.attemptId());
            }
            authenticate(ownerName); observer().reset();
            assertThat(rejectionInTransaction(started.attemptId(), Operation.VIEW, isolation).reason()).isEqualTo(IN_PROGRESS);
            assertThat(observer().sqlErrorCodes).contains(1205);
        } finally {
            release.countDown();
            try { holder.get(10, TimeUnit.SECONDS); }
            finally { worker.shutdownNow(); }
        }
        authenticate(otherName);
        for (var operation : Operation.values()) denied(() -> guarded(started.attemptId(), operation), DENIED);
        authenticate(ownerName);
        for (var operation : Operation.values())
            assertThat(access.requireAccess(started.attemptId(), operation).attemptId()).isEqualTo(started.attemptId());
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    private AttemptAccessException rejectionInTransaction(UUID attempt, Operation operation, int isolation) {
        var outer = new TransactionTemplate(manager); outer.setIsolationLevel(isolation);
        var failure = catchThrowableOfType(() -> outer.executeWithoutResult(status -> {
            int previous = sql.queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
            sql.execute("SET SESSION innodb_lock_wait_timeout=1");
            try { guarded(attempt, operation); }
            finally { sql.execute("SET SESSION innodb_lock_wait_timeout=" + previous); }
        }), AttemptAccessException.class);
        assertThat(failure).isNotNull();
        return failure;
    }

    @Test void metadataReadsStayBoundedAcrossSeveralOwnersQuizzesAndLargerForms() {
        List<Integer> totals = new ArrayList<>();
        for (int size : List.of(1, 20)) {
            List<AttemptStartService.Result> attempts = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int parent = 0; parent < 3; parent++) {
                username = "access-parent-" + UUID.randomUUID().toString().substring(0, 8);
                ownerId = user(username, true); grant(ownerId, "ATTEMPT_READ", "ATTEMPT_CREATE");
                quizId = newQuiz(); authenticate(username);
                var ids = IntStream.range(0, size).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList();
                UUID revision = publish(0, ids); binding.bind(quizId, revision);
                attempts.add(starts.start(command(revision, AnswerOrder.AUTHORED, "count"))); names.add(username);
            }
            observer().selects.clear();
            for (int i = 0; i < attempts.size(); i++) {
                authenticate(names.get(i)); access.requireAccess(attempts.get(i).attemptId(), Operation.VIEW);
            }
            totals.add(observer().selects.size());
            assertThat(observer().selects).noneMatch(query -> query.contains("question_revisions") || query.contains("canonical_json") || query.contains("hashed_password"));
            String bindingRead = observer().selects.stream()
                    .filter(query -> query.contains("FROM attempt_participant_subject")).findFirst().orElseThrow();
            var plan = sql.queryForMap("EXPLAIN " + bindingRead, ownerId.toString(), attempts.get(2).attemptId().toString());
            assertThat(plan.get("key")).isEqualTo("ix_attempt_subject_user");
            assertThat(plan.get("key_len")).isEqualTo("32"); // Both BINARY(16) keys, not an attempt-only lookup.
        }
        assertThat(totals.get(1)).isEqualTo(totals.get(0)).isLessThanOrEqualTo(18);
    }

    private AttemptStartService.Result start() { return starts.start(command(prepared(), AnswerOrder.AUTHORED, "access")); }
    private AttemptAccessService restarted() {
        return new AttemptAccessServiceImpl(identity, new JdbcAttemptAccessRepository(new NamedParameterJdbcTemplate(source)), sources, manager, metrics);
    }
    private void revoke(UUID userId, String permission) {
        tx.executeWithoutResult(status -> sql.update("""
                DELETE rp FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name=?
                """, userId.toString(), permission));
    }
    private void guarded(UUID attempt, Operation operation) {
        access.requireAccess(attempt, operation);
        fail("Unauthorized consumer was invoked");
    }
    private String denialMessage(UUID attempt) {
        var failure = catchThrowableOfType(() -> guarded(attempt, Operation.VIEW), AttemptAccessException.class);
        assertThat(failure.reason()).isEqualTo(DENIED); return failure.getMessage();
    }
    private static void denied(Runnable action, AttemptAccessException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AttemptAccessException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("Attempt access rejected: " + reason);
            assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        });
    }
    private Map<String, List<String>> targetSnapshot() {
        return tx.execute(status -> {
            Map<String, List<String>> result = new TreeMap<>();
            TARGET_TABLES.forEach(table -> result.put(table, sql.queryForList("SELECT * FROM " + table).stream().map(row -> {
                var ordered = new TreeMap<String, String>();
                row.forEach((key, value) -> ordered.put(key, value instanceof byte[] b ? HexFormat.of().formatHex(b) : String.valueOf(value)));
                return ordered.toString();
            }).sorted().toList()));
            return result;
        });
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }


    private UUID draft(QuestionType type) {
        return tx.execute(s -> {
            var q = new Question(); q.setType(type); q.setDifficulty(Difficulty.EASY); q.setQuestionText("Frozen " + type);
            var content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
            if (type == QuestionType.FILL_GAP)
                content.putArray("options").add("Paris").add("Rome").add("London").add("Berlin").add("Madrid").add("Lisbon").add("Vienna");
            q.setContent(content.toString());
            q.establishSourceOwner(ownerId); q.setQuizId(new ArrayList<>(List.of(quizzes.findById(quizId).orElseThrow())));
            return questions.saveAndFlush(q).getId();
        });
    }
    private UUID publish(long version, List<UUID> order) { return publications.publish(new Command(1, quizId, UUID.randomUUID(), version, order)).publication().revision().id(); }
    private UUID user(String name, boolean permission) {
        UUID id = tx.execute(s -> {
            var u = new User(); u.setUsername(name); u.setEmail(name + "@example.invalid"); u.setHashedPassword("test-only");
            u.setActive(true); u.setRoles(new HashSet<>()); return users.saveAndFlush(u).getId();
        });
        if (permission) tx.executeWithoutResult(s -> {
            String role = "access-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
    }

    private UUID newQuiz() {
        return tx.execute(s -> {
            var category = new Category(); category.setName("access-" + UUID.randomUUID()); categories.saveAndFlush(category);
            var quiz = new Quiz(); quiz.setCreator(users.findById(ownerId).orElseThrow()); quiz.setCategory(category);
            quiz.setTitle("Start fixture " + UUID.randomUUID()); quiz.setDescription("Synthetic source"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
    }
    private UUID prepared() {
        var revision = publish(0, FAMILIES.stream().map(this::draft).toList());
        binding.bind(quizId, revision);
        return revision;
    }
    private static ContentPolicy content(UUID revision) {
        return new ContentPolicy(revision, 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
    }
    private AttemptStartService.Command command(UUID revision, AnswerOrder order, String key) {
        return new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from(key),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", order);
    }
    private static void authenticate(String name) {
        var principal = org.springframework.security.core.userdetails.User.withUsername(name).password("test-only").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private void grant(UUID userId, String... names) {
        tx.executeWithoutResult(s -> {
            String role = "access-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            for (String name : names) assertThat(sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, name)).isEqualTo(1);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", userId.toString(), role);
        });
    }

}
