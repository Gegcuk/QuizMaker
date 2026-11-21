package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.infra.repository.*;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.LearningFormTest.rejected;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.Reason.*;

/** Real #597/#575/#647 writers and MySQL transactions. Source presentation/access are explicit
 * immutable contract fixtures, not proof of public Learning selection/access/start/review. */
@Tag("db-serial")
class LearningFormMySqlIntegrationTest {
    static final String SCHEMA = "learning_form_" + UUID.randomUUID().toString().replace("-", "");
    static final UUID ANNA = UUID.randomUUID(), BOB = UUID.randomUUID();
    static final Instant TIME = LearningFormTest.TIME;
    static JdbcTemplate admin, sql;
    static ObservedSource source;
    static DataSourceTransactionManager manager;
    static QuizRevision revision;
    static Map<String, List<Map<String, Object>>> retained;
    static boolean created;
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final AtomicBoolean access = new AtomicBoolean(true), contentAvailable = new AtomicBoolean(true);
    final AtomicInteger contentReads = new AtomicInteger();
    final Map<UUID, QuizRevision> sources = new ConcurrentHashMap<>();
    LearningSessionService sessions;
    JdbcLearningFormRepository repository;
    LearningFormService forms;

    @BeforeAll static void database() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        created = true;
        source = new ObservedSource(new DriverManagerDataSource(base + SCHEMA + options, user, password));
        sql = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        Flyway.configure().dataSource(source).target("118").load().migrate();
        for (var entry : Map.of(ANNA, "anna", BOB, "bob").entrySet()) sql.update("""
            INSERT INTO users(user_id,username,email,password,is_active,is_deleted,email_verified,created_at,password_changed_at,version)
            VALUES(UUID_TO_BIN(?),?,?,'not-a-login',1,0,0,'2026-01-01','2026-01-01',0)
            """, entry.getKey().toString(), entry.getValue(), entry.getValue() + "@example.invalid");
        revision = revision(UUID.randomUUID(), 3);
        legacyFixture(); retained = retained();
        Flyway.configure().dataSource(source).load().migrate();
    }
    @AfterAll static void drop() { if (created) admin.execute("DROP DATABASE " + SCHEMA); }
    @BeforeEach void setup() {
        source.reset(); sources.put(revision.id(), revision);
        repository = new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql));
        AuthenticatedUserLookup users = new AuthenticatedUserLookup() {
            @Override public Optional<UUID> findActiveUserId(String name) { throw new AssertionError("locking identity required"); }
            @Override public Optional<UUID> lockActiveUserId(String name) {
                return sql.query("SELECT BIN_TO_UUID(user_id) FROM users WHERE username=? AND is_active=TRUE AND is_deleted=FALSE FOR SHARE",
                        (r, i) -> UUID.fromString(r.getString(1)), name).stream().findFirst();
            }
        };
        sessions = new LearningSessionServiceImpl(new JdbcLearningSessionRepository(new NamedParameterJdbcTemplate(sql)), (learner, recorded) -> {}, users,
                List.of((learner, ref) -> access.get() && sources.containsKey(ref.quizRevisionId())), Clock.fixed(TIME, ZoneOffset.UTC), metrics, manager);
        forms = forms(repository);
        login("anna");
    }
    @AfterEach void clean() {
        source.reset(); SecurityContextHolder.clearContext();
        sql.update("DELETE FROM learning_form_option"); sql.update("DELETE FROM learning_form_item"); sql.update("DELETE FROM learning_form");
        sql.update("DELETE FROM learning_session_create_receipt"); sql.update("DELETE FROM learning_session");
        sql.update("UPDATE users SET is_active=TRUE,is_deleted=FALSE WHERE username IN ('anna','bob')");
        assertThat(retained()).isEqualTo(retained);
        metrics.close();
    }
    LearningFormService forms(JdbcLearningFormRepository store) {
        return new LearningFormServiceImpl(sessions, store, List.of(this::content), Clock.fixed(TIME, ZoneOffset.UTC), metrics, manager);
    }
    List<LearningFormContent.PresentedItem> content(LearningSession session, List<LearningForm.Item> items) {
        contentReads.incrementAndGet();
        if (!contentAvailable.get()) throw new IllegalStateException("private-source-answer");
        var saved = sources.get(session.source().quizRevisionId());
        return items.stream().map(item -> {
            var q = saved.items().stream().filter(i -> i.id().equals(item.sourceItemId())).findFirst().orElseThrow().question();
            if (!item.response().equals(new ContractVersion("question.mcq-single.response", 1))) throw new IllegalArgumentException("unknown contract");
            var options = item.optionIds().stream().map(id -> new ParticipantContent.Option(id, "Option " + q.options().stream()
                    .filter(o -> o.id().equals(id)).findFirst().orElseThrow().authoredPosition())).toList();
            return new LearningFormContent.PresentedItem(item, q.questionText(), new ParticipantContent.Choices(options));
        }).toList();
    }
    LearningSession session(QuizRevision rev) {
        sources.put(rev.id(), rev);
        return sessions.create(new LearningSessionService.Create(1, UUID.randomUUID(),
                new LearningSession.Source(1, rev.quizId(), rev.id(), rev.schemaVersion()), FsrsManifest.approved())).session();
    }
    List<LearningForm.Item> items(QuizRevision rev) {
        return rev.items().stream().map(i -> new LearningForm.Item(i.id(), i.question().id(), 1, new ContractVersion("question.mcq-single.response", 1),
                i.question().options().stream().sorted(Comparator.comparingInt(QuestionRevision.Option::authoredPosition).reversed())
                        .map(QuestionRevision.Option::id).toList())).toList();
    }
    LearningFormService.Materialize command(LearningSession s, List<LearningForm.Item> items) {
        return new LearningFormService.Materialize(1, s.id(), items);
    }

    @Test void exactIdentityContentAndOptionOrderSurviveNewRevisionAndComponentRestart() throws Exception {
        var s = session(revision); var command = command(s, items(revision));
        var first = forms.materialize(command);
        assertThat(first.replayed()).isFalse();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(first.view());
        assertThat(json).doesNotContain("private", "correct", "explanation", "source_key", "x".repeat(30));
        assertThat(first.view().form().items()).containsExactlyElementsOf(command.items());
        assertThat(first.view().items().get(0).content()).isEqualTo(new ParticipantContent.Choices(List.of(
                new ParticipantContent.Option(command.items().get(0).optionIds().get(0), "Option 1"),
                new ParticipantContent.Option(command.items().get(0).optionIds().get(1), "Option 0"))));
        var later = revision(revision.quizId(), 4); sources.put(later.id(), later);
        var restarted = forms(new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql)));
        assertThat(restarted.read(s.id())).isEqualTo(first.view());
        assertThat(restarted.materialize(command)).isEqualTo(new LearningFormService.Result(first.view(), true));
        rejected(() -> restarted.materialize(command(s, List.of())), CONFLICT);
        assertCounts(1, 3, 6);
        assertThat(sessions.read(s.id())).isEqualTo(s);
    }

    @Test void emptyIsDurableAndDifferentFromNotYetMaterialized() {
        var s = session(revision);
        rejected(() -> forms.read(s.id()), UNAVAILABLE);
        var empty = forms.materialize(command(s, List.of()));
        assertThat(empty.view().form().outcome()).isEqualTo(LearningForm.Outcome.EMPTY_COMPLETE);
        assertThat(empty.view().items()).isEmpty();
        assertThat(forms(repository).materialize(command(s, List.of())).replayed()).isTrue();
        rejected(() -> forms.materialize(command(s, items(revision))), CONFLICT);
        assertCounts(1, 0, 0);
        assertThat(sessions.read(s.id()).status()).isEqualTo(LearningSession.Status.CREATED);
    }

    @Test void actualMembershipContractsOptionsAndCurrentAccessAreRequired() {
        var s = session(revision); var selected = items(revision); var item = selected.get(0);
        for (var wrong : List.of(
                new LearningForm.Item(UUID.randomUUID(), item.questionRevisionId(), 1, item.response(), item.optionIds()),
                new LearningForm.Item(item.sourceItemId(), UUID.randomUUID(), 1, item.response(), item.optionIds()),
                new LearningForm.Item(item.sourceItemId(), item.questionRevisionId(), 1, new ContractVersion("question.mcq-single.response", 2), item.optionIds()),
                new LearningForm.Item(item.sourceItemId(), item.questionRevisionId(), 1, item.response(), List.of()))) {
            rejected(() -> forms.materialize(command(s, List.of(wrong))), SOURCE_UNAVAILABLE); assertCounts(0, 0, 0);
        }
        rejected(() -> forms.materialize(command(s, List.of(item, item))), INVALID_INPUT);
        sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=2 WHERE question_revision_id=UUID_TO_BIN(?)", item.questionRevisionId().toString());
        var unknown = new LearningForm.Item(item.sourceItemId(), item.questionRevisionId(), 1, new ContractVersion("question.mcq-single.response", 2), item.optionIds());
        rejected(() -> forms.materialize(command(s, List.of(unknown))), SOURCE_UNAVAILABLE);
        sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=1 WHERE question_revision_id=UUID_TO_BIN(?)", item.questionRevisionId().toString());
        contentAvailable.set(false);
        rejected(() -> forms.materialize(command(s, selected)), SOURCE_UNAVAILABLE); assertCounts(0, 0, 0);
        contentAvailable.set(true);
        var first = forms.materialize(command(s, selected));
        sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=2 WHERE question_revision_id=UUID_TO_BIN(?)",
                item.questionRevisionId().toString());
        rejected(() -> forms.materialize(command(s, selected)), CORRUPT);
        sql.update("UPDATE question_revision_contract_bindings SET response_contract_version=1 WHERE question_revision_id=UUID_TO_BIN(?)",
                item.questionRevisionId().toString());
        assertThat(forms.materialize(command(s, selected)).view()).isEqualTo(first.view());
        access.set(false);
        assertThatThrownBy(() -> forms.read(s.id())).isInstanceOfSatisfying(LearningSessionException.class,
                e -> assertThat(e.reason()).isEqualTo(LearningSessionException.Reason.SOURCE_UNAVAILABLE));
        assertThatThrownBy(() -> forms.materialize(command(s, selected))).isInstanceOf(LearningSessionException.class);
        access.set(true); login("bob");
        assertThatThrownBy(() -> forms.read(s.id())).isInstanceOfSatisfying(LearningSessionException.class,
                e -> assertThat(e.reason()).isEqualTo(LearningSessionException.Reason.UNAVAILABLE));
        assertThatThrownBy(() -> forms.materialize(command(s, selected))).isInstanceOf(LearningSessionException.class);
        login("anna"); sql.update("UPDATE users SET is_active=FALSE WHERE user_id=UUID_TO_BIN(?)", ANNA.toString());
        assertThatThrownBy(() -> forms.read(s.id())).isInstanceOf(LearningSessionException.class);
        sql.update("UPDATE users SET is_active=TRUE WHERE user_id=UUID_TO_BIN(?)", ANNA.toString());
        assertThat(forms.read(s.id())).isEqualTo(first.view());
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> forms.read(s.id())).isInstanceOf(LearningSessionException.class);
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT INTO learning_form(", "INSERT INTO learning_form_item(", "INSERT INTO learning_form_option("})
    void reachedSqlFailureRollsBackWholeFormAndSameSelectionRetries(String checkpoint) {
        var s = session(revision); var command = command(s, items(revision));
        source.failAfter.set(checkpoint);
        rejected(() -> forms.materialize(command), PERSISTENCE_UNAVAILABLE);
        assertThat(source.failures).hasValue(1); assertCounts(0, 0, 0);
        assertThat(sessions.read(s.id())).isEqualTo(s);
        assertThat(forms(repository).materialize(command).replayed()).isFalse();
        assertCounts(1, 3, 6);
    }

    @Test void callerRollbackCaughtFailureAndUnknownCommitPreserveAtomicity() {
        var outer = tx(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            var s = session(revision); forms.materialize(command(s, items(revision))); status.setRollbackOnly();
        });
        assertCounts(0, 0, 0); assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_session", Integer.class)).isZero();
        var s = session(revision); var command = command(s, items(revision));
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            sql.update("UPDATE users SET email_verified=TRUE WHERE user_id=UUID_TO_BIN(?)", ANNA.toString());
            source.failAfter.set("INSERT INTO learning_form_item(");
            rejected(() -> forms.materialize(command), PERSISTENCE_UNAVAILABLE);
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertCounts(0, 0, 0);
        assertThat(sql.queryForObject("SELECT email_verified FROM users WHERE user_id=UUID_TO_BIN(?)", Boolean.class, ANNA.toString())).isFalse();
        source.loseCommit.set(true);
        rejected(() -> forms.materialize(command), PERSISTENCE_UNAVAILABLE);
        assertThat(source.lostCommits).hasValue(1); assertCounts(1, 3, 6);
        assertThat(forms(repository).materialize(command).replayed()).isTrue();
    }

    @Test void reachedBeforeCommitFailureLeavesNoFormAndRetrySucceeds() {
        var s = session(revision); var command = command(s, items(revision)); var reached = new AtomicBoolean();
        var failing = new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void insert(LearningForm form) {
                super.insert(form);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly) {
                        assertCounts(1, 3, 6); reached.set(true); throw new IllegalStateException("private-before-commit");
                    }
                });
            }
        };
        rejected(() -> forms(failing).materialize(command), PERSISTENCE_UNAVAILABLE);
        assertThat(reached).isTrue(); assertCounts(0, 0, 0);
        assertThat(forms.materialize(command).replayed()).isFalse();
    }

    @Test void incompatibleOuterTransactionsRejectBeforeAnySqlAndSupportedRcRollsBack() {
        var s = session(revision); var command = command(s, items(revision));
        for (int isolation : List.of(TransactionDefinition.ISOLATION_DEFAULT, TransactionDefinition.ISOLATION_REPEATABLE_READ, TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            tx(isolation).executeWithoutResult(status -> {
                source.statements.get().clear();
                rejected(() -> forms.materialize(command), UNSUPPORTED_TRANSACTION);
                rejected(() -> forms.read(s.id()), UNSUPPORTED_TRANSACTION);
                assertThat(source.statements.get()).isEmpty();
            });
        }
        var readOnly = tx(TransactionDefinition.ISOLATION_READ_COMMITTED); readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> { source.statements.get().clear(); rejected(() -> forms.materialize(command), UNSUPPORTED_TRANSACTION); assertThat(source.statements.get()).isEmpty(); });
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
            forms.materialize(command); status.setRollbackOnly();
        });
        assertCounts(0, 0, 0);
        assertThat(forms.materialize(command).replayed()).isFalse();
    }

    @Test void missingOrCorruptSavedEvidenceIsNeverRepaired() {
        var s = session(revision); var command = command(s, items(revision));
        var first = forms.materialize(command);
        String id = first.view().form().id().toString();
        sql.update("DELETE FROM learning_form_option WHERE form_id=UUID_TO_BIN(?) AND item_position=0 AND position=1", id);
        rejected(() -> forms.read(s.id()), CORRUPT); rejected(() -> forms.materialize(command), CORRUPT);
        assertCounts(1, 3, 5);
        sql.update("INSERT INTO learning_form_option VALUES(UUID_TO_BIN(?),0,1,UUID_TO_BIN(?))", id, command.items().get(0).optionIds().get(1).toString());
        sql.update("UPDATE learning_form SET fingerprint=UNHEX(REPEAT('00',32)) WHERE id=UUID_TO_BIN(?)", id);
        rejected(() -> forms.read(s.id()), CORRUPT); rejected(() -> forms.materialize(command), CORRUPT);
        assertThat(sql.queryForObject("SELECT HEX(fingerprint) FROM learning_form WHERE id=UUID_TO_BIN(?)", String.class, id)).isEqualTo("00".repeat(32));
        for (String assignment : List.of("schema_version=2", "fingerprint_version=2", "item_count=0", "outcome='UNKNOWN'"))
            assertThatThrownBy(() -> sql.update("UPDATE learning_form SET " + assignment + " WHERE id=UUID_TO_BIN(?)", id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void concurrentSameSelectionWaitsThenReplaysOneDurableWinner() throws Exception { race(false); }
    @Test void concurrentConflictingSelectionCannotOverwriteWinner() throws Exception { race(true); }
    void race(boolean conflict) throws Exception {
        var s = session(revision); var command = command(s, items(revision));
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var holderId = new AtomicLong(); var waiterId = new AtomicLong();
        var holding = new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void insert(LearningForm form) {
                super.insert(form); holderId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class));
                reached.countDown(); await(release);
            }
        };
        var waiting = new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void lockSession(LearningSession session) {
                waiterId.set(sql.queryForObject("SELECT CONNECTION_ID()", Long.class)); super.lockSession(session);
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> as("anna", () -> forms(holding).materialize(command)));
            await(reached);
            var b = pool.submit(() -> as("anna", () -> forms(waiting).materialize(conflict ? command(s, List.of()) : command)));
            until(() -> waiterId.get() != 0 && waiting(waiterId.get(), holderId.get()), "waiter must reach actual session-key lock");
            assertThat(b).isNotDone(); release.countDown();
            var winner = a.get(30, TimeUnit.SECONDS); assertThat(winner.replayed()).isFalse();
            if (conflict) assertThatThrownBy(() -> b.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(LearningFormException.class)
                    .satisfies(e -> assertThat(((LearningFormException) e.getCause()).reason()).isEqualTo(CONFLICT));
            else assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(new LearningFormService.Result(winner.view(), true));
            assertCounts(1, 3, 6); assertThat(forms.read(s.id())).isEqualTo(winner.view());
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void independentLearnerOnSameRevisionAndOtherRevisionCommitsBeforeHolderRelease() throws Exception {
        var s = session(revision); var other = revision(UUID.randomUUID(), 2); sources.put(other.id(), other);
        login("bob"); var peer = session(revision); var unrelated = session(other); login("anna");
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var holding = new JdbcLearningFormRepository(new NamedParameterJdbcTemplate(sql)) {
            @Override public void insert(LearningForm form) { super.insert(form); reached.countDown(); await(release); }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> as("anna", () -> forms(holding).materialize(command(s, items(revision)))));
            await(reached);
            var completed = pool.submit(() -> as("bob", () -> {
                var a = forms.materialize(command(peer, items(revision)));
                var b = forms.materialize(command(unrelated, items(other)));
                assertThat(forms.read(peer.id())).isEqualTo(a.view()); assertThat(forms.read(unrelated.id())).isEqualTo(b.view());
                return List.of(a, b);
            })).get(30, TimeUnit.SECONDS);
            assertThat(completed).allSatisfy(r -> assertThat(r.replayed()).isFalse());
            assertThat(holder).isNotDone(); release.countDown();
            assertThat(holder.get(30, TimeUnit.SECONDS).replayed()).isFalse(); assertCounts(3, 8, 16);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(35, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void multipleFormsAnd500501BoundariesReadOnlySelectedMetadata() {
        var large = revision(UUID.randomUUID(), 1001); sources.put(large.id(), large);
        for (int size : List.of(1, 3, 500, 501)) {
            var s = session(large); source.statements.get().clear();
            forms.materialize(command(s, items(large).subList(0, size)));
            assertThat(source.statements.get().stream().filter(q -> q.stripLeading().startsWith("INSERT INTO learning_form")))
                    .hasSize(1 + (size + 499) / 500 + (2 * size + 499) / 500);
            source.statements.get().clear(); source.selectedRows.set(0); contentReads.set(0);
            var read = forms.read(s.id());
            assertThat(read.form().items()).hasSize(size); assertThat(contentReads).hasValue(1);
            var selects = source.statements.get().stream().filter(q -> q.stripLeading().startsWith("SELECT")).toList();
            assertThat(selects).hasSize(7 + 2 * ((size + 499) / 500));
            assertThat(source.selectedRows.get()).isEqualTo(5 + 6 * size);
            assertThat(selects).allSatisfy(q -> assertThat(q).doesNotContain("question_text", "q.content", "SELECT *", "scoring_contract", "question_hint"));
            assertThat(selects.stream().filter(q -> q.contains("FROM quiz_revision_items"))).allSatisfy(q -> assertThat(q).contains("i.id IN"));
        }
    }

    static QuizRevision revision(UUID quizId, int count) {
        var rows = new ArrayList<QuizRevision.Item>();
        for (int i = 0; i < count; i++) rows.add(new QuizRevision.Item(UUID.randomUUID(), i,
                new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.MCQ_SINGLE, Difficulty.EASY,
                        "Saved question " + i, "{\"options\":[{\"id\":\"a\",\"text\":\"Option 0\",\"correct\":true},{\"id\":\"b\",\"text\":\"Option 1\",\"correct\":false}]}",
                        "private hint", "x".repeat(2000), null, null, TIME,
                        List.of(new QuestionRevision.Option(UUID.randomUUID(), "a", 0), new QuestionRevision.Option(UUID.randomUUID(), "b", 1)))));
        var saved = new QuizRevision(UUID.randomUUID(), quizId, ANNA, 1, "Learning source", null, TIME, rows);
        tx(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(sql)).insert(saved);
            for (var item : rows) {
                var binding = new QuestionContractBinding(item.question().id(), new ContractVersion("question.mcq-single.response", 1), new ContractVersion("question.mcq-single.binary", 1));
                sql.update("INSERT INTO question_revision_contract_bindings VALUES(UUID_TO_BIN(?),'question.mcq-single.response',1,'question.mcq-single.binary',1,?)",
                        item.question().id().toString(), binding.fingerprint());
            }
        });
        return saved;
    }
    static void legacyFixture() {
        UUID category = UUID.randomUUID(), quiz = UUID.randomUUID(), attempt = UUID.randomUUID(), question = UUID.randomUUID();
        sql.update("INSERT INTO categories(category_id,category_name) VALUES(UUID_TO_BIN(?),'Form compatibility')", category.toString());
        sql.update("INSERT INTO quizzes(quiz_id,creator_id,category_id,title,visibility,difficulty,status,estimated_time_min,is_repetition_enabled,is_timer_enabled) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'Existing practice','PUBLIC','EASY','PUBLISHED',1,0,0)", quiz.toString(), ANNA.toString(), category.toString());
        sql.update("INSERT INTO questions(id,type,difficulty,question,content) VALUES(UUID_TO_BIN(?),'TRUE_FALSE','EASY','Existing question','{\"answer\":true}')", question.toString());
        sql.update("INSERT INTO quiz_questions VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?))", quiz.toString(), question.toString());
        sql.update("INSERT INTO attempts(id,user_id,quiz_id,status,mode,total_score) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'COMPLETED','ALL_AT_ONCE',1)", attempt.toString(), ANNA.toString(), quiz.toString());
        sql.update("INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'{\"answer\":true}',TRUE,1,?)", UUID.randomUUID().toString(), attempt.toString(), question.toString(), Timestamp.from(TIME));
    }
    static Map<String, List<Map<String, Object>>> retained() {
        var result = new TreeMap<String, List<Map<String, Object>>>();
        result.put("attempts", sql.queryForList("SELECT HEX(id) id,status,total_score,accepted_answer_projection FROM attempts ORDER BY id"));
        result.put("answers", sql.queryForList("SELECT HEX(id) id,response,is_correct,score,answered_at FROM answers ORDER BY id"));
        for (String table : List.of("attempt_automatic_result", "spaced_repetition_entry", "repetition_review_log"))
            result.put(table, sql.queryForList("SELECT * FROM " + table));
        return result;
    }
    static void assertCounts(int forms, int items, int options) {
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_form", Integer.class)).isEqualTo(forms);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_form_item", Integer.class)).isEqualTo(items);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_form_option", Integer.class)).isEqualTo(options);
    }
    static TransactionTemplate tx(int isolation) { var tx = new TransactionTemplate(manager); tx.setIsolationLevel(isolation); return tx; }
    static void login(String name) {
        var p = User.withUsername(name).password("unused").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p, null, p.getAuthorities()));
    }
    static <T> T as(String name, Supplier<T> work) { login(name); try { return work.get(); } finally { SecurityContextHolder.clearContext(); } }
    static void await(CountDownLatch latch) {
        try { assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    static void until(Supplier<Boolean> condition, String message) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.get()) { if (System.nanoTime() > end) fail(message); Thread.sleep(10); }
    }
    static boolean waiting(long requester, long blocker) {
        return admin.queryForObject("""
            SELECT COUNT(*) FROM performance_schema.data_lock_waits w
            JOIN performance_schema.threads r ON r.THREAD_ID=w.REQUESTING_THREAD_ID
            JOIN performance_schema.threads b ON b.THREAD_ID=w.BLOCKING_THREAD_ID
            JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
            WHERE r.PROCESSLIST_ID=? AND b.PROCESSLIST_ID=? AND l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='learning_session'
            """, Integer.class, requester, blocker, SCHEMA) > 0;
    }
    static class ObservedSource extends DelegatingDataSource {
        final ThreadLocal<List<String>> statements = ThreadLocal.withInitial(ArrayList::new);
        final ThreadLocal<Integer> selectedRows = ThreadLocal.withInitial(() -> 0);
        final AtomicReference<String> failAfter = new AtomicReference<>();
        final AtomicInteger failures = new AtomicInteger(), lostCommits = new AtomicInteger();
        final AtomicBoolean loseCommit = new AtomicBoolean();
        ObservedSource(DriverManagerDataSource delegate) { super(delegate); }
        void reset() { failAfter.set(null); failures.set(0); lostCommits.set(0); loseCommit.set(false); statements.get().clear(); selectedRows.set(0); }
        @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return observe(super.getConnection(u, p)); }
        Connection observe(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                try {
                    Object result = method.invoke(connection, args);
                    if (method.getName().equals("prepareStatement")) {
                        String query = (String) args[0]; statements.get().add(query);
                        var delegate = (PreparedStatement) result;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                            try {
                                Object value = m.invoke(delegate, a); String target = failAfter.get();
                                if (m.getName().startsWith("execute") && target != null && query.contains(target) && failAfter.compareAndSet(target, null)) {
                                    failures.incrementAndGet(); throw new SQLException("private-answer-driver", "HY000");
                                }
                                if (value instanceof ResultSet rows) return Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                                        new Class[]{ResultSet.class}, (rp, rm, ra) -> {
                                            try {
                                                var resultValue = rm.invoke(rows, ra);
                                                if (rm.getName().equals("next") && Boolean.TRUE.equals(resultValue)) selectedRows.set(selectedRows.get() + 1);
                                                return resultValue;
                                            } catch (InvocationTargetException e) { throw e.getCause(); }
                                        });
                                return value;
                            } catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                    }
                    if (method.getName().equals("commit") && loseCommit.compareAndSet(true, false)) {
                        lostCommits.incrementAndGet(); throw new SQLException("private-lost-commit", "08006");
                    }
                    return result;
                } catch (InvocationTargetException e) { throw e.getCause(); }
            });
        }
    }
}
