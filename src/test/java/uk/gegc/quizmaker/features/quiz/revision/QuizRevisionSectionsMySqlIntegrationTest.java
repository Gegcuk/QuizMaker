package uk.gegc.quizmaker.features.quiz.revision;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts;
import uk.gegc.quizmaker.features.question.application.contract.v1.FamilyResponseContractV1;
import uk.gegc.quizmaker.features.question.application.contract.v1.FamilyScoringContractV1;
import uk.gegc.quizmaker.features.question.application.impl.QuestionContractRegistryImpl;
import uk.gegc.quizmaker.features.question.application.impl.QuestionRevisionContractServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionStructureService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionStructureServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;
import static uk.gegc.quizmaker.features.quiz.revision.QuizRevisionSectionsTest.*;

/** Real storage/migration/adapter proof. No author HTTP, section runtime or provider is simulated. */
@Tag("db-serial")
class QuizRevisionSectionsMySqlIntegrationTest {
    private static final String CATALOG = "revision_sections_" + UUID.randomUUID().toString().replace("-", "");
    private static JdbcTemplate admin, sql;
    private static SectionTestDataSource source;
    private static DataSourceTransactionManager manager;
    private static QuizRevisionRepository repository;
    private static boolean created;
    private static Map<String, Object> flatBefore;
    private static final QuizRevision FLAT = quiz("pre-v110", question("pre-v110"));
    private SimpleMeterRegistry metrics;
    private QuizRevisionStructureService reader;

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
        sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        repository = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)));
        migration("109");
        repository.insert(FLAT);
        flatBefore = sql.queryForMap("SELECT * FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", FLAT.id().toString());
        migration("110");
    }

    @BeforeEach void prepare() {
        source.reset(); metrics = new SimpleMeterRegistry();
        reader = proxy(new QuizRevisionStructureServiceImpl(repository, metrics));
    }
    @AfterEach void reset() { source.reset(); metrics.close(); }
    @AfterAll static void dropOnlyOwnedCatalog() { if (created) admin.execute("DROP DATABASE " + CATALOG); }
    private static void migration(String target) { Flyway.configure().dataSource(source).locations("classpath:db/migration").target(target).load().migrate(); }

    @Test void upgradeAndRepeatPreserveFlatEvidenceWithoutBackfill() {
        migration("110"); migration("110");
        var after = new HashMap<>(sql.queryForMap("SELECT * FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", FLAT.id().toString()));
        assertThat(after.remove("section_count")).isNull();
        assertThat(after).usingRecursiveComparison().isEqualTo(flatBefore);
        assertThat(repository.findById(OWNER, FLAT.id())).contains(FLAT);
        assertThat(reader.read(OWNER, new RevisionReference(FLAT.id()))).contains(RevisionStructure.from(FLAT));
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_revision_sections WHERE quiz_revision_id=UUID_TO_BIN(?)", Integer.class, FLAT.id().toString())).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='110' AND success=1", Integer.class)).isEqualTo(1);
        assertThat(sql.queryForList("SELECT DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('quiz_revision_sections','quiz_revision_section_items')", String.class))
                .hasSize(3).doesNotContain("CASCADE");
    }

    @ParameterizedTest @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void exactStructureSurvivesFreshTransactionsRestartAndLaterAuthorRevision(String isolation) {
        var a = structured("snapshot-a-" + isolation); repository.insert(a);
        var original = reader.read(OWNER, new RevisionReference(a.id())).orElseThrow();
        var b = new QuizRevision(id("snapshot-b-" + isolation), a.quizId(), a.ownerId(), 2, a.title(), a.description(),
                a.createdAt(), a.items(), a.sections().stream().map(s -> new RevisionSection(s.id(), 1,
                s.authoredPosition(), "Edited " + s.title(), "New instructions", s.itemIds())).toList());
        repository.insert(b);
        QuizRevisionRepository restarted = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)));
        fresh(isolation).executeWithoutResult(status -> {
            assertThat(restarted.findByIdForShare(OWNER, a.id())).contains(a);
            assertThat(restarted.findByIds(OWNER, List.of(a.id(), b.id()))).containsExactly(a, b);
        });
        QuizRevisionStructureService restartedReader = proxy(new QuizRevisionStructureServiceImpl(restarted, metrics));
        assertThat(restartedReader.read(OWNER, new RevisionReference(a.id()))).contains(original);
        assertThat(original.sections()).extracting(RevisionStructure.Section::authoredPosition).containsExactly(2, 9);
        assertThat(sql.queryForObject("SELECT section_count FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", Integer.class, a.id().toString())).isEqualTo(2);
    }

    @Test void wrongOwnerMissingAndLegacyNeverRevealOrReconstructStructure() {
        var revision = structured("owner"); repository.insert(revision);
        assertThat(reader.read(OWNER, new RevisionReference(revision.id()))).contains(RevisionStructure.from(revision));
        assertThat(reader.read(id("another-owner"), new RevisionReference(revision.id()))).isEmpty();
        assertThat(reader.read(OWNER, new RevisionReference(id("missing")))).isEmpty();
        source.selects.set(0);
        assertThat(reader.read(OWNER, new RevisionReference(null))).contains(RevisionStructure.legacy());
        assertThat(source.selects).hasValue(0);
        assertThat(repository.findByIds(id("another-owner"), List.of(revision.id(), FLAT.id()))).isEmpty();
    }

    @Test void databaseRejectsPublishedChangesDowngradeLateSectionsAndLateMembership() {
        var revision = structured("sealed"); repository.insert(revision);
        String root = revision.id().toString();
        for (String statement : List.of(
                "UPDATE quiz_revisions SET section_count=3 WHERE id=UUID_TO_BIN(?)",
                "UPDATE quiz_revisions SET schema_version=1,section_count=NULL WHERE id=UUID_TO_BIN(?)",
                "DELETE FROM quiz_revisions WHERE id=UUID_TO_BIN(?)",
                "UPDATE quiz_revision_sections SET authored_position=100 WHERE quiz_revision_id=UUID_TO_BIN(?)",
                "UPDATE quiz_revision_sections SET title='Changed' WHERE quiz_revision_id=UUID_TO_BIN(?)",
                "DELETE FROM quiz_revision_sections WHERE quiz_revision_id=UUID_TO_BIN(?)",
                "UPDATE quiz_revision_section_items SET ordinal_slot=100 WHERE quiz_revision_id=UUID_TO_BIN(?)",
                "DELETE FROM quiz_revision_section_items WHERE quiz_revision_id=UUID_TO_BIN(?)"))
            assertThatThrownBy(() -> sql.update(statement, root)).isInstanceOf(DataAccessException.class).hasMessageContaining("immutable");
        assertThatThrownBy(() -> insertSection(revision.id(), id("late"), 2, 20, 0)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSection(revision.id(), id("occupied"), 0, 20, 0)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSection(FLAT.id(), id("attach-to-flat"), 0, 0, 0)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO quiz_revision_section_items(quiz_revision_id,section_id,item_id,ordinal_slot)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1)
                """, root, revision.sections().get(0).id().toString(), revision.items().get(1).id().toString())).isInstanceOf(DataAccessException.class);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test void schemaRejectsForeignItemsUnknownVersionsDuplicateOrderAndMissingRoot() {
        var a = structured("fk-a"); var b = structured("fk-b"); repository.insert(a); repository.insert(b);
        UUID partial = id("fk-partial");
        insertRoot(partial, 2, 2);
        insertSection(partial, id("fk-section"), 0, 0, 1);
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO quiz_revision_section_items(quiz_revision_id,section_id,item_id,ordinal_slot)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),0)
                """, partial.toString(), id("fk-section").toString(), b.items().get(0).id().toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSection(partial, id("duplicate-position"), 1, 0, 0)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertSection(id("missing-root"), id("no-parent"), 0, 0, 0)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertRoot(id("unknown-version"), 3, 1)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO quiz_revision_sections(quiz_revision_id,id,schema_version,ordinal_slot,authored_position,title,item_count)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),2,1,1,'Unknown',0)
                """, partial.toString(), id("unknown-section-version").toString())).isInstanceOf(DataAccessException.class);
        assertCorrupt(partial); // An incomplete administrator fixture is never served as a complete publication.
        assertThat(repository.findById(OWNER, a.id())).contains(a);
    }

    @ParameterizedTest @ValueSource(strings = {"quiz_revision_sections", "quiz_revision_section_items"})
    void reachedWriteFailureRollsBackWholeRevisionThenSameInputSucceeds(String table) {
        var revision = structured("rollback-" + table); var reached = new AtomicBoolean();
        source.after = (statement, connection) -> {
            if (statement.stripLeading().startsWith("INSERT INTO " + table)) {
                try (var check = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE quiz_revision_id=UUID_TO_BIN(?)")) {
                    check.setString(1, revision.id().toString());
                    try (var rows = check.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isPositive(); }
                }
                reached.set(true);
                throw new SQLException("Synthetic section write checkpoint", "45000");
            }
        };
        assertThatThrownBy(() -> repository.insert(revision)).isInstanceOf(DataAccessException.class);
        source.reset(); assertThat(reached).isTrue();
        fresh("REPEATABLE_READ").executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, revision.id())).isEmpty();
            for (String name : List.of("quiz_revision_items", "quiz_revision_sections", "quiz_revision_section_items"))
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + name + " WHERE quiz_revision_id=UUID_TO_BIN(?)", Integer.class, revision.id().toString())).isZero();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_revisions WHERE id=UUID_TO_BIN(?)", Integer.class,
                    revision.items().get(0).question().id().toString())).isZero();
        });
        repository.insert(revision);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test void twoConcurrentCompletePublicationsHaveOneSuccessfulWinnerWithoutMixedStructure() throws Exception {
        var a = structured("race");
        // Pre-existing shared questions avoid conflating the section-root race with question creation.
        repository.insert(quiz("race-questions", a.items().stream().map(QuizRevision.Item::question).toArray(QuestionRevision[]::new)));
        var renamed = a.sections().stream().map(s -> new RevisionSection(s.id(), 1, s.authoredPosition(), "Other " + s.title(), s.instructions(), s.itemIds())).toList();
        var b = withSections(a, renamed); var barrier = new CyclicBarrier(2);
        source.before = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO quiz_revisions ")) try { barrier.await(15, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new SQLException("Section contender barrier failed", failure); }
        };
        var workers = Executors.newFixedThreadPool(2);
        List<Object> results;
        try {
            var one = workers.submit(() -> insertOutcome(a)); var two = workers.submit(() -> insertOutcome(b));
            results = List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow(); assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); source.reset();
        }
        assertThat(results.stream().filter(QuizRevision.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(DataAccessException.class::isInstance).count()).isEqualTo(1);
        var winner = (QuizRevision) results.stream().filter(QuizRevision.class::isInstance).findFirst().orElseThrow();
        fresh("READ_COMMITTED").executeWithoutResult(status -> assertThat(repository.findByIdForShare(OWNER, a.id())).contains(winner));
    }

    @ParameterizedTest @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void currentStructureReadSeesRevisionCommittedAfterOldCallerSnapshot(String isolation) throws Exception {
        var revision = structured("old-snapshot-" + isolation);
        fresh(isolation).executeWithoutResult(status -> {
            assertThat(repository.findById(OWNER, revision.id())).isEmpty();
            var worker = Executors.newSingleThreadExecutor();
            try { worker.submit(() -> repository.insert(revision)).get(20, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new AssertionError(failure); }
            finally {
                worker.shutdownNow();
                try { assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
            assertThat(repository.findByIdForShare(OWNER, revision.id())).contains(revision);
            if (isolation.equals("REPEATABLE_READ")) assertThat(repository.findById(OWNER, revision.id())).isEmpty();
        });
    }

    @Test void multiParentSerializationUsesFiveQueriesAndFlatReadsStillUseThree() throws Exception {
        var revisions = IntStream.range(0, 5).mapToObj(i -> structured("bounded-" + i)).toList();
        revisions.forEach(repository::insert);
        source.selects.set(0);
        assertThat(repository.findById(OWNER, revisions.get(0).id())).contains(revisions.get(0));
        assertThat(source.selects).hasValue(5);
        source.selects.set(0);
        var read = repository.findByIds(OWNER, revisions.stream().map(QuizRevision::id).toList());
        assertThat(read).containsExactlyElementsOf(revisions);
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(read)).contains("Theory", "Practice");
        assertThat(source.selects).hasValue(5);
        source.selects.set(0);
        assertThat(repository.findById(OWNER, FLAT.id())).contains(FLAT);
        assertThat(source.selects).hasValue(3);
        var plan = sql.queryForList("EXPLAIN SELECT * FROM quiz_revision_sections WHERE quiz_revision_id=UUID_TO_BIN(?) ORDER BY ordinal_slot", revisions.get(0).id().toString());
        assertThat(plan).singleElement().satisfies(row -> assertThat(row.get("key")).isIn("PRIMARY", "uq_revision_section_slot", "uq_revision_section_position"));
        assertThat(sql.queryForList("EXPLAIN SELECT * FROM quiz_revision_section_items WHERE quiz_revision_id=UUID_TO_BIN(?) ORDER BY section_id,ordinal_slot", revisions.get(0).id().toString()))
                .singleElement().satisfies(row -> assertThat(row.get("key")).isIn("PRIMARY", "uq_revision_section_item_slot"));
    }

    @Test void realFlatMaterializationSucceedsButCannotReinterpretASectionedRevision() {
        var named = new NamedParameterJdbcTemplate(source);
        var registry = new QuestionContractRegistryImpl(
                List.of(new FamilyResponseContractV1(
                        QuestionType.MCQ_SINGLE)),
                List.of(new FamilyScoringContractV1(
                        QuestionType.MCQ_SINGLE)), metrics);
        QuestionContractBindingRepository bindings = proxy(
                new JdbcQuestionContractBindingRepository(named));
        var contracts = new QuestionRevisionContractServiceImpl(bindings, registry, manager);
        FixedFormRepository forms = proxy(
                new JdbcFixedFormRepository(named));
        FormOptionOrderRepository orders = proxy(
                new JdbcFormOptionOrderRepository(named));
        var ordering = new FormOptionOrderServiceImpl(
                repository, contracts, forms, orders, manager, metrics);
        var materializer = new FixedFormMaterializerImpl(
                repository, contracts, ordering, manager, metrics, forms);
        var flat = quiz("materialization-control", question("materialization-control"));
        var sectioned = structured("materialization-sectioned");
        for (var revision : List.of(flat, sectioned)) {
            repository.insert(revision);
            contracts.bind(OWNER, revision.id(), revision.items().stream().map(item ->
                    BaselineQuestionContracts.binding(item.question())).toList());
        }
        assertThat(materializer.materializeSelected(OWNER, flatCommand(flat)).items()).hasSize(1);
        int before = sql.queryForObject("SELECT COUNT(*) FROM attempt_form", Integer.class);
        assertThatThrownBy(() -> materializer.materializeSelected(OWNER, flatCommand(sectioned)))
                .isInstanceOf(FormMaterializationException.class)
                .hasMessageContaining("INPUT_MISMATCH");
        fresh("READ_COMMITTED").executeWithoutResult(status -> {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_form", Integer.class)).isEqualTo(before);
            assertThat(repository.findById(OWNER, sectioned.id())).contains(sectioned);
        });
    }

    private static FixedFormMaterializer.Command flatCommand(QuizRevision revision) {
        var selected = new QuestionSelectionServiceImpl(request -> { throw new AssertionError("ALL needs no seed"); }).select(
                new QuestionSelection.Input(1, revision.id(), 1,
                        QuestionSelection.Configuration.all(),
                        revision.items().stream().map(i -> new QuestionSelection.Item(i.id(), i.authoredPosition())).toList(), List.of()));
        var order = AuthoredQuestionOrder.order(
                new AuthoredQuestionOrder.Input(1, revision.id(), 1,
                        new ResolvedAttemptPolicy.QuestionOrderingPolicy(
                                ResolvedAttemptPolicy.QuestionOrder.AUTHORED, 1),
                        revision.items().stream().map(i -> new AuthoredQuestionOrder.Item(i.id(), i.authoredPosition())).toList(), selected.itemIds()));
        var form = new FixedForm(id("form-" + revision.id()), 1, revision.id(),
                IntStream.range(0, order.itemIds().size()).mapToObj(i -> new FixedForm.Item(
                        id("form-item-" + revision.id() + "-" + i), order.itemIds().get(i), i)).toList());
        return new FixedFormMaterializer.Command(form, selected, order,
                new ResolvedAttemptPolicy.AnswerOrderingPolicy(
                        ResolvedAttemptPolicy.AnswerOrder.AUTHORED, 1));
    }

    private static Object insertOutcome(QuizRevision revision) {
        try { repository.insert(revision); return revision; }
        catch (DataAccessException failure) { return failure; }
    }
    private static void insertRoot(UUID id, int version, int count) {
        sql.update("INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at,section_count) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,'Partial administrator fixture',?,?)",
                id.toString(), UUID.randomUUID().toString(), OWNER.toString(), version, java.sql.Timestamp.from(CREATED), count);
    }
    private static void insertSection(UUID root, UUID section, int slot, int position, int count) {
        sql.update("INSERT INTO quiz_revision_sections(quiz_revision_id,id,schema_version,ordinal_slot,authored_position,title,item_count) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),1,?,?,'Section',?)",
                root.toString(), section.toString(), slot, position, count);
    }
    private static void assertCorrupt(UUID root) {
        assertThatThrownBy(() -> repository.findById(OWNER, root)).isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("INVALID_STORED_REVISION");
    }
    private static TransactionTemplate fresh(String isolation) {
        var tx = new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(isolation.equals("REPEATABLE_READ") ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(T target) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }
}
