package uk.gegc.quizmaker.features.attempt.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.FixedFormMaterializer.Command;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.*;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;
import static uk.gegc.quizmaker.features.attempt.form.MaterializationFixtures.*;

/** Real revisions, registry, form/order services, Flyway and MySQL. Family adapters are explicit component fixtures. */
@Tag("db-serial")
class FixedFormMaterializerMySqlIntegrationTest {
    private static final List<String> TABLES = List.of("attempt_form_option", "attempt_form_item_option_order", "attempt_form_option_order",
            "attempt_policy_snapshot", "attempt_form_item", "attempt_form", "question_revision_contract_bindings",
            "quiz_revision_items", "question_option_revisions", "quiz_revisions", "question_revisions");
    private static FormDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager manager;
    private static QuizRevisionRepository revisions;
    private SimpleMeterRegistry metrics;
    private QuestionRevisionContractService contracts;
    private FixedFormMaterializer materializer;

    @BeforeAll
    static void connect() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        source = new FormDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser")));
        sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        revisions = proxy(new JdbcQuizRevisionRepository(named()));
        dropSchema();
        sql.execute("CREATE TABLE materialization_legacy_fixture (id INT PRIMARY KEY, marker VARCHAR(100))");
        sql.update("INSERT INTO materialization_legacy_fixture VALUES (1,'legacy')");
        migrate("materialization_revision_history", "80", "82");
        migrate("materialization_form_history", "83", "85");
        migrate("materialization_order_history", "88", "89");
    }

    private static void migrate(String history, String baseline, String target) {
        Flyway.configure().dataSource(source).table(history).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion(baseline).target(target).load().migrate();
    }

    @BeforeEach
    void prepare() {
        reset(); metrics = new SimpleMeterRegistry();
        contracts = contracts(MaterializationFixtures::presentation);
        materializer = service(contracts);
    }

    @AfterEach
    void cleanup() { reset(); if (metrics != null) metrics.close(); }

    private static void reset() {
        source.reset();
        for (String table : TABLES) if (!table.equals("attempt_policy_snapshot")) sql.update("DELETE FROM " + table);
        sql.update("UPDATE materialization_legacy_fixture SET marker='legacy'");
        source.reset();
    }

    @AfterAll
    static void dropSchema() {
        if (source == null) return;
        source.reset();
        for (String table : List.of("attempt_trace_identity", "attempt_trace_event", "attempt_trace_stream")) sql.execute("DROP TABLE IF EXISTS " + table);
        for (String table : TABLES) sql.execute("DROP TABLE IF EXISTS " + table);
        for (String table : List.of("materialization_revision_history", "materialization_form_history", "materialization_order_history",
                "materialization_legacy_fixture")) sql.execute("DROP TABLE IF EXISTS " + table);
    }

    @Test
    void allFiveFamiliesPersistRealExactOrderAndReplayAfterRestartWithoutProtectedFields() throws Exception {
        var questions = FAMILIES.stream().map(type -> question(type.name(), type)).toArray(QuestionRevision[]::new);
        var revision = saved(quiz("five-families", questions));
        var cmd = command("five-form", revision, SHUFFLED);
        var expected = materializer.materialize(OWNER, cmd);
        assertThat(expected.items()).hasSize(5).extracting(ParticipantForm.Item::type).containsExactlyElementsOf(FAMILIES);
        assertThat(expected.items()).extracting(ParticipantForm.Item::position).containsExactly(0, 2, 4, 6, 8);
        assertThat(materializer.materialize(OWNER, cmd)).isEqualTo(expected);
        var restarted = service(contracts(MaterializationFixtures::presentation));
        assertThat(restarted.materialize(OWNER, cmd)).isEqualTo(expected);
        fresh(() -> {
            var stored = orders(contracts).findByIds(OWNER, List.of(cmd.form().id())).get(0);
            for (var item : expected.items()) assertThat(ParticipantFormProjectorTest.options(item.content()))
                    .extracting(ParticipantContent.Option::id).containsExactlyElementsOf(stored.items().stream()
                            .filter(order -> order.formItemId().equals(item.id())).findFirst().orElseThrow().optionIds());
            assertThat(count("attempt_form")).isEqualTo(1);
            assertThat(count("attempt_form_item")).isEqualTo(5);
        });
        String json = new ObjectMapper().writeValueAsString(expected);
        assertThat(json).doesNotContain("correct", "answer", "explanation", "hint", "sourceKey", "privateProvenance", "protected-",
                "ownerId", "sourceItemId", "quizRevisionId", "questionRevisionId", "scoring");
        assertLegacyUntouched();
    }

    @Test
    void allSelectionAndEmptyFormsAreRepresentableWithoutStartingAttempts() {
        var revision = saved(quiz("all", question("all-question", QuestionType.MCQ_SINGLE)));
        var original = command("all-form", revision, AUTHORED);
        var selection = new QuestionSelectionServiceImpl().select(new QuestionSelection.Input(1, revision.id(), 1,
                QuestionSelection.Configuration.all(), revision.items().stream().map(i ->
                new QuestionSelection.Item(i.id(), i.authoredPosition())).toList(), List.of()));
        assertThat(materializer.materialize(OWNER, new Command(original.form(), selection, original.questionOrder(), AUTHORED)).items()).hasSize(1);
        var empty = saved(quiz("empty"));
        var emptyCommand = command("empty-form", empty, AUTHORED);
        assertThat(materializer.materialize(OWNER, emptyCommand).items()).isEmpty();
        assertThat(materializer.materialize(OWNER, emptyCommand).items()).isEmpty();
        assertThat(count("attempt_form")).isEqualTo(2);
        assertLegacyUntouched();
    }

    @Test
    void subsetOmitsHiddenItemsAndNewAuthorRevisionCannotChangeExistingMaterial() throws Exception {
        var original = saved(quiz("published", question("original", QuestionType.MCQ_SINGLE), question("hidden", QuestionType.TRUE_FALSE)));
        var cmd = command("subset", original, SHUFFLED, List.of(original.items().get(0).id()));
        var expected = materializer.materialize(OWNER, cmd);
        sql.update("UPDATE materialization_legacy_fixture SET marker='author edited mutable content'");
        saved(quiz("new-publication", question("new-content", QuestionType.MCQ_SINGLE)));
        assertThat(service(contracts(MaterializationFixtures::presentation)).materialize(OWNER, cmd)).isEqualTo(expected);
        assertThat(expected.items()).hasSize(1);
        assertThat(new ObjectMapper().writeValueAsString(expected)).contains("Published original").doesNotContain("hidden", "new-content");
        assertThat(count("attempt_form_item")).isEqualTo(1);
        assertThat(revisions.findById(OWNER, original.id())).contains(original);
    }

    @Test
    void wrongOwnerMissingRevisionNullAndMismatchedEvidenceLeaveNoForm() {
        var revision = saved(quiz("validation", question("validation-q", QuestionType.MCQ_SINGLE)));
        var cmd = command("validation-form", revision, AUTHORED);
        rejected(() -> materializer.materialize(OTHER_OWNER, cmd), "REVISION_UNAVAILABLE");
        rejected(() -> materializer.materialize(null, cmd), "REVISION_UNAVAILABLE");
        rejected(() -> materializer.materialize(OWNER, null), "INVALID_INPUT");
        var unknown = command("unknown", quiz("not-stored", question("not-stored-q", QuestionType.TRUE_FALSE)), AUTHORED);
        rejected(() -> materializer.materialize(OWNER, unknown), "REVISION_UNAVAILABLE");
        var wrongForm = new FixedForm(cmd.form().id(), 1, revision.id(), List.of(new FixedForm.Item(id("bad-item"), id("bad-source"), 0)));
        rejected(() -> materializer.materialize(OWNER, new Command(wrongForm, cmd.selection(), cmd.questionOrder(), AUTHORED)), "INPUT_MISMATCH");
        var other = command("other", quiz("other-revision", question("other-q", QuestionType.TRUE_FALSE)), AUTHORED);
        rejected(() -> materializer.materialize(OWNER, new Command(cmd.form(), other.selection(), cmd.questionOrder(), AUTHORED)), "INPUT_MISMATCH");
        assertNoForms();
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
    }

    @Test
    void unavailableRealRegistryAndUnboundRevisionRejectWithoutWrites() {
        var revision = saved(quiz("registry", question("registry-q", QuestionType.TRUE_FALSE)));
        var cmd = command("registry-form", revision, AUTHORED);
        var empty = new QuestionContractRegistryImpl(List.of(), List.of(), metrics);
        var missing = service(new QuestionRevisionContractServiceImpl(proxy(new JdbcQuestionContractBindingRepository(named())), empty, manager));
        assertThatThrownBy(() -> missing.materialize(OWNER, cmd)).hasMessage("Question contract rejected: UNSUPPORTED_CONTRACT");
        var unbound = quiz("unbound", question("unbound-q", QuestionType.TRUE_FALSE)); revisions.insert(unbound);
        assertThatThrownBy(() -> materializer.materialize(OWNER, command("unbound-form", unbound, AUTHORED)))
                .hasMessage("Question contract rejected: UNBOUND_REVISION");
        assertNoForms();
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
    }

    @Test
    void incorrectQuestionOrderAndConflictingReplayCannotOverwriteCommittedForm() {
        var revision = saved(quiz("order", question("first", QuestionType.MCQ_SINGLE), question("second", QuestionType.TRUE_FALSE)));
        var cmd = command("order-form", revision, SHUFFLED);
        var reversed = new ArrayList<>(cmd.questionOrder().itemIds()); Collections.reverse(reversed);
        var invalid = new Command(cmd.form(), cmd.selection(), new AuthoredQuestionOrder.Result(cmd.questionOrder().evidence(), reversed), SHUFFLED);
        rejected(() -> materializer.materialize(OWNER, invalid), "INPUT_MISMATCH"); assertNoForms();
        var expected = materializer.materialize(OWNER, cmd);
        assertThatThrownBy(() -> materializer.materialize(OWNER, new Command(cmd.form(), cmd.selection(), cmd.questionOrder(), AUTHORED)))
                .hasMessage("Option order rejected: ORDER_CONFLICT");
        assertThat(materializer.materialize(OWNER, cmd)).isEqualTo(expected);
        assertThat(count("attempt_form_item")).isEqualTo(2);
    }

    @Test
    void realOrderWriteFailureRollsBackEveryNewFormRowAndThenRetriesSuccessfully() {
        var revision = saved(quiz("sql-failure", question("sql-failure-q", QuestionType.MCQ_SINGLE)));
        var cmd = command("sql-failure-form", revision, AUTHORED); var reached = new AtomicBoolean();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT o.* FROM attempt_form_option_order o ")) {
                assertThat(count("attempt_form_option")).isPositive(); reached.set(true);
                throw new SQLException("Synthetic failure after order inserts", "08006");
            }
        };
        assertThatThrownBy(() -> materializer.materialize(OWNER, cmd)).hasMessage("Option order rejected: PERSISTENCE_UNAVAILABLE");
        source.reset(); assertThat(reached).isTrue(); fresh(FixedFormMaterializerMySqlIntegrationTest::assertNoForms);
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
    }

    @Test
    void adapterFailureAfterActualInsertsRollsBackAndCannotBeSwallowedByOuterTransaction() {
        var revision = saved(quiz("projection-failure", question("projection-failure-q", QuestionType.MCQ_SINGLE)));
        var cmd = command("projection-failure-form", revision, AUTHORED); var reached = new AtomicBoolean();
        var failingContracts = contracts(q -> {
            assertThat(count("attempt_form_item")).isEqualTo(1); assertThat(count("attempt_form_option")).isPositive();
            reached.set(true); throw new IllegalStateException("protected-source-marker");
        });
        var failing = service(failingContracts);
        rejected(() -> failing.materialize(OWNER, cmd), "INVALID_MATERIAL");
        assertThat(reached).isTrue(); fresh(FixedFormMaterializerMySqlIntegrationTest::assertNoForms);
        reached.set(false);
        assertThatThrownBy(() -> transaction("REPEATABLE_READ").executeWithoutResult(status -> {
            failingContracts.declareScope(OWNER, Set.of(revision.id()));
            sql.update("UPDATE materialization_legacy_fixture SET marker='partial'");
            rejected(() -> failing.materialize(OWNER, cmd), "INVALID_MATERIAL");
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(reached).isTrue(); fresh(() -> { assertNoForms(); assertLegacyUntouched(); });
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"false,UNEXPECTED", "true,UNEXPECTED", "false,CONTRACT", "true,CONTRACT",
            "false,ORDER", "true,ORDER", "false,REVISION", "true,REVISION"})
    void layoutDiagnosticsAreRemovedBeforeTransactionLoggingForBothCallers(boolean directOrderCaller, String kind) {
        var revision = saved(quiz("layout-failure", question("layout-failure-q", QuestionType.MCQ_SINGLE)));
        var cmd = command("layout-failure-form", revision, AUTHORED);
        String marker = "protected-source-marker";
        var reached = new AtomicBoolean();
        var failingRegistry = registry(question -> {
            assertThat(question.content()).contains(marker);
            reached.set(true);
            RuntimeException failure = switch (kind) {
                case "CONTRACT" -> new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
                case "ORDER" -> new OptionOrderException(OptionOrderException.Reason.UNSUPPORTED_VERSION);
                case "REVISION" -> new RevisionIntegrityException(RevisionIntegrityException.Reason.INVALID_CONTENT);
                default -> new IllegalArgumentException("Synthetic layout parser failure: " + question.content());
            };
            failure.initCause(new IllegalStateException("Nested source: " + question.content()));
            failure.addSuppressed(new IllegalStateException("Suppressed source: " + question.content()));
            throw failure;
        }, MaterializationFixtures::presentation);
        var failingContracts = new QuestionRevisionContractServiceImpl(
                proxy(new JdbcQuestionContractBindingRepository(named())), failingRegistry, manager);
        var logger = (Logger) LoggerFactory.getLogger(TransactionTemplate.class);
        Level previousLevel = logger.getLevel();
        Thread requestThread = Thread.currentThread();
        var captured = new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                // Shared-suite background workers are unrelated to this synchronous request.
                if (Thread.currentThread() == requestThread) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            }
        };
        captured.setContext(logger.getLoggerContext());
        captured.start(); logger.addAppender(captured); logger.setLevel(Level.DEBUG);
        try {
            Throwable failure = catchThrowable(() -> {
                if (directOrderCaller) orders(failingContracts).createOrReplay(OWNER, cmd.form(), AUTHORED);
                else service(failingContracts).materialize(OWNER, cmd);
            });
            String expectedMessage = switch (kind) {
                case "CONTRACT" -> "Question contract rejected: UNSUPPORTED_CONTRACT";
                case "ORDER" -> "Option order rejected: UNSUPPORTED_VERSION";
                case "REVISION" -> "Immutable revision rejected: INVALID_CONTENT";
                default -> "Option order rejected: INVALID_ORDER";
            };
            Class<? extends RuntimeException> expectedClass = switch (kind) {
                case "CONTRACT" -> ContractResolutionException.class;
                case "REVISION" -> RevisionIntegrityException.class;
                default -> OptionOrderException.class;
            };
            org.assertj.core.api.SoftAssertions.assertSoftly(softly -> {
                softly.assertThat(failure).isExactlyInstanceOf(expectedClass).hasMessage(expectedMessage).hasNoCause();
                if (failure != null) softly.assertThat(failure.getSuppressed()).isEmpty();
                // Nonempty throwable-bearing DEBUG events prove that the vulnerable logger actually ran.
                softly.assertThat(captured.list).anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                    assertThat(event.getThrowableProxy()).isNotNull();
                });
                softly.assertThat(captured.list).allSatisfy(event -> {
                    assertThat(event.getFormattedMessage()).doesNotContain(marker);
                    if (event.getThrowableProxy() != null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                            .contains(expectedMessage).doesNotContain(marker);
                });
            });
        } finally {
            logger.setLevel(previousLevel); logger.detachAppender(captured); captured.stop();
        }
        assertThat(reached).isTrue();
        fresh(FixedFormMaterializerMySqlIntegrationTest::assertNoForms);
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
    }

    @Test
    void outerTransactionMustDeclareScopeAndStillOwnsCommitAfterSuccessfulReturn() {
        var revision = saved(quiz("outer", question("outer-q", QuestionType.TRUE_FALSE)));
        var cmd = command("outer-form", revision, AUTHORED);
        assertThatThrownBy(() -> transaction("READ_COMMITTED").executeWithoutResult(s -> materializer.materialize(OWNER, cmd)))
                .hasMessage("Question contract rejected: SCOPE_REQUIRED");
        assertThatThrownBy(() -> transaction("READ_COMMITTED").executeWithoutResult(status -> {
            contracts.declareScope(OWNER, Set.of(revision.id()));
            assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
            assertThat(count("attempt_form")).isEqualTo(1); throw new IllegalStateException("Outer failure");
        })).hasMessage("Outer failure");
        fresh(FixedFormMaterializerMySqlIntegrationTest::assertNoForms);
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
    }

    @Test
    void lostCommitAcknowledgementReconcilesBySameIdentityAfterRestart() {
        var revision = saved(quiz("lost-ack", question("lost-ack-q", QuestionType.MCQ_MULTI)));
        var cmd = command("lost-ack-form", revision, SHUFFLED); var committed = new AtomicBoolean();
        source.afterCommit = connection -> { committed.set(true); throw new SQLException("Synthetic acknowledgement loss", "08006"); };
        rejected(() -> materializer.materialize(OWNER, cmd), "PERSISTENCE_UNAVAILABLE");
        source.reset(); assertThat(committed).isTrue();
        assertThat(metrics.get("quizmaker.attempt.form.materialization").tags("format", "1", "outcome", "persistence_unavailable")
                .timer().count()).isEqualTo(1);
        var restarted = service(contracts(MaterializationFixtures::presentation));
        var expected = restarted.materialize(OWNER, cmd);
        assertThat(restarted.materialize(OWNER, cmd)).isEqualTo(expected);
        fresh(() -> { assertThat(count("attempt_form")).isEqualTo(1); assertThat(count("attempt_form_item")).isEqualTo(1); });
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,true", "READ_COMMITTED,false", "REPEATABLE_READ,true", "REPEATABLE_READ,false"})
    void concurrentCommandsHaveOneDurableFormAndReplayOrTypedConflict(String isolation, boolean identical) throws Exception {
        var revision = saved(quiz("race", question("race-q", QuestionType.MCQ_SINGLE)));
        var cmd = command("race-form", revision, SHUFFLED);
        var secondCommand = identical ? cmd : new Command(cmd.form(), cmd.selection(), cmd.questionOrder(), AUTHORED);
        var inserted = new CountDownLatch(1); var competing = new CountDownLatch(1); var release = new CountDownLatch(1);
        var scopeLockAttempts = new AtomicInteger(); var pause = new AtomicBoolean(true);
        source.beforeSql = (statement, connection) -> {
            // #598 serializes the complete declared scope before form insertion. Observe the
            // second caller at that actual wait point, rather than waiting for its later INSERT.
            if (statement.startsWith("SELECT locked.id") && statement.contains("FOR UPDATE")
                    && scopeLockAttempts.incrementAndGet() == 2) competing.countDown();
            if (statement.startsWith("SELECT o.* FROM attempt_form_option_order o ") && pause.getAndSet(false)) {
                assertThat(count("attempt_form_option")).isPositive(); inserted.countDown();
                await(release);
            }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> race(cmd, isolation)); assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> race(secondCommand, isolation)); assertThat(competing.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.isDone()).isFalse(); release.countDown();
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.failure()).isNull(); assertThat(a.result()).isNotNull();
            if (identical) { assertThat(b.failure()).isNull(); assertThat(b.result()).isEqualTo(a.result()); }
            else { assertThat(b.result()).isNull(); assertThat(b.failure()).isEqualTo("Option order rejected: ORDER_CONFLICT"); }
            assertThat(source.sqlErrorCodes).doesNotContain(1213, 1205);
            source.beforeSql = null;
            assertThat(service(contracts(MaterializationFixtures::presentation)).materialize(OWNER, cmd)).isEqualTo(a.result());
            fresh(() -> { assertThat(count("attempt_form")).isEqualTo(1); assertThat(count("attempt_form_item")).isEqualTo(1); });
        } finally { release.countDown(); workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); source.reset(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void replayReturnsCurrentOutcomeEvenFromAnOlderSnapshot(String isolation) throws Exception {
        var revision = saved(quiz("stale", question("stale-q", QuestionType.MCQ_SINGLE)));
        var cmd = command("stale-form", revision, SHUFFLED); var worker = Executors.newSingleThreadExecutor();
        try {
            var replay = transaction(isolation).execute(status -> {
                assertThat(count("attempt_form")).isZero();
                try {
                    var committed = worker.submit(() -> materializer.materialize(OWNER, cmd)).get(15, TimeUnit.SECONDS);
                    contracts.declareScope(OWNER, Set.of(revision.id()));
                    assertThat(materializer.materialize(OWNER, cmd)).isEqualTo(committed);
                    return committed;
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            assertThat(service(contracts(MaterializationFixtures::presentation)).materialize(OWNER, cmd)).isEqualTo(replay);
        } finally { worker.shutdownNow(); assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void loadingCompleteMaterialHasBoundedSqlAcrossMultipleFormsAndIncreasingChildCounts() {
        var small = saved(quiz("small", question("small-a", QuestionType.MCQ_SINGLE), question("small-b", QuestionType.FILL_GAP)));
        var many = IntStream.range(0, 24).mapToObj(n -> question("many-" + n, FAMILIES.get(n % FAMILIES.size()))).toArray(QuestionRevision[]::new);
        var large = saved(quiz("large", many));
        source.selects.clear(); materializer.materialize(OWNER, command("small-form", small, SHUFFLED)); int queries = source.selects.size();
        source.selects.clear(); assertThat(materializer.materialize(OWNER, command("large-form", large, SHUFFLED)).items()).hasSize(24);
        assertThat(source.selects).hasSize(queries);
        assertThat(queries).isLessThan(40);
        var keys = List.of(id("small-form"), id("large-form"));
        source.selects.clear(); assertThat(orders(contracts).findByIds(OWNER, keys)).hasSize(2);
        assertThat(source.selects).hasSize(3);
    }

    @Test
    void telemetryFailureCannotChangeMaterializationAndOutageIsNotAnEmptyForm() {
        var revision = saved(quiz("telemetry", question("telemetry-q", QuestionType.TRUE_FALSE)));
        var cmd = command("telemetry-form", revision, AUTHORED);
        metrics.counter("quizmaker.attempt.form.materialization", "format", "1", "outcome", "success");
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
        source.failConnection = true;
        rejected(() -> materializer.materialize(OWNER, cmd), "PERSISTENCE_UNAVAILABLE"); source.reset();
        assertThat(materializer.materialize(OWNER, cmd).items()).hasSize(1);
        metrics.getMeters().forEach(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag ->
                assertThat(tag.getKey()).isIn("format", "outcome", "operation")));
    }

    private QuizRevision saved(QuizRevision revision) {
        revisions.insert(revision);
        contracts.bind(OWNER, revision.id(), revision.items().stream().map(item -> binding(item.question())).distinct().toList());
        return revision;
    }

    private QuestionRevisionContractService contracts(Function<QuestionRevision, ParticipantContent> projection) {
        return new QuestionRevisionContractServiceImpl(proxy(new JdbcQuestionContractBindingRepository(named())), registry(projection), manager);
    }

    private FormOptionOrderService orders(QuestionRevisionContractService contractService) {
        return new FormOptionOrderServiceImpl(revisions, contractService, proxy(new JdbcFixedFormRepository(named())),
                proxy(new JdbcFormOptionOrderRepository(named())), manager, metrics);
    }

    private FixedFormMaterializer service(QuestionRevisionContractService contractService) {
        return new FixedFormMaterializerImpl(revisions, contractService, orders(contractService), manager, metrics);
    }

    private Outcome race(Command command, String isolation) {
        try {
            return new Outcome(transaction(isolation).execute(status -> {
                assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo(isolation.replace('_', '-'));
                contracts.declareScope(OWNER, Set.of(command.form().quizRevisionId()));
                return materializer.materialize(OWNER, command);
            }), null);
        } catch (RuntimeException failure) { return new Outcome(null, failure.getMessage()); }
    }

    private static TransactionTemplate transaction(String isolation) {
        var tx = new TransactionTemplate(manager); tx.setIsolationLevelName("ISOLATION_" + isolation); return tx;
    }
    private static void fresh(Runnable assertions) {
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> assertions.run());
    }
    private static NamedParameterJdbcTemplate named() { return new NamedParameterJdbcTemplate(source); }
    private static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private static void assertNoForms() {
        for (String table : TABLES.subList(0, 6)) if (!table.equals("attempt_policy_snapshot")) assertThat(count(table)).as(table).isZero();
    }
    private static void assertLegacyUntouched() {
        assertThat(sql.queryForObject("SELECT marker FROM materialization_legacy_fixture", String.class)).isEqualTo("legacy");
    }
    private static void rejected(Runnable action, String reason) {
        assertThatThrownBy(action::run).isInstanceOf(FormMaterializationException.class)
                .hasMessage("Form materialization rejected: " + reason).hasNoCause();
    }
    private static void await(CountDownLatch latch) throws SQLException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new SQLException("Writer release timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException("Writer interrupted"); }
    }
    private static <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T result = (T) factory.getProxy(); return result;
    }
    private record Outcome(ParticipantForm result, String failure) { }
}
