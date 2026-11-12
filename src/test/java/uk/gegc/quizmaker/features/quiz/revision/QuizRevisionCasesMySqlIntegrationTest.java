package uk.gegc.quizmaker.features.quiz.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionCaseService;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionStructureService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionCaseServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionStructureServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;
import uk.gegc.quizmaker.features.attempt.application.FixedFormMaterializer;
import uk.gegc.quizmaker.features.attempt.application.impl.FixedFormMaterializerImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.FormOptionOrderServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.FormOptionOrderRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFixedFormRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFormOptionOrderRepository;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.application.impl.QuestionRevisionContractServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;
import static uk.gegc.quizmaker.features.quiz.revision.QuizRevisionCasesTest.*;

/** Actual #551/#552 storage, not attempt start, materialization or participant delivery. */
@Tag("db-serial")
class QuizRevisionCasesMySqlIntegrationTest {
    private static final String CATALOG = "revision_cases_" + UUID.randomUUID().toString().replace("-", "");
    private static final QuizRevision FLAT = quiz("pre-v111-flat", question("pre-v111-flat"));
    private static final QuizRevision SECTIONED = QuizRevisionSectionsTest.structured("pre-v111-sections");
    private static JdbcTemplate admin, sql;
    private static SectionTestDataSource source;
    private static DataSourceTransactionManager manager;
    private static QuizRevisionRepository repository;
    private static boolean created;
    private static Map<String, Object> flatBefore, sectionsBefore;
    private SimpleMeterRegistry metrics;
    private QuizRevisionCaseService publisher;
    private QuizRevisionStructureService reader;

    @BeforeAll static void migrate() {
        var port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + CATALOG + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"); created = true;
        source = new SectionTestDataSource(new DriverManagerDataSource(base + CATALOG + options, user, password));
        sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        restartRepository(); migration("110"); repository.insert(FLAT); repository.insert(SECTIONED);
        flatBefore = root(FLAT.id()); sectionsBefore = root(SECTIONED.id()); migration("111");
        sql.execute("CREATE TABLE case_test_bindings (id BINARY(16) PRIMARY KEY, revision_id BINARY(16) NOT NULL, "
                + "case_id BINARY(16) NOT NULL, FOREIGN KEY(revision_id,case_id) REFERENCES quiz_revision_cases(quiz_revision_id,id))");
    }
    @BeforeEach void start() {
        source.reset(); metrics = new SimpleMeterRegistry(); restartRepository();
        publisher = proxy(new QuizRevisionCaseServiceImpl(repository, metrics));
        reader = proxy(new QuizRevisionStructureServiceImpl(repository, metrics));
    }
    @AfterEach void reset() { source.reset(); metrics.close(); }
    @AfterAll static void cleanup() { if (created) admin.execute("DROP DATABASE " + CATALOG); }
    private static void restartRepository() { repository = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source))); }
    private static void migration(String target) { Flyway.configure().dataSource(source).locations("classpath:db/migration").target(target).load().migrate(); }

    @Test void upgradeAndRepeatRetainFlatAndSectionOnlyRowsWithoutInventingCases() {
        migration("111"); migration("111");
        for (var revision : List.of(FLAT, SECTIONED)) {
            var after = new HashMap<>(root(revision.id())); assertThat(after.remove("case_count")).isNull();
            assertThat(after).usingRecursiveComparison().isEqualTo(revision == FLAT ? flatBefore : sectionsBefore);
            assertThat(repository.findById(OWNER, revision.id())).contains(revision);
            assertThat(reader.read(OWNER, new RevisionReference(revision.id()))).contains(RevisionStructure.from(revision));
            assertThat(count("quiz_revision_cases", revision.id())).isZero();
        }
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='111' AND success=1", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForList("SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('quiz_revision_cases','quiz_revision_case_items')", String.class))
                .hasSize(3).doesNotContain("CASCADE");
    }

    @Test void invalidExistingVersionStopsAtomicUpgradeAndExplicitFixtureCorrectionAllowsRetry() throws Exception {
        String catalog = CATALOG + "_upgrade";
        var original = (DriverManagerDataSource) source.getTargetDataSource();
        var isolated = new DriverManagerDataSource(original.getUrl().replace(CATALOG, catalog), original.getUsername(), original.getPassword());
        admin.execute("CREATE DATABASE " + catalog + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        try {
            var flyway = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").target("110").load();
            flyway.migrate(); var check = new JdbcTemplate(isolated);
            // Deliberate out-of-contract administrator data tests migration refusal, not a runtime writer.
            try (var connection = isolated.getConnection(); var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE quiz_revisions ALTER CHECK chk_quiz_revision_version NOT ENFORCED");
                statement.execute("INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at) VALUES(UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),UUID_TO_BIN(UUID()),99,'Invalid fixture',UTC_TIMESTAMP(6))");
            }
            var upgrade = Flyway.configure().dataSource(isolated).locations("classpath:db/migration").target("111").load();
            assertThatThrownBy(upgrade::migrate).isInstanceOf(org.flywaydb.core.api.FlywayException.class);
            assertThat(check.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='quiz_revisions' AND COLUMN_NAME='case_count'", Integer.class)).isZero();
            assertThat(check.queryForObject("SELECT schema_version FROM quiz_revisions", Integer.class)).isEqualTo(99);
            check.update("UPDATE quiz_revisions SET schema_version=1"); // Explicit correction of this test's corrupt fixture.
            upgrade.repair(); upgrade.migrate();
            assertThat(check.queryForObject("SELECT COUNT(*) FROM quiz_revisions WHERE schema_version=1 AND case_count IS NULL", Integer.class)).isEqualTo(1);
        } finally { admin.execute("DROP DATABASE " + catalog); }
    }

    @ParameterizedTest @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void realPublicationReplayNewRevisionAndRestartKeepBoundCaseAExact(String isolation) {
        var a = cased("retained-a-" + isolation); var b = cased("retained-b-" + isolation);
        publisher.publish(OWNER, a); bind(a); publisher.publish(OWNER, b);
        restartRepository(); publisher = proxy(new QuizRevisionCaseServiceImpl(repository, metrics));
        var inserts = new java.util.concurrent.atomic.AtomicInteger();
        source.before = (statement, connection) -> { if (statement.startsWith("INSERT")) inserts.incrementAndGet(); };
        publisher.publish(OWNER, a); source.reset(); assertThat(inserts).hasValue(0);
        fresh(isolation).executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, a.id())).contains(a);
            assertThat(repository.findByIdForShare(OWNER, b.id())).contains(b);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM case_test_bindings WHERE revision_id=UUID_TO_BIN(?) AND case_id=UUID_TO_BIN(?)",
                    Integer.class, a.id().toString(), a.caseBlocks().get(0).id().toString())).isEqualTo(1);
            assertThat(count("quiz_revision_cases", a.id())).isEqualTo(a.caseBlocks().size());
        });
        var changed = changed(a);
        assertThatThrownBy(() -> publisher.publish(OWNER, changed)).hasMessageContaining("IDENTITY_CONFLICT").hasNoCause();
        assertThat(repository.findById(OWNER, a.id())).contains(a);
    }

    @Test void wrongOwnerMissingAndLegacyDoNotExposeCaseOrStimulusReferences() throws Exception {
        var revision = cased("privacy-db"); publisher.publish(OWNER, revision);
        source.selects.set(0);
        assertThat(reader.read(id("wrong-owner"), new RevisionReference(revision.id()))).isEmpty();
        assertThat(source.selects).hasValue(1);
        assertThat(reader.read(OWNER, new RevisionReference(id("missing-case-root")))).isEmpty();
        source.selects.set(0);
        assertThat(reader.read(OWNER, new RevisionReference(null))).contains(RevisionStructure.legacy());
        assertThat(source.selects).hasValue(0);
        assertThatThrownBy(() -> publisher.publish(id("wrong-owner"), cased("wrong-owner-write")))
                .hasMessageContaining("INVALID_CONTENT");
        assertThat(repository.findById(OWNER, id("wrong-owner-write"))).isEmpty();
        String safe = new ObjectMapper().writeValueAsString(reader.read(OWNER, new RevisionReference(revision.id())).orElseThrow());
        assertThat(safe).doesNotContain("stimulus", "correct", "Paris", revision.caseBlocks().get(0).stimulusRevisionId().toString());
    }

    @ParameterizedTest @ValueSource(strings = {"quiz_revision_cases", "quiz_revision_case_items"})
    void reachedWriteFailureRollsBackRootQuestionsSectionsCasesAndMembersThenRetrySucceeds(String table) {
        var revision = cased("rollback-case-" + table); var reached = new AtomicBoolean();
        source.after = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO " + table + " ") || statement.startsWith("INSERT INTO " + table + "\n")) {
                try (var check = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE quiz_revision_id=UUID_TO_BIN(?)")) {
                    check.setString(1, revision.id().toString());
                    try (var rows = check.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isPositive(); }
                }
                reached.set(true); throw new SQLException("Private stimulus SQL diagnostic", "45000");
            }
        };
        assertThatThrownBy(() -> publisher.publish(OWNER, revision)).hasMessageContaining("PERSISTENCE_UNAVAILABLE").hasNoCause();
        source.reset(); assertThat(reached).isTrue();
        assertAbsent(revision);
        publisher.publish(OWNER, revision);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test void enclosingConsumerFailureRollsBackPublicationAndItsStoredReferenceTogether() {
        var revision = cased("outer-rollback"); var reached = new AtomicBoolean();
        assertThatThrownBy(() -> fresh("REPEATABLE_READ").executeWithoutResult(status -> {
            publisher.publish(OWNER, revision); bind(revision);
            assertThat(count("quiz_revision_case_items", revision.id())).isPositive();
            reached.set(true); throw new IllegalStateException("Synthetic consumer failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reached).isTrue(); assertAbsent(revision);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM case_test_bindings WHERE revision_id=UUID_TO_BIN(?)", Integer.class, revision.id().toString())).isZero();
        fresh("READ_COMMITTED").executeWithoutResult(status -> { publisher.publish(OWNER, revision); bind(revision); });
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @ParameterizedTest @ValueSource(strings = {"quiz_revision_cases", "quiz_revision_case_items"})
    void realReadAndWriteFaultsAreSanitizedBeforeTransactionLogging(String table) {
        var revision = cased("diagnostics-" + table); publisher.publish(OWNER, revision);
        assertThat(reader.read(OWNER, new RevisionReference(revision.id()))).contains(RevisionStructure.from(revision));
        var reached = new AtomicBoolean(); String marker = "private-case-driver-diagnostic";
        var captured = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(TransactionInterceptor.class); var previous = logger.getLevel();
        captured.setContext(logger.getLoggerContext()); captured.start(); logger.addAppender(captured); logger.setLevel(Level.TRACE);
        try {
            source.before = (statement, connection) -> {
                if (statement.contains("FROM " + table + " ")) { reached.set(true); throw new SQLException(marker, "08006"); }
            };
            assertThatThrownBy(() -> reader.read(OWNER, new RevisionReference(revision.id()))).hasMessageContaining("PERSISTENCE_UNAVAILABLE").hasNoCause();
            assertThat(reached).isTrue(); source.reset(); reached.set(false);
            var write = cased("write-diagnostics-" + table);
            source.after = (statement, connection) -> {
                if (statement.startsWith("INSERT INTO " + table + " ") || statement.startsWith("INSERT INTO " + table + "\n")) {
                    reached.set(true); throw new SQLException(marker, "45000");
                }
            };
            assertThatThrownBy(() -> publisher.publish(OWNER, write)).hasMessageContaining("PERSISTENCE_UNAVAILABLE").hasNoCause();
            assertThat(reached).isTrue(); source.reset(); assertAbsent(write);
            assertThat(captured.list).isNotEmpty();
            assertThat(captured.list.stream().map(event -> event.getFormattedMessage() + (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()))).toList())
                    .allSatisfy(message -> assertThat(message).doesNotContain(marker));
            publisher.publish(OWNER, write);
            assertThat(reader.read(OWNER, new RevisionReference(write.id()))).contains(RevisionStructure.from(write));
        } finally { source.reset(); logger.detachAppender(captured); logger.setLevel(previous); captured.stop(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void concurrentPublicationHasACompleteWinnerAndDurableReplay(boolean conflicting) throws Exception {
        var a = cased("case-race-" + conflicting); var b = conflicting ? changed(a) : a;
        repository.insert(quiz("seed-race-" + conflicting, a.items().stream().map(QuizRevision.Item::question).toArray(QuestionRevision[]::new)));
        var barrier = new CyclicBarrier(2);
        source.before = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO quiz_revisions ")) try { barrier.await(15, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new SQLException("Case contender barrier failed", failure); }
        };
        var workers = Executors.newFixedThreadPool(2); List<Object> results;
        try {
            var one = workers.submit(() -> outcome(a)); var two = workers.submit(() -> outcome(b));
            results = List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
        } finally { workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); source.reset(); }
        assertThat(results.stream().filter(QuizRevision.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(RevisionIntegrityException.class::isInstance).count()).isEqualTo(1);
        var winner = (QuizRevision) results.stream().filter(QuizRevision.class::isInstance).findFirst().orElseThrow();
        startNewPublisher(); publisher.publish(OWNER, winner);
        fresh("READ_COMMITTED").executeWithoutResult(status -> {
            assertThat(repository.findByIdForShare(OWNER, winner.id())).contains(winner);
            assertThat(count("quiz_revision_cases", winner.id())).isEqualTo(winner.caseBlocks().size());
            assertThat(count("quiz_revision_case_items", winner.id())).isEqualTo(winner.items().size());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void currentReadsIncludeAllCasesAndMembersPublishedAfterAnOldSnapshot(String isolation) throws Exception {
        var revision = cased("case-snapshot-" + isolation);
        fresh(isolation).executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, revision.id())).isEmpty();
            var worker = Executors.newSingleThreadExecutor();
            try { worker.submit(() -> publisher.publish(OWNER, revision)).get(20, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
            finally { worker.shutdownNow(); try { assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); } }
            assertThat(repository.findByIdForShare(OWNER, revision.id())).contains(revision);
            if (isolation.equals("REPEATABLE_READ")) assertThat(repository.findById(OWNER, revision.id())).isEmpty();
        });
    }

    @Test void batchReadAndSerializationRemainSevenQueriesForManyParentsAndCases() throws Exception {
        for (int blocks : List.of(1, 12)) {
            var revisions = IntStream.range(0, 5).mapToObj(i -> cased("bounded-cases-" + blocks + "-" + i, blocks)).toList();
            revisions.forEach(revision -> publisher.publish(OWNER, revision)); source.selects.set(0);
            var loaded = repository.findByIds(OWNER, revisions.stream().map(QuizRevision::id).toList());
            assertThat(loaded).containsExactlyElementsOf(revisions);
            new ObjectMapper().findAndRegisterModules().writeValueAsString(loaded);
            assertThat(source.selects).hasValue(7);
            for (String table : List.of("quiz_revision_cases", "quiz_revision_case_items"))
                assertThat(sql.queryForList("EXPLAIN SELECT * FROM " + table + " WHERE quiz_revision_id=UUID_TO_BIN(?)", revisions.get(0).id().toString()))
                        .singleElement().satisfies(row -> assertThat(row.get("key")).isNotNull());
        }
        source.selects.set(0); assertThat(repository.findById(OWNER, FLAT.id())).contains(FLAT); assertThat(source.selects).hasValue(3);
        source.selects.set(0); assertThat(repository.findById(OWNER, SECTIONED.id())).contains(SECTIONED); assertThat(source.selects).hasValue(5);
    }

    @Test void actualFlatMaterializerSucceedsForOldContentAndCannotFlattenCaseEvidence() {
        var named = new NamedParameterJdbcTemplate(source);
        var registry = new QuestionContractRegistryImpl(List.of(new FamilyResponseContractV1(QuestionType.MCQ_SINGLE)),
                List.of(new FamilyScoringContractV1(QuestionType.MCQ_SINGLE)), metrics);
        QuestionContractBindingRepository bindings = proxy(new JdbcQuestionContractBindingRepository(named));
        var contracts = new QuestionRevisionContractServiceImpl(bindings, registry, manager);
        FixedFormRepository forms = proxy(new JdbcFixedFormRepository(named));
        FormOptionOrderRepository orders = proxy(new JdbcFormOptionOrderRepository(named));
        var ordering = new FormOptionOrderServiceImpl(repository, contracts, forms, orders, manager, metrics);
        var materializer = new FixedFormMaterializerImpl(repository, contracts, ordering, manager, metrics, forms);
        var flat = quiz("case-flat-control", question("case-flat-control")); var cases = cased("case-flat-denial");
        repository.insert(flat); publisher.publish(OWNER, cases);
        for (var revision : List.of(flat, cases)) contracts.bind(OWNER, revision.id(), revision.items().stream()
                .map(item -> BaselineQuestionContracts.binding(item.question())).toList());
        assertThat(materializer.materializeSelected(OWNER, flatCommand(flat)).items()).hasSize(1);
        int before = sql.queryForObject("SELECT COUNT(*) FROM attempt_form", Integer.class);
        assertThatThrownBy(() -> materializer.materializeSelected(OWNER, flatCommand(cases)))
                .isInstanceOf(FormMaterializationException.class).hasMessageContaining("INPUT_MISMATCH");
        fresh("READ_COMMITTED").executeWithoutResult(status -> {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form", Integer.class)).isEqualTo(before);
            assertThat(repository.findById(OWNER, cases.id())).contains(cases);
        });
    }

    @Test void databaseSealsCasesMembersRootAndSectionMembershipAgainstMutationOrLateInsertion() {
        var revision = cased("sealed"); publisher.publish(OWNER, revision);
        String root = revision.id().toString();
        for (String table : List.of("quiz_revision_cases", "quiz_revision_case_items")) {
            assertThatThrownBy(() -> sql.update("DELETE FROM " + table + " WHERE quiz_revision_id=UUID_TO_BIN(?)", root)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> sql.update("UPDATE " + table + " SET ordinal_slot=ordinal_slot WHERE quiz_revision_id=UUID_TO_BIN(?)", root)).isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> sql.update("UPDATE quiz_revisions SET case_count=case_count+1 WHERE id=UUID_TO_BIN(?)", root)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("UPDATE quiz_revisions SET schema_version=1,section_count=NULL,case_count=NULL WHERE id=UUID_TO_BIN(?)", root)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", root)).isInstanceOf(DataAccessException.class);
        var a = revision.caseBlocks().get(0);
        assertThatThrownBy(() -> insertCase(revision, block(a, id("late-case"), a.sectionId(), 100, a.itemIds()), 2, 2, 1))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> member(revision.id(), a.id(), a.sectionId(), a.itemIds().get(0), 2)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertCase(SECTIONED, a, 0, 2, 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertCase(FLAT, a, 0, 2, 1)).isInstanceOf(DataAccessException.class);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test void schemaRejectsForeignSectionMemberDuplicateOrderOverlapUnknownVersionAndBadSlots() {
        var revision = cased("constraints"); var a = revision.caseBlocks().get(0); var b = revision.caseBlocks().get(1);
        var foreign = cased("foreign-constraints"); publisher.publish(OWNER, foreign);
        fresh("READ_COMMITTED").executeWithoutResult(status -> {
            partial(revision, 3);
            assertThatThrownBy(() -> insertCase(revision, block(a, a.id(), id("foreign-section"), 0, a.itemIds()), 0, 2, 1)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertCase(revision, a, 0, 2, 2)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertCase(revision, a, 0, 0, 1)).isInstanceOf(DataAccessException.class);
            insertCase(revision, a, 0, 2, 1);
            assertThatThrownBy(() -> insertCase(revision, block(a, id("duplicate-position"), a.sectionId(), 0, a.itemIds()), 1, 2, 1)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insertCase(revision, b, 0, 2, 1)).isInstanceOf(DataAccessException.class);
            insertCase(revision, b, 1, 2, 1);
            assertThatThrownBy(() -> member(revision.id(), a.id(), a.sectionId(), b.itemIds().get(0), 0)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> member(revision.id(), a.id(), a.sectionId(), id("missing"), 0)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> member(revision.id(), a.id(), a.sectionId(), foreign.items().get(0).id(), 0)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> member(revision.id(), a.id(), b.sectionId(), b.itemIds().get(0), 0)).isInstanceOf(DataAccessException.class);
            member(revision.id(), a.id(), a.sectionId(), a.itemIds().get(0), 0);
            assertThatThrownBy(() -> member(revision.id(), a.id(), a.sectionId(), a.itemIds().get(1), 0)).isInstanceOf(DataAccessException.class);
            var overlap = block(a, id("overlap-case"), a.sectionId(), 9, a.itemIds()); insertCase(revision, overlap, 2, 2, 1);
            assertThatThrownBy(() -> member(revision.id(), overlap.id(), overlap.sectionId(), a.itemIds().get(0), 0)).isInstanceOf(DataAccessException.class);
            status.setRollbackOnly();
        });
        assertAbsent(revision);
    }

    @ParameterizedTest @ValueSource(strings = {"case", "member", "slot", "order"})
    void incompleteOrMisorderedAdministratorEvidenceFailsClosedWithoutRepair(String kind) {
        var revision = cased("corrupt-" + kind); var a = revision.caseBlocks().get(0); var b = revision.caseBlocks().get(1);
        partial(revision, 2);
        if (kind.equals("order")) {
            var first = block(a, a.id(), a.sectionId(), 3, List.of(a.itemIds().get(0)));
            var second = block(a, b.id(), a.sectionId(), 0, List.of(a.itemIds().get(1)));
            insertCase(revision, first, 0, 1, 1); insertCase(revision, second, 1, 1, 1);
            member(revision.id(), first.id(), first.sectionId(), first.itemIds().get(0), 0);
            member(revision.id(), second.id(), second.sectionId(), second.itemIds().get(0), 0);
        } else if (!kind.equals("case")) {
            insertCase(revision, a, kind.equals("slot") ? 1 : 0, 2, 1);
            member(revision.id(), a.id(), a.sectionId(), a.itemIds().get(0), 0);
            if (!kind.equals("member")) member(revision.id(), a.id(), a.sectionId(), a.itemIds().get(1), 1);
        }
        assertThatThrownBy(() -> repository.findById(OWNER, revision.id())).isInstanceOf(RevisionIntegrityException.class);
        assertThatThrownBy(() -> publisher.publish(OWNER, revision)).isInstanceOf(RevisionIntegrityException.class);
        assertThat(count("quiz_revision_cases", revision.id())).isLessThanOrEqualTo(2);
    }

    private void startNewPublisher() { restartRepository(); publisher = proxy(new QuizRevisionCaseServiceImpl(repository, metrics)); }
    private Object outcome(QuizRevision revision) {
        try { publisher.publish(OWNER, revision); return revision; } catch (RevisionIntegrityException failure) { return failure; }
    }
    private static QuizRevision changed(QuizRevision revision) {
        var blocks = revision.caseBlocks().stream().map(a -> new RevisionCaseBlock(a.id(), 1, a.sectionId(), a.authoredPosition(),
                id("new-stimulus-" + a.id()), !a.atomic(), a.itemIds())).toList();
        return withCases(revision, revision.sections(), blocks);
    }
    private static Map<String, Object> root(UUID id) { return sql.queryForMap("SELECT * FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", id.toString()); }
    private static int count(String table, UUID root) { return sql.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE quiz_revision_id=UUID_TO_BIN(?)", Integer.class, root.toString()); }
    private static void bind(QuizRevision revision) {
        sql.update("INSERT INTO case_test_bindings VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?))", UUID.randomUUID().toString(), revision.id().toString(), revision.caseBlocks().get(0).id().toString());
    }
    private static void assertAbsent(QuizRevision revision) {
        fresh("REPEATABLE_READ").executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, revision.id())).isEmpty();
            for (String table : List.of("quiz_revision_items", "quiz_revision_sections", "quiz_revision_section_items", "quiz_revision_cases", "quiz_revision_case_items"))
                assertThat(count(table, revision.id())).isZero();
            for (var item : revision.items()) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_revisions WHERE id=UUID_TO_BIN(?)", Integer.class, item.question().id().toString())).isZero();
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_option_revisions WHERE question_revision_id=UUID_TO_BIN(?)", Integer.class, item.question().id().toString())).isZero();
            }
        });
    }
    /** Deliberately partial administrator fixture for schema/corruption tests, not publication evidence. */
    private static void partial(QuizRevision revision, int count) {
        repository.insert(quiz("seed-" + revision.id(), revision.items().stream().map(QuizRevision.Item::question).toArray(QuestionRevision[]::new)));
        sql.update("INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at,section_count,case_count) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),3,'Partial fixture',?,?,?)",
                revision.id().toString(), revision.quizId().toString(), OWNER.toString(), Timestamp.from(CREATED), revision.sections().size(), count);
        for (var item : revision.items()) sql.update("INSERT INTO quiz_revision_items(id,quiz_revision_id,question_revision_id,authored_position) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)",
                item.id().toString(), revision.id().toString(), item.question().id().toString(), item.authoredPosition());
        for (int index = 0; index < revision.sections().size(); index++) {
            var s = revision.sections().get(index);
            sql.update("INSERT INTO quiz_revision_sections(quiz_revision_id,id,schema_version,ordinal_slot,authored_position,title,item_count) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?,'Section',?)",
                    revision.id().toString(), s.id().toString(), index, s.authoredPosition(), s.itemIds().size());
            for (int slot = 0; slot < s.itemIds().size(); slot++) sql.update("INSERT INTO quiz_revision_section_items VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)",
                    revision.id().toString(), s.id().toString(), s.itemIds().get(slot).toString(), slot);
        }
    }
    private static void insertCase(QuizRevision revision, RevisionCaseBlock block, int slot, int count, int version) {
        sql.update("INSERT INTO quiz_revision_cases VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,?,?,UUID_TO_BIN(?),?,?)",
                revision.id().toString(), block.id().toString(), block.sectionId().toString(), version, slot, block.authoredPosition(), block.stimulusRevisionId().toString(), block.atomic(), count);
    }
    private static void member(UUID root, UUID block, UUID section, UUID item, int slot) {
        sql.update("INSERT INTO quiz_revision_case_items VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?)",
                root.toString(), block.toString(), section.toString(), item.toString(), slot);
    }
    private static TransactionTemplate fresh(String isolation) {
        var tx = new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(isolation.equals("REPEATABLE_READ") ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED); return tx;
    }
    private static FixedFormMaterializer.Command flatCommand(QuizRevision revision) {
        var selected = new QuestionSelectionServiceImpl(request -> { throw new AssertionError("ALL needs no seed"); }).select(
                new QuestionSelection.Input(1, revision.id(), 1, QuestionSelection.Configuration.all(), revision.items().stream()
                        .map(i -> new QuestionSelection.Item(i.id(), i.authoredPosition())).toList(), List.of()));
        var order = AuthoredQuestionOrder.order(new AuthoredQuestionOrder.Input(1, revision.id(), 1,
                new ResolvedAttemptPolicy.QuestionOrderingPolicy(ResolvedAttemptPolicy.QuestionOrder.AUTHORED, 1),
                revision.items().stream().map(i -> new AuthoredQuestionOrder.Item(i.id(), i.authoredPosition())).toList(), selected.itemIds()));
        var form = new FixedForm(id("case-form-" + revision.id()), 1, revision.id(), IntStream.range(0, order.itemIds().size())
                .mapToObj(i -> new FixedForm.Item(id("case-form-item-" + revision.id() + "-" + i), order.itemIds().get(i), i)).toList());
        return new FixedFormMaterializer.Command(form, selected, order,
                new ResolvedAttemptPolicy.AnswerOrderingPolicy(ResolvedAttemptPolicy.AnswerOrder.AUTHORED, 1));
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(T target) {
        var factory = new ProxyFactory(target); factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource())); return (T) factory.getProxy();
    }
}
