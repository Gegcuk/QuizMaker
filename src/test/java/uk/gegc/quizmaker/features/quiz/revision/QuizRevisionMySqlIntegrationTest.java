package uk.gegc.quizmaker.features.quiz.revision;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.api.dto.QuestionContentRequest;
import uk.gegc.quizmaker.features.question.infra.handler.McqSingleHandler;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;
import uk.gegc.quizmaker.features.quiz.infra.repository.JdbcQuizRevisionRepository;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.quiz.revision.RevisionFixtures.*;

/** Real MySQL, real Spring transactions, no application context or remote provider. */
@Tag("db-serial")
class QuizRevisionMySqlIntegrationTest {
    private static final String HISTORY = "flyway_revision_identity_history";
    private static CountingDataSource dataSource;
    private static JdbcTemplate sql;
    private static QuizRevisionRepository repository;

    @BeforeAll
    static void connect() {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        var target = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + port
                + "/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser"));
        dataSource = new CountingDataSource(target);
        sql = new JdbcTemplate(dataSource);
        repository = transactionalRepository(new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void migrate() {
        migrateFreshTo("82");
    }

    private void migrateFreshTo(String version) {
        dropOwnedTables();
        sql.execute("CREATE TABLE revision_identity_legacy_fixture (id INT PRIMARY KEY, content VARCHAR(100))");
        sql.update("INSERT INTO revision_identity_legacy_fixture VALUES (1, 'existing content')");
        Flyway.configure().dataSource(dataSource).table(HISTORY).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("80").target(version).load().migrate();
    }

    @AfterEach
    void cleanUp() {
        dropOwnedTables();
    }

    @Test
    void migrationPreservesLegacyRowsAndCreatesOnlyEmptyRevisionStorage() {
        assertThat(sql.queryForObject("SELECT content FROM revision_identity_legacy_fixture WHERE id=1", String.class))
                .isEqualTo("existing content");
        assertThat(count("quiz_revisions")).isZero();
        assertThat(count("question_revisions")).isZero();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + HISTORY + " WHERE version='81' AND success=1", Integer.class))
                .isEqualTo(1);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM " + HISTORY + " WHERE version='82' AND success=1", Integer.class))
                .isEqualTo(1);
        assertThat(sql.queryForObject("""
                SELECT COUNT(*) FROM information_schema.referential_constraints
                WHERE constraint_schema=DATABASE() AND table_name IN
                ('quiz_revisions','question_revisions','quiz_revision_items','question_option_revisions')
                AND referenced_table_name IN ('quizzes','questions','users')
                """, Integer.class)).isZero();
    }

    @Test
    void forwardMigrationPreservesExistingStoredSnapshotsWithoutInventingLostPrecision() {
        migrateFreshTo("81");
        var question = trueFalse("old-json", "{\"answer\":true,\"metadata\":18446744073709551617}");
        var revision = quiz("old-json", question, question("old-options"));
        repository.insert(revision);
        String beforeContent = sql.queryForObject("SELECT content FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                String.class, question.id().toString());
        var beforeRevision = repository.findById(OWNER, revision.id()).orElseThrow();
        assertThat(beforeRevision).isNotEqualTo(revision); // V81 has already rounded the original integer.
        var beforeKeys = sql.queryForList("SELECT source_key FROM question_option_revisions ORDER BY authored_position", String.class);

        Flyway.configure().dataSource(dataSource).table(HISTORY).locations("classpath:db/migration")
                .target("82").load().migrate();

        assertThat(sql.queryForObject("SELECT content FROM question_revisions WHERE id=UUID_TO_BIN(?)",
                String.class, question.id().toString())).isEqualTo(beforeContent);
        assertThat(repository.findById(OWNER, revision.id())).contains(beforeRevision);
        assertThat(sql.queryForList("SELECT source_key FROM question_option_revisions ORDER BY authored_position", String.class))
                .isEqualTo(beforeKeys);
        assertThat(sql.queryForObject("SELECT content FROM revision_identity_legacy_fixture WHERE id=1", String.class))
                .isEqualTo("existing content");
        assertThat(sql.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='question_revisions' AND column_name='content'
                """, String.class)).isEqualTo("longtext");
        assertThat(sql.queryForObject("""
                SELECT collation_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='question_option_revisions' AND column_name='source_key'
                """, String.class)).isEqualTo("utf8mb4_0900_bin");
        repository.insert(quiz("reuse-migrated", beforeRevision.items().stream()
                .map(QuizRevision.Item::question).toArray(QuestionRevision[]::new)));
        assertThatThrownBy(() -> repository.insert(quiz("reuse-original-lost-number", question)))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("IDENTITY_CONFLICT");
        assertThat(count("question_revisions")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "[]", "null", "true"})
    void invalidStoredJsonAndNonObjectsFailClosedAtTheDomainBoundary(String content) {
        repository.insert(quiz("json-constraint", question("json-constraint")));
        sql.update("UPDATE question_revisions SET content=?", content);
        assertThatThrownBy(() -> repository.findById(OWNER, id("json-constraint")))
                .isInstanceOf(RevisionIntegrityException.class)
                .hasMessage("Immutable revision rejected: INVALID_STORED_REVISION");
    }

    @Test
    void jdbcAggregateJoinsExternalJpaTransactionForBothCommitAndRollback() {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features.quiz.revision");
        factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "none"));
        factory.afterPropertiesSet();
        try {
            var entityManagerFactory = factory.getObject();
            var manager = new JpaTransactionManager(entityManagerFactory);
            manager.setDataSource(dataSource);
            var transaction = new TransactionTemplate(manager);
            var jpaRepository = transactionalRepository(manager);
            var committed = quiz("jpa-commit", trueFalse("jpa-commit", "{\"answer\":true,\"metadata\":18446744073709551617}"));
            transaction.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
                        .createNativeQuery("UPDATE revision_identity_legacy_fixture SET content='JPA committed' WHERE id=1")
                        .executeUpdate();
                jpaRepository.insert(committed);
            });
            transaction.executeWithoutResult(status -> {
                EntityManagerFactoryUtils.getTransactionalEntityManager(entityManagerFactory)
                        .createNativeQuery("UPDATE revision_identity_legacy_fixture SET content='JPA rolled back' WHERE id=1")
                        .executeUpdate();
                jpaRepository.insert(quiz("jpa-rollback", question("jpa-rollback")));
                status.setRollbackOnly();
            });
            assertThat(repository.findById(OWNER, committed.id())).contains(committed);
            assertThat(repository.findById(OWNER, id("jpa-rollback"))).isEmpty();
            assertThat(count("question_revisions")).isEqualTo(1);
            assertThat(count("quiz_revision_items")).isEqualTo(1);
            assertThat(count("question_option_revisions")).isZero();
            assertThat(sql.queryForObject("SELECT content FROM revision_identity_legacy_fixture WHERE id=1", String.class))
                    .isEqualTo("JPA committed");
        } finally {
            factory.destroy();
        }
    }

    @Test
    void multiParentAndMultiQuestionReadsUseThreePhysicalQueries() {
        List<QuizRevision> revisions = new ArrayList<>();
        for (int parent = 0; parent < 5; parent++) {
            var revision = quiz("bounded-" + parent, question("bounded-" + parent + "-a"),
                    question("bounded-" + parent + "-b"), question("bounded-" + parent + "-c"));
            repository.insert(revision);
            revisions.add(revision);
        }
        dataSource.selects.set(0);
        assertThat(repository.findById(OWNER, revisions.get(0).id())).contains(revisions.get(0));
        assertThat(dataSource.selects).hasValue(3);
        dataSource.selects.set(0);
        assertThat(repository.findByIds(OWNER, revisions.stream().map(QuizRevision::id).toList())).isEqualTo(revisions);
        assertThat(dataSource.selects).hasValue(3);
    }

    @Test
    void missingWrongOwnerAndEmptyReadsNeverFallBackToMutableContent() {
        var revision = quiz("scoped", question("scoped"));
        repository.insert(revision);
        dataSource.selects.set(0);
        assertThat(repository.findById(id("other-owner"), revision.id())).isEmpty();
        assertThat(dataSource.selects).hasValue(1);
        assertThat(repository.findById(OWNER, id("missing-revision"))).isEmpty();
        var other = new QuizRevision(id("other-owner-revision"), revision.quizId(), id("other-owner"), 1,
                "Another owner's quiz", null, CREATED, quiz("other-owner-items", question("other-owner")).items());
        repository.insert(other);
        assertThat(repository.findByIds(OWNER, List.of(revision.id(), other.id()))).containsExactly(revision);
        dataSource.selects.set(0);
        assertThat(repository.findByIds(OWNER, List.of())).isEmpty();
        assertThat(dataSource.selects).hasValue(0);
        assertThatThrownBy(() -> repository.findByIds(null, List.of(revision.id())))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void matchingQuestionRevisionsCanBeSharedWithoutUpdatingThem() {
        var question = question("shared");
        var first = quiz("first", question);
        var second = quiz("second", question);
        repository.insert(first);
        repository.insert(second);
        assertThat(count("question_revisions")).isEqualTo(1);
        assertThat(count("question_option_revisions")).isEqualTo(2);
        assertThat(repository.findByIds(OWNER, List.of(first.id(), second.id()))).containsExactly(first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "18446744073709551617",
            "0.1234567890123456789012345678901234567890",
            "12345678901234567890.00000000000000000001",
            "1.234567890123456789E+100",
            "1.234567890123456789E-100",
            "1E+400",
            "1E-400"
    })
    void numericSnapshotsRoundTripExactly(String number) {
        String content = "{\"answer\":true,\"metadata\":" + number + "}";
        var revision = quiz("numeric", trueFalse("numeric", content));
        repository.insert(revision);
        assertThat(sql.queryForObject("SELECT content FROM question_revisions", String.class)).isEqualTo(content);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test
    void identicalLargeNumberQuestionCanBeReusedButChangedContentIsRejected() {
        var question = trueFalse("reuse-number", "{\"answer\":true,\"metadata\":18446744073709551617}");
        var first = quiz("numeric-first", question);
        var second = quiz("numeric-second", question);
        repository.insert(first);
        repository.insert(second);
        var conflicting = trueFalse("reuse-number", "{\"answer\":true,\"metadata\":18446744073709551618}");
        assertThatThrownBy(() -> repository.insert(quiz("numeric-conflict", conflicting)))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("IDENTITY_CONFLICT");
        assertThat(count("question_revisions")).isEqualTo(1);
        assertThat(count("quiz_revisions")).isEqualTo(2);
        assertThat(repository.findByIds(OWNER, List.of(first.id(), second.id()))).containsExactly(first, second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "a "})
    void optionKeysPreserveCaseAndTrailingSpacesAndRejectExactDuplicates(String secondKey) {
        String content = "{\"options\":[{\"id\":\"a\",\"text\":\"Paris\",\"correct\":true},"
                + "{\"id\":\"" + secondKey + "\",\"text\":\"London\",\"correct\":false}]}";
        var payload = QuestionContracts.read(QuestionType.MCQ_SINGLE, QuestionContracts.View.AUTHOR, content);
        new McqSingleHandler().validateContent(new QuestionContentRequest() {
            public QuestionType getType() { return QuestionType.MCQ_SINGLE; }
            public com.fasterxml.jackson.databind.JsonNode getContent() { return payload; }
        });
        var base = question("exact-key");
        var question = new QuestionRevision(base.id(), base.questionId(), 1, base.type(), base.difficulty(),
                base.questionText(), content, null, null, null, null, CREATED,
                List.of(new QuestionRevision.Option(id("exact-lower"), "a", 0),
                        new QuestionRevision.Option(id("exact-second"), secondKey, 1)));
        var revision = quiz("exact-key", question);
        repository.insert(revision);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
        assertThat(sql.queryForList("SELECT source_key FROM question_option_revisions ORDER BY authored_position", String.class))
                .containsExactly("a", secondKey);
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO question_option_revisions (id, question_revision_id, source_key, authored_position)
                VALUES (UUID_TO_BIN(?), UUID_TO_BIN(?), ?, 2)
                """, id("exact-duplicate").toString(), question.id().toString(), secondKey))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                .hasMessageContaining("uq_question_revision_option_key");
        assertThat(count("question_option_revisions")).isEqualTo(2);
    }

    @Test
    void conflictingQuestionIdentityIsRejectedWithoutChangingStoredContent() {
        var question = question("conflict");
        var first = quiz("first", question);
        repository.insert(first);
        var conflicting = changed(question);
        assertThatThrownBy(() -> repository.insert(quiz("second", conflicting)))
                .isInstanceOf(RevisionIntegrityException.class).hasMessageContaining("IDENTITY_CONFLICT");
        assertThat(count("quiz_revisions")).isEqualTo(1);
        assertThat(repository.findById(OWNER, first.id())).contains(first);
    }

    @Test
    void duplicateRootAndLateItemConstraintFailureRollBackEveryNewRow() {
        var first = quiz("duplicate-root", question("original"));
        repository.insert(first);
        assertThatThrownBy(() -> repository.insert(quiz("duplicate-root", question("orphan"))))
                .isInstanceOf(DataAccessException.class);
        assertThat(count("question_revisions")).isEqualTo(1);
        // A real database failure after parent/question writes, not a mocked collaborator result.
        sql.execute("ALTER TABLE quiz_revision_items ADD CONSTRAINT chk_synthetic_item_failure CHECK (authored_position <> 7)");
        var lateFailure = new QuizRevision(id("late"), first.quizId(), OWNER, 1, "Title", null, CREATED,
                List.of(new QuizRevision.Item(id("late-item"), 7, question("late"))));
        assertThatThrownBy(() -> repository.insert(lateFailure)).isInstanceOf(DataAccessException.class);
        assertThat(count("question_revisions")).isEqualTo(1);
        assertThat(count("question_option_revisions")).isEqualTo(2);
        assertThat(count("quiz_revisions")).isEqualTo(1);
        assertThat(count("quiz_revision_items")).isEqualTo(1);
    }

    @Test
    void stableItemAndOptionIdsSurviveReorderingInANewRevision() {
        var original = quiz("ordered-v1", question("ordered"), question("other-ordered"));
        var oldQuestion = original.items().get(0).question();
        var a = oldQuestion.options().get(0);
        var b = oldQuestion.options().get(1);
        var reorderedQuestion = new QuestionRevision(id("reordered-question-v2"), oldQuestion.questionId(), 1,
                oldQuestion.type(), oldQuestion.difficulty(), oldQuestion.questionText(),
                "{\"options\":[{\"id\":\"b\",\"text\":\"London\",\"correct\":false},{\"id\":\"a\",\"text\":\"Paris\",\"correct\":true}]}",
                oldQuestion.hint(), oldQuestion.explanation(), null, null, CREATED,
                List.of(new QuestionRevision.Option(a.id(), a.sourceKey(), 1),
                        new QuestionRevision.Option(b.id(), b.sourceKey(), 0)));
        var next = new QuizRevision(id("ordered-v2"), original.quizId(), OWNER, 1, "Title", null, CREATED, List.of(
                new QuizRevision.Item(original.items().get(0).id(), 1, reorderedQuestion),
                new QuizRevision.Item(original.items().get(1).id(), 0, original.items().get(1).question())));
        repository.insert(original);
        repository.insert(next);
        assertThat(repository.findByIds(OWNER, List.of(original.id(), next.id()))).containsExactly(original, next);
        assertThat(repository.findById(OWNER, next.id()).orElseThrow().items().get(1).question().options())
                .extracting(QuestionRevision.Option::id).containsExactly(b.id(), a.id());
    }

    @Test
    void everyCurrentQuestionFamilyRoundTripsThroughMysqlStorage() {
        List<QuestionRevision> questions = new ArrayList<>();
        for (QuestionType type : QuestionType.values()) {
            var base = question(type.name());
            var payload = QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
            List<QuestionRevision.Option> options = new ArrayList<>();
            payload.fields().forEachRemaining(field -> {
                if (field.getValue().isArray()) {
                    for (var option : field.getValue()) {
                        if (option.has("id")) {
                            String key = field.getKey() + "/" + option.get("id").asText();
                            options.add(new QuestionRevision.Option(id(type.name() + key), key, options.size()));
                        }
                    }
                }
            });
            questions.add(new QuestionRevision(base.id(), base.questionId(), 1, type, base.difficulty(),
                    base.questionText(), payload.toString(),
                    null, null, "https://example.invalid/image.png", id("asset"), CREATED, options));
        }
        var revision = quiz("all-families", questions.toArray(QuestionRevision[]::new));
        repository.insert(revision);
        assertThat(repository.findById(OWNER, revision.id())).contains(revision);
    }

    @Test
    void optionKeysAreCaseSensitiveAndInputBatchesAreDeduplicatedAndBounded() {
        var base = question("keys");
        var question = new QuestionRevision(base.id(), base.questionId(), 1, base.type(), base.difficulty(),
                base.questionText(), "{\"options\":[{\"id\":\"a\",\"text\":\"Paris\",\"correct\":true},{\"id\":\"A\",\"text\":\"London\",\"correct\":false}]}", null, null, null, null, CREATED,
                List.of(new QuestionRevision.Option(id("lower"), "a", 0),
                        new QuestionRevision.Option(id("upper"), "A", 1)));
        var first = quiz("batch-first", question);
        var last = quiz("batch-last", question);
        repository.insert(first);
        repository.insert(last);
        List<UUID> ids = new ArrayList<>();
        ids.add(first.id());
        for (int index = 0; index < 499; index++) ids.add(id("missing-" + index));
        ids.add(last.id());
        ids.add(first.id());
        dataSource.selects.set(0);
        assertThat(repository.findByIds(OWNER, ids)).containsExactly(first, last);
        assertThat(dataSource.selects).hasValue(6);
    }

    @Test
    void relationalConstraintsRejectInvalidIdentityPositionsAndDeletionOfReferencedRows() {
        var revision = quiz("constraints", question("constraints"));
        repository.insert(revision);
        String root = revision.id().toString();
        String question = revision.items().get(0).question().id().toString();
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO quiz_revision_items VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),-1)
                """, id("negative").toString(), root, question)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO quiz_revision_items VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),0)
                """, id("duplicate-position").toString(), root, question)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("""
                INSERT INTO quiz_revision_items VALUES (UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),1)
                """, id("missing-ref").toString(), root, id("missing").toString())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM quiz_revisions WHERE id=UUID_TO_BIN(?)", root))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> sql.update("DELETE FROM question_revisions WHERE id=UUID_TO_BIN(?)", question))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void unknownStoredQuestionTypeFailsClosedWithNoSensitiveContentInTheError() {
        var revision = quiz("unknown", question("unknown"));
        repository.insert(revision);
        sql.update("UPDATE question_revisions SET question_type='FUTURE_TYPE'");
        assertThatThrownBy(() -> repository.findById(OWNER, revision.id()))
                .isInstanceOf(RevisionIntegrityException.class)
                .hasMessage("Immutable revision rejected: INVALID_STORED_REVISION");
    }

    @Test
    void concurrentConflictingSnapshotsHaveOneWinnerAndNoOrphanRows() throws Exception {
        var question = question("race");
        var conflicting = new QuestionRevision(question.id(), question.questionId(), 1, question.type(),
                question.difficulty(), "Changed question", """
                {"options":[{"id":"b","text":"London","correct":true},{"id":"a","text":"Paris","correct":false}]}
                """, "Changed hint", "Changed explanation", null, null, CREATED,
                List.of(new QuestionRevision.Option(id("race-other-b"), "b", 0),
                        new QuestionRevision.Option(id("race-other-a"), "a", 1)));
        var firstInput = quiz("race-first", question);
        var secondInput = quiz("race-second", conflicting);
        assertThat(firstInput.id()).isNotEqualTo(secondInput.id());
        assertThat(count("question_revisions")).isZero();
        var gate = new QuestionInsertGateDataSource(dataSource);
        var concurrentRepository = transactionalRepository(gate, new DataSourceTransactionManager(gate));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> insertOutcome(concurrentRepository, firstInput));
            var second = executor.submit(() -> insertOutcome(concurrentRepository, secondInput));
            gate.awaitBothTransactions();
            assertThat(gate.completedEmptyReads()).isEqualTo(2);
            assertThat(gate.transactionConnections()).hasSize(2);
            assertThat(gate.executedInserts()).isZero();
            assertThat(count("question_revisions")).isZero();
            gate.releaseInserts();

            var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            var winners = outcomes.stream().filter(outcome -> outcome.conflict() == null).toList();
            var losers = outcomes.stream().filter(outcome -> outcome.conflict() != null).toList();
            assertThat(winners).hasSize(1);
            assertThat(losers).hasSize(1);
            var winner = winners.get(0).revision();
            var loser = losers.get(0);
            assertThat(loser.conflict()).hasMessageContaining("INSERT INTO question_revisions");
            assertThat(loser.conflict().getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class, cause -> {
                assertThat(cause.getErrorCode()).isEqualTo(1062);
                assertThat(cause.getSQLState()).isEqualTo("23000");
                assertThat(cause.getMessage()).contains("PRIMARY");
            });
            assertThat(gate.executedInserts()).isEqualTo(2);
            assertThat(repository.findById(OWNER, winner.id())).contains(winner);
            assertThat(repository.findById(OWNER, loser.revision().id())).isEmpty();
            assertThat(repository.findByIds(OWNER, List.of(firstInput.id(), secondInput.id()))).containsExactly(winner);
            assertThat(count("quiz_revisions")).isEqualTo(1);
            assertThat(count("question_revisions")).isEqualTo(1);
            assertThat(count("quiz_revision_items")).isEqualTo(1);
            assertThat(count("question_option_revisions")).isEqualTo(2);
            assertThat(sql.queryForObject("""
                    SELECT COUNT(*) FROM quiz_revision_items i
                    LEFT JOIN quiz_revisions r ON r.id=i.quiz_revision_id
                    LEFT JOIN question_revisions q ON q.id=i.question_revision_id
                    WHERE r.id IS NULL OR q.id IS NULL
                    """, Integer.class)).isZero();
            assertThat(sql.queryForObject("""
                    SELECT COUNT(*) FROM question_revisions q
                    LEFT JOIN quiz_revision_items i ON i.question_revision_id=q.id WHERE i.id IS NULL
                    """, Integer.class)).isZero();
            assertThat(sql.queryForObject("""
                    SELECT COUNT(*) FROM question_option_revisions o
                    LEFT JOIN question_revisions q ON q.id=o.question_revision_id WHERE q.id IS NULL
                    """, Integer.class)).isZero();
        } finally {
            gate.releaseInserts();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS))
                    .as("Both concurrent revision writers must terminate").isTrue();
        }
    }

    private InsertOutcome insertOutcome(QuizRevisionRepository target, QuizRevision revision) {
        try {
            target.insert(revision);
            return new InsertOutcome(revision, null);
        } catch (DuplicateKeyException conflict) {
            return new InsertOutcome(revision, conflict);
        }
    }

    private record InsertOutcome(QuizRevision revision, DuplicateKeyException conflict) { }

    private static QuestionRevision changed(QuestionRevision q) {
        return new QuestionRevision(q.id(), q.questionId(), 1, q.type(), q.difficulty(), "Changed question",
                q.content(), q.hint(), q.explanation(), null, null, q.createdAt(), q.options());
    }

    private static QuestionRevision trueFalse(String key, String content) {
        QuestionContracts.read(QuestionType.TRUE_FALSE, QuestionContracts.View.AUTHOR, content);
        var base = question(key);
        return new QuestionRevision(base.id(), base.questionId(), 1, QuestionType.TRUE_FALSE, base.difficulty(),
                "Is the statement true?", content, null, null, null, null, CREATED, List.of());
    }

    private static QuizRevisionRepository transactionalRepository(PlatformTransactionManager manager) {
        return transactionalRepository(dataSource, manager);
    }

    private static QuizRevisionRepository transactionalRepository(javax.sql.DataSource source, PlatformTransactionManager manager) {
        var factory = new ProxyFactory(new JdbcQuizRevisionRepository(new NamedParameterJdbcTemplate(source)));
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (QuizRevisionRepository) factory.getProxy();
    }

    private int count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static void dropOwnedTables() {
        for (String table : List.of("quiz_revision_items", "question_option_revisions", "quiz_revisions",
                "question_revisions", HISTORY, "revision_identity_legacy_fixture")) {
            sql.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private static final class CountingDataSource extends DelegatingDataSource {
        final AtomicInteger selects = new AtomicInteger();

        CountingDataSource(javax.sql.DataSource target) { super(target); }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String statement
                                && statement.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
                            selects.incrementAndGet();
                        }
                        try { return method.invoke(connection, arguments); }
                        catch (InvocationTargetException exception) { throw exception.getCause(); }
                    });
        }
    }
}
