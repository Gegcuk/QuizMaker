package uk.gegc.quizmaker.features.question.contract;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.application.impl.QuestionRevisionContractServiceImpl;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.question.contract.ContractFixtures.*;
import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

/** The schema-changing migration scenario has its own lifecycle in the serial MySQL lane. */
@Tag("db-serial")
class QuestionRevisionContractsMigrationMySqlIntegrationTest {
    private static final String IDENTITY_HISTORY = "flyway_contract_identity_history";
    private static final String BINDING_HISTORY = "flyway_contract_binding_history";
    private static DriverManagerDataSource source;
    private static JdbcTemplate sql;
    private static QuizRevisionRepository revisions;
    private static QuestionRevisionContractService service;

    @BeforeAll
    static void migrate() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser"));
        sql = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        revisions = proxy(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)), manager);
        service = service(registry(), manager);
        dropSchema();
        sql.execute("CREATE TABLE contract_legacy_fixture (id INT PRIMARY KEY)");
        Flyway.configure().dataSource(source).table(IDENTITY_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("80").target("82").load().migrate();
        migrateBindings();
    }

    private static void migrateBindings() {
        Flyway.configure().dataSource(source).table(BINDING_HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("83").target("84").load().migrate();
    }

    @AfterAll
    static void dropSchema() {
        if (sql == null) return;
        for (String table : List.of("attempt_form_option", "attempt_form_item_option_order", "attempt_form_option_order",
                "attempt_policy_snapshot", "attempt_form_item", "attempt_form",
                "contract_transaction_receipts", "question_revision_contract_bindings", "quiz_revision_items",
                "question_option_revisions", "quiz_revisions", "question_revisions", IDENTITY_HISTORY, BINDING_HISTORY,
                "contract_legacy_fixture")) sql.execute("DROP TABLE IF EXISTS " + table);
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

}
