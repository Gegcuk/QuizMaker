package uk.gegc.quizmaker.features.result.application.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.result.domain.model.QuizAnalyticsSnapshot;
import uk.gegc.quizmaker.features.result.domain.repository.QuizAnalyticsSnapshotRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for Quiz Analytics Snapshot with REQUIRES_NEW propagation.
 * <p>
 * Uses Pattern #1: No ambient test transaction + explicit cleanup.
 * This allows services to create their own transactions exactly like production,
 * making REQUIRES_NEW behavior testable.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED) // Disable framework transaction
@DisplayName("Quiz Analytics Snapshot Integration Test - REQUIRES_NEW")
public class QuizAnalyticsSnapshotIntegrationTest {

    @Autowired
    private AttemptService attemptService;

    @Autowired
    private QuizAnalyticsSnapshotRepository snapshotRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private AnswerRepository answerRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager em;

    private User testUser;
    private Quiz testQuiz;
    private Question question1;
    private Question question2;

    @BeforeEach
    void setUp() {
        // Create test data in explicit transaction (commits at end)
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // Create user
            testUser = new User();
            testUser.setUsername("snapuser_" + System.currentTimeMillis());
            testUser.setEmail("snapuser_" + System.currentTimeMillis() + "@test.com");
            testUser.setHashedPassword("hashed");
            testUser.setActive(true);
            testUser.setDeleted(false);
            testUser = userRepository.save(testUser);

            // Create category
            Category category = new Category();
            category.setName("SnapCat_" + System.currentTimeMillis());
            category = categoryRepository.save(category);

            // Create quiz
            testQuiz = new Quiz();
            testQuiz.setTitle("Snapshot Test");
            testQuiz.setDescription("Test");
            testQuiz.setCreator(testUser);
            testQuiz.setCategory(category);
            testQuiz.setDifficulty(Difficulty.MEDIUM);
            testQuiz.setStatus(QuizStatus.PUBLISHED);
            testQuiz.setVisibility(Visibility.PUBLIC);
            testQuiz.setEstimatedTime(10);
            testQuiz.setIsRepetitionEnabled(false);
            testQuiz.setIsTimerEnabled(false);
            testQuiz.setIsDeleted(false);
            testQuiz.setQuestions(new HashSet<>());
            testQuiz = quizRepository.save(testQuiz);

            // Create questions
            question1 = new Question();
            question1.setQuestionText("Q1");
            question1.setType(QuestionType.TRUE_FALSE);
            question1.setContent("{\"answer\":true}");
            question1.setDifficulty(Difficulty.EASY);
            question1.setIsDeleted(false);
            question1 = questionRepository.save(question1);

            question2 = new Question();
            question2.setQuestionText("Q2");
            question2.setType(QuestionType.TRUE_FALSE);
            question2.setContent("{\"answer\":true}");
            question2.setDifficulty(Difficulty.EASY);
            question2.setIsDeleted(false);
            question2 = questionRepository.save(question2);

            // Add questions to quiz
            testQuiz.getQuestions().add(question1);
            testQuiz.getQuestions().add(question2);
            quizRepository.save(testQuiz);
        });
        // Transaction commits here - data is now visible to all subsequent transactions
    }

    @AfterEach
    void cleanup() {
        // Fast & robust cleanup using SQL (bypasses soft-delete and JPA cascades)
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            em.flush();
            em.clear();
            
            // Temporarily disable FK checks for clean deletion
            jdbc.execute("SET FOREIGN_KEY_CHECKS=0");

            // Delete join tables first
            jdbc.update("DELETE FROM quiz_questions");
            jdbc.update("DELETE FROM question_tags");

            // Delete dependent entities
            jdbc.update("DELETE FROM answers");
            jdbc.update("DELETE FROM attempts");
            jdbc.update("DELETE FROM quiz_analytics_snapshot");

            // Delete principal entities (physically, bypassing @SQLDelete)
            jdbc.update("DELETE FROM questions");
            jdbc.update("DELETE FROM quizzes");
            jdbc.update("DELETE FROM categories");
            jdbc.update("DELETE FROM users");

            // Re-enable FK checks
            jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
        });
    }

    @Test
    @DisplayName("Completing attempt creates snapshot via async event with REQUIRES_NEW")
    void completeAttempt_createsSnapshot_viaRequiresNew() throws Exception {
        // Given - create attempt in explicit transaction (commits, visible to all)
        UUID attemptId = new TransactionTemplate(txManager).execute(status -> {
            Attempt attempt = new Attempt();
            attempt.setUser(testUser);
            attempt.setQuiz(testQuiz);
            attempt.setMode(AttemptMode.ALL_AT_ONCE);
            attempt.setStatus(AttemptStatus.IN_PROGRESS);
            attempt.setAnswers(new ArrayList<>());
            attempt = attemptRepository.save(attempt);

            // Add correct answer
            Answer answer = new Answer();
            answer.setAttempt(attempt);
            answer.setQuestion(question1);
            answer.setResponse("{\"answer\":true}");
            answer.setIsCorrect(true);
            answer.setScore(10.0);
            answer.setAnsweredAt(Instant.now());
            answer = answerRepository.save(answer);
            
            attempt.getAnswers().add(answer);
            return attempt.getId();
        });
        // Transaction commits - attempt is now committed and visible

        // When - complete attempt (publishes event, async handler runs in REQUIRES_NEW)
        attemptService.completeAttempt(testUser.getUsername(), attemptId);

        // Wait for async event processing (event listener is @Async)
        Thread.sleep(3000);

        // Then - verify snapshot was created by async event handler
        // Query in new transaction to see committed data from REQUIRES_NEW
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            em.clear(); // Avoid 1st-level cache

            QuizAnalyticsSnapshot snapshot = snapshotRepository.findByQuizId(testQuiz.getId()).orElse(null);
            assertThat(snapshot).as("Async event handler should have created snapshot via REQUIRES_NEW").isNotNull();
            assertThat(snapshot.getAttemptsCount()).as("Should see committed attempt").isEqualTo(1);
            assertThat(snapshot.getPassRate()).as("100% correct should be passing").isEqualTo(100.0);
        });
    }
}

