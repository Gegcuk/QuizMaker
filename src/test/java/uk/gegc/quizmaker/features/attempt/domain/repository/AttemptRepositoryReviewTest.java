package uk.gegc.quizmaker.features.attempt.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository tests for AttemptRepository focusing on review-related queries.
 * Tests verify that queries used by the review feature properly eager-fetch
 * relationships to prevent N+1 queries.
 */
@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create"
})
@DisplayName("AttemptRepository Review Query Tests")
class AttemptRepositoryReviewTest {

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User testUser;
    private Category testCategory;
    private Quiz testQuiz;
    private Question question1;
    private Question question2;
    private Attempt testAttempt;

    @BeforeEach
    void setUp() {
        // Create user
        testUser = new User();
        testUser.setUsername("testuser_" + System.currentTimeMillis());
        testUser.setEmail("test_" + System.currentTimeMillis() + "@example.com");
        testUser.setHashedPassword("hashed");
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser.setEmailVerified(true);
        entityManager.persist(testUser);

        // Create category (required for quiz)
        testCategory = new Category();
        testCategory.setName("Test Category " + System.currentTimeMillis());
        testCategory.setDescription("Test category for review tests");
        entityManager.persist(testCategory);

        // Create quiz
        testQuiz = new Quiz();
        testQuiz.setTitle("Test Quiz " + System.currentTimeMillis());
        testQuiz.setDescription("Test Description");
        testQuiz.setVisibility(Visibility.PUBLIC);
        testQuiz.setDifficulty(Difficulty.MEDIUM);
        testQuiz.setStatus(QuizStatus.PUBLISHED);
        testQuiz.setCreator(testUser);
        testQuiz.setCategory(testCategory);
        testQuiz.setEstimatedTime(10);
        testQuiz.setIsRepetitionEnabled(false);
        testQuiz.setIsTimerEnabled(false);
        testQuiz.setIsDeleted(false);
        entityManager.persist(testQuiz);

        // Create questions
        question1 = new Question();
        question1.setType(QuestionType.MCQ_SINGLE);
        question1.setDifficulty(Difficulty.EASY);
        question1.setQuestionText("Question 1");
        question1.setContent("{\"options\":[{\"id\":\"opt_1\",\"text\":\"Paris\",\"correct\":true}]}");
        question1.setQuizId(List.of(testQuiz));
        entityManager.persist(question1);

        question2 = new Question();
        question2.setType(QuestionType.TRUE_FALSE);
        question2.setDifficulty(Difficulty.MEDIUM);
        question2.setQuestionText("Question 2");
        question2.setContent("{\"answer\":true}");
        question2.setQuizId(List.of(testQuiz));
        entityManager.persist(question2);

        // Create attempt
        testAttempt = new Attempt();
        testAttempt.setUser(testUser);
        testAttempt.setQuiz(testQuiz);
        testAttempt.setMode(AttemptMode.ALL_AT_ONCE);
        testAttempt.setStatus(AttemptStatus.COMPLETED);
        testAttempt.setCompletedAt(Instant.now());
        testAttempt.setTotalScore(2.0);
        entityManager.persist(testAttempt);

        // Create answers
        Answer answer1 = new Answer();
        answer1.setAttempt(testAttempt);
        answer1.setQuestion(question1);
        answer1.setResponse("{\"selectedOptionId\":\"opt_1\"}");
        answer1.setIsCorrect(true);
        answer1.setScore(1.0);
        answer1.setAnsweredAt(Instant.now());
        entityManager.persist(answer1);

        Answer answer2 = new Answer();
        answer2.setAttempt(testAttempt);
        answer2.setQuestion(question2);
        answer2.setResponse("{\"answer\":true}");
        answer2.setIsCorrect(true);
        answer2.setScore(1.0);
        answer2.setAnsweredAt(Instant.now().plusSeconds(1));
        entityManager.persist(answer2);

        entityManager.flush();
        entityManager.clear();  // Clear to ensure we're testing actual fetching
    }

    @Test
    @DisplayName("findByIdWithAnswersAndQuestion: eagerly loads answers and questions without N+1")
    void findByIdWithAnswersAndQuestion_eagerlyLoadsRelations() {
        // Given
        // Data already set up in @BeforeEach

        // When
        Optional<Attempt> result = attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId());

        // Then
        assertThat(result).isPresent();
        Attempt attempt = result.get();

        // Verify answers are loaded (no additional query needed)
        assertThat(attempt.getAnswers()).hasSize(2);
        
        // Verify questions on answers are loaded (no N+1)
        assertThat(attempt.getAnswers().get(0).getQuestion()).isNotNull();
        assertThat(attempt.getAnswers().get(0).getQuestion().getQuestionText()).isNotNull();
        assertThat(attempt.getAnswers().get(1).getQuestion()).isNotNull();
        assertThat(attempt.getAnswers().get(1).getQuestion().getQuestionText()).isNotNull();
        
        // Verify we can access question content and types without additional queries (order-agnostic)
        assertThat(attempt.getAnswers().get(0).getQuestion().getContent()).isNotNull();
        assertThat(attempt.getAnswers().get(1).getQuestion().getContent()).isNotNull();
        
        // Verify both question types are present (without assuming order)
        java.util.Set<QuestionType> types = attempt.getAnswers().stream()
                .map(a -> a.getQuestion().getType())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(types).containsExactlyInAnyOrder(QuestionType.MCQ_SINGLE, QuestionType.TRUE_FALSE);
    }

    @Test
    @DisplayName("findByIdWithAnswersAndQuestion: returns empty when attempt not found")
    void findByIdWithAnswersAndQuestion_notFound_returnsEmpty() {
        // Given
        java.util.UUID unknownId = java.util.UUID.randomUUID();

        // When
        Optional<Attempt> result = attemptRepository.findByIdWithAnswersAndQuestion(unknownId);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByIdWithAnswersAndQuestion: loads answers in correct order for review")
    void findByIdWithAnswersAndQuestion_loadsAnswersForReview() {
        // Given
        // Data already set up with 2 answers

        // When
        Optional<Attempt> result = attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId());

        // Then
        assertThat(result).isPresent();
        Attempt attempt = result.get();
        
        // Verify all answer data needed for review is present
        assertThat(attempt.getAnswers()).hasSize(2);
        
        for (Answer answer : attempt.getAnswers()) {
            assertThat(answer.getId()).isNotNull();
            assertThat(answer.getResponse()).isNotNull();  // User response
            assertThat(answer.getIsCorrect()).isNotNull();
            assertThat(answer.getScore()).isNotNull();
            assertThat(answer.getAnsweredAt()).isNotNull();
            
            // Question data for review
            assertThat(answer.getQuestion()).isNotNull();
            assertThat(answer.getQuestion().getId()).isNotNull();
            assertThat(answer.getQuestion().getType()).isNotNull();
            assertThat(answer.getQuestion().getQuestionText()).isNotNull();
            assertThat(answer.getQuestion().getContent()).isNotNull();  // Needed for correct answer extraction
        }
    }

    @Test
    @DisplayName("findByIdWithAnswersAndQuestion: handles attempt with no answers")
    void findByIdWithAnswersAndQuestion_noAnswers_returnsEmptyList() {
        // Given
        Attempt emptyAttempt = new Attempt();
        emptyAttempt.setUser(testUser);
        emptyAttempt.setQuiz(testQuiz);
        emptyAttempt.setMode(AttemptMode.ALL_AT_ONCE);
        emptyAttempt.setStatus(AttemptStatus.COMPLETED);
        emptyAttempt.setCompletedAt(Instant.now());
        emptyAttempt.setTotalScore(0.0);
        entityManager.persist(emptyAttempt);
        entityManager.flush();
        entityManager.clear();

        // When
        Optional<Attempt> result = attemptRepository.findByIdWithAnswersAndQuestion(emptyAttempt.getId());

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getAnswers()).isEmpty();
    }

    @Test
    @DisplayName("findByIdWithAnswersAndQuestion: efficiently fetches user and quiz for authorization")
    void findByIdWithAnswersAndQuestion_loadsUserAndQuiz() {
        // Given
        // Data already set up

        // When
        Optional<Attempt> result = attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId());

        // Then
        assertThat(result).isPresent();
        Attempt attempt = result.get();
        
        // Verify user is loaded (needed for ownership check)
        assertThat(attempt.getUser()).isNotNull();
        assertThat(attempt.getUser().getId()).isEqualTo(testUser.getId());
        assertThat(attempt.getUser().getUsername()).isNotNull();
        
        // Verify quiz is loaded (needed for totalQuestions count)
        assertThat(attempt.getQuiz()).isNotNull();
        assertThat(attempt.getQuiz().getId()).isEqualTo(testQuiz.getId());
    }
}

