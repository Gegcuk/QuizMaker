package uk.gegc.quizmaker.features.attempt.submission;

import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubmissionRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.CommandReceiptRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
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
        AttemptSubmissionServiceImpl.class, AttemptTerminalSubmissionImpl.class, AcceptedSubmissionResponseFinalizer.class, JdbcAttemptSubmissionRepository.class,
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        AttemptSubmissionMySqlIntegrationTest.Config.class})
class AttemptSubmissionMySqlIntegrationTest {
    static final String SCHEMA = "attempt_submission_" + UUID.randomUUID().toString().replace("-", "");
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
                    return bean instanceof DataSource ds && !(bean instanceof SubmissionDataSource) ? new SubmissionDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired AttemptSubmissionService submissions;
    @Autowired AttemptTerminalSubmission terminal;
    @Autowired SubmissionResponseFinalizer finalizer;
    @Autowired AttemptReadFactsService facts;
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
    static final List<String> TARGET_TABLES = List.of("attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form", "attempt_policy_snapshot");
    SubmissionDataSource observer() { return (SubmissionDataSource) source; }
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



    record Fixture(UUID attempt, List<ParticipantForm.Item> items, long version) { }

    private Fixture fixture(boolean requireAll, int size, int answered) {
        var revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList());
        binding.bind(quizId, revision);
        var started = starts.start(command(revision, AnswerOrder.AUTHORED, "start-" + UUID.randomUUID()));
        UUID attempt = started.attemptId();
        if (requireAll) {
            // The public minimal starter owns its fixed OFF preset. Build a separate captured ON
            // fixture through real foundation services, without mutating any captured policy.
            // This proves submission of a supported saved policy, not a new ON start capability.
            UUID other = UUID.randomUUID();
            tx.executeWithoutResult(s -> {
                var original = facts.read(started.attemptId(), AttemptReadFactsService.Purpose.SUBMIT_ATTEMPT);
                var policy = withRequireAll(original.policy(), true);
                var resolution = new uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat()
                        .encode(BuiltInPracticePreset.REFERENCE, policy, Map.of());
                var snapshot = context.getBean(PolicySnapshotService.class).createOrReplay(ownerId,
                        new uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot(UUID.randomUUID(), resolution));
                var subject = context.getBean(AuthenticatedSubjectResolver.class).resolve(other);
                context.getBean(AttemptSubjectService.class).bindOrReplay(ownerId, subject);
                var initial = lifecycle.initialize(other);
                var next = lifecycle.save(other, 0, new AttemptLifecycle.State(1, AttemptLifecycle.Execution.IN_PROGRESS,
                        AttemptLifecycle.AnswerCollection.NOT_STARTED));
                var scope = new ExecutionTrace.Scope(other, snapshot.id(), original.form().id());
                for (var type : List.of(ExecutionTrace.Type.FORM_CREATED,
                        ExecutionTrace.Type.ATTEMPT_STARTED))
                    context.getBean(ExecutionTraceService.class).append(scope, new ExecutionTrace.Event(
                            1, UUID.randomUUID(), type, ExecutionTrace.Actor.SYSTEM,
                            new ExecutionTrace.Data(null, null, initial.state(), next.state(),
                                    ExecutionTrace.ReasonCode.ACCEPTED)));
            });
            attempt = other;
        }
        var items = delivery.read(attempt).form().items();
        for (int n = 0; n < answered; n++) responses.accept(answer(attempt, items.get(n), n + 1, "accepted-" + n + "-" + attempt));
        return new Fixture(attempt, items, answered + 1);
    }

    private static ResolvedAttemptPolicy withRequireAll(
            ResolvedAttemptPolicy p, boolean all) {
        var s = p.submission();
        return new ResolvedAttemptPolicy(p.schemaVersion(), p.content(),
                p.questionSelection(), p.questionOrdering(), p.answerOrdering(), p.delivery(), p.navigation(),
                new SubmissionPolicy(s.draftsEnabled(), s.autosaveEnabled(), all, s.completionConfirmationRequired(), s.mode(), s.retries(), s.finalSubmitPayload(), s.unanswered()),
                p.answerChange(), p.disclosure(), p.hints(), p.timing(), p.pause(), p.attempts(), p.access(), p.grading(), p.review(), p.results(), p.accessibility(), p.integrity());
    }

    private AttemptSubmissionService.Command submit(Fixture f, String key) {
        return new AttemptSubmissionService.Command(f.attempt(), f.version(), true, CommandReceipt.Key.from(key));
    }
    private AttemptSubmissionService recreated() {
        return new AttemptSubmissionServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), access,
                context.getBean(AcceptedResponseRepository.class), lifecycle,
                facts, finalizer, terminal, new CommandReceiptServiceImpl(
                        context.getBean(CommandReceiptRepository.class),
                        manager, context.getBean(Clock.class), metrics), metrics);
    }
    private void reject(AttemptSubmissionService service, AttemptSubmissionService.Command c, String reason) {
        assertThatThrownBy(() -> service.submit(c)).isInstanceOfSatisfying(
                AttemptSubmissionException.class,
                failure -> { assertThat(failure.reason().name()).isEqualTo(reason); assertThat(failure.getSuppressed()).isEmpty(); }).hasNoCause();
    }

    @ParameterizedTest @CsvSource({"false,0", "false,8", "false,10", "true,10"})
    void realSubmissionLocksAcceptedSetCommitsIntentAndReplaysAfterServiceRestart(boolean all, int answered) {
        var f = fixture(all, 10, answered); var c = submit(f, "submit");
        var result = submissions.submit(c);
        assertThat(result.replayed()).isFalse(); assertThat(result.stateVersion()).isEqualTo(f.version() + 1);
        assertThat(sql.queryForObject("SELECT execution_state FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?)", String.class, f.attempt().toString())).isEqualTo("SUBMITTED");
        assertThat(sql.queryForObject("SELECT answer_collection_state FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?)", String.class, f.attempt().toString())).isEqualTo("COMPLETE");
        assertThat(sql.queryForMap("SELECT accepted_count,unanswered_count,state_version FROM attempt_submission"))
                .containsEntry("accepted_count", answered).containsEntry("unanswered_count", 10 - answered).containsEntry("state_version", result.stateVersion());
        assertThat(count("attempt_finalization_intent")).isEqualTo(1);
        var before = targetSnapshot();
        var replay = recreated().submit(c);
        assertThat(replay.replayed()).isTrue(); assertThat(replay.submissionId()).isEqualTo(result.submissionId());
        assertThat(replay.stateVersion()).isEqualTo(result.stateVersion());
        assertThat(targetSnapshot()).isEqualTo(before);
        reject(submissions, submit(f, "new-key"), "INACTIVE");
        reject(submissions, new AttemptSubmissionService.Command(f.attempt(), f.version(), false, c.key()), "CONFLICT");
        if (answered < 10) rejected(answer(f.attempt(), f.items().get(answered), result.stateVersion(), "late"), "INACTIVE");
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(sql.queryForList("SELECT event_type FROM attempt_trace_event WHERE attempt_id=UUID_TO_BIN(?) ORDER BY sequence_number", String.class, f.attempt().toString()))
                .endsWith("ANSWER_LOCKED", "ATTEMPT_SUBMITTED");
        assertThat(count("answers")).isZero(); // No legacy grade/result/event is produced here.
    }

    @Test void requiredOmissionsAndUnconfirmedSubmissionHaveNoSideEffectsAndSafeMissingIds() {
        var f = fixture(true, 10, 8); var before = targetSnapshot();
        assertThatThrownBy(() -> submissions.submit(submit(f, "submit"))).isInstanceOfSatisfying(
                AttemptSubmissionException.class, ex -> {
                    assertThat(ex.reason().name()).isEqualTo("INCOMPLETE");
                    assertThat(ex.missing()).containsExactly(f.items().get(8).id(), f.items().get(9).id());
                });
        assertThat(targetSnapshot()).isEqualTo(before);
        responses.accept(answer(f.attempt(), f.items().get(8), 9, "ninth"));
        responses.accept(answer(f.attempt(), f.items().get(9), 10, "tenth"));
        var full = new Fixture(f.attempt(), f.items(), 11); before = targetSnapshot();
        reject(submissions, new AttemptSubmissionService.Command(f.attempt(), 11, false, CommandReceipt.Key.from("unconfirmed")), "CONFIRMATION_REQUIRED");
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(submissions.submit(submit(full, "submit")).stateVersion()).isEqualTo(12);
    }

    @ParameterizedTest @ValueSource(strings = {"lifecycle", "trace", "submission", "intent", "receipt"})
    void reachedWriteFailureRollsBackEverythingAndOriginalEightResponsesSurvive(String checkpoint) {
        var f = fixture(false, 10, 8); var c = submit(f, "submit"); var before = targetSnapshot();
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            String prefix = switch (checkpoint) {
                case "lifecycle" -> "UPDATE attempt_lifecycle_state"; case "trace" -> "INSERT INTO attempt_trace_event";
                case "submission" -> "INSERT INTO attempt_submission"; case "intent" -> "INSERT INTO attempt_finalization_intent";
                default -> "UPDATE attempt_command_receipt";
            };
            if (statement.stripLeading().startsWith(prefix) && reached.compareAndSet(false, true)) {
                if (checkpoint.equals("intent")) {
                    try (var other = source.getConnection(); var query = other.createStatement();
                         var rows = query.executeQuery("SELECT COUNT(*) FROM attempt_finalization_intent")) {
                        assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isZero();
                    }
                }
                throw new SQLException("private-submission-marker", "HY000");
            }
        };
        reject(submissions, c, "UNAVAILABLE"); assertThat(reached).isTrue(); observer().reset();
        assertThat(targetSnapshot()).isEqualTo(before); // fresh transactions, before cleanup
        assertThat(count("attempt_accepted_response")).isEqualTo(8);
        assertThat(recreated().submit(c).replayed()).isFalse();
        assertThat(count("attempt_submission")).isEqualTo(1); assertThat(count("attempt_finalization_intent")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostCommitAcknowledgementIsReconciledWithoutRepeatingTerminalEffects(boolean committed) {
        var f = fixture(false, 10, 8); var request = submit(f, "submit"); var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.stripLeading().startsWith("UPDATE attempt_command_receipt") && reached.compareAndSet(false, true)) {
                if (committed) observer().failAfterCommit.set(true); else observer().failBeforeCommit.set(true);
            }
        };
        reject(submissions, request, "UNKNOWN_OUTCOME"); observer().reset(); assertThat(reached).isTrue();
        assertThat(count("attempt_submission")).isEqualTo(committed ? 1 : 0);
        assertThat(recreated().submit(request).replayed()).isEqualTo(committed);
        assertThat(count("attempt_submission")).isEqualTo(1); assertThat(count("attempt_finalization_intent")).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"2,true", "4,true", "2,false", "4,false"})
    void concurrentSubmitCommandsHaveOneRealWinner(int isolation, boolean identical) throws Exception {
        var f = fixture(false, 10, 8); var one = submit(f, "submit"); var two = submit(f, identical ? "submit" : "other");
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1); var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> race(one, isolation, ready, go)); var b = workers.submit(() -> race(two, isolation, ready, go));
            await(ready); go.countDown(); var results = List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            var successes = results.stream().filter(AttemptSubmissionService.Result.class::isInstance).map(AttemptSubmissionService.Result.class::cast).toList();
            assertThat(successes.stream().filter(r -> !r.replayed())).hasSize(1);
            assertThat(successes).hasSize(identical ? 2 : 1);
            if (identical) assertThat(successes).extracting(AttemptSubmissionService.Result::submissionId).containsOnly(successes.get(0).submissionId());
            else assertThat(results).anyMatch(r -> r == AttemptSubmissionException.Reason.INACTIVE);
            assertThat(count("attempt_submission")).isEqualTo(1); assertThat(count("attempt_finalization_intent")).isEqualTo(1);
        } finally { go.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
    }
    private Object race(AttemptSubmissionService.Command request, int isolation, CountDownLatch ready, CountDownLatch go) {
        authenticate(username); var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
        try { return transaction.execute(s -> { ready.countDown(); await(go); return submissions.submit(request); }); }
        catch (AttemptSubmissionException failure) { return failure.reason(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @ParameterizedTest @CsvSource({"2,true", "4,true", "2,false", "4,false"})
    void answerVersusSubmitBothOrderingsPreserveTheAcceptedSet(int isolation, boolean answerFirst) throws Exception {
        var f = fixture(false, 10, 8); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        var waiting = new CountDownLatch(1); var workers = Executors.newFixedThreadPool(2); var firstConnection = new AtomicInteger(-1);
        observer().beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT * FROM attempt_lifecycle_state") && statement.contains("FOR UPDATE")
                    && firstConnection.get() != -1 && firstConnection.get() != connection) waiting.countDown();
        };
        observer().afterSql = (statement, connection) -> {
            if (statement.startsWith("SELECT * FROM attempt_lifecycle_state") && statement.contains("FOR UPDATE")
                    && firstConnection.compareAndSet(-1, connection)) {
                held.countDown(); await(release); // Actual SQL has acquired the lock, not merely reached it.
            }
        };
        java.util.concurrent.Callable<Object> accepting = () -> {
            authenticate(username); var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
            try { return transaction.execute(s -> responses.accept(answer(f.attempt(), f.items().get(8), 9, "ninth"))); }
            catch (ResponseAcceptanceException failure) { return failure.reason(); }
            finally { SecurityContextHolder.clearContext(); }
        };
        java.util.concurrent.Callable<Object> submitting = () -> race(submit(f, "submit"), isolation, new CountDownLatch(0), new CountDownLatch(0));
        try {
            var first = workers.submit(answerFirst ? accepting : submitting); await(held);
            var second = workers.submit(answerFirst ? submitting : accepting); await(waiting); release.countDown();
            Object a = first.get(20, TimeUnit.SECONDS), b = second.get(20, TimeUnit.SECONDS);
            observer().reset();
            if (answerFirst) {
                assertThat(a).isInstanceOf(ResponseAcceptanceService.Result.class);
                assertThat(b).isEqualTo(AttemptSubmissionException.Reason.STALE);
                assertThat(count("attempt_submission")).isZero();
                submissions.submit(submit(new Fixture(f.attempt(), f.items(), 10), "submit"));
            } else {
                assertThat(a).isInstanceOf(AttemptSubmissionService.Result.class);
                assertThat(b).isEqualTo(ResponseAcceptanceException.Reason.INACTIVE);
            }
            assertThat(sql.queryForObject("SELECT accepted_count FROM attempt_submission", Integer.class)).isEqualTo(answerFirst ? 9 : 8);
            assertThat(count("attempt_accepted_response")).isEqualTo(answerFirst ? 9 : 8);
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); observer().reset(); }
    }

    @Test void currentPermissionOwnershipAndAccountChecksApplyBeforeReplayAndWrite() {
        var f = fixture(false, 10, 8); var c = submit(f, "submit"); var before = targetSnapshot();
        SecurityContextHolder.clearContext(); reject(submissions, c, "UNAUTHENTICATED"); authenticate(username);
        String foreignName = "foreign-" + UUID.randomUUID(); UUID foreign = user(foreignName, true); grant(foreign, "ATTEMPT_CREATE");
        authenticate(foreignName); reject(submissions, c, "NOT_FOUND"); authenticate(username);
        assertThat(targetSnapshot()).isEqualTo(before);
        revoke(ownerId, "ATTEMPT_CREATE"); reject(submissions, c, "NOT_FOUND"); grant(ownerId, "ATTEMPT_CREATE");
        var result = submissions.submit(c); before = targetSnapshot();
        revoke(ownerId, "ATTEMPT_CREATE"); reject(submissions, c, "NOT_FOUND"); grant(ownerId, "ATTEMPT_CREATE");
        sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()); reject(submissions, c, "NOT_FOUND");
        sql.update("UPDATE users SET is_active=TRUE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString());
        revoke(ownerId, "ATTEMPT_READ"); assertThat(recreated().submit(c).submissionId()).isEqualTo(result.submissionId());
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @Test void fiveRealFamilyResponsesAndHistoricalSubmissionSurviveDraftEditsAndReceiptCleanup() {
        var started = starts.start(command(prepared(), AnswerOrder.SHUFFLED_PER_ATTEMPT, "start"));
        var items = delivery.read(started.attemptId()).form().items();
        for (int n = 0; n < items.size(); n++) responses.accept(answer(started.attemptId(), items.get(n), n + 1, "family-" + n));
        var request = submit(new Fixture(started.attemptId(), items, 6), "submit");
        // Mutable author drafts are not historical response or completion authority.
        tx.executeWithoutResult(s -> {
            for (var question : questions.findAll()) {
                question.setQuestionText("Changed author draft"); question.setExplanation("Changed explanation");
            }
            questions.flush();
        });
        var result = submissions.submit(request);
        assertThat(sql.queryForObject("SELECT accepted_count FROM attempt_submission", Integer.class)).isEqualTo(5);
        var future = Clock.offset(context.getBean(Clock.class), Duration.ofDays(31));
        var receipts = new CommandReceiptServiceImpl(context.getBean(CommandReceiptRepository.class), manager, future, metrics);
        var later = new AttemptSubmissionServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), access,
                context.getBean(AcceptedResponseRepository.class), lifecycle, facts, finalizer, terminal, receipts, metrics);
        reject(later, request, "EXPIRED");
        Integer purged = tx.execute(s -> receipts.purgeExpired(started.attemptId(), result.stateVersion(), 100));
        assertThat(purged).isEqualTo(7);
        assertThat(count("attempt_command_receipt")).isZero();
        assertThat(count("attempt_accepted_response")).isEqualTo(5);
        assertThat(count("attempt_submission")).isEqualTo(1); assertThat(count("attempt_finalization_intent")).isEqualTo(1);
        reject(submissions, request, "INACTIVE");
    }

    @Test void databaseNaturalKeysRejectAnotherSubmissionAndFinalizationIntent() {
        submissions.submit(submit(fixture(false, 10, 8), "submit"));
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO attempt_submission SELECT UUID_TO_BIN(UUID()),schema_version,attempt_id,form_id,
                policy_snapshot_id,quiz_revision_id,UUID_TO_BIN(UUID()),state_version,accepted_count,unanswered_count,submitted_at
                FROM attempt_submission
                """)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> sql.update("INSERT INTO attempt_finalization_intent SELECT * FROM attempt_finalization_intent"))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(count("attempt_submission")).isEqualTo(1); assertThat(count("attempt_finalization_intent")).isEqualTo(1);
    }

    @Test void staleCommandsCrossOperationKeysAndMissingTargetsLeaveNoSubmission() {
        var f = fixture(false, 10, 8); var before = targetSnapshot();
        reject(submissions, new AttemptSubmissionService.Command(f.attempt(), 0, true, CommandReceipt.Key.from("stale")), "STALE");
        reject(submissions, submit(f, "accepted-0-" + f.attempt()), "CONFLICT");
        reject(submissions, new AttemptSubmissionService.Command(UUID.randomUUID(), 9, true, CommandReceipt.Key.from("missing")), "NOT_FOUND");
        reject(submissions, null, "INVALID_INPUT");
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(submissions.submit(submit(f, "submit")).replayed()).isFalse();
    }

    @Test void corruptAcceptedContractCannotBeCountedAsTrustedCompletionEvidence() {
        var f = fixture(false, 10, 8);
        String contract = sql.queryForObject("SELECT response_contract_id FROM attempt_accepted_response WHERE form_item_id=UUID_TO_BIN(?)",
                String.class, f.items().get(0).id().toString());
        sql.update("UPDATE attempt_accepted_response SET response_contract_id='unrecognized-contract' WHERE form_item_id=UUID_TO_BIN(?)", f.items().get(0).id().toString());
        var before = targetSnapshot(); reject(submissions, submit(f, "submit"), "CORRUPT"); assertThat(targetSnapshot()).isEqualTo(before);
        sql.update("UPDATE attempt_accepted_response SET response_contract_id=? WHERE form_item_id=UUID_TO_BIN(?)", contract, f.items().get(0).id().toString());
        assertThat(submissions.submit(submit(f, "submit")).replayed()).isFalse();
    }

    @Test void optionalMetricFailureDoesNotChangeCommittedSubmission() {
        var f = fixture(false, 10, 8);
        metrics.counter("quizmaker.attempt.submission", "version", "1", "outcome", "submitted");
        var result = submissions.submit(submit(f, "submit"));
        assertThat(result.replayed()).isFalse(); assertThat(count("attempt_finalization_intent")).isEqualTo(1);
        assertThat(submissions.submit(submit(f, "submit")).submissionId()).isEqualTo(result.submissionId());
        metrics.remove(metrics.find("quizmaker.attempt.submission").tags("version", "1", "outcome", "submitted").counter());
    }

    @Test void realHttpSubmissionReportsMissingItemsThenSucceedsThroughTheSameService() throws Exception {
        var f = fixture(true, 10, 8);
        var converters = new ArrayList<org.springframework.http.converter.HttpMessageConverter<?>>(List.of(
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()));
        new uk.gegc.quizmaker.features.attempt.api.AttemptSubmissionJsonConfig().extendMessageConverters(converters);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new uk.gegc.quizmaker.features.attempt.api.AttemptSubmissionController(submissions))
                .setControllerAdvice(new uk.gegc.quizmaker.features.attempt.api.AttemptSubmissionErrorHandler())
                .setMessageConverters(converters.toArray(org.springframework.http.converter.HttpMessageConverter[]::new)).build();
        var path = "/api/v1/attempts/" + f.attempt() + "/submit";
        var body = json.createObjectNode().put("expectedStateVersion", 9).put("completionConfirmed", true);
        var before = targetSnapshot();
        String result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Idempotency-Key", "http-submit").contentType("application/json").content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SUBMISSION_INCOMPLETE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(result).path("missingFormItemIds")).hasSize(2);
        assertThat(targetSnapshot()).isEqualTo(before);
        responses.accept(answer(f.attempt(), f.items().get(8), 9, "ninth"));
        responses.accept(answer(f.attempt(), f.items().get(9), 10, "tenth")); body.put("expectedStateVersion", 11);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Idempotency-Key", "http-submit").contentType("application/json").content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.stateVersion").value(12));
        assertThat(count("attempt_finalization_intent")).isEqualTo(1);
    }

    @ParameterizedTest @CsvSource({"1213,RETRYABLE", "1205,IN_PROGRESS", "0,UNAVAILABLE"})
    void nestedReadsPreserveDatabaseFailureClassificationAndAllowExactRetry(int code, String reason) {
        var f = fixture(false, 10, 8); var c = submit(f, "submit"); var before = targetSnapshot(); var reached = new AtomicInteger();
        observer().beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT locked.id FROM (")) {
                reached.incrementAndGet(); throw new SQLException("private-read-marker", code == 1213 ? "40001" : "HY000", code);
            }
        };
        reject(submissions, c, reason); assertThat(reached).hasValue(1); observer().reset();
        assertThat(targetSnapshot()).isEqualTo(before); assertThat(recreated().submit(c).replayed()).isFalse();
    }

    @Test void submissionReadsHaveBoundedQueryCountAcrossMultipleParentsAndLargerForms(TestReporter reporter) {
        var measured = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var commands = new ArrayList<AttemptSubmissionService.Command>();
            for (int parent = 0; parent < 3; parent++) { quizId = newQuiz(); commands.add(submit(fixture(false, size, size), "submit-" + UUID.randomUUID())); }
            observer().selects.clear(); commands.forEach(submissions::submit); measured.add(observer().selects.size());
            assertThat(observer().selects.stream().filter(q -> q.contains("FROM attempt_accepted_response")))
                    .hasSize(3).noneMatch(q -> q.contains("normalized_response"));
        }
        assertThat(measured.get(0)).isPositive(); assertThat(measured.get(1)).isEqualTo(measured.get(0));
        reporter.publishEntry("submission-selects-three-parents-1-vs-20-items", measured.toString());
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void anOlderCallerSnapshotUsesCurrentAcceptedSetAndVersion(int isolation) throws Exception {
        var f = fixture(false, 10, 8); var snapshot = new CountDownLatch(1); var changed = new CountDownLatch(1);
        var workers = Executors.newSingleThreadExecutor();
        try {
            var result = workers.submit(() -> {
                authenticate(username); var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                try { return transaction.execute(s -> {
                    assertThat(sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state", Long.class)).isEqualTo(9);
                    snapshot.countDown(); await(changed);
                    return submissions.submit(submit(new Fixture(f.attempt(), f.items(), 10), "submit"));
                }); } finally { SecurityContextHolder.clearContext(); }
            });
            await(snapshot); responses.accept(answer(f.attempt(), f.items().get(8), 9, "ninth")); changed.countDown();
            assertThat(result.get(20, TimeUnit.SECONDS).stateVersion()).isEqualTo(11);
            assertThat(sql.queryForObject("SELECT accepted_count FROM attempt_submission", Integer.class)).isEqualTo(9);
        } finally { changed.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void closedScopeContractPreservesRealEightAnswersAndOnlyOpenScopeCanBlock(boolean all, boolean closed) {
        var f = fixture(all, 10, 8); var before = targetSnapshot();
        // Captured authoritative scope contract, not evidence of a section/timer runtime writer.
        var result = internalFinish(f, closed, false, false);
        if (all && !closed) {
            assertThat(result).isEqualTo(AttemptSubmissionException.Reason.INCOMPLETE);
            assertThat(targetSnapshot()).isEqualTo(before);
        } else {
            assertThat(result).isInstanceOf(CommandReceipt.Result.class);
            assertThat(sql.queryForMap("SELECT accepted_count,unanswered_count FROM attempt_submission"))
                    .containsEntry("accepted_count", 8).containsEntry("unanswered_count", 2);
            assertThat(count("attempt_accepted_response")).isEqualTo(8);
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void finalizerContractDoubleProvesOrchestrationAndFailureRollbackOnly(boolean all, boolean fail) {
        var f = fixture(all, 10, 0);
        UUID attempt = f.attempt();
        // #834's contract represents provisional activity. No fake accepted inserts are claimed.
        tx.executeWithoutResult(s -> lifecycle.save(attempt, 1, new AttemptLifecycle.State(1,
                AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.IN_PROGRESS)));
        f = new Fixture(f.attempt(), f.items(), 2); var before = targetSnapshot();
        Object result = internalFinish(f, false, true, fail);
        if (all || fail) {
            assertThat(result).isEqualTo(all
                    ? AttemptSubmissionException.Reason.INCOMPLETE
                    : AttemptSubmissionException.Reason.UNAVAILABLE);
            assertThat(targetSnapshot()).isEqualTo(before);
        } else {
            assertThat(result).isInstanceOf(CommandReceipt.Result.class);
            assertThat(count("attempt_finalization_intent")).isEqualTo(1);
            assertThat(count("attempt_accepted_response")).isZero(); // Contract double is NOT conversion evidence.
        }
    }

    private Object internalFinish(Fixture f, boolean closed, boolean editable, boolean fail) {
        var called = new AtomicBoolean(); var receipts = context.getBean(CommandReceiptService.class);
        var failure = new AtomicReference<AttemptSubmissionException.Reason>();
        var fake = new SubmissionResponseFinalizer() {
            public CompletionFacts prepare(AttemptReadFactsService.Facts saved, boolean confirmed) {
                throw new AssertionError("The version-bound prepared contract is supplied below");
            }
            public void finalizeResponses(AttemptReadFactsService.Facts saved, CompletionFacts.Plan plan) {
                called.set(true);
                assertThat(plan.finalizeItems()).hasSize(editable ? 8 : 0);
                assertThat(plan.unansweredItems()).containsExactly(f.items().get(8).id(), f.items().get(9).id());
                if (fail) throw new AttemptSubmissionException(
                        AttemptSubmissionException.Reason.UNAVAILABLE);
            }
        };
        var writer = new AttemptTerminalSubmissionImpl(lifecycle, fake, context.getBean(ExecutionTraceService.class),
                context.getBean(AttemptSubmissionRepository.class));
        var request = new CommandReceipt.Request(1, ownerId, f.attempt(),
                AttemptCommands.Type.SUBMIT_ATTEMPT, f.attempt(), f.version(),
                CommandReceipt.Key.from("internal"), CanonicalCommandInput.fingerprint(1, json.createObjectNode()));
        try {
            var result = receipts.execute(request, () -> { lifecycle.lock(f.attempt()); access.requireAccess(f.attempt(), AttemptAccessService.Operation.ANSWER); }, id -> {
                var saved = facts.read(f.attempt(), AttemptReadFactsService.Purpose.SUBMIT_ATTEMPT);
                var items = IntStream.range(0, 10).mapToObj(n -> new CompletionFacts.Item(
                        f.items().get(n).id(), n < 8 ? editable
                        ? CompletionFacts.ResponseState.EDITABLE
                        : CompletionFacts.ResponseState.ACCEPTED
                        : n == 8 ? CompletionFacts.ResponseState.PENDING_CONFIRMATION
                        : CompletionFacts.ResponseState.EMPTY,
                        closed && n >= 8 ? CompletionFacts.Scope.PERMANENTLY_CLOSED
                        : CompletionFacts.Scope.APPLICABLE)).toList();
                try { return writer.finish(saved, f.version(), id, new CompletionFacts(1,
                        editable ? CompletionFacts.FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION
                        : CompletionFacts.FinalizationMode.LOCK_ON_ACCEPTANCE,
                        saved.policy().submission().requireAllAnswers(), true, items)); }
                catch (AttemptSubmissionException ex) { failure.set(ex.reason()); throw ex; }
            });
            assertThat(called).isTrue(); return result;
        } catch (uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException rejected) {
            assertThat(rejected.reason()).isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.Reason.COMMAND_DENIED);
            // The public service restores the original bounded domain reason; this direct
            // receipt/kernel fixture observes the same write/rollback with local classification.
            var all = tx.execute(s -> facts.read(f.attempt(), AttemptReadFactsService.Purpose.SUBMIT_ATTEMPT).policy().submission().requireAllAnswers());
            assertThat(called.get()).isEqualTo(!all || closed);
            assertThat(failure.get()).isNotNull();
            return failure.get();
        }
    }

    private long count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)); }
    private void rejected(ResponseAcceptanceService.Command c, String reason) {
        assertThatThrownBy(() -> responses.accept(c)).isInstanceOfSatisfying(ResponseAcceptanceException.class,
                ex -> assertThat(ex.reason().name()).isEqualTo(reason)).hasNoCause();
    }
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
