package uk.gegc.quizmaker.features.quiz.application;

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
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository integration tests for duplicate title handling.
 * Tests the existsByCreatorIdAndTitle method behavior in realistic scenarios
 * with actual database persistence.
 */
@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update"
})
class QuizServiceDuplicateTitleIntegrationTest {

    @Autowired
    private QuizRepository quizRepository;


    @Autowired
    private TestEntityManager entityManager;

    private User testUser;
    private User anotherUser;
    private Category generalCategory;

    @BeforeEach
    void setUp() {
        // Create test users
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("testuser@example.com");
        testUser.setHashedPassword("password123");
        testUser = entityManager.persistAndFlush(testUser);

        anotherUser = new User();
        anotherUser.setUsername("anotheruser");
        anotherUser.setEmail("anotheruser@example.com");
        anotherUser.setHashedPassword("password123");
        anotherUser = entityManager.persistAndFlush(anotherUser);

        // Create test category
        generalCategory = new Category();
        generalCategory.setName("General");
        generalCategory.setDescription("General category for testing");
        generalCategory = entityManager.persistAndFlush(generalCategory);
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when multiple quizzes exist for same creator then check specific titles")
    void existsByCreatorIdAndTitle_whenMultipleQuizzesExistForSameCreator_thenCheckSpecificTitles() {
        // Given
        String title1 = "My First Quiz";
        String title2 = "My Second Quiz";
        String title3 = "My Third Quiz";
        
        Quiz quiz1 = createTestQuiz(testUser, title1);
        Quiz quiz2 = createTestQuiz(testUser, title2);
        Quiz quiz3 = createTestQuiz(testUser, title3);
        
        entityManager.persistAndFlush(quiz1);
        entityManager.persistAndFlush(quiz2);
        entityManager.persistAndFlush(quiz3);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), title1)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), title2)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), title3)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "Non-existent Quiz")).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when same title exists for different creators then return true for each")
    void existsByCreatorIdAndTitle_whenSameTitleExistsForDifferentCreators_thenReturnTrueForEach() {
        // Given
        String sharedTitle = "Shared Quiz Title";
        
        Quiz quiz1 = createTestQuiz(testUser, sharedTitle);
        Quiz quiz2 = createTestQuiz(anotherUser, sharedTitle);
        
        entityManager.persistAndFlush(quiz1);
        entityManager.persistAndFlush(quiz2);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), sharedTitle)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(anotherUser.getId(), sharedTitle)).isTrue();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with case variations then return true (MySQL case-insensitive)")
    void existsByCreatorIdAndTitle_whenCheckingWithCaseVariations_thenReturnTrue() {
        // Given
        String quizTitle = "My Test Quiz";
        Quiz quiz = createTestQuiz(testUser, quizTitle);
        entityManager.persistAndFlush(quiz);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), quizTitle)).isTrue();
        // MySQL is case-insensitive by default, so different case should still match
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "MY TEST QUIZ")).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "my test quiz")).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "My Test Quiz")).isTrue(); // Exact match
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with whitespace variations then return false")
    void existsByCreatorIdAndTitle_whenCheckingWithWhitespaceVariations_thenReturnFalse() {
        // Given
        String quizTitle = "My Test Quiz";
        Quiz quiz = createTestQuiz(testUser, quizTitle);
        entityManager.persistAndFlush(quiz);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), quizTitle)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "  My Test Quiz  ")).isFalse();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "\tMy Test Quiz\n")).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with special characters then work correctly")
    void existsByCreatorIdAndTitle_whenCheckingWithSpecialCharacters_thenWorkCorrectly() {
        // Given
        String quizTitleWithSpecialChars = "My Quiz with Special Chars!@#$%^&*()";
        Quiz quiz = createTestQuiz(testUser, quizTitleWithSpecialChars);
        entityManager.persistAndFlush(quiz);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), quizTitleWithSpecialChars)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "My Quiz with Special Chars!@#$%^&*()")).isTrue();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with unicode characters then work correctly")
    void existsByCreatorIdAndTitle_whenCheckingWithUnicodeCharacters_thenWorkCorrectly() {
        // Given
        String quizTitleWithUnicode = "My Quiz with Unicode: 测试 🎯 ñáéíóú";
        Quiz quiz = createTestQuiz(testUser, quizTitleWithUnicode);
        entityManager.persistAndFlush(quiz);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), quizTitleWithUnicode)).isTrue();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with very long titles then work correctly")
    void existsByCreatorIdAndTitle_whenCheckingWithVeryLongTitles_thenWorkCorrectly() {
        // Given
        String longTitle = "A".repeat(95); // Close to but under 100 character limit
        Quiz quiz = createTestQuiz(testUser, longTitle);
        entityManager.persistAndFlush(quiz);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), longTitle)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), longTitle + "X")).isFalse();
    }

    @Test
    @DisplayName("existsByCreatorIdAndTitle: when checking with titles that have numeric suffixes then work correctly")
    void existsByCreatorIdAndTitle_whenCheckingWithTitlesThatHaveNumericSuffixes_thenWorkCorrectly() {
        // Given
        String baseTitle = "My Quiz";
        String titleWithSuffix = "My Quiz-2";
        
        Quiz quiz1 = createTestQuiz(testUser, baseTitle);
        Quiz quiz2 = createTestQuiz(testUser, titleWithSuffix);
        
        entityManager.persistAndFlush(quiz1);
        entityManager.persistAndFlush(quiz2);

        // When & Then
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), baseTitle)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), titleWithSuffix)).isTrue();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "My Quiz-3")).isFalse();
        assertThat(quizRepository.existsByCreatorIdAndTitle(testUser.getId(), "My Quiz-1")).isFalse();
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
