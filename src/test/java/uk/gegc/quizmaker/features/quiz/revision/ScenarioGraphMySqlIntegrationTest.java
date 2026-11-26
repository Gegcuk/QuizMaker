package uk.gegc.quizmaker.features.quiz.revision;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.StatementCreatorUtils;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.impl.FixedFormMaterializerImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.FixedFormServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.FormOptionOrderServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFixedFormRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFormOptionOrderRepository;
import uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.FamilyResponseContractV1;
import uk.gegc.quizmaker.features.question.application.contract.v1.FamilyScoringContractV1;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.application.impl.QuestionRevisionContractServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizScenarioRevisionService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizScenarioRevisionServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraph;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;
import static uk.gegc.quizmaker.features.quiz.revision.ScenarioGraphTest.CONDITION;
import static uk.gegc.quizmaker.features.quiz.revision.ScenarioGraphTest.scenario;

@Tag("db-serial")
class ScenarioGraphMySqlIntegrationTest {
    private static final String CATALOG = "revision_scenario_" + UUID.randomUUID().toString().replace("-", "");
    private static final String WHERE_REVISION = " WHERE quiz_revision_id=UUID_TO_BIN(?)";
    private static final List<String> GRAPH_TABLES = List.of("quiz_scenario_graphs", "quiz_scenario_nodes", "quiz_scenario_edges");
    private static final String PUBLICATION_KEYS = "quiz_scenario_publication_keys";
    private static final String QUESTION_KEYS = "quiz_scenario_question_keys";
    private static final List<QuizRevision> PREVIOUS_FORMATS = List.of(
            quiz("scenario-upgrade", question("scenario-upgrade")),
            QuizRevisionSectionsTest.structured("scenario-upgrade-sections"),
            QuizRevisionCasesTest.cased("scenario-upgrade-cases"));
    private static JdbcTemplate admin;
    private static JdbcTemplate sql;
    private static SectionTestDataSource source;
    private static DataSourceTransactionManager manager;
    private static List<Map<String, Object>> beforeUpgrade;
    private static QuizRevision retainedV128;
    private static boolean created;
    private QuizRevisionRepository repository;
    private QuizScenarioRevisionService publisher;
    private SimpleMeterRegistry metrics;

    @BeforeAll static void migrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + CATALOG + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        source = new SectionTestDataSource(new DriverManagerDataSource(base + CATALOG + options, user, password));
        sql = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        migrate("127");
        var baseline = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)));
        PREVIOUS_FORMATS.forEach(baseline::insert);
        beforeUpgrade = rootRows();
        migrate("128");
        var legacyEntry = id("scenario-v128-entry");
        var legacyItem = PREVIOUS_FORMATS.get(0).items().get(0);
        retainedV128 = new QuizRevision(id("scenario-v128"), id("scenario-v128-quiz"), OWNER, QuizRevision.SCENARIO_FORMAT,
                "Previously sealed graph", null, CREATED, List.of(legacyItem), List.of(), List.of(),
                new ScenarioGraph(1, legacyEntry,
                        List.of(new ScenarioGraph.Node(legacyEntry, 0, ScenarioGraph.NodeType.ITEM, legacyItem.id())), List.of()));
        insertUnsealed(retainedV128.id(), retainedV128.quizId(), retainedV128.title(), 1);
        sql.update("INSERT INTO quiz_revision_items VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)",
                legacyItem.id().toString(), retainedV128.id().toString(), legacyItem.question().id().toString(),
                legacyItem.authoredPosition());
        insertNode(retainedV128.id(), legacyEntry, 0, "ITEM", legacyItem.id());
        seal(retainedV128.id(), legacyEntry);
        migrate("129");
        migrate("130");
        migrate("131");
    }

    @BeforeEach void start() {
        source.reset();
        metrics = new SimpleMeterRegistry();
        restart();
    }

    @AfterEach void reset() {
        source.reset();
        metrics.close();
    }

    @AfterAll static void cleanup() {
        if (created) admin.execute("DROP DATABASE " + CATALOG);
    }

    @Test void migrationPreservesOldFormatsAndOldReadsDoNotLoadGraphTables() {
        migrate("131");
        assertThat(rootRows()).usingRecursiveComparison().isEqualTo(beforeUpgrade);
        List<UUID> ids = PREVIOUS_FORMATS.stream().map(QuizRevision::id).toList();
        assertThat(repository.findByIds(OWNER, ids)).containsExactlyElementsOf(PREVIOUS_FORMATS);
        for (UUID id : ids) {
            for (String table : GRAPH_TABLES) assertThat(count(table, id)).isZero();
            assertThat(count(PUBLICATION_KEYS, id)).isZero();
        }
        source.selects.set(0);
        assertThat(repository.findById(OWNER, ids.get(0))).contains(PREVIOUS_FORMATS.get(0));
        assertThat(source.selects).hasValue(3);
        assertThat(repository.findById(OWNER, retainedV128.id())).contains(retainedV128);
        assertThat(publisher.publish(OWNER, retainedV128)).isEqualTo(retainedV128);
        assertThat(count(PUBLICATION_KEYS, retainedV128.id())).isOne();
        assertThat(questionOptionCount(legacyQuestion(retainedV128).id())).isEqualTo(2);
    }

    @Test void publishesOneGraphOnExactReplayAndRetainsOriginalAfterEditAndRestart() {
        var original = scenario("original", 3, CONDITION);
        var changed = scenario("edited", 3, CONDITION + "edited");
        assertThat(publisher.publish(OWNER, original)).isEqualTo(original);
        assertThat(publisher.publish(OWNER, changed)).isEqualTo(changed);
        restart();

        assertThat(publisher.publish(OWNER, original)).isEqualTo(original);
        assertThat(repository.findByIds(OWNER, List.of(changed.id(), original.id()))).containsExactly(changed, original);
        assertThat(count("quiz_scenario_graphs", original.id())).isEqualTo(1);
        assertThat(count("quiz_scenario_nodes", original.id())).isEqualTo(original.scenarioGraph().nodes().size());
        assertThat(count("quiz_scenario_edges", original.id())).isEqualTo(original.scenarioGraph().edges().size());
        rejected(() -> publisher.publish(OWNER, changedCondition(original)), "IDENTITY_CONFLICT");
        assertThat(repository.findById(OWNER, original.id())).contains(original);
        UUID foreign = id("foreign-owner");
        rejected(() -> publisher.publish(foreign, original), "INVALID_CONTENT");
        assertThat(repository.findByIds(foreign, List.of(original.id(), changed.id()))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"quiz_scenario_nodes", "quiz_scenario_edges", "quiz_scenario_graphs", PUBLICATION_KEYS})
    void reachedGraphWriteFailureRollsBackEntireRevisionAndRetrySucceeds(String table) {
        var revision = scenario("scenario-fault-" + table, 3, CONDITION);
        var reached = new AtomicBoolean();
        source.after = (statement, connection) -> {
            String write = table.equals("quiz_scenario_graphs") || table.equals(PUBLICATION_KEYS) ? "UPDATE " : "INSERT INTO ";
            if (statement.startsWith(write + table)) {
                reached.set(true);
                throw new SQLException("private scenario fault", "HYT00");
            }
        };
        assertThatThrownBy(() -> repeatableRead().executeWithoutResult(status ->
                rejected(() -> publisher.publish(OWNER, revision), "PERSISTENCE_UNAVAILABLE")))
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(reached).isTrue();
        source.reset();

        fresh().executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, revision.id())).isEmpty();
            assertThat(count("quiz_revision_items", revision.id())).isZero();
            assertThat(count(PUBLICATION_KEYS, revision.id())).isZero();
            for (String graphTable : GRAPH_TABLES) assertThat(count(graphTable, revision.id())).isZero();
            for (var item : revision.items()) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + QUESTION_KEYS + " WHERE question_revision_id=UUID_TO_BIN(?)",
                        Integer.class, item.question().id().toString())).isZero();
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                        Integer.class, item.question().id().toString())).isZero();
            }
        });
        assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test void unrelatedAuthorPublishesOnBothSidesOfHeldRepeatableReadReplay() throws Exception {
        var held = independentScenario("held-replay", 20L, OWNER);
        var before = independentScenario("before-replay", 10L, id("before-owner"));
        var after = independentScenario("after-replay", 30L, id("after-owner"));
        publisher.publish(held.ownerId(), held);
        var completed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var replay = workers.submit(() -> repeatableRead().executeWithoutResult(status -> {
                assertThat(publisher.publish(held.ownerId(), held)).isEqualTo(held);
                completed.countDown();
                awaitGate(release, "release replay transaction");
            }));
            awaitGate(completed, "exact replay has completed");
            var peers = workers.submit(() -> List.of(publisher.publish(before.ownerId(), before),
                    publisher.publish(after.ownerId(), after)));
            assertThat(peers.get(30, TimeUnit.SECONDS)).containsExactly(before, after);
            assertThat(replay.isDone()).isFalse();
            assertThat(repository.findById(before.ownerId(), before.id())).contains(before);
            assertThat(repository.findById(after.ownerId(), after.id())).contains(after);
            release.countDown();
            replay.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void heldReplayOf512QuestionsAllowsOneNewQuestionAboveItsKeys() throws Exception {
        var published = scenario("large-held-replay", 512, CONDITION);
        assertThat(publisher.publish(OWNER, published)).isEqualTo(published);
        var peer = publicationWithOneQuestion("one-above-held", id("one-above-owner"), new UUID(-1L, -1L));
        assertIndependentWhileHeld(published, peer, QUESTION_KEYS);
    }

    @Test void firstUseOfOrdinaryQuestionDoesNotBlockAnotherQuestionsOptions() throws Exception {
        var held = publicationWithOneQuestion("first-ordinary-use", OWNER, new UUID(-1L, -10L));
        var ordinary = quiz("ordinary-before-first-use", legacyQuestion(held));
        repository.insert(ordinary);
        assertThat(questionOptionCount(legacyQuestion(ordinary).id())).isNull();
        var peer = publicationWithOneQuestion("one-after-adoption", id("after-adoption-owner"),
                new UUID(-1L, -9L));
        assertIndependentWhileHeld(held, peer, "question_option_revisions");
        assertThat(questionOptionCount(legacyQuestion(ordinary).id())).isEqualTo(2);
    }

    private void assertIndependentWhileHeld(QuizRevision held, QuizRevision peer, String measuredTable) throws Exception {
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var connection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var waiting = workers.submit(() -> repeatableRead().executeWithoutResult(status -> {
                connection.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
                assertThat(publisher.publish(held.ownerId(), held)).isEqualTo(held);
                ready.countDown();
                awaitGate(release, "release the holder");
            }));
            awaitGate(ready, "the first publication call has returned");
            var locks = admin.queryForList("""
                    SELECT l.LOCK_MODE, l.LOCK_DATA FROM performance_schema.data_locks l
                    JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
                    WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=? AND l.LOCK_TYPE='RECORD'
                    """, connection.get(), CATALOG, measuredTable);
            assertThat(locks).isNotEmpty().allSatisfy(lock -> {
                assertThat((String) lock.get("LOCK_MODE")).contains("REC_NOT_GAP");
                assertThat(String.valueOf(lock.get("LOCK_DATA"))).doesNotContain("supremum");
            });
            var other = workers.submit(() -> publisher.publish(peer.ownerId(), peer));
            assertThat(other.get(30, TimeUnit.SECONDS)).isEqualTo(peer);
            assertThat(waiting.isDone()).isFalse();
            assertThat(repository.findById(peer.ownerId(), peer.id())).contains(peer);
            release.countDown();
            waiting.get(30, TimeUnit.SECONDS);
            assertThat(repository.findById(held.ownerId(), held.id())).contains(held);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void databaseRejectsOptionMembershipChangesOnceTheQuestionIsSealed() {
        var revision = scenario("sealed-option-membership", 1, CONDITION);
        var question = legacyQuestion(revision);
        repository.insert(quiz("ordinary-before-option-seal", question));
        var option = question.options().get(0);
        String query = "INSERT INTO question_option_revisions VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'additional',5)";
        var extra = id("extra-option-while-unsealed");
        assertThat(sql.update(query, extra.toString(), question.id().toString())).isOne();
        assertThat(sql.update("DELETE FROM question_option_revisions WHERE question_revision_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)",
                question.id().toString(), extra.toString())).isOne();
        assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
        sqlRejected(() -> sql.update(query, extra.toString(), question.id().toString()));
        sqlRejected(() -> sql.update("""
                UPDATE question_option_revisions SET source_key='changed'
                WHERE question_revision_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                """, question.id().toString(), option.id().toString()));
        sqlRejected(() -> sql.update("DELETE FROM question_option_revisions WHERE question_revision_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)",
                question.id().toString(), option.id().toString()));
        sqlRejected(() -> sql.update("UPDATE " + QUESTION_KEYS + " SET option_count=NULL WHERE question_revision_id=UUID_TO_BIN(?)",
                question.id().toString()));
        sqlRejected(() -> sql.update("DELETE FROM " + QUESTION_KEYS + " WHERE question_revision_id=UUID_TO_BIN(?)",
                question.id().toString()));
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
        assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
    }

    @Test void publishes128NewQuestionsWithOneJdbcQuestionInsertBatch() {
        var revision = scenario("question-insert-batch", 128, CONDITION);
        var writes = new AtomicInteger();
        source.before = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO question_revisions")) writes.incrementAndGet();
        };
        assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
        assertThat(writes).hasValue(1);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void twoDifferentGraphsCanShareOneQuestionOnlyIfItsContentMatches(boolean sameQuestion) throws Exception {
        var left = scenario("shared-question-left-" + sameQuestion, 2, CONDITION);
        var question = legacyQuestion(left);
        var right = replaceFirstQuestion(scenario("shared-question-right-" + sameQuestion, 2, CONDITION),
                sameQuestion ? question : withOptionKey(question, "different-option-key"));
        var atKey = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        source.before = (statement, connection) -> {
            if (!statement.startsWith("INSERT INTO question_revisions")) return;
            atKey.countDown();
            awaitGate(release, "release shared question writers");
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> racePut(left));
            var b = workers.submit(() -> racePut(right));
            awaitGate(atKey, "both graph publishers reached question keys");
            release.countDown();
            var outcomes = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            if (sameQuestion) {
                assertThat(outcomes).containsExactly(left, right);
                assertThat(repository.findByIds(OWNER, List.of(left.id(), right.id()))).containsExactly(left, right);
            } else {
                var winner = outcomes.stream().filter(QuizRevision.class::isInstance).map(QuizRevision.class::cast).toList();
                assertThat(winner).hasSize(1);
                assertThat(outcomes).contains(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
                assertThat(repository.findByIds(OWNER, List.of(left.id(), right.id()))).containsExactlyElementsOf(winner);
                assertThat(count(PUBLICATION_KEYS, winner.get(0).id().equals(left.id()) ? right.id() : left.id())).isZero();
            }
            assertThat(questionOptionCount(question.id())).isEqualTo(question.options().size());
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_option_revisions WHERE question_revision_id=UUID_TO_BIN(?)",
                    Integer.class, question.id().toString())).isEqualTo(question.options().size());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void newGraphReusesAQuestionCommittedAfterItsRepeatableReadSnapshot(boolean scenarioWriter) throws Exception {
        var key = "shared-question-stale-" + scenarioWriter;
        var question = question(key);
        var original = scenarioWriter ? replaceFirstQuestion(scenario(key + "-original", 2, CONDITION), question)
                : quiz(key + "-original", question);
        var other = replaceFirstQuestion(scenario(key + "-peer", 2, CONDITION), question);
        var worker = Executors.newSingleThreadExecutor();
        try {
            repeatableRead().executeWithoutResult(status -> {
                assertThat(repository.findById(OWNER, original.id())).isEmpty();
                try {
                    worker.submit(() -> {
                        if (scenarioWriter) publisher.publish(OWNER, original);
                        else repository.insert(original);
                    }).get(30, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError("Question reuse requires a committed writer", failure);
                }
                assertThat(repository.findById(OWNER, original.id())).isEmpty();
                assertThat(publisher.publish(OWNER, other)).isEqualTo(other);
            });
            assertThat(repository.findByIds(OWNER, List.of(original.id(), other.id()))).containsExactly(original, other);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void scenarioOverAnExistingFlatIdentityHasAStableConflict() {
        var flat = PREVIOUS_FORMATS.get(0);
        var original = scenario("scenario-flat-id-conflict", 2, CONDITION);
        var conflicting = new QuizRevision(flat.id(), flat.quizId(), OWNER, QuizRevision.SCENARIO_FORMAT,
                original.title(), original.description(), CREATED, original.items(), List.of(), List.of(), original.scenarioGraph());
        rejected(() -> publisher.publish(OWNER, conflicting), "IDENTITY_CONFLICT");
        rejected(() -> publisher.publish(OWNER, conflicting), "IDENTITY_CONFLICT");
        assertThat(repository.findById(OWNER, flat.id())).contains(flat);
        assertThat(count(PUBLICATION_KEYS, flat.id())).isZero();
        assertThat(questionOptionCount(legacyQuestion(original).id())).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentPutsCompareAgainstOneCompleteWinner(boolean identical) throws Exception {
        var a = scenario("racing-" + identical, 3, CONDITION);
        var b = identical ? a : changedCondition(a);
        var atKey = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        source.before = (statement, connection) -> {
            if (!statement.startsWith("INSERT INTO " + PUBLICATION_KEYS)) return;
            atKey.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new SQLException("Test insert gate was not released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new SQLException("Test insert gate interrupted", failure);
            }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var left = workers.submit(() -> racePut(a));
            var right = workers.submit(() -> racePut(b));
            assertThat(atKey.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var outcomes = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
            var winner = outcomes.stream().filter(QuizRevision.class::isInstance).map(QuizRevision.class::cast).toList();
            if (identical) assertThat(outcomes).containsExactly(a, a);
            else {
                assertThat(winner).hasSize(1);
                assertThat(outcomes).contains(RevisionIntegrityException.Reason.IDENTITY_CONFLICT);
            }
            restart();
            assertThat(repository.findById(OWNER, a.id())).contains(winner.get(0));
            assertThat(publisher.publish(OWNER, winner.get(0))).isEqualTo(winner.get(0));
            assertThat(count("quiz_scenario_graphs", a.id())).isEqualTo(1);
            assertThat(count(PUBLICATION_KEYS, a.id())).isOne();
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void exactReplayUsesTheCommittedGraphEvenInAnOlderCallerSnapshot() throws Exception {
        var revision = scenario("scenario-stale-snapshot", 2, CONDITION);
        var caller = new TransactionTemplate(manager);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var worker = Executors.newSingleThreadExecutor();
        try {
            caller.executeWithoutResult(status -> {
                assertThat(repository.findById(OWNER, revision.id())).isEmpty();
                try {
                    assertThat(worker.submit(() -> publisher.publish(OWNER, revision)).get(30, TimeUnit.SECONDS))
                            .isEqualTo(revision);
                } catch (Exception failure) {
                    throw new AssertionError("Publication must commit in a separate transaction", failure);
                }
                assertThat(repository.findById(OWNER, revision.id())).isEmpty();
                assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
                assertThat(repository.findById(OWNER, revision.id())).isEmpty();
            });
            assertThat(repository.findById(OWNER, revision.id())).contains(revision);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void independentRevisionCommitsWhileFirstCallerHoldsAndRollsBackSealedGraph() throws Exception {
        var first = scenario("scenario-held", 2, CONDITION);
        var peer = scenario("scenario-peer", 2, CONDITION);
        var sealed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var caller = new TransactionTemplate(manager);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var held = workers.submit(() -> caller.executeWithoutResult(status -> {
                assertThat(publisher.publish(OWNER, first)).isEqualTo(first);
                assertThat(repository.findById(OWNER, first.id())).contains(first);
                sealed.countDown();
                try {
                    assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                status.setRollbackOnly();
            }));
            assertThat(sealed.await(30, TimeUnit.SECONDS)).isTrue();
            var other = workers.submit(() -> caller.execute(status -> publisher.publish(OWNER, peer)));
            assertThat(other.get(30, TimeUnit.SECONDS)).isEqualTo(peer);
            assertThat(held.isDone()).isFalse();
            assertThat(repository.findById(OWNER, peer.id())).contains(peer);
            assertThat(repository.findById(OWNER, first.id())).isEmpty();
            release.countDown();
            held.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
        fresh().executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, first.id())).isEmpty();
            for (String table : GRAPH_TABLES) assertThat(count(table, first.id())).isZero();
            assertThat(count(PUBLICATION_KEYS, first.id())).isZero();
            assertThat(repository.findById(OWNER, peer.id())).contains(peer);
        });
    }

    @Test void schemaRejectsLateWritesCrossRevisionReferencesAndPartialReads() {
        var revision = scenario("sealed-scenario", 2, CONDITION);
        publisher.publish(OWNER, revision);
        String root = revision.id().toString();
        sqlRejected(() -> sql.update("UPDATE quiz_scenario_nodes SET authored_position=99" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("DELETE FROM quiz_scenario_edges" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("DELETE FROM quiz_scenario_graphs" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("UPDATE quiz_revision_items SET authored_position=99" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("INSERT INTO quiz_revision_items VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),99)",
                id("late-item").toString(), root, revision.items().get(0).question().id().toString()));
        sqlRejected(() -> insertNode(revision.id(), id("late-node"), 99, "TERMINAL", null));
        var entry = revision.scenarioGraph().entryNodeId();
        sqlRejected(() -> insertEdge(revision.id(), entry, entry));

        var partial = id("unsealed-scenario");
        var localNode = id("unsealed-node");
        insertUnsealed(partial, revision.quizId(), "Partial fixture", 0);
        sqlRejected(() -> seal(partial, localNode));
        sqlRejected(() -> insertNode(partial, localNode, 0, "ITEM", revision.items().get(0).id()));
        sqlRejected(() -> insertNode(partial, localNode, 0, "TERMINAL ", null));
        insertNode(partial, localNode, 0, "TERMINAL", null);
        sqlRejected(() -> insertNode(partial, id("duplicate-position"), 0, "TERMINAL", null));
        sqlRejected(() -> insertEdge(partial, localNode, entry));
        sqlRejected(() -> seal(partial, entry));
        rejected(() -> repository.findById(OWNER, partial), "INVALID_STORED_REVISION");
        seal(partial, localNode);
        assertThat(repository.findById(OWNER, partial).orElseThrow().scenarioGraph().entryNodeId()).isEqualTo(localNode);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test void batchReadUsesSixStatementsAndIndexedGraphLookupsAsParentsAndEdgesGrow() {
        for (int size : List.of(2, 12)) {
            var revisions = IntStream.range(0, size == 2 ? 2 : 6)
                    .mapToObj(i -> scenario("scenario-batch-" + size + "-" + i, size, CONDITION)).toList();
            revisions.forEach(revision -> publisher.publish(OWNER, revision));
            source.selects.set(0);
            var ids = revisions.stream().map(QuizRevision::id).toList();
            assertThat(repository.findByIds(OWNER, ids)).containsExactlyElementsOf(revisions);
            assertThat(source.selects).hasValue(6);
            source.selects.set(0);
            assertThat(publisher.publish(OWNER, revisions.get(0))).isEqualTo(revisions.get(0));
            assertThat(source.selects).hasValue(9);
            for (String table : GRAPH_TABLES) {
                assertThat(sql.queryForList("EXPLAIN SELECT * FROM " + table + WHERE_REVISION, ids.get(0).toString()))
                        .singleElement().satisfies(row -> assertThat(row.get("key")).isNotNull());
            }
        }
    }

    @Test void fixedMaterializerKeepsLinearControlAndRejectsScenarioBeforeWritingForm() {
        var named = new NamedParameterJdbcTemplate(source);
        var registry = new QuestionContractRegistryImpl(List.of(new FamilyResponseContractV1(QuestionType.MCQ_SINGLE)),
                List.of(new FamilyScoringContractV1(QuestionType.MCQ_SINGLE)), metrics);
        var contracts = new QuestionRevisionContractServiceImpl(proxy(new JdbcQuestionContractBindingRepository(named)), registry, manager);
        var forms = proxy(new JdbcFixedFormRepository(named));
        var orders = proxy(new JdbcFormOptionOrderRepository(named));
        var ordering = new FormOptionOrderServiceImpl(repository, contracts, forms, orders, manager, metrics);
        var materializer = new FixedFormMaterializerImpl(repository, contracts, ordering, manager, metrics, forms);
        var flat = quiz("scenario-fixed-control", question("scenario-fixed-control"));
        var graph = scenario("scenario-fixed-denial", 2, CONDITION);
        repository.insert(flat);
        publisher.publish(OWNER, graph);
        for (var revision : List.of(flat, graph)) {
            contracts.bind(OWNER, revision.id(), revision.items().stream()
                    .map(item -> BaselineQuestionContracts.binding(item.question())).toList());
        }
        assertThat(materializer.materializeSelected(OWNER, QuizRevisionCasesMySqlIntegrationTest.flatCommand(flat)).items()).hasSize(1);
        var command = QuizRevisionCasesMySqlIntegrationTest.flatCommand(graph);
        assertThatThrownBy(() -> materializer.materializeSelected(OWNER, command))
                .isInstanceOf(FormMaterializationException.class).hasMessageContaining("INPUT_MISMATCH");
        fresh().executeWithoutResult(status -> assertThat(forms.findByIds(OWNER, List.of(command.form().id()))).isEmpty());
        assertThat(repository.findById(OWNER, graph.id())).contains(graph);
    }

    @Test void sharedFormWriterRejectsScenarioThroughBothServicesAndPreservesFormatsOneToThree() {
        var named = new NamedParameterJdbcTemplate(source);
        var registry = new QuestionContractRegistryImpl(List.of(new FamilyResponseContractV1(QuestionType.MCQ_SINGLE)),
                List.of(new FamilyScoringContractV1(QuestionType.MCQ_SINGLE)), metrics);
        var contracts = new QuestionRevisionContractServiceImpl(proxy(new JdbcQuestionContractBindingRepository(named)), registry, manager);
        var forms = proxy(new JdbcFixedFormRepository(named));
        var fixed = new FixedFormServiceImpl(forms, manager, metrics);
        var orders = proxy(new JdbcFormOptionOrderRepository(named));
        var ordering = new FormOptionOrderServiceImpl(repository, contracts, forms, orders, manager, metrics);
        var graph = scenario("scenario-shared-form-denial", 2, CONDITION);
        publisher.publish(OWNER, graph);
        var revisions = new ArrayList<>(PREVIOUS_FORMATS);
        revisions.add(graph);
        for (var revision : revisions) {
            contracts.bind(OWNER, revision.id(), revision.items().stream()
                    .map(item -> BaselineQuestionContracts.binding(item.question())).toList());
        }
        for (var revision : PREVIOUS_FORMATS) {
            var command = QuizRevisionCasesMySqlIntegrationTest.flatCommand(revision);
            assertThat(fixed.createOrReplay(OWNER, command.form())).isEqualTo(command.form());
            assertThat(ordering.createOrReplay(OWNER, command.form(), command.answerOrdering()).items())
                    .hasSize(command.form().items().size());
        }
        var command = QuizRevisionCasesMySqlIntegrationTest.flatCommand(graph);
        assertThatThrownBy(() -> fixed.createOrReplay(OWNER, command.form()))
                .isInstanceOf(FixedFormException.class).hasMessageContaining("REVISION_UNAVAILABLE");
        assertThatThrownBy(() -> ordering.createOrReplay(OWNER, command.form(), command.answerOrdering()))
                .isInstanceOf(FixedFormException.class).hasMessageContaining("REVISION_UNAVAILABLE");
        fresh().executeWithoutResult(status -> {
            assertThat(forms.findByIds(OWNER, List.of(command.form().id()))).isEmpty();
            assertThat(count("attempt_form", graph.id())).isZero();
        });
    }

    @Test void jdbcParameterTraceOmitsNewQuestionAndConditionContent() {
        String questionMarker = "private-question-marker";
        String answerMarker = "private-answer-marker";
        String explanationMarker = "private-explanation-marker";
        String conditionMarker = "scenario-condition-marker";
        String optionMarker = "private-option-key-marker";
        String titleMarker = "private-quiz-title-marker";
        String descriptionMarker = "private-quiz-description-marker";
        var base = scenario("scenario-private-parameter", 2, CONDITION);
        var first = base.items().get(0);
        var question = first.question();
        var privateQuestion = withOptionKey(new QuestionRevision(question.id(), question.questionId(), question.schemaVersion(),
                question.type(), question.difficulty(), questionMarker,
                question.content().replace("Paris", answerMarker), question.hint(), explanationMarker,
                question.attachmentUrl(), question.attachmentAssetId(), question.createdAt(), question.options()), optionMarker);
        var revision = new QuizRevision(base.id(), base.quizId(), base.ownerId(), base.schemaVersion(),
                titleMarker, descriptionMarker, base.createdAt(),
                List.of(new QuizRevision.Item(first.id(), first.authoredPosition(), privateQuestion), base.items().get(1)),
                List.of(), List.of(), base.scenarioGraph());
        var logger = (Logger) LoggerFactory.getLogger(StatementCreatorUtils.class);
        var prior = logger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.TRACE);
        try {
            assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
        } finally {
            logger.detachAppender(capture);
            logger.setLevel(prior);
            capture.stop();
        }
        assertThat(capture.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("[private revision text]"))
                .noneMatch(message -> message.contains(questionMarker) || message.contains(answerMarker)
                        || message.contains(explanationMarker) || message.contains(conditionMarker)
                        || message.contains(optionMarker) || message.contains(titleMarker) || message.contains(descriptionMarker));
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
        assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
    }

    private static QuizRevision independentScenario(String key, long revisionId, UUID owner) {
        var source = scenario(key, 2, CONDITION);
        var old = source.scenarioGraph();
        var nodes = old.nodes().stream().map(node -> new ScenarioGraph.Node(id(key + node.id()),
                node.authoredPosition(), node.type(), node.itemId())).toList();
        var edges = old.edges().stream().map(edge -> new ScenarioGraph.Edge(id(key + edge.id()),
                id(key + edge.fromNodeId()), id(key + edge.toNodeId()), edge.authoredPosition(), edge.type(),
                edge.conditionVersion(), edge.condition())).toList();
        return new QuizRevision(new UUID(0, revisionId), id(key + "-quiz"), owner, source.schemaVersion(),
                source.title(), source.description(), source.createdAt(), source.items(), List.of(), List.of(),
                new ScenarioGraph(old.schemaVersion(), id(key + old.entryNodeId()), nodes, edges));
    }

    private static QuizRevision publicationWithOneQuestion(String key, UUID owner, UUID questionId) {
        var source = scenario(key, 1, CONDITION);
        var old = legacyQuestion(source);
        var question = new QuestionRevision(questionId, old.questionId(), old.schemaVersion(), old.type(),
                old.difficulty(), old.questionText(), old.content(), old.hint(), old.explanation(),
                old.attachmentUrl(), old.attachmentAssetId(), old.createdAt(), old.options());
        var scoped = new QuizRevision(source.id(), id(key + "-quiz"), owner, source.schemaVersion(), source.title(),
                source.description(), source.createdAt(), source.items(), List.of(), List.of(), source.scenarioGraph());
        return replaceFirstQuestion(scoped, question);
    }

    private static QuestionRevision legacyQuestion(QuizRevision revision) { return revision.items().get(0).question(); }

    private static QuizRevision replaceFirstQuestion(QuizRevision revision, QuestionRevision question) {
        var items = new ArrayList<>(revision.items());
        var first = items.get(0);
        items.set(0, new QuizRevision.Item(first.id(), first.authoredPosition(), question));
        return new QuizRevision(revision.id(), revision.quizId(), revision.ownerId(), revision.schemaVersion(),
                revision.title(), revision.description(), revision.createdAt(), items, List.of(), List.of(), revision.scenarioGraph());
    }

    private static QuestionRevision withOptionKey(QuestionRevision question, String key) {
        var options = new ArrayList<>(question.options());
        var first = options.get(0);
        options.set(0, new QuestionRevision.Option(first.id(), key, first.authoredPosition()));
        return new QuestionRevision(question.id(), question.questionId(), question.schemaVersion(), question.type(),
                question.difficulty(), question.questionText(), question.content(), question.hint(), question.explanation(),
                question.attachmentUrl(), question.attachmentAssetId(), question.createdAt(), options);
    }

    private static void awaitGate(CountDownLatch gate, String purpose) {
        try {
            if (!gate.await(30, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting to " + purpose);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to " + purpose, failure);
        }
    }

    private Object racePut(QuizRevision revision) {
        try {
            return fresh().execute(status -> publisher.publish(OWNER, revision));
        } catch (RevisionIntegrityException failure) {
            return failure.reason();
        }
    }

    private static QuizRevision changedCondition(QuizRevision revision) {
        var old = revision.scenarioGraph();
        var edges = old.edges().stream().map(edge -> edge.condition() == null ? edge : new ScenarioGraph.Edge(
                edge.id(), edge.fromNodeId(), edge.toNodeId(), edge.authoredPosition(), edge.type(),
                edge.conditionVersion(), edge.condition() + "changed")).toList();
        return new QuizRevision(revision.id(), revision.quizId(), revision.ownerId(), revision.schemaVersion(),
                revision.title(), revision.description(), revision.createdAt(), revision.items(), List.of(), List.of(),
                new ScenarioGraph(old.schemaVersion(), old.entryNodeId(), old.nodes(), edges));
    }

    private void restart() {
        repository = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)));
        publisher = proxy(new QuizScenarioRevisionServiceImpl(repository, metrics));
    }

    private static TransactionTemplate fresh() {
        var transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static TransactionTemplate repeatableRead() {
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction;
    }

    private static void migrate(String version) {
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target(version).load().migrate();
    }

    private static List<Map<String, Object>> rootRows() {
        return PREVIOUS_FORMATS.stream().map(revision -> sql.queryForMap("SELECT * FROM quiz_revisions WHERE id=UUID_TO_BIN(?)",
                revision.id().toString())).toList();
    }

    private static int count(String table, UUID revisionId) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table + WHERE_REVISION, Integer.class, revisionId.toString());
    }

    private static Integer questionOptionCount(UUID questionId) {
        var rows = sql.queryForList("SELECT option_count FROM " + QUESTION_KEYS + " WHERE question_revision_id=UUID_TO_BIN(?)",
                Integer.class, questionId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static void insertNode(UUID root, UUID node, int position, String type, UUID item) {
        sql.update("INSERT INTO quiz_scenario_nodes VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,UUID_TO_BIN(?))",
                root.toString(), node.toString(), position, type, item == null ? null : item.toString());
    }

    private static void insertEdge(UUID root, UUID from, UUID to) {
        sql.update("INSERT INTO quiz_scenario_edges VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),99,'DEFAULT',NULL,NULL)",
                root.toString(), id("invalid-edge").toString(), from.toString(), to.toString());
    }

    private static void seal(UUID root, UUID node) {
        sql.update("UPDATE quiz_scenario_graphs SET entry_node_id=UUID_TO_BIN(?)" + WHERE_REVISION,
                node.toString(), root.toString());
    }

    private static void insertUnsealed(UUID revisionId, UUID quizId, String title, int items) {
        sql.update("INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at) "
                        + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),4,?,?)",
                revisionId.toString(), quizId.toString(), OWNER.toString(), title, Timestamp.from(CREATED));
        sql.update("INSERT INTO quiz_scenario_graphs (quiz_revision_id,schema_version,item_count,node_count,edge_count) "
                + "VALUES(UUID_TO_BIN(?),1,?,1,0)", revisionId.toString(), items);
    }

    private static void rejected(Runnable action, String reason) {
        assertThatThrownBy(action::run).isInstanceOf(RevisionIntegrityException.class).hasMessageContaining(reason);
    }

    private static void sqlRejected(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(DataAccessException.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
