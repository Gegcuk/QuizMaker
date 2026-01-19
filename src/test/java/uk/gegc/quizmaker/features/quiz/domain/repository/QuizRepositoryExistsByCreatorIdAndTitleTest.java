package uk.gegc.quizmaker.features.quiz.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository tests for QuizRepository.existsByCreatorIdAndTitle method.
 * Tests the database query that checks if a quiz with the same title
 * already exists for a specific creator.
 */
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.auto=create-drop"
})
class QuizRepositoryExistsByCreatorIdAndTitleTest {

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private TestEntityManager entityManager;

    private User alice;
    private User bob;
    private Category generalCategory;

    @BeforeEach
    void setUp() {
        // Create test users
        alice = new User();
        alice.setUsername("alice");
        alice.setEmail("alice@example.com");
        alice.setHashedPassword("password123");
        alice = entityManager.persistAndFlush(alice);

        bob = new User();
        bob.setUsername("bob");
        bob.setEmail("bob@example.com");
        bob.setHashedPassword("password123");
        bob = entityManager.persistAndFlush(bob);

        // Create test category
        generalCategory = new Category();
        generalCategory.setName("General");
        generalCategory.setDescription("General category for testing");
        generalCategory = entityManager.persistAndFlush(generalCategory);
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when quiz exists for creator then return true")
    void existsByCreatorIdAndTitle_whenQuizExistsForCreator_thenReturnTrue() {
        // Given
        String quizTitle = "My Test Quiz";
        Quiz quiz = createTestQuiz(alice, quizTitle);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), quizTitle);

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when quiz does not exist for creator then return false")
    void existsByCreatorIdAndTitle_whenQuizDoesNotExistForCreator_thenReturnFalse() {
        // Given
        String quizTitle = "My Test Quiz";

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), quizTitle);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when quiz exists for different creator then return false")
    void existsByCreatorIdAndTitle_whenQuizExistsForDifferentCreator_thenReturnFalse() {
        // Given
        String quizTitle = "My Test Quiz";
        Quiz quiz = createTestQuiz(alice, quizTitle);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(bob.getId(), quizTitle);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when quiz exists for same creator with different title then return false")
    void existsByCreatorIdAndTitle_whenQuizExistsForSameCreatorWithDifferentTitle_thenReturnFalse() {
        // Given
        String existingTitle = "My Test Quiz";
        String differentTitle = "My Different Quiz";
        Quiz quiz = createTestQuiz(alice, existingTitle);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), differentTitle);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when title case differs then return true (MySQL case-insensitive)")
    void existsByCreatorIdAndTitle_whenTitleCaseDiffers_thenReturnTrue() {
        // Given
        String quizTitle = "My Test Quiz";
        String differentCaseTitle = "MY TEST QUIZ";
        Quiz quiz = createTestQuiz(alice, quizTitle);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), differentCaseTitle);

        // Then
        // MySQL is case-insensitive by default, so different case should still match
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when title has extra whitespace then return false")
    void existsByCreatorIdAndTitle_whenTitleHasExtraWhitespace_thenReturnFalse() {
        // Given
        String quizTitle = "My Test Quiz";
        String titleWithWhitespace = "  My Test Quiz  ";
        Quiz quiz = createTestQuiz(alice, quizTitle);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), titleWithWhitespace);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when multiple quizzes exist for same creator then check specific title")
    void existsByCreatorIdAndTitle_whenMultipleQuizzesExistForSameCreator_thenCheckSpecificTitle() {
        // Given
        String title1 = "My First Quiz";
        String title2 = "My Second Quiz";
        String title3 = "My Third Quiz";
        
        Quiz quiz1 = createTestQuiz(alice, title1);
        Quiz quiz2 = createTestQuiz(alice, title2);
        Quiz quiz3 = createTestQuiz(alice, title3);
        
        entityManager.persistAndFlush(quiz1);
        entityManager.persistAndFlush(quiz2);
        entityManager.persistAndFlush(quiz3);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(alice.getId(), title1)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(alice.getId(), title2)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(alice.getId(), title3)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(alice.getId(), "Non-existent Quiz")).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when creator has no quizzes then return false")
    void existsByCreatorIdAndTitle_whenCreatorHasNoQuizzes_thenReturnFalse() {
        // Given
        String quizTitle = "My Test Quiz";
        // No quizzes created for alice

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), quizTitle);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with null title then return false")
    void existsByCreatorIdAndTitle_whenCheckingWithNullTitle_thenReturnFalse() {
        // Given
        Quiz quiz = createTestQuiz(alice, "My Test Quiz");
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), null);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with empty title then return false")
    void existsByCreatorIdAndTitle_whenCheckingWithEmptyTitle_thenReturnFalse() {
        // Given
        Quiz quiz = createTestQuiz(alice, "My Test Quiz");
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), "");

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with non-existent creator ID then return false")
    void existsByCreatorIdAndTitle_whenCheckingWithNonExistentCreatorId_thenReturnFalse() {
        // Given
        String quizTitle = "My Test Quiz";
        Quiz quiz = createTestQuiz(alice, quizTitle);
        entityManager.persistAndFlush(quiz);
        UUID nonExistentCreatorId = UUID.randomUUID();

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(nonExistentCreatorId, quizTitle);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with very long title then return false")
    void existsByCreatorIdAndTitle_whenCheckingWithVeryLongTitle_thenReturnFalse() {
        // Given
        String quizTitle = "My Test Quiz";
        String veryLongTitle = "A".repeat(200); // Exceeds database column limit
        Quiz quiz = createTestQuiz(alice, quizTitle);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), veryLongTitle);

        // Then
        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with special characters in title then work correctly")
    void existsByCreatorIdAndTitle_whenCheckingWithSpecialCharactersInTitle_thenWorkCorrectly() {
        // Given
        String quizTitleWithSpecialChars = "My Quiz with Special Chars!@#$%^&*()";
        Quiz quiz = createTestQuiz(alice, quizTitleWithSpecialChars);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), quizTitleWithSpecialChars);

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with unicode characters in title then work correctly")
    void existsByCreatorIdAndTitle_whenCheckingWithUnicodeCharactersInTitle_thenWorkCorrectly() {
        // Given
        String quizTitleWithUnicode = "My Quiz with Unicode: 测试 🎯 ñáéíóú";
        Quiz quiz = createTestQuiz(alice, quizTitleWithUnicode);
        entityManager.persistAndFlush(quiz);

        // When
        boolean exists = quizRepository.existsByCreatorIdAndTitle(alice.getId(), quizTitleWithUnicode);

        // Then
        assertThat(exists).isTrue();
    }

    /**
     * Helper method to create a test quiz with the given creator and title.
     */
    private Quiz createTestQuiz(User creator, String title) {
        Quiz quiz = new Quiz();
        quiz.setCreator(creator);
        quiz.setCategory(generalCategory);
        quiz.setTitle(title);
        quiz.setDescription("Test quiz description");
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(10);
        quiz.setIsTimerEnabled(false);
        quiz.setIsRepetitionEnabled(false);
        return quiz;
    }
}
