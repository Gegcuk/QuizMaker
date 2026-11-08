package uk.gegc.quizmaker.features.attempt.draft;

import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;

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

import uk.gegc.quizmaker.features.attempt.domain.model.draft.*;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Reference;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.Reason.*;
import static org.assertj.core.api.Assertions.*;

/** Real publication/start/acceptance/submission; the internal storage fixture does not activate draft policies. */
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
        ResponseAcceptanceServiceImpl.class, JdbcAcceptedResponseRepository.class,
        AnswerDraftStoreImpl.class, JdbcAnswerDraftRepository.class,
        AttemptSubmissionServiceImpl.class, AttemptTerminalSubmissionImpl.class, AcceptedSubmissionResponseFinalizer.class, JdbcAttemptSubmissionRepository.class,
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        AnswerDraftMySqlIntegrationTest.Config.class})
class AnswerDraftMySqlIntegrationTest {
    static final String SCHEMA = "answer_drafts_" + UUID.randomUUID().toString().replace("-", "");
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
                    return bean instanceof DataSource ds && !(bean instanceof DraftDataSource) ? new DraftDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired AnswerDraftStore drafts;
    @Autowired AttemptSubmissionService submissions;
    @Autowired AttemptAccessService access;
    @Autowired ResponseAcceptanceService responses;
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
    static final List<String> TARGET_TABLES = List.of("attempt_answer_draft", "attempt_result_release", "attempt_result_created", "attempt_automatic_grade", "attempt_automatic_result", "attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form", "attempt_policy_snapshot");
    DraftDataSource observer() { return (DraftDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
        username = "acceptance-" + UUID.randomUUID().toString().substring(0, 12);
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
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'acceptance-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'acceptance-%'");
        });
    }


    /** #541 deliberately calls the internal store: it does not enable drafts in FREE_PRACTICE. */
    record Fixture(AttemptStartService.Result start, Reference reference) { }

    private Fixture fixture() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "draft-start"));
        var items = delivery.read(started.attemptId()).form().items();
        // A real accepted control already exists, on a different item of this attempt.
        responses.accept(answer(started.attemptId(), items.get(0), 1, "control-answer"));
        return new Fixture(started, reference(started, items.get(2)));
    }

    private Reference reference(AttemptStartService.Result start, ParticipantForm.Item item) {
        return tx.execute(s -> sql.queryForObject("""
                SELECT BIN_TO_UUID(o.question_revision_id) question_id,b.response_contract_id,b.response_contract_version
                FROM attempt_form_item_option_order o JOIN question_revision_contract_bindings b
                ON b.question_revision_id=o.question_revision_id
                WHERE o.form_id=UUID_TO_BIN(?) AND o.item_id=UUID_TO_BIN(?)
                """, (r, n) -> new Reference(start.attemptId(), start.formId(), item.id(),
                UUID.fromString(r.getString("question_id")), new ContractVersion(r.getString("response_contract_id"),
                r.getInt("response_contract_version"))), start.formId().toString(), item.id().toString()));
    }

    private AnswerDraftStore recreated() {
        return new AnswerDraftStoreImpl(new JdbcAnswerDraftRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source)),
                lifecycle, context.getBean(QuestionContractRegistry.class), manager, context.getBean(Clock.class), metrics);
    }

    @Test void realControlAndDraftSaveUpdateDeleteRecreateSurviveNewPersistenceInstances() {
        var f = fixture(); var before = targetSnapshot();
        assertThat(drafts.read(ownerId, f.reference())).isEmpty();
        var first = drafts.save(ownerId, f.reference(), 0, " { \"answer\" : true } ");
        assertThat(first.version()).isEqualTo(1); assertThat(first.normalizedResponse()).isEqualTo("{\"answer\":true}");
        assertThat(first.status()).isEqualTo(AnswerDraft.Status.PRESENT);
        var updated = recreated().save(ownerId, f.reference(), 1, "{\"answer\":false}");
        assertThat(updated.version()).isEqualTo(2); assertThat(updated.createdAt()).isEqualTo(first.createdAt());
        assertThat(recreated().read(ownerId, f.reference())).contains(updated);
        var deleted = recreated().delete(ownerId, f.reference(), 2);
        assertThat(deleted.version()).isEqualTo(3); assertThat(deleted.status()).isEqualTo(AnswerDraft.Status.DELETED);
        assertThat(deleted.normalizedResponse()).isNull(); assertThat(recreated().read(ownerId, f.reference())).contains(deleted);
        assertThat(sql.queryForObject("SELECT normalized_response FROM attempt_answer_draft", String.class)).isNull();
        var restored = recreated().save(ownerId, f.reference(), 3, "{\"answer\":true}");
        assertThat(restored.version()).isEqualTo(4); assertThat(restored.createdAt()).isEqualTo(first.createdAt());
        assertThat(recreated().read(ownerId, f.reference())).contains(restored);
        assertThat(count("attempt_answer_draft")).isEqualTo(1);
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(metrics.find("attempt.draft.age").summary().count()).isPositive();
    }

    @Test void everyDeliveredFamilyUsesItsRealSavedResponseContractWithoutGrading() {
        var start = starts.start(command(prepared(), AnswerOrder.AUTHORED, "all-families"));
        var before = targetSnapshot();
        for (var item : delivery.read(start.attemptId()).form().items()) {
            var ref = reference(start, item);
            var raw = QuestionContracts.example(item.type(), QuestionContracts.View.RESPONSE).toString();
            var saved = drafts.save(ownerId, ref, 0, raw);
            assertThat(recreated().read(ownerId, ref)).contains(saved);
            assertThat(saved.reference().responseContract()).isEqualTo(ref.responseContract());
        }
        assertThat(count("attempt_answer_draft")).isEqualTo(5); assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"save", "delete"})
    void staleOperationsCannotOverwriteNewValuesOrResetTheVersionAfterDeletion(String operation) {
        var f = fixture();
        drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}");
        var newer = drafts.save(ownerId, f.reference(), 1, "{\"answer\":false}");
        assertRejected(() -> operation.equals("save") ? drafts.save(ownerId, f.reference(), 1, "{\"answer\":true}")
                : drafts.delete(ownerId, f.reference(), 1), CONFLICT);
        assertThat(recreated().read(ownerId, f.reference())).contains(newer);
        drafts.delete(ownerId, f.reference(), 2);
        assertRejected(() -> drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}"), CONFLICT);
        var restored = drafts.save(ownerId, f.reference(), 3, "{\"answer\":true}");
        assertRejected(() -> drafts.delete(ownerId, f.reference(), 3), CONFLICT);
        assertThat(recreated().read(ownerId, f.reference())).contains(restored);
    }

    @ParameterizedTest @CsvSource({"2,create", "4,create", "2,update", "4,update", "2,delete-save", "4,delete-save", "2,delete", "4,delete"})
    void independentTransactionsHaveOneWinnerAndOneConflict(int isolation, String variant) throws Exception {
        var f = fixture();
        long expected = variant.equals("create") ? 0 : drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}").version();
        var before = targetSnapshot(); var gate = new CyclicBarrier(2);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<Future<Object>>();
            for (int worker = 0; worker < 2; worker++) {
                final int index = worker;
                jobs.add(workers.submit(() -> {
                    var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                    try { return transaction.execute(s -> {
                        try { gate.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
                        return variant.equals("delete") || variant.equals("delete-save") && index == 0
                                ? drafts.delete(ownerId, f.reference(), expected)
                                : drafts.save(ownerId, f.reference(), expected, "{\"answer\":" + (index == 0) + "}");
                    }); } catch (AnswerDraftException e) { return e.reason(); }
                }));
            }
            var results = List.of(jobs.get(0).get(20, TimeUnit.SECONDS), jobs.get(1).get(20, TimeUnit.SECONDS));
            assertThat(results.stream().filter(AnswerDraft.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(CONFLICT::equals)).hasSize(1);
            var winner = (AnswerDraft) results.stream().filter(AnswerDraft.class::isInstance).findFirst().orElseThrow();
            assertThat(winner.version()).isEqualTo(expected + 1);
            assertThat(recreated().read(ownerId, f.reference())).contains(winner);
            assertThat(count("attempt_answer_draft")).isEqualTo(1); assertThat(targetSnapshot()).isEqualTo(before);
        } finally { workers.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings = {"insert", "update", "delete"})
    void reachedSqlFailureRollsBackDraftAndPreservesAllAuthoritativeEvidence(String operation) {
        var f = fixture(); var reached = new AtomicBoolean();
        if (!operation.equals("insert")) drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}");
        var before = targetSnapshot(); var old = drafts.read(ownerId, f.reference());
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("INTO attempt_answer_draft") || statement.contains("UPDATE attempt_answer_draft")) {
                reached.set(true); throw new SQLException("private-draft-fault-marker", "08006");
            }
        };
        assertRejected(() -> operation.equals("delete") ? drafts.delete(ownerId, f.reference(), 1)
                : drafts.save(ownerId, f.reference(), operation.equals("insert") ? 0 : 1, "{\"answer\":false}"), UNAVAILABLE);
        observer().reset(); assertThat(reached).isTrue();
        assertThat(recreated().read(ownerId, f.reference())).isEqualTo(old); assertThat(targetSnapshot()).isEqualTo(before);
        var retried = operation.equals("delete") ? drafts.delete(ownerId, f.reference(), 1)
                : drafts.save(ownerId, f.reference(), operation.equals("insert") ? 0 : 1, "{\"answer\":false}");
        assertThat(recreated().read(ownerId, f.reference())).contains(retried);
    }

    @Test void laterOuterRollbackAndRecreatedServiceLeaveNoDraft() {
        var f = fixture(); var before = targetSnapshot(); var reached = new AtomicBoolean();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}"); reached.set(true);
            throw new IllegalStateException("test outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reached).isTrue(); assertThat(recreated().read(ownerId, f.reference())).isEmpty();
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"attempt", "form", "item", "question", "contract", "contract-version"})
    void mismatchedImmutableReferencesCannotReadOrWriteAnotherItem(String field) {
        var f = fixture(); var r = f.reference(); var unknown = UUID.randomUUID();
        var bad = new Reference(field.equals("attempt") ? unknown : r.attemptId(), field.equals("form") ? unknown : r.formId(),
                field.equals("item") ? unknown : r.formItemId(), field.equals("question") ? unknown : r.questionRevisionId(),
                field.equals("contract") ? new ContractVersion("unknown-response", 1)
                        : field.equals("contract-version") ? new ContractVersion(r.responseContract().id(), 2) : r.responseContract());
        var saved = drafts.save(ownerId, r, 0, "{\"answer\":true}"); var before = targetSnapshot();
        assertRejected(() -> drafts.save(ownerId, bad, 0, "{\"answer\":false}"), NOT_FOUND);
        assertRejected(() -> drafts.delete(ownerId, bad, 1), NOT_FOUND);
        if (field.equals("attempt")) assertThat(drafts.read(ownerId, bad)).isEmpty();
        else assertRejected(() -> drafts.read(ownerId, bad), NOT_FOUND);
        assertThat(recreated().read(ownerId, r)).contains(saved); assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "inactive", "deleted"})
    void subjectPrivacyIsRecheckedOnReadsAndWrites(String variant) {
        var f = fixture(); var saved = drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}");
        UUID reader = variant.equals("foreign") ? user("foreign-" + UUID.randomUUID(), false) : ownerId;
        if (!variant.equals("foreign")) tx.executeWithoutResult(s -> sql.update("UPDATE users SET "
                + (variant.equals("inactive") ? "is_active=FALSE" : "is_deleted=TRUE") + " WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        assertThat(drafts.read(reader, f.reference())).isEmpty();
        assertRejected(() -> drafts.save(reader, f.reference(), 1, "{\"answer\":false}"), NOT_FOUND);
        assertRejected(() -> drafts.delete(reader, f.reference(), 1), NOT_FOUND);
        tx.executeWithoutResult(s -> sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        assertThat(recreated().read(ownerId, f.reference())).contains(saved);
    }

    @Test void noTargetAndPartialTargetNeverAcquireInventedDrafts() {
        UUID legacyId = tx.execute(s -> {
            var attempt = new uk.gegc.quizmaker.features.attempt.domain.model.Attempt();
            attempt.setUser(users.findById(ownerId).orElseThrow()); attempt.setQuiz(quizzes.findById(quizId).orElseThrow());
            attempt.setMode(uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode.ALL_AT_ONCE);
            attempt.setStatus(uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus.IN_PROGRESS);
            return context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository.class).saveAndFlush(attempt).getId();
        });
        var legacyRef = new Reference(legacyId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new ContractVersion("true-false-response", 1));
        assertThat(drafts.read(ownerId, legacyRef)).isEmpty();
        assertRejected(() -> drafts.save(ownerId, legacyRef, 0, "{\"answer\":true}"), NOT_FOUND);
        tx.executeWithoutResult(s -> sql.update("DELETE FROM attempts WHERE id=UUID_TO_BIN(?)", legacyId.toString()));
        var r = new Reference(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new ContractVersion("true-false-response", 1));
        assertThat(drafts.read(ownerId, r)).isEmpty();
        assertRejected(() -> drafts.save(ownerId, r, 0, "{\"answer\":true}"), NOT_FOUND);
        context.getBean(AttemptSubjectService.class).bindOrReplay(ownerId,
                uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject.authenticated(r.attemptId(), ownerId));
        assertRejected(() -> drafts.read(ownerId, r), NOT_FOUND);
        assertRejected(() -> drafts.save(ownerId, r, 0, "{\"answer\":true}"), NOT_FOUND);
        assertThat(count("attempt_answer_draft")).isZero();
    }

    @Test void acceptedItemsAndRealTerminalSubmissionCannotBeEditedOrPromoteStoredFixtureValues() {
        var f = fixture(); var items = delivery.read(f.start().attemptId()).form().items();
        assertRejected(() -> drafts.save(ownerId, reference(f.start(), items.get(0)), 0,
                QuestionContracts.example(items.get(0).type(), QuestionContracts.View.RESPONSE).toString()), INACTIVE);
        var saved = drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}");
        var submitted = submissions.submit(new AttemptSubmissionService.Command(f.start().attemptId(), 2, true, CommandReceipt.Key.from("terminal")));
        assertThat(submitted.submissionId()).isNotNull();
        var before = targetSnapshot();
        assertRejected(() -> drafts.save(ownerId, f.reference(), 1, "{\"answer\":false}"), INACTIVE);
        assertRejected(() -> drafts.delete(ownerId, f.reference(), 1), INACTIVE);
        assertThat(recreated().read(ownerId, f.reference())).contains(saved);
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT accepted_count FROM attempt_submission", Integer.class)).isEqualTo(1);
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "", "[]", "{}", "{\"answer\":\"true\"}", "{\"answer\":true} false", "{\"answer\":true,\"answer\":false}"})
    void invalidResponseShapesAndAmbiguousJsonWriteNothing(String raw) {
        var f = fixture(); var before = targetSnapshot();
        assertRejected(() -> drafts.save(ownerId, f.reference(), 0, raw), INVALID_INPUT);
        assertThat(count("attempt_answer_draft")).isZero(); assertThat(targetSnapshot()).isEqualTo(before);
    }

    @Test void byteLimitIncludesUtf8AndAcceptsTheExactExistingBoundary() {
        var f = fixture();
        var value = "{\"answer\":true}";
        var exact = value + " ".repeat(65_536 - value.length());
        assertThat(drafts.save(ownerId, f.reference(), 0, exact).normalizedResponse()).isEqualTo(value);
        assertRejected(() -> drafts.save(ownerId, f.reference(), 1, exact + " "), INVALID_INPUT);
        assertRejected(() -> drafts.save(ownerId, f.reference(), 1, "{\"answer\":true,\"extra\":\"" + "😀".repeat(20_000) + "\"}"), INVALID_INPUT);
        assertThat(recreated().read(ownerId, f.reference()).orElseThrow().version()).isEqualTo(1);
    }

    @Test void readQueryCountIsBoundedAcrossMultipleAttemptsAndGrowingForms() {
        var totals = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var refs = new ArrayList<Reference>();
            for (int parent = 0; parent < 3; parent++) {
                quizId = newQuiz();
                var revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList());
                binding.bind(quizId, revision);
                var start = starts.start(command(revision, AnswerOrder.AUTHORED, "bounded-" + parent + "-" + size));
                for (var item : delivery.read(start.attemptId()).form().items()) {
                    var ref = reference(start, item); drafts.save(ownerId, ref, 0, "{\"answer\":true}");
                    if (item.position() == 0) refs.add(ref);
                }
            }
            observer().reset();
            for (var ref : refs) assertThat(recreated().read(ownerId, ref)).isPresent();
            totals.add(observer().selects.size());
        }
        assertThat(totals).containsExactly(12, 12);
        System.out.println("Draft reads for 3 attempts with 1/20 items: " + totals);
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void oldCallerSnapshotReadsTheCommittedWinnerAndCanUpdateIt(int isolation) throws Exception {
        var f = fixture(); var snapshot = new CountDownLatch(1); var committed = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var future = worker.submit(() -> {
                var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                return transaction.execute(s -> {
                    assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_answer_draft", Integer.class)).isZero();
                    snapshot.countDown(); await(committed);
                    var saved = recreated().read(ownerId, f.reference()).orElseThrow();
                    assertThat(saved.version()).isEqualTo(1);
                    return drafts.save(ownerId, f.reference(), 1, "{\"answer\":false}");
                });
            });
            await(snapshot); drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}"); committed.countDown();
            assertThat(future.get(20, TimeUnit.SECONDS).version()).isEqualTo(2);
            assertThat(recreated().read(ownerId, f.reference()).orElseThrow().normalizedResponse()).isEqualTo("{\"answer\":false}");
        } finally { committed.countDown(); worker.shutdownNow(); }
    }

    @Test void lostCommitAcknowledgementIsReconciledByTheDurableVersionWithoutAnotherInsert() {
        var f = fixture(); var before = targetSnapshot(); observer().failAfterCommit.set(true);
        assertRejected(() -> drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}"), UNAVAILABLE);
        observer().reset(); var saved = recreated().read(ownerId, f.reference()).orElseThrow();
        assertThat(saved.version()).isEqualTo(1); assertThat(saved.normalizedResponse()).isEqualTo("{\"answer\":true}");
        assertRejected(() -> drafts.save(ownerId, f.reference(), 0, "{\"answer\":false}"), CONFLICT);
        assertThat(count("attempt_answer_draft")).isEqualTo(1); assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @CsvSource({"1213,RETRYABLE", "1205,BUSY", "0,UNAVAILABLE"})
    void databaseFailuresAreSanitizedBeforeTransactionalDebugLogging(int code, AnswerDraftException.Reason reason) {
        var f = fixture(); var reached = new AtomicBoolean();
        var markers = new String[] {"private-draft-fault-marker", "private-draft-cause-marker", "private-draft-suppressed-marker"};
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TransactionTemplate.class);
        var previous = logger.getLevel(); var requestThread = Thread.currentThread();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                if (Thread.currentThread() == requestThread) {
                    event.prepareForDeferredProcessing(); super.append(event);
                }
            }
        };
        appender.setContext(logger.getLoggerContext());
        appender.start(); logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            observer().afterSql = (statement, connection) -> {
                if (statement.contains("FROM attempt_trace_stream")) {
                    reached.set(true);
                    var failure = new SQLException(markers[0], "HY000", code, new IllegalStateException(markers[1]));
                    failure.addSuppressed(new IllegalStateException(markers[2]));
                    throw failure;
                }
            };
            assertRejected(() -> drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}"), reason);
            assertThat(reached).isTrue();
            // TransactionTemplate logs callback failures; manager-only or empty captures prove nothing.
            assertThat(appender.list).anySatisfy(event -> {
                assertThat(event.getLoggerName()).isEqualTo(TransactionTemplate.class.getName());
                assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.DEBUG);
                assertThat(event.getThrowableProxy()).isNotNull();
            });
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(markers);
                // The rendered throwable includes nested causes and suppressed exceptions.
                if (event.getThrowableProxy() != null) assertThat(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain(markers);
            });
        } finally { observer().reset(); logger.detachAppender(appender); logger.setLevel(previous); appender.stop(); }
        assertThat(drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}").version()).isEqualTo(1);
    }

    @Test void schemaRejectsInvalidMetadataPayloadsDuplicateKeysAndCrossQuestionBindings() {
        var f = fixture(); var saved = drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}");
        for (String assignment : List.of("schema_version=2", "row_version=0", "deleted=TRUE",
                "normalized_response='[]'", "normalized_response='invalid-json'", "normalized_response=REPEAT('x',65537)",
                "updated_at='1000-01-01'")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> sql.update("UPDATE attempt_answer_draft SET " + assignment)))
                    .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, e ->
                            assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                                    sqlFailure -> assertThat(sqlFailure.getErrorCode()).isIn(3819, 3140, 3141)));
            assertThat(recreated().read(ownerId, f.reference())).contains(saved);
        }
        var another = delivery.read(f.start().attemptId()).form().items().get(1);
        var otherRef = reference(f.start(), another);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> sql.update("UPDATE attempt_answer_draft SET question_revision_id=UUID_TO_BIN(?)",
                otherRef.questionRevisionId().toString()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> sql.update("UPDATE attempt_answer_draft SET response_contract_version=999")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> sql.update("INSERT INTO attempt_answer_draft SELECT * FROM attempt_answer_draft")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(recreated().read(ownerId, f.reference())).contains(saved);
    }

    @Test void unknownSavedContractAndCorruptPersistedResponseFailClosed() {
        var f = fixture(); var ref = f.reference();
        var saved = drafts.save(ownerId, ref, 0, "{\"answer\":true}");
        tx.executeWithoutResult(s -> sql.update("UPDATE attempt_answer_draft SET normalized_response='{}'"));
        assertRejected(() -> drafts.read(ownerId, ref), CORRUPT);
        tx.executeWithoutResult(s -> sql.update("UPDATE attempt_answer_draft SET normalized_response=?", saved.normalizedResponse()));
        assertThat(drafts.read(ownerId, ref)).contains(saved);
        // A deployment missing the retained adapter must reject the exact real saved version.
        var unavailableRegistry = new QuestionContractRegistryImpl(List.of(), List.of(), metrics);
        var unavailable = new AnswerDraftStoreImpl(context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AnswerDraftRepository.class),
                lifecycle, unavailableRegistry, manager, context.getBean(Clock.class), metrics);
        assertRejected(() -> unavailable.read(ownerId, ref), UNSUPPORTED);
        assertRejected(() -> unavailable.delete(ownerId, ref, 1), UNSUPPORTED);
        drafts.delete(ownerId, ref, 1);
        assertRejected(() -> unavailable.read(ownerId, ref), UNSUPPORTED);
        tx.executeWithoutResult(s -> sql.update("DELETE FROM attempt_answer_draft"));
        assertRejected(() -> unavailable.save(ownerId, ref, 0, "{\"answer\":true}"), UNSUPPORTED);
        assertThat(count("attempt_answer_draft")).isZero();
        assertThat(drafts.save(ownerId, ref, 0, "{\"answer\":true}").version()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void firstDraftsInDifferentAttemptsDoNotDeadlockOnMissingKeyGaps(int isolation) throws Exception {
        var first = fixture();
        var second = starts.start(command(first.start().quizRevisionId(), AnswerOrder.AUTHORED, "independent-start"));
        var secondRef = reference(second, delivery.read(second.attemptId()).form().items().get(2));
        var refs = List.of(first.reference(), secondRef); var gate = new CyclicBarrier(2);
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("INTO attempt_answer_draft")) {
                try { gate.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
            }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var jobs = refs.stream().map(ref -> workers.submit(() -> {
                var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                return transaction.execute(s -> drafts.save(ownerId, ref, 0, "{\"answer\":true}"));
            })).toList();
            for (var job : jobs) assertThat(job.get(20, TimeUnit.SECONDS).version()).isEqualTo(1);
            observer().reset();
            for (var ref : refs) assertThat(recreated().read(ownerId, ref)).isPresent();
            assertThat(count("attempt_answer_draft")).isEqualTo(2);
        } finally { observer().reset(); workers.shutdownNow(); }
    }

    @Test void optionalMetricsCannotChangeSuccessfulWritesOrReads() {
        var f = fixture();
        var broken = new SimpleMeterRegistry();
        try {
            io.micrometer.core.instrument.Timer.builder("attempt.draft.storage").tags("version", "1", "operation", "create", "outcome", "returned").register(broken);
            broken.counter("attempt.draft.age", "version", "1");
            var store = new AnswerDraftStoreImpl(context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AnswerDraftRepository.class),
                    lifecycle, context.getBean(QuestionContractRegistry.class), manager, context.getBean(Clock.class), broken);
            var saved = store.save(ownerId, f.reference(), 0, "{\"answer\":true}");
            assertThat(store.read(ownerId, f.reference())).contains(saved);
        } finally { broken.close(); }
    }

    @Test void nullInputsInvalidVersionsAndClockRegressionLeaveExistingDataIntact() {
        var f = fixture(); var saved = drafts.save(ownerId, f.reference(), 0, "{\"answer\":true}");
        assertRejected(() -> drafts.read(null, f.reference()), INVALID_INPUT);
        assertRejected(() -> drafts.read(ownerId, null), INVALID_INPUT);
        assertRejected(() -> drafts.save(ownerId, f.reference(), -1, "{}"), INVALID_INPUT);
        assertRejected(() -> drafts.save(ownerId, f.reference(), Long.MAX_VALUE, "{}"), INVALID_INPUT);
        assertRejected(() -> drafts.save(ownerId, f.reference(), 1, null), INVALID_INPUT);
        var earlier = new AnswerDraftStoreImpl(context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AnswerDraftRepository.class),
                lifecycle, context.getBean(QuestionContractRegistry.class), manager, Clock.fixed(saved.updatedAt().minusSeconds(1), ZoneOffset.UTC), metrics);
        assertRejected(() -> earlier.save(ownerId, f.reference(), 1, "{\"answer\":false}"), INVALID_INPUT);
        assertThat(recreated().read(ownerId, f.reference())).contains(saved);
    }

    private static void assertRejected(java.util.concurrent.Callable<?> call, AnswerDraftException.Reason reason) {
        assertThatThrownBy(call::call).isInstanceOfSatisfying(AnswerDraftException.class,
                e -> { assertThat(e.reason()).isEqualTo(reason); assertThat(e.getSuppressed()).isEmpty(); }).hasNoCause();
    }

    private long count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)); }
    private ResponseAcceptanceService.Command answer(UUID attempt, ParticipantForm.Item item, long version, String key) {
        return new ResponseAcceptanceService.Command(attempt, item.id(), version, CommandReceipt.Key.from(key), item.type(), stableInput(item));
    }
    private ObjectNode stableInput(ParticipantForm.Item item) {
        var value = json.createObjectNode();
        switch (item.type()) {
            case TRUE_FALSE -> value.put("answer", true);
            case MCQ_SINGLE -> value.put("selectedOptionId", ((ParticipantContent.Choices) item.content()).options().get(0).id().toString());
            case MCQ_MULTI -> value.putArray("selectedOptionIds").add(((ParticipantContent.Choices) item.content()).options().get(0).id().toString());
            case ORDERING -> { var ids = value.putArray("orderedItemIds"); ((ParticipantContent.Ordering) item.content()).items().forEach(o -> ids.add(o.id().toString())); }
            case FILL_GAP -> { var entries = value.putArray("answers"); ((ParticipantContent.FillGap) item.content()).gaps()
                    .forEach(g -> entries.addObject().put("gapId", g.id()).put("answer", "Paris")); }
            default -> throw new AssertionError(item.type());
        }
        return value;
    }

    private Map<String, List<String>> targetSnapshot() {
        return tx.execute(status -> {
            Map<String, List<String>> result = new TreeMap<>();
            TARGET_TABLES.stream().filter(t -> !t.equals("attempt_answer_draft")).forEach(table -> result.put(table, sql.queryForList("SELECT * FROM " + table).stream().map(row -> {
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
            String role = "acceptance-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
    }

    private UUID newQuiz() {
        return tx.execute(s -> {
            var category = new Category(); category.setName("acceptance-" + UUID.randomUUID()); categories.saveAndFlush(category);
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
            String role = "acceptance-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            for (String name : names) assertThat(sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, name)).isEqualTo(1);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", userId.toString(), role);
        });
    }

}
