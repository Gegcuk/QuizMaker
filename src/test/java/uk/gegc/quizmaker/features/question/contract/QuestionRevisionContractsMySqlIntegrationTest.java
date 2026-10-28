package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.node.BooleanNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.question.contract.ContractFixtures.*;
import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

/** Real MySQL and Spring transactions; contract adapters are explicit registry doubles, not #701 proof. */
@Tag("db-serial")
class QuestionRevisionContractsMySqlIntegrationTest {
    private static final String IDENTITY_HISTORY = "flyway_contract_identity_history";
    private static final String BINDING_HISTORY = "flyway_contract_binding_history";
    private static ObservedDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager manager;
    private static QuizRevisionRepository revisions;
    private static QuestionRevisionContractService service;

    @BeforeAll
    static void connect() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        source = new ObservedDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser")));
        sql = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        revisions = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)), manager);
        service = service(registry(), manager);
        dropSchema();
        sql.execute("CREATE TABLE contract_legacy_fixture (id INT PRIMARY KEY)");
        sql.execute("CREATE TABLE contract_transaction_receipts (writer INT PRIMARY KEY) ENGINE=InnoDB");
        Flyway.configure().dataSource(source).table(IDENTITY_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("80").target("82").load().migrate();
        // V83 belongs to normalized documents, outside this revision-storage fixture.
        Flyway.configure().dataSource(source).table(BINDING_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("83").target("84").load().migrate();
        source.reset();
    }

    @BeforeEach
    void prepareScenario() { resetData(); }

    @AfterEach
    void cleanupScenario() { resetData(); }

    private static void resetData() {
        source.fault = null;
        source.gate = null;
        source.scopeLockAttempt = null;
        // Each statement commits on a fresh connection. Scenario transactions have already ended.
        // Keep FK enforcement enabled and delete children before parents.
        for (String table : List.of("contract_transaction_receipts", "question_revision_contract_bindings",
                "quiz_revision_items", "question_option_revisions", "quiz_revisions", "question_revisions",
                "contract_legacy_fixture")) {
            sql.update("DELETE FROM " + table);
        }
        source.reset(); // Fixture SQL must not contribute to bounded-query or rollback assertions.
    }

    @AfterAll
    static void dropSchema() {
        if (source == null) return;
        source.fault = null;
        source.gate = null;
        source.scopeLockAttempt = null;
        for (String table : List.of("attempt_trace_identity", "attempt_trace_event", "attempt_trace_stream", "attempt_form_option", "attempt_form_item_option_order", "attempt_form_option_order",
                "attempt_policy_snapshot", "attempt_form_item", "attempt_form",
                "contract_transaction_receipts", "question_revision_contract_bindings", "quiz_revision_items",
                "question_option_revisions", "quiz_revisions", "question_revisions", IDENTITY_HISTORY, BINDING_HISTORY,
                "contract_legacy_fixture")) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void fixtureCleanupRemovesCommittedRowsAndFaultsWithoutChangingSchema() {
        var history = sql.queryForList("SELECT * FROM " + BINDING_HISTORY + " ORDER BY installed_rank");
        var definition = sql.queryForMap("SHOW CREATE TABLE question_revision_contract_bindings");
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        sql.update("INSERT INTO contract_transaction_receipts VALUES (1)");
        sql.update("INSERT INTO contract_legacy_fixture VALUES (1)");
        sql.update("INSERT INTO question_option_revisions VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),'option',0)",
                id(700).toString(), id(1).toString());
        source.fault = Fault.OPEN;
        source.gate = new Gate();
        source.scopeLockAttempt = new CountDownLatch(1);

        resetData();

        assertThat(source.selects).isEmpty();
        assertThat(source.bindingWrites).hasValue(0);
        assertThat(source.rollbacks).hasValue(0);
        assertThat(countBindings()).isZero();
        assertThat(revisions.findById(OWNER, id(10))).isEmpty();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM contract_transaction_receipts", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM contract_legacy_fixture", Integer.class)).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_option_revisions", Integer.class)).isZero();
        assertThat(sql.queryForList("SELECT * FROM " + BINDING_HISTORY + " ORDER BY installed_rank")).isEqualTo(history);
        assertThat(sql.queryForMap("SHOW CREATE TABLE question_revision_contract_bindings")).isEqualTo(definition);
        // The same identities can now represent the next scenario, without a hidden retained binding.
        revisions.insert(quiz(10, question(1, "{\"answer\":false}")));
        service.bind(OWNER, id(10), List.of(binding(1, 2)));
        assertThat(service.resolve(OWNER, id(10)).get(id(1)).binding()).isEqualTo(binding(1, 2));
    }

    @Test
    void contracts01ExactSavedVersionsSurviveLaterRevisionAndComponentRestart() {
        var oldQuestion = question(1, "{\"answer\":true}");
        var newQuestion = question(2, "{\"answer\":false}");
        assertThat(oldQuestion.questionId()).isEqualTo(newQuestion.questionId());
        revisions.insert(quiz(10, oldQuestion));
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        revisions.insert(quiz(11, newQuestion));
        service.bind(OWNER, id(11), List.of(binding(2, 2)));
        var restarted = service(registry(), new DataSourceTransactionManager(source));
        var first = restarted.resolve(OWNER, id(10)).get(id(1));
        var second = restarted.resolve(OWNER, id(11)).get(id(2));
        first.response().validate(oldQuestion, BooleanNode.TRUE);
        second.response().validate(newQuestion, BooleanNode.TRUE);
        assertThat(first.scoring().score(oldQuestion, BooleanNode.TRUE).earned()).isEqualByComparingTo("1");
        assertThat(second.scoring().score(newQuestion, BooleanNode.TRUE).earned()).isEqualByComparingTo("0");
        assertThat(first.binding()).isEqualTo(binding(1, 1));
        assertThat(second.binding()).isEqualTo(binding(2, 2));
        // A deployment without the saved adapter must fail, even though it knows another version.
        var onlyV1 = new QuestionContractRegistryImpl(List.of(response(1)), List.of(scoring(1)), new SimpleMeterRegistry());
        var olderDeployment = service(onlyV1, manager);
        assertThat(olderDeployment.resolve(OWNER, id(10))).hasSize(1);
        rejected(() -> olderDeployment.resolve(OWNER, id(11)), UNSUPPORTED_CONTRACT);
    }

    @Test
    void contracts02UnknownSecondVersionRollsBackReachedBindingAndOuterRevisionWrites() {
        var revision = quiz(10, question(1, "{\"answer\":true}"), question(2, "{\"answer\":false}"));
        source.bindingWrites.set(0);
        rejected(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            revisions.insert(revision);
            service.declareScope(OWNER, Set.of(revision.id()));
            service.bind(OWNER, revision.id(), List.of(binding(1, 1), binding(2, 99)));
        }), UNSUPPORTED_CONTRACT);
        assertThat(source.bindingWrites).as("The supported first binding reached real SQL before the failure").hasValue(1);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            assertThat(countBindings()).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_revisions", Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_revisions", Integer.class)).isZero();
        });
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            revisions.insert(revision);
            service.declareScope(OWNER, Set.of(revision.id()));
            service.bind(OWNER, revision.id(), List.of(binding(1, 1), binding(2, 2)));
        });
        assertThat(service(registry(), manager).resolve(OWNER, revision.id())).hasSize(2);
    }

    @Test
    void conflictingLaterBindingRollsBackEarlierAdditionAndRetainsOriginalWinner() {
        revisions.insert(quiz(10, question(2, "{\"answer\":false}")));
        service.bind(OWNER, id(10), List.of(binding(2, 1)));
        revisions.insert(quiz(11, question(1, "{\"answer\":true}"), question(2, "{\"answer\":false}")));
        source.bindingWrites.set(0);
        rejected(() -> service.bind(OWNER, id(11), List.of(binding(1, 1), binding(2, 2))), BINDING_CONFLICT);
        assertThat(source.bindingWrites).as("Addition and conflicting no-op both executed").hasValue(2);
        assertThat(countBindings()).isEqualTo(1);
        assertThat(service(registry(), manager).resolve(OWNER, id(10)).get(id(2)).binding()).isEqualTo(binding(2, 1));
        rejected(() -> service.resolve(OWNER, id(11)), UNBOUND_REVISION);
        service.bind(OWNER, id(11), List.of(binding(1, 1), binding(2, 1)));
        assertThat(service.resolve(OWNER, id(11))).hasSize(2);
    }

    @Test
    void caughtBindingFailureStillPreventsTheOuterTransactionFromCommittingPartialWrites() {
        var revision = quiz(10, question(1, "{\"answer\":true}"), question(2, "{\"answer\":false}"));
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            revisions.insert(revision);
            service.declareScope(OWNER, Set.of(revision.id()));
            rejected(() -> service.bind(OWNER, revision.id(), List.of(binding(1, 1), binding(2, 99))),
                    UNSUPPORTED_CONTRACT);
            assertThat(source.bindingWrites).hasValue(1);
            assertThat(status.isRollbackOnly()).isTrue();
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(countBindings()).isZero();
        assertThat(revisions.findById(OWNER, revision.id())).isEmpty();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            revisions.insert(revision);
            service.declareScope(OWNER, Set.of(revision.id()));
            service.bind(OWNER, revision.id(), List.of(binding(1, 1), binding(2, 2)));
        });
        assertThat(service.resolve(OWNER, revision.id())).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"1,2,READ_COMMITTED", "1,2,REPEATABLE_READ", "1,1,READ_COMMITTED", "1,1,REPEATABLE_READ"})
    void contracts03ConcurrentBindingsHaveSuccessfulWinnersAndImmutableReplay(int firstVersion, int secondVersion,
                                                                           String isolation) throws Exception {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        var gate = new Gate();
        source.gate = gate;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentBind(firstVersion, isolation));
            var second = executor.submit(() -> concurrentBind(secondVersion, isolation));
            assertThat(gate.arrived.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.connections).hasSize(2);
            assertThat(source.bindingWrites).hasValue(0);
            assertThat(countBindings()).isZero();
            gate.release.countDown();
            var outcomes = Arrays.asList(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(firstVersion == secondVersion ? 2 : 1);
            if (firstVersion != secondVersion) assertThat(outcomes).contains(BINDING_CONFLICT);
            source.gate = null;
            var restarted = service(registry(), new DataSourceTransactionManager(source));
            var winner = restarted.resolve(OWNER, id(10)).get(id(1)).binding();
            assertThat(countBindings()).isEqualTo(1);
            restarted.bind(OWNER, id(10), List.of(winner));
            rejected(() -> restarted.bind(OWNER, id(10), List.of(binding(1, winner.response().version() == 1 ? 2 : 1))), BINDING_CONFLICT);
            assertThat(restarted.resolve(OWNER, id(10)).get(id(1)).binding()).isEqualTo(winner);
        } finally {
            gate.release.countDown();
            source.gate = null;
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ContractResolutionException.Reason concurrentBind(int version, String isolation) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevelName("ISOLATION_" + isolation);
        try {
            tx.executeWithoutResult(status -> {
                service.declareScope(OWNER, Set.of(id(10)));
                service.bind(OWNER, id(10), List.of(binding(1, version)));
            });
            return null;
        } catch (ContractResolutionException failure) { return failure.reason(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void identicalConcurrentBindCanResolveInsideTheSameOlderTransaction(String isolation) throws Exception {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        var snapshotEstablished = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var replay = executor.submit(() -> {
                var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
                tx.setIsolationLevelName("ISOLATION_" + isolation);
                return tx.execute(status -> {
                    assertThat(countBindings()).as("Establish the loser's snapshot before the winner commits").isZero();
                    snapshotEstablished.countDown();
                    await(winnerCommitted);
                    service.declareScope(OWNER, Set.of(id(10)));
                    service.bind(OWNER, id(10), List.of(binding(1, 1)));
                    return service.resolve(OWNER, id(10)).get(id(1)).binding();
                });
            });
            assertThat(snapshotEstablished.await(10, TimeUnit.SECONDS)).isTrue();
            service.bind(OWNER, id(10), List.of(binding(1, 1)));
            winnerCommitted.countDown();
            assertThat(replay.get(15, TimeUnit.SECONDS)).isEqualTo(binding(1, 1));
            assertThat(service(registry(), manager).resolve(OWNER, id(10)).get(id(1)).binding()).isEqualTo(binding(1, 1));
            assertThat(countBindings()).isEqualTo(1);
        } finally {
            winnerCommitted.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({"1,READ_COMMITTED", "2,READ_COMMITTED", "1,REPEATABLE_READ", "2,REPEATABLE_READ"})
    void concurrentQuizzesWithSharedQuestionsAndOppositeInputOrdersDoNotDeadlock(int secondVersion,
                                                                               String isolation) throws Exception {
        var firstQuestion = question(1, "{\"answer\":true}");
        var secondQuestion = question(-1, "{\"answer\":false}");
        revisions.insert(quiz(10, firstQuestion, secondQuestion));
        revisions.insert(quiz(11, secondQuestion, firstQuestion));
        var gate = new Gate();
        source.gate = gate;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> sharedQuizBind(10, List.of(binding(1, 1), binding(-1, 1)), isolation));
            var second = executor.submit(() -> sharedQuizBind(11,
                    List.of(binding(-1, secondVersion), binding(1, secondVersion)), isolation));
            assertThat(gate.arrived.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.connections).hasSize(2);
            assertThat(source.bindingWrites).hasValue(0);
            gate.release.countDown();
            var outcomes = Arrays.asList(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(Objects::isNull).count()).as("Writer outcomes: %s", outcomes)
                    .isEqualTo(secondVersion == 1 ? 2 : 1);
            if (secondVersion != 1) assertThat(outcomes).contains(BINDING_CONFLICT);
            source.gate = null;
            var restarted = service(registry(), manager);
            var winner = restarted.resolve(OWNER, id(10));
            assertThat(winner).hasSize(2);
            assertThat(winner.get(id(1)).binding().response().version())
                    .isEqualTo(winner.get(id(-1)).binding().response().version());
            assertThat(restarted.resolve(OWNER, id(11))).isEqualTo(winner);
            assertThat(countBindings()).isEqualTo(2);
        } finally {
            gate.release.countDown();
            source.gate = null;
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ContractResolutionException.Reason sharedQuizBind(long quiz, List<QuestionContractBinding> bindings,
                                                            String isolation) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setIsolationLevelName("ISOLATION_" + isolation);
        try {
            tx.executeWithoutResult(status -> {
                service.declareScope(OWNER, Set.of(id(quiz)));
                service.bind(OWNER, id(quiz), bindings);
                assertThat(service.resolve(OWNER, id(quiz)).values().stream().map(value -> value.binding()).toList())
                        .containsExactlyInAnyOrderElementsOf(bindings);
            });
            return null;
        } catch (ContractResolutionException failure) { return failure.reason(); }
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED,1", "false,REPEATABLE_READ,1",
            "true,READ_COMMITTED,1", "true,REPEATABLE_READ,1",
            "false,READ_COMMITTED,2", "false,REPEATABLE_READ,2",
            "true,READ_COMMITTED,2", "true,REPEATABLE_READ,2"})
    void concurrentResolveThenIdenticalBindCommitsBothOuterTransactions(boolean jpa, String isolation,
                                                                       int questionCount) throws Exception {
        var firstQuestion = question(1, "{\"answer\":true}");
        var secondQuestion = question(-1, "{\"answer\":false}");
        revisions.insert(questionCount == 1 ? quiz(10, firstQuestion) : quiz(10, firstQuestion, secondQuestion));
        revisions.insert(questionCount == 1 ? quiz(11, firstQuestion) : quiz(11, secondQuestion, firstQuestion));
        var bindings = questionCount == 1 ? List.of(binding(1, 1)) : List.of(binding(1, 1), binding(-1, 1));
        service.bind(OWNER, id(10), bindings);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM contract_transaction_receipts", Integer.class)).isZero();
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        var firstRead = new CountDownLatch(1);
        var secondRead = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        source.bindingWrites.set(0);
        try {
            var first = executor.submit(() -> resolveThenBind(transactions, isolation, 1, 10, 11,
                    bindings, firstRead, release));
            assertThat(firstRead.await(10, TimeUnit.SECONDS)).isTrue();
            var attempt = new CountDownLatch(1);
            source.scopeLockAttempt = attempt;
            var reversed = new ArrayList<>(bindings);
            Collections.reverse(reversed);
            var second = executor.submit(() -> resolveThenBind(transactions, isolation, 2, 11, 10,
                    reversed, secondRead, release));
            assertThat(attempt.await(10, TimeUnit.SECONDS)).as("Second connection reached the declared-scope SELECT").isTrue();
            // The complete scope is reserved before the first resolve, while the outer transaction stays open.
            boolean secondResolvedBeforeFirstCompleted = secondRead.await(1, TimeUnit.SECONDS);
            assertThat(source.bindingWrites).hasValue(0);
            source.scopeLockAttempt = null;
            release.countDown();
            var outcomes = Arrays.asList(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(outcomes).as("Both identical commands must commit: %s", outcomes).containsOnlyNulls();
            assertThat(secondResolvedBeforeFirstCompleted).isFalse();
            assertThat(source.bindingWrites).as("Both callers reached the real insert/no-op for every binding")
                    .hasValue(2 * questionCount);
            assertThat(sql.queryForList("SELECT writer FROM contract_transaction_receipts ORDER BY writer", Integer.class))
                    .containsExactly(1, 2);
            var restarted = service(registry(), new DataSourceTransactionManager(source));
            for (long parent : List.of(10L, 11L)) {
                assertThat(restarted.resolve(OWNER, id(parent)).values().stream().map(value -> value.binding()).toList())
                        .containsExactlyInAnyOrderElementsOf(bindings);
            }
            assertThat(countBindings()).isEqualTo(questionCount);
        } finally {
            release.countDown();
            source.scopeLockAttempt = null;
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            if (factory != null) factory.destroy();
        }
    }

    private ContractResolutionException.Reason resolveThenBind(PlatformTransactionManager transactions, String isolation,
            int writer, long readQuiz, long bindQuiz, List<QuestionContractBinding> bindings,
            CountDownLatch resolved, CountDownLatch release) {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevelName("ISOLATION_" + isolation);
        var scoped = service(registry(), transactions);
        try {
            tx.executeWithoutResult(status -> {
                scoped.declareScope(OWNER, Set.of(id(readQuiz), id(bindQuiz)));
                sql.update("INSERT INTO contract_transaction_receipts (writer) VALUES (?)", writer);
                assertThat(scoped.resolve(OWNER, id(readQuiz))).hasSize(bindings.size());
                resolved.countDown();
                await(release);
                scoped.bind(OWNER, id(bindQuiz), bindings);
                assertThat(scoped.resolve(OWNER, id(bindQuiz)).values().stream().map(value -> value.binding()).toList())
                        .containsExactlyInAnyOrderElementsOf(bindings);
            });
            return null;
        } catch (ContractResolutionException failure) { return failure.reason(); }
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED", "false,REPEATABLE_READ", "true,READ_COMMITTED", "true,REPEATABLE_READ"})
    void readOnlyTransactionsCanResolveTheSameBindingsConcurrently(boolean jpa, String isolation) throws Exception {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}"), question(-1, "{\"answer\":false}")));
        service.bind(OWNER, id(10), List.of(binding(1, 1), binding(-1, 1)));
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        var readers = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        source.bindingWrites.set(0);
        try {
            Callable<Void> read = () -> {
                var tx = new TransactionTemplate(transactions);
                tx.setReadOnly(true);
                tx.setIsolationLevelName("ISOLATION_" + isolation);
                tx.executeWithoutResult(status -> {
                    service(registry(), transactions).declareScope(OWNER, Set.of(id(10)));
                    assertThat(service(registry(), transactions).resolve(OWNER, id(10))).hasSize(2);
                    readers.countDown();
                    await(release);
                });
                return null;
            };
            var first = executor.submit(read);
            var second = executor.submit(read);
            assertThat(readers.await(10, TimeUnit.SECONDS)).as("Both reads finish while both transactions stay open").isTrue();
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(source.bindingWrites).hasValue(0);
            assertThat(countBindings()).isEqualTo(2);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            if (factory != null) factory.destroy();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transactionOpenFailureIsTypedForBothWriteAndRead(boolean read) {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        source.fault = Fault.OPEN;
        try {
            rejected(() -> {
                if (read) service.resolve(OWNER, id(10));
                else service.bind(OWNER, id(10), List.of(binding(1, 1)));
            }, PERSISTENCE_UNAVAILABLE);
        } finally { source.fault = null; }
        assertThat(source.bindingWrites).hasValue(0);
        assertThat(countBindings()).isZero();
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        assertThat(service.resolve(OWNER, id(10))).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"SQL_AFTER_FIRST_WRITE", "ROLLBACK_AFTER"})
    void sqlOrRollbackFailureIsTypedAndFreshConnectionProvesNoPartialBindings(Fault fault) {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}"), question(2, "{\"answer\":false}")));
        source.fault = fault;
        try {
            rejected(() -> service.bind(OWNER, id(10), List.of(binding(1, 1), binding(2, 2))), PERSISTENCE_UNAVAILABLE);
        } finally { source.fault = null; }
        assertThat(source.bindingWrites).as("First authoritative write really executed before SQL failure").hasValue(1);
        assertThat(source.rollbacks).hasValue(1);
        assertThat(countBindings()).isZero();
        rejected(() -> service(registry(), manager).resolve(OWNER, id(10)), UNBOUND_REVISION);
        service.bind(OWNER, id(10), List.of(binding(1, 1), binding(2, 2)));
        assertThat(service.resolve(OWNER, id(10))).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = Fault.class, names = {"COMMIT_BEFORE", "COMMIT_AFTER"})
    void commitFailureIsTypedAndUnknownOutcomeCanBeReconciledAndReplayed(Fault fault) {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        source.fault = fault;
        try {
            rejected(() -> service.bind(OWNER, id(10), List.of(binding(1, 1))), PERSISTENCE_UNAVAILABLE);
        } finally { source.fault = null; }
        assertThat(source.bindingWrites).hasValue(1);
        assertThat(countBindings()).isEqualTo(fault == Fault.COMMIT_AFTER ? 1 : 0);
        var restarted = service(registry(), new DataSourceTransactionManager(source));
        if (fault == Fault.COMMIT_AFTER) {
            assertThat(restarted.resolve(OWNER, id(10)).get(id(1)).binding()).isEqualTo(binding(1, 1));
        } else rejected(() -> restarted.resolve(OWNER, id(10)), UNBOUND_REVISION);
        restarted.bind(OWNER, id(10), List.of(binding(1, 1)));
        assertThat(restarted.resolve(OWNER, id(10)).get(id(1)).binding()).isEqualTo(binding(1, 1));
        assertThat(countBindings()).isEqualTo(1);
    }

    @Test
    void readTransactionCommitFailureAlsoUsesTheTypedBoundary() {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        source.fault = Fault.COMMIT_AFTER;
        try { rejected(() -> service.resolve(OWNER, id(10)), PERSISTENCE_UNAVAILABLE); }
        finally { source.fault = null; }
        assertThat(service(registry(), manager).resolve(OWNER, id(10))).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted at the transaction barrier", failure);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED,false", "false,REPEATABLE_READ,false",
            "true,READ_COMMITTED,false", "true,REPEATABLE_READ,false",
            "false,READ_COMMITTED,true", "false,REPEATABLE_READ,true",
            "true,READ_COMMITTED,true", "true,REPEATABLE_READ,true"})
    void declaredUnionAllowsOppositeSubsetReadsAndOptionalSupersetBind(boolean jpa, String isolation,
                                                                      boolean bind) throws Exception {
        separateAndCombinedRevisions();
        var bindings = List.of(binding(1, 1), binding(-1, 1));
        service.bind(OWNER, id(12), bindings);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM contract_transaction_receipts", Integer.class)).isZero();
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        var firstRead = new CountDownLatch(1);
        var secondRead = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        source.reset();
        try {
            var first = executor.submit(() -> composedOperation(transactions, isolation, 1, bind, firstRead, release));
            assertThat(firstRead.await(10, TimeUnit.SECONDS)).isTrue();
            source.scopeLockAttempt = new CountDownLatch(1);
            var second = executor.submit(() -> composedOperation(transactions, isolation, 2, bind, secondRead, release));
            assertThat(source.scopeLockAttempt.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondRead.await(1, TimeUnit.SECONDS)).as("Full union is reserved before either subset read").isFalse();
            assertThat(source.bindingWrites).hasValue(0);
            source.scopeLockAttempt = null;
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertThat(sql.queryForList("SELECT writer FROM contract_transaction_receipts ORDER BY writer", Integer.class))
                    .containsExactly(1, 2);
            assertThat(source.bindingWrites).hasValue(bind ? 4 : 0);
            assertThat(service(registry(), manager).resolve(OWNER, id(12)).values().stream().map(value -> value.binding()))
                    .containsExactlyInAnyOrderElementsOf(bindings);
            assertThat(countBindings()).isEqualTo(2);
        } finally {
            release.countDown();
            source.scopeLockAttempt = null;
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            if (factory != null) factory.destroy();
        }
    }

    private void composedOperation(PlatformTransactionManager transactions, String isolation, int writer,
                                   boolean bind, CountDownLatch firstRead, CountDownLatch release) {
        var scoped = service(registry(), transactions);
        transaction(transactions, isolation).executeWithoutResult(status -> {
            var roots = new LinkedHashSet<UUID>(writer == 1 ? List.of(id(10), id(11), id(12))
                    : List.of(id(12), id(11), id(10)));
            scoped.declareScope(OWNER, roots);
            sql.update("INSERT INTO contract_transaction_receipts VALUES (?)", writer);
            assertThat(scoped.resolve(OWNER, id(writer == 1 ? 10 : 11))).hasSize(1);
            firstRead.countDown();
            await(release);
            if (bind) scoped.bind(OWNER, id(12), List.of(binding(-1, 1), binding(1, 1)));
            assertThat(scoped.resolve(OWNER, id(writer == 1 ? 11 : 10))).hasSize(1);
        });
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED,false", "false,REPEATABLE_READ,false",
            "true,READ_COMMITTED,false", "true,REPEATABLE_READ,false",
            "false,READ_COMMITTED,true", "false,REPEATABLE_READ,true",
            "true,READ_COMMITTED,true", "true,REPEATABLE_READ,true"})
    void disjointWritersAndOverlappingReadersRemainParallel(boolean jpa, String isolation,
                                                            boolean readOnly) throws Exception {
        var a = question(1, "{}");
        var b = question(2, "{}");
        var c = question(-1, "{}");
        var d = question(-2, "{}");
        revisions.insert(quiz(10, a, b));
        revisions.insert(quiz(11, c, d));
        revisions.insert(quiz(12, d, b, c, a));
        if (readOnly) service.bind(OWNER, id(12), List.of(binding(1, 1), binding(2, 1), binding(-1, 1), binding(-2, 1)));
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int writer = 1; writer <= 2; writer++) {
                int number = writer;
                futures.add(executor.submit(() -> {
                    var tx = transaction(transactions, isolation);
                    tx.setReadOnly(readOnly);
                    var scoped = service(registry(), transactions);
                    tx.executeWithoutResult(status -> {
                        long root = number == 1 ? 10 : 11;
                        scoped.declareScope(OWNER, readOnly ? Set.of(id(10), id(11)) : Set.of(id(root)));
                        if (readOnly) {
                            assertThat(scoped.resolve(OWNER, id(root))).hasSize(2);
                            assertThat(scoped.resolve(OWNER, id(number == 1 ? 11 : 10))).hasSize(2);
                        } else {
                            scoped.bind(OWNER, id(root), number == 1 ? List.of(binding(1, 1), binding(2, 1))
                                    : List.of(binding(-1, 1), binding(-2, 1)));
                            assertThat(scoped.resolve(OWNER, id(root))).hasSize(2);
                        }
                        entered.countDown();
                        await(release);
                    });
                }));
            }
            boolean bothEntered = entered.await(5, TimeUnit.SECONDS);
            if (!bothEntered) {
                for (var future : futures) if (future.isDone()) future.get(1, TimeUnit.SECONDS);
            }
            assertThat(bothEntered).as("Both operations enter while both transactions stay open; binding writes=%s, SQL=%s",
                    source.bindingWrites.get(), source.selects).isTrue();
            release.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
            assertThat(service.resolve(OWNER, id(12))).hasSize(4);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            if (factory != null) factory.destroy();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED", "false,REPEATABLE_READ", "true,READ_COMMITTED", "true,REPEATABLE_READ"})
    void unboundQuestionsAreReservedBeforeOppositeOrderMultiCallBinds(boolean jpa, String isolation) throws Exception {
        separateAndCombinedRevisions();
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        var gate = new Gate();
        source.gate = gate;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<?>>();
            for (int writer = 1; writer <= 2; writer++) {
                int number = writer;
                futures.add(executor.submit(() -> transaction(transactions, isolation).executeWithoutResult(status -> {
                    var scoped = service(registry(), transactions);
                    scoped.declareScope(OWNER, Set.of(id(10), id(11)));
                    for (long root : number == 1 ? List.of(10L, 11L) : List.of(11L, 10L)) {
                        scoped.bind(OWNER, id(root), List.of(binding(root == 10 ? 1 : -1, 1)));
                        assertThat(scoped.resolve(OWNER, id(root))).hasSize(1);
                    }
                })));
            }
            assertThat(gate.arrived.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(gate.connections).hasSize(2);
            assertThat(countBindings()).isZero();
            gate.release.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
            assertThat(source.bindingWrites).hasValue(4);
            assertThat(service.resolve(OWNER, id(12))).hasSize(2);
        } finally {
            gate.release.countDown();
            source.gate = null;
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            if (factory != null) factory.destroy();
        }
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED,declare", "false,REPEATABLE_READ,declare",
            "true,READ_COMMITTED,declare", "true,REPEATABLE_READ,declare",
            "false,READ_COMMITTED,resolve", "false,REPEATABLE_READ,resolve",
            "true,READ_COMMITTED,resolve", "true,REPEATABLE_READ,resolve",
            "false,READ_COMMITTED,bind", "false,REPEATABLE_READ,bind",
            "true,READ_COMMITTED,bind", "true,REPEATABLE_READ,bind"})
    void caughtScopeExpansionRollsBackWholeOuterTransaction(boolean jpa, String isolation, String operation) {
        separateAndCombinedRevisions();
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        try {
            var scoped = service(registry(), transactions);
            Runnable outer = () -> transaction(transactions, isolation).executeWithoutResult(status -> {
                scoped.declareScope(OWNER, Set.of(id(10), id(11)));
                scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
                assertThat(source.bindingWrites).hasValue(1);
                // Root 12 contains exactly the same question union, but was not declared.
                // A recreated component must not give the caller a second scope for this transaction.
                var restarted = service(registry(), transactions);
                rejected(() -> {
                    switch (operation) {
                        case "declare" -> restarted.declareScope(OWNER, Set.of(id(12)));
                        case "resolve" -> restarted.resolve(OWNER, id(12));
                        case "bind" -> restarted.bind(OWNER, id(12), List.of(binding(1, 1), binding(-1, 1)));
                        default -> throw new AssertionError(operation);
                    }
                }, SCOPE_VIOLATION);
                assertThat(status.isRollbackOnly()).isTrue();
            });
            // JPA invokes beforeCommit even with a global rollback marker; JDBC rolls back earlier.
            if (jpa) rejected(outer, SCOPE_VIOLATION);
            else assertThatThrownBy(outer::run).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
            assertThat(countBindings()).isZero();
            transaction(transactions, isolation).executeWithoutResult(status -> {
                var requested = new HashSet<>(Set.of(id(10), id(11), id(12)));
                scoped.declareScope(OWNER, requested);
                requested.clear();
                scoped.declareScope(OWNER, Set.of(id(10))); // Idempotent subset does not shrink the original.
                scoped.bind(OWNER, id(11), List.of(binding(-1, 1)));
                scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
                assertThat(scoped.resolve(OWNER, id(12))).hasSize(2);
            });
            assertThat(service.resolve(OWNER, id(12))).hasSize(2);
        } finally { if (factory != null) factory.destroy(); }
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED", "false,REPEATABLE_READ", "true,READ_COMMITTED", "true,REPEATABLE_READ"})
    void requiresNewGetsItsOwnScopeAndRestoresOuterScope(boolean jpa, String isolation) {
        separateAndCombinedRevisions();
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        var scoped = service(registry(), transactions);
        try {
            transaction(transactions, isolation).executeWithoutResult(status -> {
                scoped.declareScope(OWNER, Set.of(id(10)));
                var inner = transaction(transactions, isolation);
                inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                inner.executeWithoutResult(child -> {
                    scoped.declareScope(OWNER, Set.of(id(11)));
                    scoped.bind(OWNER, id(11), List.of(binding(-1, 1)));
                });
                scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
                assertThat(scoped.resolve(OWNER, id(10))).hasSize(1);
                status.setRollbackOnly();
            });
            assertThat(countBindings()).isEqualTo(1);
            assertThat(service.resolve(OWNER, id(11))).hasSize(1);
            rejected(() -> service.resolve(OWNER, id(10)), UNBOUND_REVISION);
            scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
            assertThat(scoped.resolve(OWNER, id(12))).hasSize(2);
        } finally { if (factory != null) factory.destroy(); }
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,declare,false", "REPEATABLE_READ,declare,false",
            "READ_COMMITTED,resolve,false", "REPEATABLE_READ,resolve,false",
            "READ_COMMITTED,bind,false", "REPEATABLE_READ,bind,false",
            "READ_COMMITTED,declare,true", "REPEATABLE_READ,declare,true",
            "READ_COMMITTED,resolve,true", "REPEATABLE_READ,resolve,true",
            "READ_COMMITTED,bind,true", "REPEATABLE_READ,bind,true"})
    void scopeViolationSurvivesManualAndNestedSavepointRollback(String isolation, String operation, boolean nested) {
        separateAndCombinedRevisions();
        var transactions = manager;
        var scoped = service(registry(), transactions);
        rejected(() -> transaction(transactions, isolation).executeWithoutResult(status -> {
            scoped.declareScope(OWNER, Set.of(id(10)));
            scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
            assertThat(source.bindingWrites).hasValue(1);
            Runnable violation = () -> {
                var restarted = service(registry(), transactions);
                switch (operation) {
                    case "declare" -> restarted.declareScope(OWNER, Set.of(id(11)));
                    case "resolve" -> restarted.resolve(OWNER, id(11));
                    case "bind" -> restarted.bind(OWNER, id(11), List.of(binding(-1, 1)));
                    default -> throw new AssertionError(operation);
                }
            };
            if (nested) {
                var step = transaction(transactions, isolation);
                step.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
                rejected(() -> step.executeWithoutResult(child -> violation.run()), SCOPE_VIOLATION);
            } else {
                var savepoint = status.createSavepoint();
                rejected(violation, SCOPE_VIOLATION);
                status.rollbackToSavepoint(savepoint);
                status.releaseSavepoint(savepoint);
            }
            assertThat(status.isRollbackOnly()).as("Spring clears its rollback marker at this savepoint").isFalse();
            assertThat(countBindings()).as("A predates the savepoint and is still visible before outer completion").isEqualTo(1);
        }), SCOPE_VIOLATION);
        assertThat(countBindings()).as("Fresh connection after the rejected outer commit").isZero();
        rejected(() -> service.resolve(OWNER, id(10)), UNBOUND_REVISION);
        // Completion must clear the poisoned scope so a fresh operation can commit normally.
        scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
        assertThat(scoped.resolve(OWNER, id(10))).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({"false,READ_COMMITTED,false", "false,REPEATABLE_READ,false",
            "true,READ_COMMITTED,false", "true,REPEATABLE_READ,false",
            "false,READ_COMMITTED,true", "false,REPEATABLE_READ,true",
            "true,READ_COMMITTED,true", "true,REPEATABLE_READ,true"})
    void requiresNewKeepsViolationFlagsIndependent(boolean jpa, String isolation, boolean poisonOuter) {
        separateAndCombinedRevisions();
        var factory = jpa ? jpaFactory() : null;
        var transactions = transactionManager(factory);
        var scoped = service(registry(), transactions);
        try {
            Runnable outer = () -> transaction(transactions, isolation).executeWithoutResult(status -> {
                scoped.declareScope(OWNER, Set.of(id(10)));
                scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
                if (poisonOuter) violateScope(status, scoped, 11, jpa);
                var inner = transaction(transactions, isolation);
                inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                Runnable child = () -> inner.executeWithoutResult(childStatus -> {
                    scoped.declareScope(OWNER, Set.of(id(11)));
                    scoped.bind(OWNER, id(11), List.of(binding(-1, 1)));
                    if (!poisonOuter) violateScope(childStatus, scoped, 10, jpa);
                });
                if (poisonOuter) child.run();
                else rejected(child, SCOPE_VIOLATION);
                assertThat(status.isRollbackOnly()).isEqualTo(jpa && poisonOuter);
                assertThat(scoped.resolve(OWNER, id(10))).hasSize(1);
            });
            if (poisonOuter) rejected(outer, SCOPE_VIOLATION);
            else outer.run();
            assertThat(countBindings()).isEqualTo(1);
            long committedRoot = poisonOuter ? 11 : 10;
            long rolledBackRoot = poisonOuter ? 10 : 11;
            assertThat(service.resolve(OWNER, id(committedRoot))).hasSize(1);
            rejected(() -> service.resolve(OWNER, id(rolledBackRoot)), UNBOUND_REVISION);
            service.bind(OWNER, id(rolledBackRoot), List.of(binding(poisonOuter ? 1 : -1, 1)));
            assertThat(service.resolve(OWNER, id(12))).hasSize(2);
        } finally { if (factory != null) factory.destroy(); }
    }

    private static void violateScope(org.springframework.transaction.TransactionStatus status,
                                     QuestionRevisionContractService scoped, long undeclaredRoot, boolean jpa) {
        if (jpa) {
            rejected(() -> scoped.declareScope(OWNER, Set.of(id(undeclaredRoot))), SCOPE_VIOLATION);
            assertThat(status.isRollbackOnly()).isTrue();
            return;
        }
        var savepoint = status.createSavepoint();
        rejected(() -> scoped.declareScope(OWNER, Set.of(id(undeclaredRoot))), SCOPE_VIOLATION);
        status.rollbackToSavepoint(savepoint);
        status.releaseSavepoint(savepoint);
        assertThat(status.isRollbackOnly()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void ordinaryNestedRollbackDoesNotPoisonAValidScope(String isolation) {
        separateAndCombinedRevisions();
        var transactions = manager;
        var scoped = service(registry(), transactions);
        transaction(transactions, isolation).executeWithoutResult(status -> {
            scoped.declareScope(OWNER, Set.of(id(10), id(11)));
            scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
            var nested = transaction(transactions, isolation);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            nested.executeWithoutResult(child -> {
                scoped.bind(OWNER, id(11), List.of(binding(-1, 1)));
                child.setRollbackOnly();
            });
            assertThat(status.isRollbackOnly()).isFalse();
        });
        assertThat(source.bindingWrites).hasValue(2);
        assertThat(countBindings()).isEqualTo(1);
        assertThat(service.resolve(OWNER, id(10))).hasSize(1);
        rejected(() -> service.resolve(OWNER, id(11)), UNBOUND_REVISION);
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,false", "REPEATABLE_READ,false", "READ_COMMITTED,true", "REPEATABLE_READ,true"})
    void hibernateJpaRejectsSavepointsWithoutPoisoningTheOuterScope(String isolation, boolean nested) {
        separateAndCombinedRevisions();
        var factory = jpaFactory();
        var transactions = (org.springframework.orm.jpa.JpaTransactionManager) transactionManager(factory);
        // Enabling nested transactions cannot add savepoint support to HibernateJpaDialect.
        transactions.setNestedTransactionAllowed(true);
        var scoped = service(registry(), transactions);
        try {
            transaction(transactions, isolation).executeWithoutResult(status -> {
                scoped.declareScope(OWNER, Set.of(id(10)));
                scoped.bind(OWNER, id(10), List.of(binding(1, 1)));
                if (nested) {
                    var step = transaction(transactions, isolation);
                    step.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
                    assertThatThrownBy(() -> step.executeWithoutResult(child -> fail("Unsupported nested callback must not run")))
                            .isInstanceOf(org.springframework.transaction.NestedTransactionNotSupportedException.class);
                } else assertThatThrownBy(status::createSavepoint)
                        .isInstanceOf(org.springframework.transaction.NestedTransactionNotSupportedException.class);
                assertThat(status.isRollbackOnly()).isFalse();
            });
            assertThat(countBindings()).isEqualTo(1);
            assertThat(service.resolve(OWNER, id(10))).hasSize(1);
        } finally { factory.destroy(); }
    }

    @Test
    void declarationValidatesAllOwnersBeforeLockingAndScopeCannotBeBypassed() {
        separateAndCombinedRevisions();
        rejected(() -> service.declareScope(OWNER, Set.of(id(10))), TRANSACTION_REQUIRED);
        for (boolean read : List.of(false, true)) {
            assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                rejected(() -> {
                    if (read) service.resolve(OWNER, id(10));
                    else service.bind(OWNER, id(10), List.of(binding(1, 1)));
                }, SCOPE_REQUIRED);
                assertThat(status.isRollbackOnly()).isTrue();
            })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        }
        var foreign = quiz(13, question(3, "{}"));
        revisions.insert(new uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision(foreign.id(), foreign.quizId(),
                id(999), foreign.schemaVersion(), foreign.title(), foreign.description(), foreign.createdAt(), foreign.items()));
        for (Set<UUID> invalid : Arrays.asList(null, new HashSet<>(Arrays.asList(id(10), null)),
                Set.of(id(10), id(13)), Set.of(id(10), id(999)))) {
            source.reset();
            rejected(() -> new TransactionTemplate(manager).executeWithoutResult(status -> service.declareScope(OWNER, invalid)),
                    invalid == null ? INVALID_SCOPE : REVISION_UNAVAILABLE);
            assertThat(source.selects).noneMatch(statement -> statement.contains("FROM question_revisions q")
                    || statement.contains("FROM question_revision_contract_bindings"));
        }
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var requested = new HashSet<>(Set.of(id(10)));
            service.declareScope(OWNER, requested);
            requested.add(id(11));
            rejected(() -> service.declareScope(OWNER, requested), SCOPE_VIOLATION);
            status.setRollbackOnly();
        });
        assertThat(countBindings()).isZero();
        service.bind(OWNER, id(12), List.of(binding(1, 1), binding(-1, 1)));
        assertThat(service.resolve(OWNER, id(12))).hasSize(2);
    }

    @Test
    void savepointRollbackCannotMakeCachedScopeAuthorizeAnAbsentRevision() {
        var outer = new TransactionTemplate(manager);
        outer.executeWithoutResult(status -> {
            var nested = new TransactionTemplate(manager);
            nested.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
            nested.executeWithoutResult(child -> {
                revisions.insert(quiz(10, question(1, "{}")));
                service.declareScope(OWNER, Set.of(id(10)));
                child.setRollbackOnly();
            });
            rejected(() -> service.resolve(OWNER, id(10)), REVISION_UNAVAILABLE);
            assertThat(status.isRollbackOnly()).isTrue();
            status.setRollbackOnly();
        });
        assertThat(countBindings()).isZero();
        revisions.insert(quiz(10, question(1, "{}")));
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        assertThat(service.resolve(OWNER, id(10))).hasSize(1);
    }

    @Test
    void bulkScopeLoadsAllParentsInTwoMetadataQueriesThenTwoPerResolve() {
        separateAndCombinedRevisions();
        service.bind(OWNER, id(12), List.of(binding(1, 1), binding(-1, 1)));
        source.reset();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.declareScope(OWNER, Set.of(id(12), id(11), id(10)));
            assertThat(source.selects).hasSize(2);
            assertThat(source.selects.get(1)).contains("FOR UPDATE");
            for (long root : List.of(10L, 11L, 12L)) service.resolve(OWNER, id(root));
            assertThat(source.selects).hasSize(8);
        });
        assertThat(source.selects).allSatisfy(statement -> assertThat(statement)
                .doesNotContain("content", "question_text", "normalized_text", "SELECT *"));
        assertThat(source.bindingWrites).hasValue(0);
    }

    private static TransactionTemplate transaction(PlatformTransactionManager transactions, String isolation) {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevelName("ISOLATION_" + isolation);
        return tx;
    }

    @Test
    void largeScopeAndResolutionUseBoundedBatchesWithoutLosingAnIdentity() {
        var questions = java.util.stream.LongStream.rangeClosed(1, 501).mapToObj(index -> question(index, "{}"))
                .toArray(uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision[]::new);
        revisions.insert(quiz(10_000, questions));
        var roots = new HashSet<>(Set.of(id(10_000)));
        for (long root = 10_001; root <= 10_500; root++) {
            revisions.insert(quiz(root));
            roots.add(id(root));
        }
        service.bind(OWNER, id(10_000), java.util.stream.LongStream.rangeClosed(1, 501)
                .mapToObj(index -> binding(index, 1)).toList());
        source.reset();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.declareScope(OWNER, roots);
            assertThat(source.selects).hasSize(4);
            assertThat(service.resolve(OWNER, id(10_000))).hasSize(501);
            assertThat(source.selects).hasSize(7);
            assertThat(service.resolve(OWNER, id(10_500))).isEmpty();
            assertThat(source.selects).hasSize(8);
        });
        assertThat(source.selects).allSatisfy(statement -> assertThat(statement)
                .doesNotContain("content", "question_text", "normalized_text", "SELECT *"));
        assertThat(source.bindingWrites).hasValue(0);
    }

    private static void separateAndCombinedRevisions() {
        var a = question(1, "{\"answer\":true}");
        var b = question(-1, "{\"answer\":false}");
        revisions.insert(quiz(10, a));
        revisions.insert(quiz(11, b));
        revisions.insert(quiz(12, b, a));
    }

    @Test
    void contracts04MetadataReadsAreFourQueriesForMultipleParentsAndNeverSelectLargeContent() {
        var questions = new ArrayList<uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision>();
        for (int index = 1; index <= 12; index++) {
            questions.add(question(index, "{\"answer\":true,\"padding\":\"" + "x".repeat(100_000) + "\"}"));
        }
        var revision = quiz(10, questions.toArray(uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision[]::new));
        revisions.insert(revision);
        service.bind(OWNER, id(10), questions.stream().map(q -> binding(q.id().getLeastSignificantBits(), 1)).toList());
        // Multiple quiz parents reuse the same question revisions without mutable authoring joins.
        for (long parent = 11; parent <= 14; parent++) revisions.insert(quiz(parent, questions.get(0), questions.get(1)));
        source.reset();
        assertThat(service.resolve(OWNER, id(10))).hasSize(12);
        assertThat(source.selects).hasSize(4);
        assertThat(source.selects).allSatisfy(statement -> assertThat(statement)
                .doesNotContain("content", "question_text", "normalized_text", "SELECT *"));
        source.reset();
        assertThat(service.resolve(OWNER, id(11))).hasSize(2);
        assertThat(source.selects).hasSize(4);
        assertThat(source.selects).allSatisfy(statement -> assertThat(statement).doesNotContain("content", "question_text"));
        source.reset();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.declareScope(OWNER, Set.of(id(10)));
            assertThat(service.resolve(OWNER, id(10))).hasSize(12);
        });
        assertThat(source.selects).hasSize(4);
        assertThat(source.selects).allSatisfy(statement -> assertThat(statement)
                .doesNotContain("content", "question_text", "normalized_text", "SELECT *"));
        assertThat(source.bindingWrites).hasValue(0);
    }

    @Test
    void ownerScopeMissingIdentityAndForeignQuestionAreDeniedWithoutWritesOrDisclosure() {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        rejected(() -> service.resolve(id(999), id(10)), REVISION_UNAVAILABLE);
        rejected(() -> service.bind(id(999), id(10), List.of(binding(1, 99))), REVISION_UNAVAILABLE);
        rejected(() -> service.resolve(OWNER, id(999)), REVISION_UNAVAILABLE);
        rejected(() -> service.resolve(null, id(10)), REVISION_UNAVAILABLE);
        rejected(() -> service.resolve(OWNER, null), REVISION_UNAVAILABLE);
        rejected(() -> service.bind(OWNER, id(10), List.of(binding(999, 1))), INVALID_BINDING);
        assertThat(countBindings()).isZero();
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        assertThat(service.resolve(OWNER, id(10))).hasSize(1);
    }

    @Test
    void partialDuplicateAndNullBatchesCannotCreatePartialConfiguration() {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}"), question(2, "{\"answer\":false}")));
        for (List<QuestionContractBinding> invalid : Arrays.asList(null, List.<QuestionContractBinding>of(),
                List.of(binding(1, 1)), List.of(binding(1, 1), binding(1, 1)), Arrays.asList(binding(1, 1), null))) {
            rejected(() -> service.bind(OWNER, id(10), invalid), INVALID_BINDING);
            assertThat(countBindings()).isZero();
        }
        service.bind(OWNER, id(10), List.of(binding(2, 1), binding(1, 1)));
        var resolved = service.resolve(OWNER, id(10));
        assertThat(resolved).hasSize(2);
        assertThatThrownBy(resolved::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void corruptStoredMetadataIsRejectedAndDoesNotFallBackToCurrentDefaults() {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        sql.update("UPDATE question_revision_contract_bindings SET binding_hash=?", "0".repeat(64));
        rejected(() -> service(registry(), manager).resolve(OWNER, id(10)), INVALID_STORED_BINDING);
        rejected(() -> service.bind(OWNER, id(10), List.of(binding(1, 1))), INVALID_STORED_BINDING);
        assertThat(countBindings()).isEqualTo(1);
    }

    @Test
    void databaseEnforcesParentIdentityAndPositiveVersions() {
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO question_revision_contract_bindings VALUES (UUID_TO_BIN(?),'fixture.response',1,'fixture.scoring',1,?)
                """, id(999).toString(), "0".repeat(64))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        assertThatThrownBy(() -> sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=0"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE question_revision_contract_bindings SET scoring_contract_version=-1"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(service.resolve(OWNER, id(10)).get(id(1)).binding()).isEqualTo(binding(1, 1));
    }

    @Test
    void emptyRevisionIsDistinguishedFromUnavailableRevision() {
        revisions.insert(quiz(10));
        service.bind(OWNER, id(10), List.of());
        assertThat(service.resolve(OWNER, id(10))).isEmpty();
        rejected(() -> service.resolve(OWNER, id(11)), REVISION_UNAVAILABLE);
    }

    @Test
    void persistencePortAlsoRejectsWrongOwnerAndRequiresATransaction() {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        QuestionContractBindingRepository repository = proxy(
                new JdbcQuestionContractBindingRepository(new NamedParameterJdbcTemplate(source)), manager);
        assertThatThrownBy(() -> repository.insert(OWNER, id(10), binding(1, 1)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        rejected(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                repository.insert(id(999), id(10), binding(1, 1))), REVISION_UNAVAILABLE);
        rejected(() -> new TransactionTemplate(manager).executeWithoutResult(status ->
                repository.insert(OWNER, id(10), binding(999, 1))), REVISION_UNAVAILABLE);
        assertThat(countBindings()).isZero();
        service.bind(OWNER, id(10), List.of(binding(1, 1)));
        assertThat(service.resolve(OWNER, id(10))).hasSize(1);
    }

    @Test
    void bindingAndRevisionWritesJoinTheSameJpaTransaction() {
        var factory = jpaFactory();
        try {
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
            jpa.setDataSource(source);
            QuizRevisionRepository jpaRevisions = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)), jpa);
            var jpaService = service(registry(), jpa);
            var first = quiz(10, question(1, "{\"answer\":true}"));
            new TransactionTemplate(jpa).executeWithoutResult(status -> {
                jpaRevisions.insert(first);
                jpaService.declareScope(OWNER, Set.of(first.id()));
                jpaService.bind(OWNER, first.id(), List.of(binding(1, 1)));
            });
            source.bindingWrites.set(0);
            new TransactionTemplate(jpa).executeWithoutResult(status -> {
                var second = quiz(11, question(2, "{\"answer\":false}"));
                jpaRevisions.insert(second);
                jpaService.declareScope(OWNER, Set.of(second.id()));
                jpaService.bind(OWNER, second.id(), List.of(binding(2, 2)));
                assertThat(source.bindingWrites).hasValue(1);
                status.setRollbackOnly();
            });
            assertThat(service.resolve(OWNER, first.id()).get(id(1)).binding()).isEqualTo(binding(1, 1));
            rejected(() -> service.resolve(OWNER, id(11)), REVISION_UNAVAILABLE);
            assertThat(revisions.findById(OWNER, id(11))).isEmpty();
            assertThat(countBindings()).isEqualTo(1);
        } finally { factory.destroy(); }
    }

    @ParameterizedTest
    @CsvSource({"OPEN,false", "OPEN,true", "COMMIT_AFTER,false", "COMMIT_AFTER,true"})
    void ownedJpaTransactionLifecycleFailuresAlsoUseTheTypedBoundary(Fault fault, boolean read) {
        revisions.insert(quiz(10, question(1, "{\"answer\":true}")));
        if (read) service.bind(OWNER, id(10), List.of(binding(1, 1)));
        var factory = jpaFactory();
        try {
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
            jpa.setDataSource(source);
            var jpaService = service(registry(), jpa);
            source.reset();
            source.fault = fault;
            try {
                rejected(() -> {
                    if (read) jpaService.resolve(OWNER, id(10));
                    else jpaService.bind(OWNER, id(10), List.of(binding(1, 1)));
                }, PERSISTENCE_UNAVAILABLE);
            } finally { source.fault = null; }
            assertThat(source.bindingWrites).hasValue(!read && fault == Fault.COMMIT_AFTER ? 1 : 0);
            assertThat(countBindings()).isEqualTo(read || fault == Fault.COMMIT_AFTER ? 1 : 0);
            jpaService.bind(OWNER, id(10), List.of(binding(1, 1)));
            assertThat(service(registry(), manager).resolve(OWNER, id(10)).get(id(1)).binding())
                    .isEqualTo(binding(1, 1));
            assertThat(countBindings()).isEqualTo(1);
        } finally { factory.destroy(); }
    }

    private static org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean jpaFactory() {
        var factory = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.question.contract");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        return factory;
    }

    private static PlatformTransactionManager transactionManager(org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean factory) {
        if (factory == null) return manager;
        var jpa = new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
        jpa.setDataSource(source);
        return jpa;
    }

    private static QuestionRevisionContractService service(QuestionContractRegistry registry, PlatformTransactionManager manager) {
        QuestionContractBindingRepository repository = proxy(
                new JdbcQuestionContractBindingRepository(new NamedParameterJdbcTemplate(source)), manager);
        return proxy(new QuestionRevisionContractServiceImpl(repository, registry, manager), manager);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Object target, PlatformTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        if (target instanceof JdbcQuestionContractBindingRepository) {
            // The application also applies @Repository exception translation through its JPA dialect.
            factory.addAdvice(new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(
                    new org.springframework.orm.jpa.vendor.HibernateJpaDialect()));
        }
        return (T) factory.getProxy();
    }

    private static void rejected(Runnable action, ContractResolutionException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ContractResolutionException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("Question contract rejected: " + reason);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
        });
    }

    private static int countBindings() {
        return sql.queryForObject("SELECT COUNT(*) FROM question_revision_contract_bindings", Integer.class);
    }

    private static final class Gate {
        final CountDownLatch arrived = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    }

    private enum Fault { OPEN, SQL_AFTER_FIRST_WRITE, COMMIT_BEFORE, COMMIT_AFTER, ROLLBACK_AFTER }

    private static final class ObservedDataSource extends DelegatingDataSource {
        final List<String> selects = new CopyOnWriteArrayList<>();
        final AtomicInteger bindingWrites = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        volatile Gate gate;
        volatile Fault fault;
        volatile CountDownLatch scopeLockAttempt;
        ObservedDataSource(DataSource target) { super(target); }
        void reset() { selects.clear(); bindingWrites.set(0); rollbacks.set(0); }

        @Override
        public Connection getConnection() throws SQLException {
            if (fault == Fault.OPEN) throw new SQLException("private connection diagnostics");
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("commit") && fault == Fault.COMMIT_BEFORE) {
                            // Losing the connection before commit makes MySQL roll the transaction back.
                            connection.close();
                        }
                        Object result = invoke(connection, method, arguments);
                        if (method.getName().equals("commit") && fault == Fault.COMMIT_AFTER) {
                            throw new SQLException("private commit acknowledgement diagnostics");
                        }
                        if (method.getName().equals("rollback")) {
                            rollbacks.incrementAndGet();
                            if (fault == Fault.ROLLBACK_AFTER) throw new SQLException("private rollback diagnostics");
                        }
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String statement) {
                            if (statement.stripLeading().startsWith("SELECT")) selects.add(statement);
                            if (statement.stripLeading().startsWith("SELECT")
                                    && statement.contains("FROM question_revisions q")) {
                                var prepared = (PreparedStatement) result;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                        (unused, operation, values) -> {
                                            var attempt = scopeLockAttempt;
                                            if (operation.getName().equals("executeQuery")) {
                                                if (attempt != null) attempt.countDown();
                                                Gate current = gate;
                                                if (current != null) {
                                                    current.connections.add(connection);
                                                    current.arrived.countDown();
                                                    if (!current.release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Scope gate timed out");
                                                }
                                            }
                                            return invoke(prepared, operation, values);
                                        });
                            }
                            if (statement.stripLeading().startsWith("INSERT INTO question_revision_contract_bindings")) {
                                var prepared = (PreparedStatement) result;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                        (unused, operation, values) -> {
                                            if (operation.getName().equals("executeUpdate")) {
                                                if ((fault == Fault.SQL_AFTER_FIRST_WRITE || fault == Fault.ROLLBACK_AFTER)
                                                        && bindingWrites.get() == 1) {
                                                    throw new SQLException("private SQL diagnostics");
                                                }
                                                Object count = invoke(prepared, operation, values);
                                                bindingWrites.incrementAndGet();
                                                return count;
                                            }
                                            return invoke(prepared, operation, values);
                                        });
                            }
                        }
                        return result;
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] values) throws Throwable {
            try { return method.invoke(target, values); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
