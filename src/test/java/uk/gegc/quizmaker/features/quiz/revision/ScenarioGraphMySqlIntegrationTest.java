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
import uk.gegc.quizmaker.features.quiz.domain.model.revision.ScenarioGraphValidationException;
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
import static uk.gegc.quizmaker.features.quiz.revision.ScenarioGraphTest.OPAQUE_CONDITION;
import static uk.gegc.quizmaker.features.quiz.revision.ScenarioGraphTest.scenario;

@Tag("db-serial")
class ScenarioGraphMySqlIntegrationTest {
    private static final String CATALOG = "revision_scenario_" + UUID.randomUUID().toString().replace("-", "");
    private static final String WHERE_REVISION = " WHERE quiz_revision_id=UUID_TO_BIN(?)";
    private static final List<String> GRAPH_TABLES = List.of("quiz_scenario_graphs", "quiz_scenario_nodes", "quiz_scenario_edges");
    private static final String PUBLICATION_KEYS = "quiz_scenario_publication_keys";
    private static final String QUESTION_KEYS = "quiz_scenario_question_keys";
    private static final String GRAPH_COUNTS = "quiz_scenario_graph_counts";
    private static final UUID OPEN_V132 = id("open-graph-v132");
    private static final QuizRevision DURING_V134 = scenario("ordinary-during-option-evidence-install", 1, CONDITION);
    private static final QuizRevision BEFORE_V134_EDIT = scenario("ordinary-option-edited-during-v134", 1, CONDITION);
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
    private static final List<QuizRevision> DURING_V131 = List.of(
            scenario("v131-before-question-tracking", 1, CONDITION),
            scenario("v131-before-option-tracking", 1, CONDITION),
            scenario("v131-after-tracking", 1, CONDITION));
    private static boolean created;
    private QuizRevisionRepository repository;
    private QuizScenarioRevisionService publisher;
    private SimpleMeterRegistry metrics;

    @BeforeAll static void migrate() throws Exception {
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
        var legacyTerminal = id("scenario-v128-terminal");
        var legacyEdge = id("scenario-v128-conditional");
        var legacyItem = PREVIOUS_FORMATS.get(0).items().get(0);
        retainedV128 = new QuizRevision(id("scenario-v128"), id("scenario-v128-quiz"), OWNER, QuizRevision.SCENARIO_FORMAT,
                "Previously sealed graph", null, CREATED, List.of(legacyItem), List.of(), List.of(),
                new ScenarioGraph(1, legacyEntry,
                        List.of(new ScenarioGraph.Node(legacyEntry, 0, ScenarioGraph.NodeType.ITEM, legacyItem.id()),
                                new ScenarioGraph.Node(legacyTerminal, 1, ScenarioGraph.NodeType.TERMINAL, null)),
                        List.of(new ScenarioGraph.Edge(legacyEdge, legacyEntry, legacyTerminal, 0,
                                ScenarioGraph.EdgeType.CONDITIONAL, 1, OPAQUE_CONDITION))));
        insertUnsealed(retainedV128.id(), retainedV128.quizId(), retainedV128.title(), 1, 2, 1);
        sql.update("INSERT INTO quiz_revision_items VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)",
                legacyItem.id().toString(), retainedV128.id().toString(), legacyItem.question().id().toString(),
                legacyItem.authoredPosition());
        insertNode(retainedV128.id(), legacyEntry, 0, "ITEM", legacyItem.id());
        insertNode(retainedV128.id(), legacyTerminal, 1, "TERMINAL", null);
        sql.update("""
                INSERT INTO quiz_scenario_edges (quiz_revision_id,id,from_node_id,to_node_id,authored_position,
                    edge_type,condition_version,condition_payload)
                VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),0,'CONDITIONAL',1,?)
                """, retainedV128.id().toString(), legacyEdge.toString(), legacyEntry.toString(),
                legacyTerminal.toString(), OPAQUE_CONDITION);
        seal(retainedV128.id(), legacyEntry);
        migrate("129");
        migrate("130");
        // Flyway and the previous publisher use different connections, as in a rolling deployment.
        source.before = (statement, connection) -> {
            if (statement.stripLeading().startsWith("CREATE TRIGGER scenario_question_track_insert "))
                baseline.insert(quiz("v131-no-tracking-key", legacyQuestion(DURING_V131.get(0))));
            if (statement.stripLeading().startsWith("CREATE TRIGGER scenario_question_option_insert "))
                baseline.insert(quiz("v131-zero-tracking-count", legacyQuestion(DURING_V131.get(1))));
        };
        migrate("131");
        source.reset();
        baseline.insert(quiz("v131-complete-tracking", legacyQuestion(DURING_V131.get(2))));
        assertThat(trackedOptionCounts(DURING_V131.get(0))).isEmpty();
        assertThat(trackedOptionCounts(DURING_V131.get(1))).containsExactly(0);
        assertThat(trackedOptionCounts(DURING_V131.get(2))).containsExactly(2);
        migrate("132");
        insertUnsealed(OPEN_V132, retainedV128.quizId(), "Previously open graph", 0);
        migrate("133");
        baseline.insert(quiz("ordinary-before-v134-edit", legacyQuestion(BEFORE_V134_EDIT)));
        var backfillStarting = new CountDownLatch(1);
        var editWritten = new CountDownLatch(1);
        var releaseEdit = new CountDownLatch(1);
        var writerConnection = new AtomicLong();
        source.before = (statement, connection) -> {
            if (statement.contains("CREATE TRIGGER scenario_question_evidence_init")) {
                var question = legacyQuestion(DURING_V134);
                baseline.insert(quiz("ordinary-during-v134", question));
                // Retained JSON can have equivalent content with a different stored representation.
                sql.update("UPDATE question_revisions SET content=REPLACE(content,'\"options\":','\"options\" : ') WHERE id=UUID_TO_BIN(?)",
                        question.id().toString());
                assertThat(sql.queryForObject("""
                        SELECT option_evidence IS NULL AND question_evidence IS NULL FROM quiz_scenario_question_keys
                        WHERE question_revision_id=UUID_TO_BIN(?)
                        """, Boolean.class, question.id().toString())).isTrue();
            }
            if (statement.contains("CALL scenario_backfill_question_evidence()")) {
                backfillStarting.countDown();
                awaitGate(editWritten, "the concurrent option replacement");
            }
        };
        var workers = Executors.newFixedThreadPool(2);
        try {
            var upgrade = workers.submit(() -> migrate("134"));
            var edit = workers.submit(() -> {
                awaitGate(backfillStarting, "the evidence backfill");
                fresh().executeWithoutResult(status -> {
                    writerConnection.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    var question = legacyQuestion(BEFORE_V134_EDIT);
                    var option = question.options().get(0);
                    sql.update("""
                            DELETE FROM question_option_revisions
                            WHERE question_revision_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                            """, question.id().toString(), option.id().toString());
                    sql.update("""
                            INSERT INTO question_option_revisions (id,question_revision_id,source_key,authored_position)
                            VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),?,?)
                            """, option.id().toString(), question.id().toString(), "Changed", option.authoredPosition());
                    editWritten.countDown();
                    awaitGate(releaseEdit, "release the concurrent edit");
                });
            });
            awaitGate(editWritten, "the concurrent option replacement");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (!waiting && System.nanoTime() < deadline && !upgrade.isDone()) {
                waiting = Boolean.TRUE.equals(admin.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM performance_schema.data_lock_waits w
                          JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.BLOCKING_ENGINE_LOCK_ID
                          JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
                          WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?)
                        """, Boolean.class, writerConnection.get(), CATALOG, QUESTION_KEYS));
                if (!waiting) Thread.sleep(20);
            }
            assertThat(waiting).as("backfill waits for the option writer's metadata lock").isTrue();
            releaseEdit.countDown();
            edit.get(30, TimeUnit.SECONDS);
            upgrade.get(30, TimeUnit.SECONDS);
        } finally {
            releaseEdit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            source.reset();
        }
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
        migrate("134");
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
        assertThat(hasReplayFingerprint(retainedV128.id())).isFalse();
        rejected(() -> publisher.publish(OWNER, replaceFirstQuestion(retainedV128,
                withOptionKey(legacyQuestion(retainedV128), "different-legacy-option"))), "IDENTITY_CONFLICT");
        assertThat(hasReplayFingerprint(retainedV128.id())).isFalse();
        assertThat(publisher.publish(OWNER, retainedV128)).isEqualTo(retainedV128);
        assertThat(hasReplayFingerprint(retainedV128.id())).isTrue();
        assertThat(publisher.publish(OWNER, retainedV128)).isEqualTo(retainedV128);
        assertThat(count(PUBLICATION_KEYS, retainedV128.id())).isOne();
        assertThat(questionOptionCount(legacyQuestion(retainedV128).id())).isEqualTo(2);

        var node = id("open-graph-v132-node");
        insertNode(OPEN_V132, node, 0, "TERMINAL", null);
        seal(OPEN_V132, node);
        assertThat(repository.findById(OWNER, OPEN_V132).orElseThrow().scenarioGraph().entryNodeId()).isEqualTo(node);
    }

    @Test void migrationReconcilesQuestionPublicationsBetweenV131TriggerInstalls() {
        for (var graph : DURING_V131) {
            assertThat(trackedOptionCounts(graph)).containsExactly(2);
            assertThat(questionOptionCount(legacyQuestion(graph).id())).isNull();
            var published = repeatableRead().execute(status -> publisher.publish(OWNER, graph));
            assertThat(published).isEqualTo(graph);
            assertThat(questionOptionCount(legacyQuestion(graph).id())).isEqualTo(2);
            assertThat(repository.findById(OWNER, graph.id())).contains(graph);
        }
    }

    @Test void migrationBackfillsEvidenceForOrdinaryPublicationBeforeV134TrackingStarts() {
        var question = legacyQuestion(DURING_V134);
        assertThat(questionOptionCount(question.id())).isNull();
        assertThat(sql.queryForObject("""
                SELECT evidenced_option_count FROM quiz_scenario_question_keys WHERE question_revision_id=UUID_TO_BIN(?)
                """, Integer.class, question.id().toString())).isEqualTo(question.options().size());
        assertThat(publisher.publish(OWNER, DURING_V134)).isEqualTo(DURING_V134);
        assertThat(repository.findById(OWNER, DURING_V134.id())).contains(DURING_V134);
    }

    @Test void migrationBackfillsTheOptionVersionCommittedWhileWaitingForItsLock() {
        var old = BEFORE_V134_EDIT;
        var current = replaceFirstQuestion(old, withOptionKey(legacyQuestion(old), "Changed"));
        rejected(() -> publisher.publish(OWNER, old), "IDENTITY_CONFLICT");
        assertThat(repository.findById(OWNER, old.id())).isEmpty();
        assertThat(publisher.publish(OWNER, current)).isEqualTo(current);
        assertThat(repository.findById(OWNER, current.id())).contains(current);
        assertThat(publisher.publish(OWNER, current)).isEqualTo(current);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsonWhitespaceAddedAfterAnOlderSnapshotKeepsQuestionIdentity(boolean legacyOrder) {
        var graph = scenario("json-whitespace-" + legacyOrder, 1, CONDITION);
        var question = legacyQuestion(graph);
        var ordinary = quiz("ordinary-before-json-spaces-" + legacyOrder, question);
        repository.insert(ordinary);
        String content = legacyOrder
                ? question.content().replace("\"correct\":true,\"id\":\"a\"", "\"id\":\"a\",\"correct\":true")
                : question.content();
        assertThat(sql.update("UPDATE question_revisions SET content=? WHERE id=UUID_TO_BIN(?)",
                content, question.id().toString())).isOne();
        var formatted = " \t" + content.replace("\"options\":", "\"options\" \t:\r\n ") + "\n ";
        repeatableRead().executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, ordinary.id())).contains(ordinary);
            fresh().executeWithoutResult(writer -> assertThat(sql.update(
                    "UPDATE question_revisions SET content=? WHERE id=UUID_TO_BIN(?)",
                    formatted, question.id().toString())).isOne());
            assertThat(publisher.publish(OWNER, graph)).isEqualTo(graph);
        });
        assertThat(sql.queryForObject("SELECT content FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                String.class, question.id().toString())).isEqualTo(formatted);
        assertThat(repository.findById(OWNER, graph.id())).contains(graph);
    }

    @Test void jsonWhitespaceNormalizationKeepsQuotedTextAndSeparatedTokens() {
        String quoted = "\" a  : \\\"q\\\" \\\\ \\t [ ] , \"";
        String formatted = "\t { \"n\" : [ 1.00e+3, \n 9223372036854775807 ] , \"s\" : " + quoted + " } \r\n";
        assertThat(sql.queryForObject("SELECT scenario_compact_question_json(?)", String.class, formatted))
                .isEqualTo("{\"n\":[1.00e+3,9223372036854775807],\"s\":" + quoted + "}");
        assertThat(sql.queryForObject("SELECT scenario_compact_question_json(?)", String.class, "{ \"n\": 1 2 }"))
                .isEqualTo("{\"n\":1 2}");
    }

    @Test void jsonObjectOrderChangedAfterAnOlderSnapshotKeepsQuestionIdentity() {
        var graph = scenario("json-object-order", 1, CONDITION);
        var question = legacyQuestion(graph);
        var ordinary = quiz("ordinary-before-json-order", question);
        repository.insert(ordinary);
        var reordered = question.content().replace("\"correct\":true,\"id\":\"a\"", "\"id\":\"a\",\"correct\":true");
        assertThat(reordered).isNotEqualTo(question.content());
        repeatableRead().executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, ordinary.id())).contains(ordinary);
            fresh().executeWithoutResult(writer -> assertThat(sql.update(
                    "UPDATE question_revisions SET content=? WHERE id=UUID_TO_BIN(?)",
                    reordered, question.id().toString())).isOne());
            assertThat(publisher.publish(OWNER, graph)).isEqualTo(graph);
        });
        assertThat(sql.queryForObject("SELECT content FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                String.class, question.id().toString())).isEqualTo(reordered);
        assertThat(repository.findById(OWNER, graph.id())).contains(graph);
    }

    @Test void unorderedJsonComparisonPreservesArrayOrderNumericPrecisionAndUniqueKeys() {
        String original = """
                {"label":"🧪 42: \\"quote\\"","data":{"big":9007199254740993,"scale":1.00,"order":[true,false]}}
                """;
        String reordered = """
                {"data":{"order":[true,false],"scale":1.00,"big":9007199254740993},"label":"🧪 42: \\"quote\\""}
                """;
        String same = "SELECT scenario_same_question_json(?,?)";
        assertThat(sql.queryForObject(same, Boolean.class, original, reordered)).isTrue();
        assertThat(sql.queryForObject(same, Boolean.class, original,
                reordered.replace("9007199254740993", "9007199254740992"))).isFalse();
        assertThat(sql.queryForObject(same, Boolean.class, original, reordered.replace("1.00", "1.0"))).isFalse();
        assertThat(sql.queryForObject(same, Boolean.class, original, reordered.replace("1.00", "\"n1.00\""))).isFalse();
        assertThat(sql.queryForObject(same, Boolean.class, original,
                reordered.replace("[true,false]", "[false,true]"))).isFalse();
        assertThat(sql.queryForObject(same, Boolean.class, original,
                reordered.replace("\"big\":", "\"big\":0,\"big\":"))).isFalse();
        assertThat(sql.queryForObject(same, Boolean.class, "{\"n\":1e999,\"b\":true}",
                "{\"b\":true,\"n\":1e999}")).isTrue();
        assertThat(sql.queryForObject(same, Boolean.class, "{\"n\":42,\"s\":\"\\u001e\\u001f\"}",
                "{\"s\":\"\\u001e\\u001f\",\"n\":42}")).isTrue();
        String normalize = "SELECT scenario_ordered_question_json(?)";
        assertThat(sql.queryForObject(normalize, String.class, "{\"n\":00}")).isNull();
        assertThat(sql.queryForObject(normalize, String.class, "{42 \t:true}")).isNull();
        assertThat(sql.queryForObject(normalize, String.class, "{\"n\":" + (char) 30 + "42" + (char) 31 + "}")).isNull();
    }

    @Test void largeJsonQuestionRetainsItsProofOnlyForEquivalentUpdates() {
        var template = legacyQuestion(scenario("large-json-update", 1, CONDITION));
        String entries = String.join(",", IntStream.range(0, 1024)
                .mapToObj(index -> "{\"id\":" + index + ",\"label\":\"word\",\"value\":" + index + "}")
                .toList());
        String content = "{\"list\":[" + entries + "],\"ready\":true}";
        var question = new QuestionRevision(template.id(), template.questionId(), template.schemaVersion(), template.type(),
                template.difficulty(), template.questionText(), content, null, null, null, null, template.createdAt(), template.options());
        repository.insert(quiz("large-json-update-ordinary", question));

        String id = question.id().toString();
        String evidence = "SELECT question_evidence FROM quiz_scenario_question_keys WHERE question_revision_id=UUID_TO_BIN(?)";
        String update = "UPDATE question_revisions SET content=? WHERE id=UUID_TO_BIN(?)";
        byte[] original = sql.queryForObject(evidence, byte[].class, id);
        assertThat(original).hasSize(32);
        assertThat(sql.update(update, "{\"ready\":true,\"list\":[" + entries + "]}", id)).isOne();
        assertThat(sql.queryForObject(evidence, byte[].class, id)).isEqualTo(original);
        assertThat(sql.update(update, "{\"ready\":false,\"list\":[" + entries + "]}", id)).isOne();
        assertThat(sql.queryForObject(evidence, byte[].class, id)).isNotEqualTo(original);
    }

    @Test void questionsBeyondTheNativeJsonNestingLimitKeepTheWhitespaceComparison() {
        String values = "[".repeat(105) + "0" + "]".repeat(105);
        String original = "{\"values\":" + values + ",\"ready\":true}";
        String formatted = original.replace("\"values\":", "\"values\" \t:\n");
        String changed = original.replace("\"ready\":true", "\"ready\":false");
        String same = "SELECT scenario_same_question_json(?,?)";
        assertThat(sql.queryForObject(same, Boolean.class, original, formatted)).isTrue();
        assertThat(sql.queryForObject(same, Boolean.class, original, changed)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 128})
    void legacyJsonRetriesKeepScenarioItemWritesBatched(int legacyCount) {
        var graph = scenario("batched-legacy-json-" + legacyCount, 128, CONDITION);
        var questions = graph.items().stream().map(QuizRevision.Item::question).toArray(QuestionRevision[]::new);
        repository.insert(quiz("ordinary-batched-legacy-json-" + legacyCount, questions));
        for (int index = 0; index < legacyCount; index++) {
            var question = questions[index];
            var reordered = question.content().replace("\"correct\":true,\"id\":\"a\"", "\"id\":\"a\",\"correct\":true");
            assertThat(reordered).isNotEqualTo(question.content());
            // Emulate a legacy import whose original proof used noncanonical JSON.
            fresh().executeWithoutResult(status -> {
                String update = "UPDATE question_revisions SET content=? WHERE id=UUID_TO_BIN(?)";
                sql.update(update, "{}", question.id().toString());
                sql.update(update, reordered, question.id().toString());
            });
        }
        var statements = new AtomicInteger();
        source.before = (statement, connection) -> statements.incrementAndGet();
        assertThat(publisher.publish(OWNER, graph)).isEqualTo(graph);
        assertThat(statements.get()).isLessThanOrEqualTo(13);
        assertThat(repository.findById(OWNER, graph.id())).contains(graph);
    }

    @Test void publishesOneGraphOnExactReplayAndRetainsOriginalAfterEditAndRestart() {
        var original = scenario("original", 3, CONDITION);
        var changed = scenario("edited", 3, CONDITION.replace("FULL_CREDIT", "NOT_FULL_CREDIT"));
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

    @Test void rejectsQuestionReachedOnlyByLosingPriorityAndPublishesWhenAnotherOutcomeCanReachIt() {
        var draft = scenario("shadowed-scenario-question", 2, CONDITION);
        var graph = draft.scenarioGraph();
        var first = graph.nodes().get(0);
        var hidden = graph.nodes().get(1);
        var terminal = graph.nodes().get(2);
        var edges = new ArrayList<>(graph.edges());
        String rankedScore = "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\",\"priority\":%d}";
        edges.set(0, new ScenarioGraph.Edge(id("fallback-to-terminal"), first.id(), terminal.id(), 0,
                ScenarioGraph.EdgeType.DEFAULT, null, null));
        edges.set(1, new ScenarioGraph.Edge(id("winning-edge"), first.id(), terminal.id(), 6,
                ScenarioGraph.EdgeType.CONDITIONAL, 1, rankedScore.formatted(1)));
        edges.add(new ScenarioGraph.Edge(id("losing-edge"), first.id(), hidden.id(), 7,
                ScenarioGraph.EdgeType.CONDITIONAL, 1, rankedScore.formatted(2)));
        var rejected = withScenario(draft, draft.items(), graph.nodes(), edges);
        assertFirstPublicationRejected(rejected, ScenarioGraphValidationException.Reason.UNREACHABLE_NODE, "nodes[1]");

        edges.set(0, graph.edges().get(0));
        var corrected = withScenario(draft, draft.items(), graph.nodes(), edges);
        assertThat(publisher.publish(OWNER, corrected)).isEqualTo(corrected);
        assertThat(repository.findById(OWNER, corrected.id())).contains(corrected);
        assertThat(publisher.publish(OWNER, corrected)).isEqualTo(corrected);
    }

    @Test void rejectsRepeatedSourceItemButPublishesDistinctItemsOfTheSameQuestionRevision() {
        var draft = scenario("repeated-scenario-item", 2, CONDITION);
        var graph = draft.scenarioGraph();
        var first = graph.nodes().get(0);
        var second = graph.nodes().get(1);
        var terminal = graph.nodes().get(2);
        var repeated = new ScenarioGraph.Node(second.id(), second.authoredPosition(), second.type(), first.itemId());
        var rejected = withScenario(draft, draft.items(), List.of(first, repeated, terminal), graph.edges());
        assertFirstPublicationRejected(rejected, ScenarioGraphValidationException.Reason.REPEATED_ITEM_ON_PATH, "nodes[1].itemId");

        var firstItem = draft.items().get(0);
        var secondItem = draft.items().get(1);
        var items = List.of(firstItem, new QuizRevision.Item(secondItem.id(), secondItem.authoredPosition(), firstItem.question()));
        var corrected = withScenario(draft, items, graph.nodes(), graph.edges());
        assertThat(publisher.publish(OWNER, corrected)).isEqualTo(corrected);
        assertThat(repository.findById(OWNER, corrected.id())).contains(corrected);
        assertThat(publisher.publish(OWNER, corrected)).isEqualTo(corrected);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidFirstPublicationRollsBackItsKeyAndAllowsCorrectedContent(boolean cycle) {
        String key = "invalid-first-scenario-" + cycle;
        var corrected = scenario(key, 2, CONDITION);
        var invalid = scenario(key, 2, cycle ? CONDITION : OPAQUE_CONDITION);
        if (cycle) {
            var graph = invalid.scenarioGraph();
            var edges = new ArrayList<>(graph.edges());
            var second = graph.nodes().get(1);
            edges.set(2, new ScenarioGraph.Edge(id("back-edge"), second.id(), graph.entryNodeId(), 0,
                    ScenarioGraph.EdgeType.DEFAULT, null, null));
            invalid = new QuizRevision(invalid.id(), invalid.quizId(), OWNER, invalid.schemaVersion(), invalid.title(),
                    invalid.description(), invalid.createdAt(), invalid.items(), List.of(), List.of(),
                    new ScenarioGraph(1, graph.entryNodeId(), graph.nodes(), edges));
        }
        var candidate = invalid;
        var reason = cycle ? ScenarioGraphValidationException.Reason.CYCLE : ScenarioGraphValidationException.Reason.INVALID_CONDITION;
        var keyWasWritten = new AtomicBoolean();
        var rootWriteAttempted = new AtomicBoolean();
        source.after = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO " + PUBLICATION_KEYS)) keyWasWritten.set(true);
        };
        source.before = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO quiz_revisions")) rootWriteAttempted.set(true);
        };
        assertThatThrownBy(() -> publisher.publish(OWNER, candidate)).isInstanceOfSatisfying(
                ScenarioGraphValidationException.class, failure -> assertThat(failure.reason()).isEqualTo(reason));
        assertThat(keyWasWritten).isTrue();
        assertThat(rootWriteAttempted).isFalse();
        assertThat(metrics.get("quiz.revision.scenario.publish")
                .tag("outcome", cycle ? "graph_cycle" : "graph_invalid_condition").counter().count()).isEqualTo(1);
        assertThatThrownBy(() -> repository.insert(candidate)).isInstanceOf(ScenarioGraphValidationException.class);
        assertNoStoredScenario(corrected);

        assertThat(publisher.publish(OWNER, corrected)).isEqualTo(corrected);
        assertThat(repository.findById(OWNER, corrected.id())).contains(corrected);
        assertThat(publisher.publish(OWNER, corrected)).isEqualTo(corrected);
    }

    private void assertFirstPublicationRejected(QuizRevision candidate, ScenarioGraphValidationException.Reason reason,
                                                String path) {
        assertThatThrownBy(() -> publisher.publish(OWNER, candidate)).isInstanceOfSatisfying(
                ScenarioGraphValidationException.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(reason);
                    assertThat(failure.path()).isEqualTo(path);
                });
        assertNoStoredScenario(candidate);
    }

    private void assertNoStoredScenario(QuizRevision revision) {
        fresh().executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, revision.id())).isEmpty();
            assertThat(count(PUBLICATION_KEYS, revision.id())).isZero();
            assertThat(count(GRAPH_COUNTS, revision.id())).isZero();
            assertThat(count("quiz_revision_items", revision.id())).isZero();
            for (String table : GRAPH_TABLES) assertThat(count(table, revision.id())).isZero();
            for (var item : revision.items()) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                        Integer.class, item.question().id().toString())).isZero();
            }
        });
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
            assertThat(count(GRAPH_COUNTS, revision.id())).isZero();
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

    @Test void heldReplayOf512QuestionsAllowsOneNewQuestionBetweenOrAboveItsKeys() throws Exception {
        var published = scenario("dense-replay", 512, CONDITION);
        assertThat(publisher.publish(OWNER, published)).isEqualTo(published);
        var peers = new ArrayList<QuizRevision>();
        // Adjacent UUIDs inside the populated index, exercising both reported MySQL layouts.
        for (int position : List.of(28, 374)) {
            var neighbor = published.items().get(position).question().id();
            var next = new UUID(neighbor.getMostSignificantBits(), neighbor.getLeastSignificantBits() ^ 1);
            peers.add(publicationWithOneQuestion("one-near-held-" + position, id("near-owner-" + position), next));
        }
        peers.add(publicationWithOneQuestion("one-above-held", id("one-above-owner"), new UUID(-1L, -1L)));
        assertIndependentWhileHeld(published, peers, List.of());
    }

    @Test void firstUseOfOrdinaryQuestionDoesNotBlockAnotherQuestionsOptions() throws Exception {
        var held = publicationWithOneQuestion("first-ordinary-use", OWNER, new UUID(-1L, -10L));
        var ordinary = quiz("ordinary-before-first-use", legacyQuestion(held));
        repository.insert(ordinary);
        assertThat(questionOptionCount(legacyQuestion(ordinary).id())).isNull();
        var peer = publicationWithOneQuestion("one-after-adoption", id("after-adoption-owner"),
                new UUID(-1L, -9L));
        assertIndependentWhileHeld(held, List.of(peer), List.of(QUESTION_KEYS));
        assertThat(questionOptionCount(legacyQuestion(ordinary).id())).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512})
    void firstUseOfOrdinaryQuestionsAllowsAnAdjacentQuestionToCommit(int adoptedCount) throws Exception {
        var existing = scenario(adoptedCount == 1 ? "new-adopt-ordinary" : "new-adopt-ordinary-bulk", 512, CONDITION);
        var questions = existing.items().stream().map(QuizRevision.Item::question).toArray(QuestionRevision[]::new);
        repository.insert(quiz("ordinary-dense-for-" + adoptedCount, questions));
        var held = adoptedCount == 1
                ? replaceFirstQuestion(scenario("isolated-existing-question", 1, CONDITION), questions[280])
                : existing;
        var peers = new ArrayList<QuizRevision>();
        for (int selected : adoptedCount == 1 ? List.of(280) : List.of(280, 453)) {
            var neighbor = questions[selected].id();
            var adjacent = new UUID(neighbor.getMostSignificantBits(), neighbor.getLeastSignificantBits() ^ 1L);
            peers.add(publicationWithOneQuestion("near-adoption-" + adoptedCount + "-" + selected,
                    id("independent-adoption-author-" + selected), adjacent));
        }
        assertIndependentWhileHeld(held, peers, List.of(QUESTION_KEYS));
        assertThat(questionOptionCount(questions[280].id())).isEqualTo(questions[280].options().size());
    }

    private void assertIndependentWhileHeld(QuizRevision held, List<QuizRevision> peers, List<String> adoptionTables) throws Exception {
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
            var locks = revisionRecordLocks(connection.get());
            if (adoptionTables.isEmpty()) {
                assertThat(locks).extracting(lock -> lock.get("OBJECT_NAME")).containsOnly(PUBLICATION_KEYS);
                assertRecordOnly(locks);
            } else {
                var metadataLocks = locks.stream().filter(lock -> QUESTION_KEYS.equals(lock.get("OBJECT_NAME"))).toList();
                assertThat(metadataLocks).isNotEmpty().allSatisfy(lock -> assertThat((String) lock.get("LOCK_MODE")).startsWith("S"));
                if (held.items().size() == 1) assertRecordOnly(metadataLocks);
                assertThat(locks).noneMatch(lock ->
                        List.of("question_revisions", "question_option_revisions").contains(lock.get("OBJECT_NAME")));
            }
            for (var peer : peers) {
                var other = workers.submit(() -> fresh().execute(status -> {
                    sql.execute("SET SESSION innodb_lock_wait_timeout=2");
                    return publisher.publish(peer.ownerId(), peer);
                }));
                assertThat(other.get(30, TimeUnit.SECONDS)).isEqualTo(peer);
                assertThat(waiting.isDone()).isFalse();
                assertThat(repository.findById(peer.ownerId(), peer.id())).contains(peer);
            }
            release.countDown();
            waiting.get(30, TimeUnit.SECONDS);
            assertThat(repository.findById(held.ownerId(), held.id())).contains(held);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static List<Map<String, Object>> revisionRecordLocks(long connection) {
        return admin.queryForList("""
                SELECT l.OBJECT_NAME, l.LOCK_MODE, l.LOCK_DATA FROM performance_schema.data_locks l
                JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
                WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.LOCK_TYPE='RECORD'
                """, connection, CATALOG);
    }

    private static void assertQuestionLocksAreRecordOnly(List<Map<String, Object>> locks, List<String> tables) {
        for (String table : tables) {
            assertRecordOnly(locks.stream().filter(lock -> table.equals(lock.get("OBJECT_NAME"))).toList());
        }
    }

    private static void assertRecordOnly(List<Map<String, Object>> locks) {
        assertThat(locks).isNotEmpty().allSatisfy(lock -> {
            assertThat((String) lock.get("LOCK_MODE")).contains("REC_NOT_GAP");
            assertThat(String.valueOf(lock.get("LOCK_DATA"))).doesNotContain("supremum");
        });
    }

    @Test void databaseRejectsOptionMembershipChangesOnceTheQuestionIsSealed() {
        var revision = scenario("sealed-option-membership", 1, CONDITION);
        var question = legacyQuestion(revision);
        repository.insert(quiz("ordinary-before-option-seal", question));
        var option = question.options().get(0);
        String query = "INSERT INTO question_option_revisions VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'additional',5)";
        var extra = id("extra-option-while-unsealed");
        assertThat(sql.update(query, extra.toString(), question.id().toString())).isOne();
        var earlier = id("earlier-option-while-unsealed");
        assertThat(sql.update("""
                INSERT INTO question_option_revisions VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),'earlier',3)
                """, earlier.toString(), question.id().toString())).isOne();
        assertThat(sql.update("DELETE FROM question_option_revisions WHERE question_revision_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)",
                question.id().toString(), earlier.toString())).isOne();
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
        sqlRejected(() -> sql.update("""
                UPDATE quiz_scenario_question_keys SET stored_option_count=stored_option_count+1
                WHERE question_revision_id=UUID_TO_BIN(?)
                """,
                question.id().toString()));
        sqlRejected(() -> sql.update("DELETE FROM " + QUESTION_KEYS + " WHERE question_revision_id=UUID_TO_BIN(?)",
                question.id().toString()));
        sqlRejected(() -> sql.update("UPDATE " + QUESTION_KEYS + " SET option_evidence=NULL WHERE question_revision_id=UUID_TO_BIN(?)",
                question.id().toString()));
        sqlRejected(() -> sql.update("UPDATE question_revisions SET question_text=CONCAT(question_text,' ') WHERE id=UUID_TO_BIN(?)",
                question.id().toString()));
        sqlRejected(() -> sql.update("UPDATE " + PUBLICATION_KEYS + " SET replay_sha256=NULL" + WHERE_REVISION,
                revision.id().toString()));
        sqlRejected(() -> sql.update("DELETE FROM " + PUBLICATION_KEYS + WHERE_REVISION, revision.id().toString()));
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
        assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
    }

    @Test void adoptionUsesTheCurrentQuestionAndExactOptionKeyFromAnOlderSnapshot() {
        var revision = scenario("adopt-after-option-change", 1, CONDITION);
        var question = legacyQuestion(revision);
        var ordinary = quiz("ordinary-before-option-edit", question);
        repository.insert(ordinary);
        var option = question.options().get(0);
        String sourceKey = "Ключ 🚀 ";
        String prompt = "Changed ordinary question";
        var updatedQuestion = new QuestionRevision(question.id(), question.questionId(), question.schemaVersion(),
                question.type(), question.difficulty(), prompt, question.content(), question.hint(), question.explanation(),
                question.attachmentUrl(), question.attachmentAssetId(), question.createdAt(),
                withOptionKey(question, sourceKey).options());
        var updated = replaceFirstQuestion(revision, updatedQuestion);

        repeatableRead().executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, ordinary.id())).contains(ordinary);
            fresh().executeWithoutResult(writer -> {
                sql.update("UPDATE question_revisions SET question_text=? WHERE id=UUID_TO_BIN(?)",
                        prompt, question.id().toString());
                sql.update("""
                        UPDATE question_option_revisions SET source_key=?
                        WHERE question_revision_id=UUID_TO_BIN(?) AND id=UUID_TO_BIN(?)
                        """, sourceKey, question.id().toString(), option.id().toString());
            });
            assertThat(repository.findById(OWNER, ordinary.id())).contains(ordinary);
            assertThat(publisher.publish(OWNER, updated)).isEqualTo(updated);
        });
        rejected(() -> publisher.publish(OWNER,
                replaceFirstQuestion(scenario("adopt-with-trimmed-option-key", 1, CONDITION),
                        withOptionKey(updatedQuestion, sourceKey.stripTrailing()))), "IDENTITY_CONFLICT");
        rejected(() -> publisher.publish(OWNER,
                replaceFirstQuestion(scenario("adopt-with-superseded-question", 1, CONDITION),
                        withOptionKey(question, sourceKey))), "IDENTITY_CONFLICT");
        assertThat(repository.findById(OWNER, updated.id())).contains(updated);
    }

    @Test void publishes128NewQuestionsWithOneQuestionBatchCall() {
        var revision = scenario("question-insert-batch", 128, CONDITION);
        var writes = new AtomicInteger();
        source.before = (statement, connection) -> {
            if (statement.startsWith("CALL scenario_insert_questions")) writes.incrementAndGet();
        };
        assertThat(publisher.publish(OWNER, revision)).isEqualTo(revision);
        assertThat(writes).hasValue(1);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test void ordinaryPublicationIndexesOptionEvidenceAfterAllOptionsAreWritten() {
        var question = legacyQuestion(scenario("ordinary-indexed-option-evidence", 1, CONDITION));
        var ordinary = quiz("ordinary-indexed-option-evidence", question);
        var observed = new AtomicBoolean();
        source.after = (statement, connection) -> {
            if (!statement.startsWith("INSERT INTO question_option_revisions")) return;
            try (var check = connection.prepareStatement("""
                    SELECT evidenced_option_count,content_evidence IS NULL
                    FROM quiz_scenario_question_keys WHERE question_revision_id=UUID_TO_BIN(?)
                    """)) {
                check.setString(1, question.id().toString());
                try (var result = check.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(question.options().size());
                    assertThat(result.getBoolean(2)).isTrue();
                }
            }
            observed.set(true);
        };
        repository.insert(ordinary);
        assertThat(observed).isTrue();
        assertThat(sql.queryForObject("""
                SELECT content_evidence IS NOT NULL FROM quiz_scenario_question_keys WHERE question_revision_id=UUID_TO_BIN(?)
                """, Boolean.class, question.id().toString())).isTrue();
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
            if (!statement.startsWith("CALL scenario_insert_questions")) return;
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
                assertQuestionLocksAreRecordOnly(revisionRecordLocks(sql.queryForObject("SELECT CONNECTION_ID()", Long.class)),
                        List.of(QUESTION_KEYS));
            });
            assertThat(repository.findByIds(OWNER, List.of(original.id(), other.id()))).containsExactly(original, other);
        } finally {
            worker.shutdownNow();
            assertThat(worker.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void insertsMissingQuestionsAndReusesCommittedQuestionsInTheSameOldSnapshotBatch() {
        var graph = scenario("batched-stale-questions-mixed", 128, CONDITION);
        var concurrent = graph.items().stream().limit(64).map(QuizRevision.Item::question)
                .toArray(QuestionRevision[]::new);
        var ordinary = quiz("ordinary-batched-stale-questions-mixed", concurrent);
        repeatableRead().executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, ordinary.id())).isEmpty();
            fresh().executeWithoutResult(writer -> repository.insert(ordinary));
            assertThat(repository.findById(OWNER, ordinary.id())).isEmpty();
            assertThat(publisher.publish(OWNER, graph)).isEqualTo(graph);
        });
        assertThat(repository.findById(OWNER, graph.id())).contains(graph);
        assertThat(repository.findById(OWNER, ordinary.id())).contains(ordinary);
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 128})
    void questionsCommittedAfterSnapshotAddOneCommandAtTheMySqlServer(int questionCount) {
        boolean wasEnabled = Boolean.TRUE.equals(admin.queryForObject("SELECT @@GLOBAL.general_log", Boolean.class));
        String oldOutput = admin.queryForObject("SELECT @@GLOBAL.log_output", String.class);
        try {
            admin.execute("SET GLOBAL log_output='TABLE'");
            admin.execute("SET GLOBAL general_log=ON");
            long visible = wireCommandsForPublication(questionCount, false);
            long stale = wireCommandsForPublication(questionCount, true);
            assertThat(stale).isEqualTo(visible + 1);
        } finally {
            admin.update("SET GLOBAL log_output=?", oldOutput);
            admin.update("SET GLOBAL general_log=?", wasEnabled ? 1 : 0);
        }
    }

    private long wireCommandsForPublication(int questionCount, boolean late) {
        var key = questionCount + "-" + late;
        var graph = scenario("wire-scenario-" + key, questionCount, CONDITION);
        var questions = graph.items().stream().map(QuizRevision.Item::question).toArray(QuestionRevision[]::new);
        var flat = quiz("wire-ordinary-" + key, questions);
        if (!late) repository.insert(flat);
        String startMarker = "SET @scenario_wire_boundary='start-" + key + "'";
        String endMarker = "SET @scenario_wire_boundary='end-" + key + "'";
        long connectionId = repeatableRead().execute(status -> {
            assertThat(repository.findById(OWNER, flat.id()).isPresent()).isEqualTo(!late);
            if (late) {
                fresh().executeWithoutResult(writer -> repository.insert(flat));
                assertThat(repository.findById(OWNER, flat.id())).isEmpty();
            }
            long connection = sql.queryForObject("SELECT CONNECTION_ID()", Long.class);
            sql.execute(startMarker);
            assertThat(publisher.publish(OWNER, graph)).isEqualTo(graph);
            sql.execute(endMarker);
            return connection;
        });
        assertThat(repository.findById(OWNER, graph.id())).contains(graph);
        var commands = admin.queryForMap("""
                SELECT COUNT(*) AS commands,
                    COALESCE(SUM(argument LIKE 'CALL scenario_insert_questions(%'),0) AS creation_calls,
                    COALESCE(SUM(argument LIKE 'INSERT INTO question_revisions%'),0) AS individual_inserts
                FROM mysql.general_log WHERE thread_id=? AND command_type IN ('Query','Execute')
                    AND event_time>(SELECT MAX(event_time) FROM mysql.general_log WHERE thread_id=? AND argument=?)
                    AND event_time<(SELECT MAX(event_time) FROM mysql.general_log WHERE thread_id=? AND argument=?)
                """, connectionId, connectionId, startMarker, connectionId, endMarker);
        assertThat(((Number) commands.get("creation_calls")).intValue()).isEqualTo(late ? 1 : 0);
        assertThat(((Number) commands.get("individual_inserts")).intValue()).isZero();
        long count = ((Number) commands.get("commands")).longValue();
        assertThat(count).isPositive();
        return count;
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void neighboringTerminalGraphCommitsWhileFirstSealIsHeldOpen(boolean holderRepeatableRead) throws Exception {
        String key = holderRepeatableRead ? "terminal-rr" : "terminal-rc";
        long position = holderRepeatableRead ? -8L : -4L;
        var first = terminalScenario(key + "-first", new UUID(-1L, position), OWNER);
        var peer = terminalScenario(key + "-peer", new UUID(-1L, position + 1), id("other-terminal-author"));
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var connection = new AtomicLong();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var held = workers.submit(() -> (holderRepeatableRead ? repeatableRead() : fresh()).executeWithoutResult(status -> {
                connection.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
                assertThat(publisher.publish(first.ownerId(), first)).isEqualTo(first);
                ready.countDown();
                awaitGate(release, "release the first seal");
                status.setRollbackOnly();
            }));
            awaitGate(ready, "the first seal");

            var other = workers.submit(() -> fresh().execute(status -> {
                sql.execute("SET SESSION innodb_lock_wait_timeout=2");
                return publisher.publish(peer.ownerId(), peer);
            }));
            assertThat(other.get(10, TimeUnit.SECONDS)).isEqualTo(peer);
            assertThat(held.isDone()).isFalse();
            assertThat(repository.findById(peer.ownerId(), peer.id())).contains(peer);
            assertThat(repository.findById(first.ownerId(), first.id())).isEmpty();
            assertThat(admin.queryForList("""
                    SELECT l.OBJECT_NAME FROM performance_schema.data_locks l
                    JOIN performance_schema.threads t ON t.THREAD_ID=l.THREAD_ID
                    WHERE t.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=?
                      AND l.OBJECT_NAME IN ('quiz_revision_items','quiz_scenario_nodes','quiz_scenario_edges')
                      AND l.LOCK_DATA='supremum pseudo-record'
                    """, connection.get(), CATALOG)).isEmpty();

            release.countDown();
            held.get(30, TimeUnit.SECONDS);
            assertThat(count(PUBLICATION_KEYS, first.id())).isZero();
            assertThat(count(GRAPH_COUNTS, first.id())).isZero();
            for (String table : GRAPH_TABLES) assertThat(count(table, first.id())).isZero();
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void sealDetectsAnExtraNodeCommittedAfterTheCallerSnapshot() {
        var root = id("seal-after-node-insert");
        var entry = id("seal-after-node-entry");
        insertUnsealed(root, id("seal-after-node-quiz"), "Open graph", 0);
        insertNode(root, entry, 0, "TERMINAL", null);

        repeatableRead().executeWithoutResult(status -> {
            assertThat(count("quiz_scenario_nodes", root)).isOne();
            fresh().executeWithoutResult(writer -> insertNode(root, id("seal-after-node-extra"), 1, "TERMINAL", null));
            assertThat(count("quiz_scenario_nodes", root)).isOne();
            sqlRejected(() -> seal(root, entry));
        });
        assertThat(count("quiz_scenario_nodes", root)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT entry_node_id IS NULL FROM quiz_scenario_graphs" + WHERE_REVISION,
                Boolean.class, root.toString())).isTrue();
    }

    @Test void schemaRejectsLateWritesCrossRevisionReferencesAndPartialReads() {
        var revision = scenario("sealed-scenario", 2, CONDITION);
        publisher.publish(OWNER, revision);
        String root = revision.id().toString();
        sqlRejected(() -> sql.update("UPDATE quiz_scenario_nodes SET authored_position=99" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("DELETE FROM quiz_scenario_edges" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("DELETE FROM quiz_scenario_graphs" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("UPDATE quiz_revision_items SET authored_position=99" + WHERE_REVISION, root));
        sqlRejected(() -> sql.update("""
                INSERT INTO quiz_revision_items (id,quiz_revision_id,question_revision_id,authored_position)
                VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),99)
                """,
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
            assertThat(source.selects).hasValue(1);
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
        String conditionMarker = CONDITION.strip();
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

    private static QuizRevision terminalScenario(String key, UUID revisionId, UUID owner) {
        var terminal = new ScenarioGraph.Node(id(key + "-terminal"), 0, ScenarioGraph.NodeType.TERMINAL, null);
        return new QuizRevision(revisionId, id(key + "-quiz"), owner, QuizRevision.SCENARIO_FORMAT,
                "Terminal graph", null, CREATED, List.of(), List.of(), List.of(),
                new ScenarioGraph(1, terminal.id(), List.of(terminal), List.of()));
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
                edge.conditionVersion(), edge.condition().replace("FULL_CREDIT", "NOT_FULL_CREDIT"))).toList();
        return withScenario(revision, revision.items(), old.nodes(), edges);
    }

    private static QuizRevision withScenario(QuizRevision revision, List<QuizRevision.Item> items,
                                             List<ScenarioGraph.Node> nodes, List<ScenarioGraph.Edge> edges) {
        var old = revision.scenarioGraph();
        return new QuizRevision(revision.id(), revision.quizId(), revision.ownerId(), revision.schemaVersion(),
                revision.title(), revision.description(), revision.createdAt(), items, List.of(), List.of(),
                new ScenarioGraph(old.schemaVersion(), old.entryNodeId(), nodes, edges));
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
        var rows = sql.queryForList("""
                SELECT COALESCE(k.option_count, IF(EXISTS (
                    SELECT 1 FROM quiz_revision_items i WHERE i.question_revision_id=k.question_revision_id
                    AND i.scenario_content_evidence IS NOT NULL
                ), k.stored_option_count, NULL))
                FROM quiz_scenario_question_keys k WHERE k.question_revision_id=UUID_TO_BIN(?)
                """,
                Integer.class, questionId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<Integer> trackedOptionCounts(QuizRevision graph) {
        return sql.queryForList("SELECT stored_option_count FROM " + QUESTION_KEYS + " WHERE question_revision_id=UUID_TO_BIN(?)",
                Integer.class, legacyQuestion(graph).id().toString());
    }

    private static boolean hasReplayFingerprint(UUID revisionId) {
        return Boolean.TRUE.equals(sql.queryForObject(
                "SELECT replay_sha256 IS NOT NULL FROM " + PUBLICATION_KEYS + WHERE_REVISION,
                Boolean.class, revisionId.toString()));
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
        insertUnsealed(revisionId, quizId, title, items, 1, 0);
    }

    private static void insertUnsealed(UUID revisionId, UUID quizId, String title, int items, int nodes, int edges) {
        sql.update("INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at) "
                        + "VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),4,?,?)",
                revisionId.toString(), quizId.toString(), OWNER.toString(), title, Timestamp.from(CREATED));
        sql.update("INSERT INTO quiz_scenario_graphs (quiz_revision_id,schema_version,item_count,node_count,edge_count) "
                + "VALUES(UUID_TO_BIN(?),1,?,?,?)", revisionId.toString(), items, nodes, edges);
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
