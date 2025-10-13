package uk.gegc.quizmaker.features.result.domain.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.result.domain.model.QuizAnalyticsSnapshot;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository integration tests for QuizAnalyticsSnapshot.
 * <p>
 * Tests verify basic CRUD operations and optimistic locking behavior
 * with real database persistence.
 * </p>
 */
@DisplayName("QuizAnalyticsSnapshot Repository Tests")
public class QuizAnalyticsSnapshotRepositoryTest extends BaseIntegrationTest {

    @Autowired
    private QuizAnalyticsSnapshotRepository snapshotRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private Quiz testQuiz;

    @BeforeEach
    void setUp() {
        // Create minimal test data
        User user = new User();
        user.setUsername("repotest_" + System.currentTimeMillis());
        user.setEmail("repotest@test.com");
        user.setHashedPassword("hashed");
        user.setActive(true);
        user.setDeleted(false);
        user = userRepository.saveAndFlush(user);

        Category category = new Category();
        category.setName("RepoCat_" + System.currentTimeMillis());
        category = categoryRepository.saveAndFlush(category);

        testQuiz = new Quiz();
        testQuiz.setTitle("Repository Test Quiz");
        testQuiz.setDescription("Test");
        testQuiz.setCreator(user);
        testQuiz.setCategory(category);
        testQuiz.setDifficulty(Difficulty.EASY);
        testQuiz.setStatus(QuizStatus.PUBLISHED);
        testQuiz.setVisibility(Visibility.PUBLIC);
        testQuiz.setEstimatedTime(5);
        testQuiz.setIsRepetitionEnabled(false);
        testQuiz.setIsTimerEnabled(false);
        testQuiz.setIsDeleted(false);
        testQuiz.setQuestions(new HashSet<>());
        testQuiz = quizRepository.saveAndFlush(testQuiz);

        entityManager.flush();
    }

    @Test
    @DisplayName("Save and retrieve snapshot by quizId")
    void saveAndRetrieve() {
        // Given
        QuizAnalyticsSnapshot snapshot = new QuizAnalyticsSnapshot();
        snapshot.setQuizId(testQuiz.getId());
        snapshot.setAttemptsCount(10);
        snapshot.setAverageScore(75.5);
        snapshot.setBestScore(95.0);
        snapshot.setWorstScore(45.0);
        snapshot.setPassRate(80.0);
        snapshot.setUpdatedAt(Instant.now());

        // When
        QuizAnalyticsSnapshot saved = snapshotRepository.save(snapshot);
        entityManager.flush();
        entityManager.clear();

        // Then
        Optional<QuizAnalyticsSnapshot> retrieved = snapshotRepository.findByQuizId(testQuiz.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getQuizId()).isEqualTo(testQuiz.getId());
        assertThat(retrieved.get().getAttemptsCount()).isEqualTo(10);
        assertThat(retrieved.get().getAverageScore()).isEqualTo(75.5);
        assertThat(retrieved.get().getPassRate()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("Update existing snapshot increments version (optimistic locking)")
    void updateSnapshot_incrementsVersion() {
        // Given - create initial snapshot
        QuizAnalyticsSnapshot snapshot = new QuizAnalyticsSnapshot();
        snapshot.setQuizId(testQuiz.getId());
        snapshot.setAttemptsCount(5);
        snapshot.setAverageScore(70.0);
        snapshot.setBestScore(90.0);
        snapshot.setWorstScore(50.0);
        snapshot.setPassRate(60.0);
        snapshot.setUpdatedAt(Instant.now());
        snapshot = snapshotRepository.saveAndFlush(snapshot);
        entityManager.clear();

        Long initialVersion = snapshot.getVersion();

        // When - update the snapshot
        snapshot.setAttemptsCount(6);
        snapshot.setAverageScore(72.0);
        QuizAnalyticsSnapshot updated = snapshotRepository.saveAndFlush(snapshot);
        entityManager.clear();

        // Then - version should increment
        assertThat(updated.getVersion()).isGreaterThan(initialVersion);
        assertThat(updated.getAttemptsCount()).isEqualTo(6);
    }

    @Test
    @DisplayName("findByQuizId returns empty for non-existent quiz")
    void findByQuizId_nonExistent_returnsEmpty() {
        // Given
        java.util.UUID nonExistentId = java.util.UUID.randomUUID();

        // When
        Optional<QuizAnalyticsSnapshot> result = snapshotRepository.findByQuizId(nonExistentId);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Only one snapshot per quiz (updates existing when quizId matches)")
    void oneSnapshotPerQuiz() {
        // Given - create first snapshot
        QuizAnalyticsSnapshot first = new QuizAnalyticsSnapshot();
        first.setQuizId(testQuiz.getId());
        first.setAttemptsCount(1);
        first.setAverageScore(80.0);
        first.setBestScore(80.0);
        first.setWorstScore(80.0);
        first.setPassRate(100.0);
        first.setUpdatedAt(Instant.now());
        first = snapshotRepository.saveAndFlush(first);
        entityManager.clear();

        // When - update the same snapshot (fetch, modify, save pattern)
        QuizAnalyticsSnapshot existing = snapshotRepository.findByQuizId(testQuiz.getId()).orElseThrow();
        existing.setAttemptsCount(2);
        existing.setAverageScore(75.0);
        existing.setBestScore(90.0);
        existing.setWorstScore(60.0);
        existing.setPassRate(50.0);
        existing.setUpdatedAt(Instant.now());
        snapshotRepository.saveAndFlush(existing);
        entityManager.flush();

        // Then - should still only have one snapshot
        long count = snapshotRepository.count();
        assertThat(count).isEqualTo(1);

        Optional<QuizAnalyticsSnapshot> result = snapshotRepository.findByQuizId(testQuiz.getId());
        assertThat(result).isPresent();
        assertThat(result.get().getAttemptsCount()).isEqualTo(2); // Updated value
    }
}

