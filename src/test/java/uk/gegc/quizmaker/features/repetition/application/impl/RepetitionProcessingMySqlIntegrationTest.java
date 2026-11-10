package uk.gegc.quizmaker.features.repetition.application.impl;

import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.domain.model.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.repetition.application.*;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.config.TransactionConfig;
import uk.gegc.quizmaker.shared.transaction.SafeJpaTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** Real processing and commit-time uniqueness after Flyway; no collaborator replaces persistence. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({TransactionConfig.class, RepetitionProcessingServiceImpl.class, QuestionRepetitionStrategy.class,
        QuestionAttemptContentResolver.class, RepetitionStrategyRegistry.class, RepetitionReviewLogFactory.class,
        Sm2Algorithm.class, RepetitionProcessingMySqlIntegrationTest.Config.class})
class RepetitionProcessingMySqlIntegrationTest {
    static final String SCHEMA = "repetition_processing_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    static boolean schemaCreated;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true;
        properties.add("spring.datasource.url", () -> base + SCHEMA + options);
        properties.add("spring.datasource.username", () -> user);
        properties.add("spring.datasource.password", () -> password);
    }

    @AfterAll static void dropDatabase() {
        if (schemaCreated) admin.execute("DROP DATABASE " + SCHEMA);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC); }
    }

    @Autowired RepetitionProcessingService processing;
    @Autowired AttemptRepository attempts;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate sql;

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void resumesPastAnAlreadyProcessedFirstAnswerAndFullReplayDoesNotReschedule(boolean partiallyProcessed) {
        assertThat(manager).isInstanceOf(SafeJpaTransactionManager.class);
        var tx = new TransactionTemplate(manager);
        UUID attemptId = tx.execute(status -> fixture());
        var loaded = attempts.findFullyLoadedById(attemptId).orElseThrow();
        assertThat(loaded.getAnswers()).hasSize(2);
        var first = loaded.getAnswers().get(0);
        var second = loaded.getAnswers().get(1);

        List<Map<String, Object>> firstSchedule = List.of();
        List<Map<String, Object>> firstLog = List.of();
        if (partiallyProcessed) {
            processing.processAnswerTx(first);
            firstSchedule = schedule(tx, first.getQuestion().getId(), loaded.getUser().getId());
            firstLog = logs(tx, attemptId);
            assertThat(firstSchedule).hasSize(1);
            assertThat(firstLog).hasSize(1).allSatisfy(row -> assertThat(row.get("source_id")).isEqualTo(first.getId().toString()));
            assertThat(schedule(tx, second.getQuestion().getId(), loaded.getUser().getId())).isEmpty();
            // Prove the duplicate is visited before the pending answer, rather than relying on list order assumptions.
            assertThat(attempts.findFullyLoadedById(attemptId).orElseThrow().getAnswers().get(0).getId()).isEqualTo(first.getId());
        }

        processing.processAttempt(attemptId);
        var completed = logs(tx, attemptId);
        assertThat(completed).hasSize(2);
        assertThat(completed).extracting(row -> row.get("source_id"))
                .containsExactlyInAnyOrder(first.getId().toString(), second.getId().toString());
        var firstAfter = schedule(tx, first.getQuestion().getId(), loaded.getUser().getId());
        var secondAfter = schedule(tx, second.getQuestion().getId(), loaded.getUser().getId());
        assertThat(firstAfter).hasSize(1).allSatisfy(row -> assertThat(row.get("repetition_count")).isEqualTo(1));
        assertThat(secondAfter).hasSize(1).allSatisfy(row -> assertThat(row.get("repetition_count")).isEqualTo(1));
        if (partiallyProcessed) {
            assertThat(firstAfter).isEqualTo(firstSchedule);
            assertThat(completed).containsAll(firstLog);
        }

        processing.processAttempt(attemptId);
        assertThat(logs(tx, attemptId)).isEqualTo(completed);
        assertThat(schedule(tx, first.getQuestion().getId(), loaded.getUser().getId())).isEqualTo(firstAfter);
        assertThat(schedule(tx, second.getQuestion().getId(), loaded.getUser().getId())).isEqualTo(secondAfter);
    }

    private UUID fixture() {
        var user = new User();
        String name = "repetition-" + UUID.randomUUID().toString().substring(0, 20);
        user.setUsername(name); user.setEmail(name + "@example.invalid"); user.setHashedPassword("test-only");
        user.setActive(true); user.setRoles(new HashSet<>()); entityManager.persist(user);
        var category = new Category(); category.setName(name); entityManager.persist(category);
        var quiz = new Quiz(); quiz.setCreator(user); quiz.setCategory(category); quiz.setTitle(name);
        quiz.setDifficulty(Difficulty.EASY); quiz.setVisibility(Visibility.PRIVATE); quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(5); quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(true); quiz.setIsDeleted(false);
        entityManager.persist(quiz);
        var attempt = new Attempt(); attempt.setUser(user); attempt.setQuiz(quiz);
        attempt.setMode(AttemptMode.ALL_AT_ONCE); attempt.setStatus(AttemptStatus.COMPLETED);
        attempt.setCompletedAt(Instant.parse("2026-11-10T12:00:00Z")); entityManager.persist(attempt);
        for (int n = 0; n < 2; n++) {
            var question = new Question(); question.setQuestionText("Synthetic question " + n);
            question.setType(QuestionType.TRUE_FALSE); question.setDifficulty(Difficulty.EASY);
            question.setContent("{\"answer\":true}"); entityManager.persist(question); quiz.getQuestions().add(question);
            var answer = new Answer(); answer.setAttempt(attempt); answer.setQuestion(question);
            answer.setResponse("true"); answer.setIsCorrect(true); answer.setScore(1.0);
            answer.setAnsweredAt(attempt.getCompletedAt()); entityManager.persist(answer); attempt.getAnswers().add(answer);
        }
        return attempt.getId();
    }

    private List<Map<String, Object>> schedule(TransactionTemplate tx, UUID question, UUID user) {
        return tx.execute(status -> sql.queryForList("""
                SELECT BIN_TO_UUID(id) id, next_review_at, interval_days, repetition_count, ease_factor,
                       last_reviewed_at, last_grade, reminder_enabled, version, created_at, updated_at
                FROM spaced_repetition_entry WHERE question_id=UUID_TO_BIN(?) AND user_id=UUID_TO_BIN(?)
                """, question.toString(), user.toString()));
    }

    private List<Map<String, Object>> logs(TransactionTemplate tx, UUID attempt) {
        return tx.execute(status -> sql.queryForList("""
                SELECT BIN_TO_UUID(repetition_review_id) id, BIN_TO_UUID(entry_id) entry_id,
                       BIN_TO_UUID(source_id) source_id, content_type, BIN_TO_UUID(content_id) content_id,
                       grade, reviewed_at, interval_days, ease_factor, repetition_count, source_type
                FROM repetition_review_log WHERE attempt_id=UUID_TO_BIN(?) ORDER BY source_id
                """, attempt.toString()));
    }
}
