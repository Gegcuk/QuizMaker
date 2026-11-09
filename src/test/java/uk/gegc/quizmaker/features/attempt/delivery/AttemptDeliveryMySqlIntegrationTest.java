package uk.gegc.quizmaker.features.attempt.delivery;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

/** Real publication and five-family bindings feed the real atomic start. Only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({uk.gegc.quizmaker.features.attempt.infra.repository.JdbcNavigationRepository.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAcceptedResponseRepository.class, QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, PublicationContent.class, RetainedImageServiceImpl.class,
        AppPermissionEvaluator.class, QuestionHandlerFactory.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        QuestionContractRegistryImpl.class, QuestionRevisionContractServiceImpl.class, JdbcQuestionContractBindingRepository.class,
        PublishedQuestionContractServiceImpl.class, BaselineQuestionContractConfig.class,
        FixedFormMaterializerImpl.class, FormOptionOrderServiceImpl.class,
        JdbcFixedFormRepository.class, JdbcFormOptionOrderRepository.class,
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        AttemptDeliveryMySqlIntegrationTest.Config.class})
class AttemptDeliveryMySqlIntegrationTest {
    static final String SCHEMA = "attempt_delivery_" + UUID.randomUUID().toString().replace("-", "");
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
                    return bean instanceof DataSource ds && !(bean instanceof DeliveryDataSource) ? new DeliveryDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired AttemptAccessService access;
    @Autowired AttemptDeliveryService delivery;
    @Autowired org.springframework.context.ApplicationContext context;
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
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
    @Autowired AttemptLifecycleService lifecycle;
    @Autowired SimpleMeterRegistry metrics;
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    static final List<String> TARGET_TABLES = List.of("attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form", "attempt_policy_snapshot");
    DeliveryDataSource observer() { return (DeliveryDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
        username = "delivery-" + UUID.randomUUID().toString().substring(0, 12);
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
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'delivery-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'delivery-%'");
        });
    }


    @ParameterizedTest @EnumSource(AnswerOrder.class)
    void realPublicationStartAndRepeatedReadReturnAllFiveFamiliesWithoutWrites(AnswerOrder order) throws Exception {
        UUID revision = prepared();
        var started = starts.start(command(revision, order, "delivery"));
        var before = targetSnapshot();
        List<String> writes = new ArrayList<>();
        observer().beforeSql = (statement, connection) -> {
            if (statement.stripLeading().matches("(?is)^(INSERT|UPDATE|DELETE).*")) writes.add(statement);
        };
        var first = delivery.read(started.attemptId());
        var resumed = recreated().read(started.attemptId());
        assertThat(first).isEqualTo(resumed);
        assertThat(first.form().id()).isEqualTo(started.formId());
        assertThat(first.form().items()).extracting(uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm.Item::type)
                .containsExactlyElementsOf(FAMILIES);
        assertThat(first.allowedActions()).containsExactly(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.VIEW_ATTEMPT);
        assertThat(first.stateVersion()).isEqualTo(1);
        String response = json.writeValueAsString(uk.gegc.quizmaker.features.attempt.api.dto.AttemptFormResponse.from(first));
        assertThat(response).doesNotContain("correct", "explanation", "protected-explanation", "sourceKey", "binding", "owner", "scoring", "revision");
        assertThat(response).contains("CHOICES", "TRUE_FALSE", "FILL_GAP", "ORDERING");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new uk.gegc.quizmaker.features.attempt.api.AttemptFormController(delivery))
                .setControllerAdvice(new uk.gegc.quizmaker.features.attempt.api.AttemptDeliveryErrorHandler()).build();
        var http = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/attempts/{id}/form", started.attemptId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        assertThat(json.readTree(http.getContentAsString())).isEqualTo(json.readTree(response));
        assertThat(writes).isEmpty(); observer().reset();
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @Test void authorEditsAndNewPublicationCannotReplaceSavedQuestionsOrOptionOrder() {
        var started = starts.start(command(prepared(), AnswerOrder.SHUFFLED_PER_ATTEMPT, "delivery"));
        var first = delivery.read(started.attemptId());
        tx.executeWithoutResult(s -> questions.findAllByQuizId_IdOrderById(quizId).forEach(q -> q.setQuestionText("New author text")));
        var ids = tx.execute(s -> questions.findAllByQuizId_IdOrderById(quizId).stream().map(Question::getId).toList());
        UUID next = publish(1, ids); binding.bind(quizId, next);
        assertThat(next).isNotEqualTo(started.quizRevisionId());
        assertThat(recreated().read(started.attemptId())).isEqualTo(first);
    }

    @Test void permissionRevocationAndWrongUserAreNonEnumeratingWithSuccessfulControls() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
        revoke(ownerId, "ATTEMPT_READ");
        reject(started.attemptId(), "NOT_FOUND"); reject(UUID.randomUUID(), "NOT_FOUND");
        grant(ownerId, "ATTEMPT_READ"); revoke(ownerId, "ATTEMPT_CREATE");
        assertThat(delivery.read(started.attemptId()).allowedActions()).extracting(Enum::name).containsExactly("VIEW_ATTEMPT");
        String owner = username; String other = "delivery-other-" + UUID.randomUUID().toString().substring(0, 8);
        UUID otherId = user(other, true);
        grant(otherId, "ATTEMPT_READ", "ATTEMPT_CREATE", "ATTEMPT_READ_ALL", "QUESTION_UPDATE", "QUESTION_ADMIN");
        authenticate(other);
        var own = starts.start(command(started.quizRevisionId(), AnswerOrder.AUTHORED, "other"));
        assertThat(delivery.read(own.attemptId()).form().items()).hasSize(5);
        reject(started.attemptId(), "NOT_FOUND"); reject(UUID.randomUUID(), "NOT_FOUND");
        SecurityContextHolder.clearContext(); reject(started.attemptId(), "UNAUTHENTICATED");
        authenticate(owner); assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
    }

    @Test void editorLosingAuthorityCannotReadEvenAPublicQuiz() {
        UUID revision = prepared();
        String editor = "delivery-editor-" + UUID.randomUUID().toString().substring(0, 8); UUID editorId = user(editor, false);
        grant(editorId, "ATTEMPT_READ", "ATTEMPT_CREATE", "QUESTION_UPDATE", "QUESTION_ADMIN");
        authenticate(editor); var started = starts.start(command(revision, AnswerOrder.AUTHORED, "editor"));
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
        tx.executeWithoutResult(s -> sql.update("UPDATE quizzes SET visibility='PUBLIC' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString()));
        revoke(editorId, "QUESTION_UPDATE"); reject(started.attemptId(), "NOT_FOUND");
        grant(editorId, "QUESTION_UPDATE"); assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
    }

    @ParameterizedTest @ValueSource(strings = {"policy", "form", "lifecycle"})
    void unknownRootVersionFailsClosedWithoutReadingQuestionContent(String root) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
        String statement = switch (root) {
            case "policy" -> "UPDATE attempt_policy_snapshot SET schema_version=2";
            case "form" -> "UPDATE attempt_form SET schema_version=2";
            default -> "UPDATE attempt_lifecycle_state SET lifecycle_version=2";
        };
        String table = switch (root) { case "policy" -> "attempt_policy_snapshot"; case "form" -> "attempt_form"; default -> "attempt_lifecycle_state"; };
        String check = switch (root) { case "policy" -> "chk_policy_snapshot_version"; case "form" -> "chk_form_version"; default -> "chk_attempt_lifecycle_version"; };
        // Explicit future-version corruption fixture in this generated test catalogue only.
        sql.execute("ALTER TABLE " + table + " ALTER CHECK " + check + " NOT ENFORCED");
        try {
            tx.executeWithoutResult(s -> sql.update(statement));
            var before = targetSnapshot(); observer().reset(); reject(started.attemptId(), "NOT_FOUND");
            assertThat(observer().selects).noneMatch(q -> q.contains("question_revisions"));
            assertThat(targetSnapshot()).isEqualTo(before);
        } finally {
            tx.executeWithoutResult(s -> sql.update(statement.replace("=2", "=1")));
            sql.execute("ALTER TABLE " + table + " ALTER CHECK " + check + " ENFORCED");
        }
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
    }

    @ParameterizedTest @ValueSource(strings = {"order", "item", "policy-json", "binding"})
    void corruptOrUnsupportedEvidenceNeverBecomesAPartialOrRegeneratedForm(String fault) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
        tx.executeWithoutResult(s -> {
            switch (fault) {
                case "order" -> sql.update("DELETE FROM attempt_form_option LIMIT 1");
                case "item" -> sql.update("UPDATE attempt_form SET item_count=item_count+1");
                case "policy-json" -> sql.update("UPDATE attempt_policy_snapshot SET canonical_json='{}'");
                case "binding" -> sql.update("UPDATE question_revision_contract_bindings SET binding_hash=REPEAT('0',64)");
            }
        });
        var before = targetSnapshot();
        reject(started.attemptId(), "CORRUPT");
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(ints = {Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_REPEATABLE_READ})
    void olderCallerSnapshotCannotRestoreInactiveState(int isolation) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        assertThat(delivery.read(started.attemptId()).stateVersion()).isEqualTo(1);
        var old = new TransactionTemplate(manager); old.setIsolationLevel(isolation);
        var separate = new TransactionTemplate(manager); separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        old.executeWithoutResult(s -> {
            sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state", Long.class);
            // A real lifecycle storage fixture, not proof of the separately owned terminal command.
            separate.executeWithoutResult(inner -> lifecycle.save(started.attemptId(), 1,
                    new uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.State(1,
                            uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution.SUBMITTED,
                            uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection.COMPLETE)));
            reject(started.attemptId(), "INACTIVE");
            s.setRollbackOnly(); // Joined read rejects the stale caller transaction as well.
        });
    }

    @ParameterizedTest(name = "isolation={0}, publishAfterSnapshot={1}")
    @CsvSource({"2,false", "4,false", "2,true", "4,true"}) // JDBC READ_COMMITTED / REPEATABLE_READ
    void olderSnapshotCanReadNewlyCommittedAttempt(int isolation, boolean publishAfterSnapshot) {
        UUID revision = publishAfterSnapshot ? null : prepared();
        var old = new TransactionTemplate(manager); old.setIsolationLevel(isolation);
        var separate = new TransactionTemplate(manager);
        // Publication requires no ambient transaction; real publication/start own their commits.
        separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var started = new AtomicReference<AttemptStartService.Result>();
        var result = old.execute(status -> {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class)).isZero();
            started.set(separate.execute(inner -> starts.start(command(
                    publishAfterSnapshot ? prepared() : revision, AnswerOrder.SHUFFLED_PER_ATTEMPT, "new-after-snapshot"))));
            // Prove that the caller still has its original snapshot, while access sees the committed start.
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class))
                    .isEqualTo(isolation == Connection.TRANSACTION_REPEATABLE_READ ? 0 : 1);
            assertThat(access.requireAccess(started.get().attemptId(), AttemptAccessService.Operation.VIEW).formId())
                    .isEqualTo(started.get().formId());
            var view = delivery.read(started.get().attemptId());
            assertThat(view.form().items()).hasSize(5);
            assertThat(status.isRollbackOnly()).isFalse();
            return view;
        });
        var before = targetSnapshot();
        assertThat(recreated().read(started.get().attemptId())).isEqualTo(result);
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @Test void databaseFailureAfterContentReadIsSanitizedAndNoStateChanges() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        var before = targetSnapshot(); AtomicBoolean reached = new AtomicBoolean();
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("question_option_revisions")) {
                reached.set(true); throw new SQLException("private-content-must-not-escape", "08006");
            }
        };
        reject(started.attemptId(), "UNAVAILABLE"); assertThat(reached).isTrue(); observer().reset();
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
    }

    @Test void completeProjectionQueryCountIsBoundedAcrossSeveralParentsAndQuestionCounts() throws Exception {
        List<Integer> totals = new ArrayList<>();
        for (int size : List.of(1, 20)) {
            List<AttemptStartService.Result> attempts = new ArrayList<>(); List<String> names = new ArrayList<>();
            for (int parent = 0; parent < 3; parent++) {
                username = "delivery-parent-" + UUID.randomUUID().toString().substring(0, 8);
                ownerId = user(username, true); grant(ownerId, "ATTEMPT_READ", "ATTEMPT_CREATE");
                quizId = newQuiz(); authenticate(username);
                UUID revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(QuestionType.MCQ_SINGLE)).toList());
                binding.bind(quizId, revision); attempts.add(starts.start(command(revision, AnswerOrder.SHUFFLED_PER_ATTEMPT, "count")));
                names.add(username);
            }
            observer().reset();
            for (int i = 0; i < attempts.size(); i++) {
                authenticate(names.get(i));
                var view = delivery.read(attempts.get(i).attemptId());
                assertThat(view.form().items()).hasSize(size);
                var serialized = json.writeValueAsString(uk.gegc.quizmaker.features.attempt.api.dto.AttemptFormResponse.from(view));
                assertThat(json.readTree(serialized).path("items")).hasSize(size);
            }
            totals.add(observer().selects.size());
        }
        assertThat(totals.get(1)).isEqualTo(totals.get(0));
        assertThat(totals.get(0)).isLessThanOrEqualTo(90);
        System.out.println("Delivery SQL counts for 3 parents with 1/20 questions: " + totals);
    }

    @Test void caughtDeliveryFailureStillRollsBackOuterWrite() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "rollback"));
        var before = targetSnapshot();
        String originalTitle = sql.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quizId.toString());
        AtomicBoolean reached = new AtomicBoolean();
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("question_option_revisions")) {
                reached.set(true); throw new SQLException("synthetic private diagnostic", "08006");
            }
        };
        try {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                assertThat(sql.update("UPDATE quizzes SET title='rollback marker' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString())).isEqualTo(1);
                reject(started.attemptId(), "UNAVAILABLE");
                assertThat(status.isRollbackOnly()).isTrue();
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        } finally { observer().reset(); }
        assertThat(reached).isTrue();
        assertThat(sql.queryForObject("SELECT title FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quizId.toString())).isEqualTo(originalTitle);
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
    }

    @Test void concurrentReadersBothReachProjectionWithoutConflicts() throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "concurrent-reads"));
        var expected = delivery.read(started.attemptId());
        var before = targetSnapshot();
        var arrived = new CountDownLatch(2); var release = new CountDownLatch(1);
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("question_option_revisions")) { arrived.countDown(); await(release); }
        };
        var pool = Executors.newFixedThreadPool(2);
        Callable<AttemptDeliveryService.View> read = () -> {
            authenticate(username);
            try { return delivery.read(started.attemptId()); }
            finally { SecurityContextHolder.clearContext(); }
        };
        var one = pool.submit(read); var two = pool.submit(read);
        try {
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(observer().sqlErrorCodes).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); observer().reset(); }
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    private AttemptDeliveryService recreated() {
        return new AttemptDeliveryServiceImpl(context.getBean(AttemptReadFactsService.class), manager, metrics, context.getBean(Clock.class));
    }
    @Test void authorityRemainsLockedThroughActualContentProjection() throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        var reading = new CountDownLatch(1); var release = new CountDownLatch(1); var revokeIssued = new CountDownLatch(1);
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("question_option_revisions")) { reading.countDown(); await(release); }
            if (statement.startsWith("DELETE rp FROM role_permissions")) revokeIssued.countDown();
        };
        var workers = Executors.newFixedThreadPool(2);
        Future<AttemptDeliveryService.View> reader = workers.submit(() -> {
            authenticate(username);
            try { return delivery.read(started.attemptId()); }
            finally { SecurityContextHolder.clearContext(); }
        });
        Future<?> revoker = null;
        try {
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            revoker = workers.submit(() -> revoke(ownerId, "ATTEMPT_READ"));
            assertThat(revokeIssued.await(5, TimeUnit.SECONDS)).isTrue();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
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
            release.countDown();
            try {
                assertThat(reader.get(10, TimeUnit.SECONDS).form().items()).hasSize(5);
                if (revoker != null) revoker.get(10, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); observer().reset(); }
        }
        reject(started.attemptId(), "NOT_FOUND");
        grant(ownerId, "ATTEMPT_READ"); assertThat(delivery.read(started.attemptId()).form().items()).hasSize(5);
    }

    @Test void telemetryFailureDoesNotReplaceSuccessfulFormOrAccessDenial() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        var broken = org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
        org.mockito.Mockito.when(broken.config()).thenThrow(new IllegalStateException("synthetic metrics outage"));
        var checked = new AttemptDeliveryServiceImpl(context.getBean(AttemptReadFactsService.class), manager, broken, context.getBean(Clock.class));
        assertThat(checked.read(started.attemptId())).isEqualTo(delivery.read(started.attemptId()));
        revoke(ownerId, "ATTEMPT_READ");
        assertThatThrownBy(() -> checked.read(started.attemptId())).isInstanceOfSatisfying(
                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                failure -> assertThat(failure.reason().name()).isEqualTo("NOT_FOUND"));
    }

    private void reject(UUID attempt, String reason) {
        assertThatThrownBy(() -> delivery.read(attempt)).isInstanceOfSatisfying(
                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class, failure -> {
                    assertThat(failure.reason().name()).isEqualTo(reason);
                    assertThat(failure.getMessage()).isEqualTo("Attempt delivery rejected: " + reason);
                    assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
                });
    }

    private void revoke(UUID userId, String permission) {
        tx.executeWithoutResult(status -> sql.update("""
                DELETE rp FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name=?
                """, userId.toString(), permission));
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
            q.setContent(content.toString()); q.setExplanation("protected-explanation");
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
            String role = "delivery-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
    }

    private UUID newQuiz() {
        return tx.execute(s -> {
            var category = new Category(); category.setName("delivery-" + UUID.randomUUID()); categories.saveAndFlush(category);
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
            String role = "delivery-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            for (String name : names) assertThat(sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, name)).isEqualTo(1);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", userId.toString(), role);
        });
    }

}
