package uk.gegc.quizmaker.features.attempt.form;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.FixedFormService;
import uk.gegc.quizmaker.features.attempt.application.impl.FixedFormServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JdbcFixedFormRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.form.FixedFormFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException.Reason.*;

/** Real service, Flyway and independent MySQL transactions; no application context or external provider. */
@Tag("db-serial")
class FixedFormMySqlIntegrationTest {
    private static final String HISTORY = "flyway_fixed_form_identity_history";
    private static final String FORM_HISTORY = "flyway_fixed_form_history";
    private static FormDataSource source;
    private static JdbcTemplate sql;
    private static DataSourceTransactionManager transactions;
    private static QuizRevisionRepository revisions;
    private SimpleMeterRegistry metrics;
    private FixedFormService service;

    @BeforeAll
    static void connect() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        source = new FormDataSource(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser")));
        sql = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        revisions = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)), transactions);
    }

    @BeforeEach
    void migrate() {
        clean();
        sql.execute("CREATE TABLE form_legacy_fixture (id INT PRIMARY KEY, marker VARCHAR(50))");
        sql.update("INSERT INTO form_legacy_fixture VALUES (1,'legacy')");
        Flyway.configure().dataSource(source).table(HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("80").target("82").load().migrate();
        // V83 belongs to normalized documents. V84 and V85 need only immutable revision storage.
        Flyway.configure().dataSource(source).table(FORM_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("83").target("85").load().migrate();
        metrics = new SimpleMeterRegistry();
        service = service(transactions);
        source.reset();
    }

    @AfterEach
    void cleanup() {
        clean();
        if (metrics != null) metrics.close();
    }

    private static void clean() {
        source.reset();
        for (String table : List.of("attempt_form_item", "attempt_form", "question_revision_contract_bindings",
                "quiz_revision_items", "question_option_revisions", "quiz_revisions", "question_revisions",
                HISTORY, FORM_HISTORY, "form_legacy_fixture")) sql.execute("DROP TABLE IF EXISTS " + table);
    }

    @Test
    void migrationCreatesEmptyAdditiveStorageAndDoesNotInventLegacyForms() {
        assertThat(count("attempt_form")).isZero();
        assertThat(count("attempt_form_item")).isZero();
        assertThat(sql.queryForObject("SELECT marker FROM form_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + FORM_HISTORY + " WHERE version='85' AND success=1", Integer.class)).isEqualTo(1);
        var r = savedRevision("migration", 2);
        assertThat(service.findByIds(OWNER, List.of(r.id()))).isEmpty();
        assertThat(revisions.findById(OWNER, r.id())).contains(r);
    }

    @Test
    void exactReplayAndRecreatedServiceRetainIdsSuppliedPositionsAndRevisionBinding() {
        var r = savedRevision("stable", 3);
        var input = form("stable-form", r, 3);
        assertThat(service.createOrReplay(OWNER, input)).isEqualTo(input);
        assertThat(service.createOrReplay(OWNER, new FixedForm(input.id(), 1, r.id(),
                List.of(input.items().get(2), input.items().get(0), input.items().get(1))))).isEqualTo(input);
        assertThat(service(transactions).findByIds(OWNER, List.of(input.id()))).containsExactly(input);
        assertThat(count("attempt_form")).isEqualTo(1);
        assertThat(count("attempt_form_item")).isEqualTo(3);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM question_revision_contract_bindings", Integer.class)).isZero();
    }

    @Test
    void laterRevisionOfSameQuizCannotChangeStoredFormSources() {
        var old = savedRevision("old", 2);
        var form = form("historical", old, 2);
        service.createOrReplay(OWNER, form);
        var next = savedRevision("next", 3);
        assertThat(next.quizId()).isEqualTo(old.quizId());
        assertThat(service.findByIds(OWNER, List.of(form.id()))).containsExactly(form);
        assertThat(revisions.findById(OWNER, old.id())).contains(old);
    }

    @Test
    void changedRetryCannotAppendRemoveReorderOrReplaceItemsOrRebindRevision() {
        var r = savedRevision("retry", 3);
        var original = form("retry-form", r, 2);
        service.createOrReplay(OWNER, original);
        var other = savedRevision("retry-other", 2);
        var a = original.items().get(0);
        var b = original.items().get(1);
        for (var changed : List.of(form("retry-form", r, 3), form("retry-form", r, 1),
                new FixedForm(original.id(), 1, r.id(), List.of(new FixedForm.Item(a.id(), a.sourceItemId(), 2),
                        new FixedForm.Item(b.id(), b.sourceItemId(), 0))),
                new FixedForm(original.id(), 1, r.id(), List.of(a, new FixedForm.Item(id("new-item"), r.items().get(2).id(), 4))),
                new FixedForm(original.id(), 1, other.id(), form("other-items", other, 2).items()))) {
            rejected(() -> service.createOrReplay(OWNER, changed), FORM_CONFLICT);
            assertThat(service.findByIds(OWNER, List.of(original.id()))).containsExactly(original);
            assertThat(count("attempt_form_item")).isEqualTo(2);
        }
    }

    @Test
    void changedRetryThatReachedANewItemInsertRollsThatInsertBack() {
        var r = savedRevision("late-conflict", 3);
        var original = form("late-conflict-form", r, 2);
        service.createOrReplay(OWNER, original);
        var reached = new AtomicInteger();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT id,source_item_id,position FROM attempt_form_item")) {
                reached.set(count("attempt_form_item"));
            }
        };
        var changed = new FixedForm(original.id(), 1, r.id(), List.of(original.items().get(0),
                new FixedForm.Item(id("late-new-item"), r.items().get(2).id(), 4)));
        rejected(() -> service.createOrReplay(OWNER, changed), FORM_CONFLICT);
        source.reset();
        assertThat(reached).hasValue(3);
        assertThat(service.findByIds(OWNER, List.of(original.id()))).containsExactly(original);
        assertThat(count("attempt_form_item")).isEqualTo(2);
    }

    @Test
    void missingForeignAndMutableReferencesNeverCreateAuthoritativeState() {
        var r = savedRevision("access", 2);
        var f = form("access-form", r, 2);
        rejected(() -> service.createOrReplay(null, f), REVISION_UNAVAILABLE);
        rejected(() -> service.createOrReplay(OTHER_OWNER, f), REVISION_UNAVAILABLE);
        rejected(() -> service.createOrReplay(OWNER, new FixedForm(f.id(), 1, r.quizId(), f.items())), REVISION_UNAVAILABLE);
        var other = savedRevision("other-sources", 1);
        rejected(() -> service.createOrReplay(OWNER, new FixedForm(f.id(), 1, r.id(),
                List.of(new FixedForm.Item(id("foreign-source"), other.items().get(0).id(), 0)))), REVISION_UNAVAILABLE);
        rejected(() -> service.createOrReplay(OWNER, new FixedForm(f.id(), 1, r.id(),
                List.of(new FixedForm.Item(id("mutable-source"), r.items().get(0).question().questionId(), 0)))), REVISION_UNAVAILABLE);
        assertThat(count("attempt_form")).isZero();
        assertThat(count("attempt_form_item")).isZero();
        service.createOrReplay(OWNER, f);
        assertThat(service.findByIds(OTHER_OWNER, List.of(f.id()))).isEmpty();
        assertThat(service.findByIds(OWNER, List.of(id("missing"), f.id(), f.id()))).containsExactly(f);
        rejected(() -> service.findByIds(null, List.of(f.id())), FORM_UNAVAILABLE);
        rejected(() -> service.findByIds(OWNER, null), INVALID_FORM);
        rejected(() -> service.findByIds(OWNER, Arrays.asList((UUID) null)), INVALID_FORM);
        rejected(() -> service.createOrReplay(OWNER, null), INVALID_FORM);
    }

    @Test
    void foreignExistingFormIdDoesNotBecomeAnOwnerScopedConflictOracle() {
        var r = savedRevision("foreign-form", 1);
        var f = form("occupied", r, 1);
        service.createOrReplay(OWNER, f);
        var input = revision("my-revision", 1);
        var own = new QuizRevision(input.id(), input.quizId(), OTHER_OWNER, input.schemaVersion(), input.title(),
                input.description(), input.createdAt(), input.items());
        revisions.insert(own);
        rejected(() -> service.createOrReplay(OTHER_OWNER, form("occupied", own, 1)), FORM_UNAVAILABLE);
        assertThat(service.findByIds(OWNER, List.of(f.id()))).containsExactly(f);
        assertThat(count("attempt_form")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void emptyAndNonEmptyFormsHaveImmutableCardinality(int items) {
        var r = savedRevision("cardinality", 2);
        var original = form("cardinality-form", r, items);
        service.createOrReplay(OWNER, original);
        service.createOrReplay(OWNER, original);
        rejected(() -> service.createOrReplay(OWNER, form("cardinality-form", r, items == 0 ? 1 : 0)), FORM_CONFLICT);
        assertThat(service.findByIds(OWNER, List.of(original.id()))).containsExactly(original);
        assertThat(count("attempt_form_item")).isEqualTo(items);
    }

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,true", "REPEATABLE_READ,true", "READ_COMMITTED,false", "REPEATABLE_READ,false"})
    void independentConcurrentCreatesHaveStableSuccessfulWinners(String isolation, boolean identical) throws Exception {
        var r = savedRevision("race", 3);
        var first = form("race-form", r, 2);
        var second = identical ? first : new FixedForm(first.id(), 1, r.id(), List.of(first.items().get(0),
                new FixedForm.Item(id("race-alternative"), r.items().get(2).id(), 4)));
        var barrier = new CyclicBarrier(2);
        Set<Integer> connections = ConcurrentHashMap.newKeySet();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO attempt_form (")) {
                connections.add(connection);
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError("Both real transactions must reach the root insert", failure); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Outcome> one = pool.submit(() -> createOutcome(first, isolation));
            Future<Outcome> two = pool.submit(() -> createOutcome(second, isolation));
            var outcomes = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
            source.reset();
            assertThat(connections).hasSize(2);
            var winners = outcomes.stream().filter(o -> o.failure() == null).toList();
            assertThat(winners).hasSize(identical ? 2 : 1);
            if (!identical) assertThat(outcomes.stream().filter(o -> o.failure() != null).toList())
                    .extracting(Outcome::failure).containsExactly(FORM_CONFLICT);
            var winner = winners.get(0).form();
            assertThat(service.findByIds(OWNER, List.of(first.id()))).containsExactly(winner);
            assertThat(count("attempt_form")).isEqualTo(1);
            assertThat(count("attempt_form_item")).isEqualTo(2);
            service.createOrReplay(OWNER, winner);
            if (!identical) {
                var loser = outcomes.stream().filter(o -> o.failure() != null).findFirst().orElseThrow().form();
                rejected(() -> service.createOrReplay(OWNER, loser), FORM_CONFLICT);
            }
        } finally {
            source.reset(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"READ_COMMITTED", "REPEATABLE_READ"})
    void replayAndReadInsideAnOlderTransactionSeeTheCommittedWinner(String isolation) throws Exception {
        var r = savedRevision("old-snapshot", 2);
        var f = form("old-snapshot-form", r, 2);
        var outer = transaction(isolation);
        var pool = Executors.newSingleThreadExecutor();
        try {
            outer.executeWithoutResult(status -> {
                assertThat(count("attempt_form")).isZero();
                try { pool.submit(() -> service.createOrReplay(OWNER, f)).get(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(service.createOrReplay(OWNER, f)).isEqualTo(f);
                assertThat(service.findByIds(OWNER, List.of(f.id()))).containsExactly(f);
            });
        } finally {
            pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void reachedWriteFailureAndCaughtFailureInOuterTransactionLeaveNoPartialForm() {
        var r = savedRevision("rollback", 2);
        var f = form("rollback-form", r, 2);
        var reached = new AtomicInteger();
        source.beforeSql = (statement, connection) -> {
            if (statement.startsWith("SELECT id,source_item_id,position FROM attempt_form_item")) {
                reached.set(count("attempt_form_item"));
                throw new SQLException("Synthetic reached post-item checkpoint", "08006");
            }
        };
        var outer = new TransactionTemplate(transactions);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            sql.update("UPDATE form_legacy_fixture SET marker='partial' WHERE id=1");
            rejected(() -> service.createOrReplay(OWNER, f), PERSISTENCE_UNAVAILABLE);
        })).isInstanceOf(UnexpectedRollbackException.class);
        source.reset();
        assertThat(reached).hasValue(2);
        assertThat(service.findByIds(OWNER, List.of(f.id()))).isEmpty();
        assertThat(count("attempt_form")).isZero();
        assertThat(count("attempt_form_item")).isZero();
        assertThat(sql.queryForObject("SELECT marker FROM form_legacy_fixture WHERE id=1", String.class)).isEqualTo("legacy");
        service.createOrReplay(OWNER, f);
        assertThat(service.findByIds(OWNER, List.of(f.id()))).containsExactly(f);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmedRollbackAndLostCommitAcknowledgementRetainDurableRetryIdentity(boolean committed) {
        var r = savedRevision("commit", 2);
        var f = form("commit-form", r, 2);
        var fault = new AtomicBoolean(true);
        var reached = new AtomicInteger();
        FormDataSource.CommitHook hook = connection -> {
            if (fault.getAndSet(false)) {
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM attempt_form_item");
                     var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    reached.set(rows.getInt(1));
                }
                // Model a server-confirmed rollback, rather than leaving a usable uncommitted
                // connection which JDBC cleanup could commit by restoring autoCommit=true.
                if (!committed) connection.rollback();
                throw new SQLException("Synthetic commit acknowledgement failure", "08006");
            }
        };
        if (committed) source.afterCommit = hook; else source.beforeCommit = hook;
        rejected(() -> service.createOrReplay(OWNER, f), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(reached).as("complete form write reached the commit fault").hasValue(2);
        assertThat(service.findByIds(OWNER, List.of(f.id()))).hasSize(committed ? 1 : 0);
        assertThat(count("attempt_form_item")).isEqualTo(committed ? 2 : 0);
        service(transactions).createOrReplay(OWNER, f);
        assertThat(service.findByIds(OWNER, List.of(f.id()))).containsExactly(f);
        assertThat(count("attempt_form")).isEqualTo(1);
        assertThat(count("attempt_form_item")).isEqualTo(2);
    }

    @Test
    void connectionAndReadFailuresUseNonSensitiveTypedBoundaries() {
        var r = savedRevision("outage", 1);
        var f = form("outage-form", r, 1);
        source.failConnection = true;
        rejected(() -> service.createOrReplay(OWNER, f), PERSISTENCE_UNAVAILABLE);
        rejected(() -> service.findByIds(OWNER, List.of(f.id())), PERSISTENCE_UNAVAILABLE);
        source.reset();
        service.createOrReplay(OWNER, f);
        source.beforeSql = (statement, connection) -> { throw new SQLException("Synthetic metadata read failure", "08006"); };
        rejected(() -> service.findByIds(OWNER, List.of(f.id())), PERSISTENCE_UNAVAILABLE);
        source.reset();
        assertThat(service.findByIds(OWNER, List.of(f.id()))).containsExactly(f);
    }

    @Test
    void jdbcFormWritesJoinRealJpaTransactionsForCommitAndRollback() {
        var r = savedRevision("jpa", 2);
        var committed = form("jpa-committed", r, 2);
        var rolledBack = form("jpa-rolled-back", r, 1);
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source); factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.attempt.form");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none")); factory.afterPropertiesSet();
        try {
            var entityManagers = factory.getObject();
            var manager = new JpaTransactionManager(entityManagers); manager.setDataSource(source);
            var jpaService = service(manager);
            var tx = new TransactionTemplate(manager);
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers)
                        .createNativeQuery("UPDATE form_legacy_fixture SET marker='committed' WHERE id=1").executeUpdate();
                jpaService.createOrReplay(OWNER, committed);
            });
            tx.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagers)
                        .createNativeQuery("UPDATE form_legacy_fixture SET marker='rolled back' WHERE id=1").executeUpdate();
                jpaService.createOrReplay(OWNER, rolledBack); status.setRollbackOnly();
            });
            assertThat(service.findByIds(OWNER, List.of(committed.id(), rolledBack.id()))).containsExactly(committed);
            assertThat(sql.queryForObject("SELECT marker FROM form_legacy_fixture WHERE id=1", String.class)).isEqualTo("committed");
        } finally { factory.destroy(); }
    }

    @Test
    void multipleParentsAndIncreasingItemsUseTwoMetadataQueriesPerBatch() {
        var r = savedRevision("bounded", 12);
        var forms = new ArrayList<FixedForm>();
        for (int i = 0; i < 6; i++) {
            var f = form("bounded-" + i, r, i % 2 == 0 ? 1 : 12);
            service.createOrReplay(OWNER, f); forms.add(f);
        }
        source.selects.clear();
        assertThat(service.findByIds(OWNER, List.of(forms.get(0).id()))).containsExactly(forms.get(0));
        assertThat(source.selects).hasSize(2);
        source.selects.clear();
        assertThat(service.findByIds(OWNER, forms.stream().map(FixedForm::id).toList())).isEqualTo(forms);
        assertThat(source.selects).hasSize(2).allSatisfy(statement ->
                assertThat(statement.toLowerCase(Locale.ROOT)).doesNotContain("question_revisions", "content", "question_text", "explanation"));
        source.selects.clear();
        assertThat(service.findByIds(OWNER, List.of())).isEmpty();
        assertThat(source.selects).isEmpty();
    }

    @Test
    void sqlBatchesDoNotImposeAProductLimitOnFormItemsOrReadParents() {
        var r = savedRevision("large", 501);
        var large = form("large-form", r, 501);
        service.createOrReplay(OWNER, large);
        var forms = new ArrayList<FixedForm>(); forms.add(large);
        for (int i = 0; i < 500; i++) {
            var f = form("large-parent-" + i, r, 0); service.createOrReplay(OWNER, f); forms.add(f);
        }
        source.selects.clear();
        assertThat(service.findByIds(OWNER, forms.stream().map(FixedForm::id).toList())).isEqualTo(forms);
        assertThat(source.selects).hasSize(4);
        assertThat(count("attempt_form_item")).isEqualTo(501);
    }

    @Test
    void databaseConstraintsProtectSourceMembershipNaturalKeysAndRetainedReferences() {
        var r = savedRevision("constraints", 2);
        var other = savedRevision("constraints-other", 1);
        var f = form("constraints-form", r, 1); service.createOrReplay(OWNER, f);
        var a = f.items().get(0);
        for (var candidate : List.of(new FixedForm.Item(a.id(), r.items().get(1).id(), 1),
                new FixedForm.Item(id("same-source"), a.sourceItemId(), 1),
                new FixedForm.Item(id("same-position"), r.items().get(1).id(), 0),
                new FixedForm.Item(id("wrong-revision"), other.items().get(0).id(), 1))) {
            assertThatThrownBy(() -> sql.update("INSERT INTO attempt_form_item (form_id,id,quiz_revision_id,source_item_id,position) VALUES (?,?,?,?,?)",
                    bytes(f.id()), bytes(candidate.id()), bytes(r.id()), bytes(candidate.sourceItemId()), candidate.position()))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> sql.update("INSERT INTO attempt_form (id,schema_version,quiz_revision_id,item_count) VALUES (?,2,?,0)",
                bytes(id("unknown-version")), bytes(r.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("INSERT INTO attempt_form_item (form_id,id,quiz_revision_id,source_item_id,position) VALUES (?,?,?,?,-1)",
                bytes(f.id()), bytes(id("negative")), bytes(r.id()), bytes(r.items().get(1).id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM attempt_form WHERE id=?", bytes(f.id()))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM quiz_revision_items WHERE quiz_revision_id=? AND id=?", bytes(r.id()), bytes(a.sourceItemId())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM quiz_revisions WHERE id=?", bytes(r.id()))).isInstanceOf(DataAccessException.class);
        assertThat(service.findByIds(OWNER, List.of(f.id()))).containsExactly(f);
    }

    @Test
    void metadataCountMismatchFailsClosedWithoutReconstructingFromCurrentQuestions() {
        var r = savedRevision("stored", 1);
        var f = form("stored-form", r, 1); service.createOrReplay(OWNER, f);
        sql.update("UPDATE attempt_form SET item_count=2 WHERE id=?", bytes(f.id()));
        rejected(() -> service.findByIds(OWNER, List.of(f.id())), INVALID_STORED_FORM);
        assertThat(count("attempt_form_item")).isEqualTo(1);
    }

    @Test
    void metricsUseOnlyBoundedOperationsAndOutcomes() {
        var r = savedRevision("metrics", 1);
        var f = form("metrics-form", r, 1);
        service.createOrReplay(OWNER, f); service.createOrReplay(OWNER, f);
        rejected(() -> service.createOrReplay(OWNER, form("metrics-form", r, 0)), FORM_CONFLICT);
        service.findByIds(OWNER, List.of(f.id()));
        var success = metrics.get("quizmaker.attempt.form.operations").tags("operation", "create_or_replay", "outcome", "success").timer();
        assertThat(success.count()).isEqualTo(2);
        assertThat(metrics.get("quizmaker.attempt.form.operations").tag("outcome", "form_conflict").timer().count()).isEqualTo(1);
        metrics.getMeters().forEach(meter -> assertThat(meter.getId().getTags()).allSatisfy(tag ->
                assertThat(tag.getKey()).isIn("operation", "outcome")));
    }

    @Test
    void independentFormsForTheSameRevisionDoNotShareAnExclusiveQuizLock() throws Exception {
        var r = savedRevision("independent", 2);
        var first = form("independent-first", r, 2);
        var second = form("independent-second", r, 1);
        var firstHasWritten = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        source.beforeSql = (statement, connection) -> {
            if (Thread.currentThread().getName().equals("form-first")
                    && statement.startsWith("SELECT id,source_item_id,position FROM attempt_form_item")) {
                firstHasWritten.countDown();
                try { assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        };
        var one = Executors.newSingleThreadExecutor(task -> new Thread(task, "form-first"));
        var two = Executors.newSingleThreadExecutor(task -> new Thread(task, "form-second"));
        try {
            var pending = one.submit(() -> service.createOrReplay(OWNER, first));
            assertThat(firstHasWritten.await(10, TimeUnit.SECONDS)).as("first form really holds its write locks").isTrue();
            assertThat(two.submit(() -> service.createOrReplay(OWNER, second)).get(10, TimeUnit.SECONDS)).isEqualTo(second);
            releaseFirst.countDown();
            assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(first);
            source.reset();
            assertThat(service.findByIds(OWNER, List.of(first.id(), second.id()))).containsExactly(first, second);
        } finally {
            releaseFirst.countDown(); one.shutdownNow(); two.shutdownNow(); source.reset();
            assertThat(one.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(two.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void databaseEnforcesParentRevisionMembershipEvenWhenBothSourcesExist() {
        var r = savedRevision("parent-membership", 1);
        var other = savedRevision("other-parent-membership", 1);
        var f = form("parent-membership-form", r, 0); service.createOrReplay(OWNER, f);
        assertThatThrownBy(() -> sql.update("INSERT INTO attempt_form_item (form_id,id,quiz_revision_id,source_item_id,position) VALUES (?,?,?,?,0)",
                bytes(f.id()), bytes(id("mismatched-parent")), bytes(other.id()), bytes(other.items().get(0).id())))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("INSERT INTO attempt_form (id,schema_version,quiz_revision_id,item_count) VALUES (?,1,?,0)",
                bytes(id("missing-parent")), bytes(id("nonexistent-revision")))).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("INSERT INTO attempt_form (id,schema_version,quiz_revision_id,item_count) VALUES (?,1,?,-1)",
                bytes(id("negative-count")), bytes(r.id()))).isInstanceOf(DataAccessException.class);
        assertThat(count("attempt_form_item")).isZero();
        assertThat(service.findByIds(OWNER, List.of(f.id()))).containsExactly(f);
    }

    @Test
    void metadataQueriesHaveIndexedLookupPlans() {
        var r = savedRevision("plans", 3);
        var f = form("plans-form", r, 3); service.createOrReplay(OWNER, f);
        var root = sql.queryForMap("EXPLAIN SELECT id,schema_version,quiz_revision_id,item_count FROM attempt_form WHERE id=?", bytes(f.id()));
        assertThat(root.get("key")).isEqualTo("PRIMARY");
        assertThat(root.get("type")).isEqualTo("const");
        var children = sql.queryForList("EXPLAIN SELECT id FROM attempt_form_item WHERE form_id=? ORDER BY position", bytes(f.id()));
        assertThat(children).hasSize(1);
        assertThat(children.get(0).get("possible_keys").toString()).contains("PRIMARY", "uq_form_item_position");
        var membership = sql.queryForMap("EXPLAIN SELECT id FROM quiz_revision_items WHERE quiz_revision_id=? AND id=?",
                bytes(r.id()), bytes(r.items().get(0).id()));
        assertThat(membership.get("key")).isEqualTo("PRIMARY");
        assertThat(membership.get("type")).isEqualTo("const");
    }

    private Outcome createOutcome(FixedForm form, String isolation) {
        try { return new Outcome(transaction(isolation).execute(status -> service.createOrReplay(OWNER, form)), null); }
        catch (FixedFormException failure) { return new Outcome(form, failure.reason()); }
    }

    private static TransactionTemplate transaction(String isolation) {
        var tx = new TransactionTemplate(transactions);
        tx.setIsolationLevel(isolation.equals("READ_COMMITTED") ? TransactionDefinition.ISOLATION_READ_COMMITTED
                : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.setTimeout(20);
        return tx;
    }

    private FixedFormService service(PlatformTransactionManager manager) {
        FixedFormRepository repository = proxy(new JdbcFixedFormRepository(new NamedParameterJdbcTemplate(source)), manager);
        return new FixedFormServiceImpl(repository, manager, metrics);
    }

    private static QuizRevision savedRevision(String key, int questions) {
        var r = revision(key, questions); revisions.insert(r); return r;
    }

    private static int count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }

    private static <T> T proxy(T target, PlatformTransactionManager manager) {
        var factory = new ProxyFactory(target);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        @SuppressWarnings("unchecked") T proxy = (T) factory.getProxy();
        return proxy;
    }

    private static void rejected(Runnable action, FixedFormException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(FixedFormException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessage("Fixed form rejected: " + reason).hasNoCause();
    }

    private record Outcome(FixedForm form, FixedFormException.Reason failure) { }
}
