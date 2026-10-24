package uk.gegc.quizmaker.features.question.contract;

import com.fasterxml.jackson.databind.node.BooleanNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    }

    @BeforeEach
    void migrate() {
        clean();
        sql.execute("CREATE TABLE contract_legacy_fixture (id INT PRIMARY KEY)");
        Flyway.configure().dataSource(source).table(IDENTITY_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("80").target("82").load().migrate();
        migrateBindings();
        source.reset();
    }

    private void migrateBindings() {
        // V83 changes normalized_documents, outside this narrowly scoped revision-storage fixture.
        Flyway.configure().dataSource(source).table(BINDING_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("83").target("84").load().migrate();
    }

    @AfterEach
    void clean() {
        source.gate = null;
        for (String table : List.of("question_revision_contract_bindings", "quiz_revision_items",
                "question_option_revisions", "quiz_revisions", "question_revisions", IDENTITY_HISTORY, BINDING_HISTORY,
                "contract_legacy_fixture")) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    void additiveMigrationKeepsExistingRevisionsUnboundWithoutInventingVersions() {
        sql.execute("DROP TABLE question_revision_contract_bindings");
        sql.execute("DROP TABLE " + BINDING_HISTORY);
        var revision = quiz(10, question(1, "{\"answer\":true}"));
        revisions.insert(revision);
        migrateBindings();
        assertThat(revisions.findById(OWNER, revision.id())).contains(revision);
        assertThat(countBindings()).isZero();
        rejected(() -> service.resolve(OWNER, revision.id()), UNBOUND_REVISION);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + BINDING_HISTORY
                + " WHERE version='84' AND success=1", Integer.class)).isEqualTo(1);
        service.bind(OWNER, revision.id(), List.of(binding(1, 1)));
        assertThat(service.resolve(OWNER, revision.id()).keySet()).containsExactly(id(1));
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
            tx.executeWithoutResult(status -> service.bind(OWNER, id(10), List.of(binding(1, version))));
            return null;
        } catch (ContractResolutionException failure) { return failure.reason(); }
    }

    @Test
    void contracts04MetadataReadsAreOneQueryForMultipleParentsAndNeverSelectLargeContent() {
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
        assertThat(source.selects).hasSize(1);
        assertThat(source.selects.get(0)).doesNotContain("content", "question_text", "normalized_text", "SELECT *");
        source.reset();
        assertThat(service.resolve(OWNER, id(11))).hasSize(2);
        assertThat(source.selects).hasSize(1);
        assertThat(source.selects).allSatisfy(statement -> assertThat(statement).doesNotContain("content", "question_text"));
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
        var factory = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(source);
        factory.setJpaVendorAdapter(new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.question.contract");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        try {
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager(factory.getObject());
            jpa.setDataSource(source);
            QuizRevisionRepository jpaRevisions = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)), jpa);
            var jpaService = service(registry(), jpa);
            var first = quiz(10, question(1, "{\"answer\":true}"));
            new TransactionTemplate(jpa).executeWithoutResult(status -> {
                jpaRevisions.insert(first);
                jpaService.bind(OWNER, first.id(), List.of(binding(1, 1)));
            });
            source.bindingWrites.set(0);
            new TransactionTemplate(jpa).executeWithoutResult(status -> {
                var second = quiz(11, question(2, "{\"answer\":false}"));
                jpaRevisions.insert(second);
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

    private static QuestionRevisionContractService service(QuestionContractRegistry registry, PlatformTransactionManager manager) {
        QuestionContractBindingRepository repository = proxy(
                new JdbcQuestionContractBindingRepository(new NamedParameterJdbcTemplate(source)), manager);
        return proxy(new QuestionRevisionContractServiceImpl(repository, registry), manager);
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

    private static final class ObservedDataSource extends DelegatingDataSource {
        final List<String> selects = new CopyOnWriteArrayList<>();
        final AtomicInteger bindingWrites = new AtomicInteger();
        volatile Gate gate;
        ObservedDataSource(DataSource target) { super(target); }
        void reset() { selects.clear(); bindingWrites.set(0); }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        Object result = invoke(connection, method, arguments);
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String statement) {
                            if (statement.stripLeading().startsWith("SELECT")) selects.add(statement);
                            if (statement.stripLeading().startsWith("INSERT INTO question_revision_contract_bindings")) {
                                var prepared = (PreparedStatement) result;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                        (unused, operation, values) -> {
                                            if (operation.getName().equals("executeUpdate")) {
                                                Gate current = gate;
                                                if (current != null) {
                                                    current.connections.add(connection);
                                                    current.arrived.countDown();
                                                    if (!current.release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer gate timed out");
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
