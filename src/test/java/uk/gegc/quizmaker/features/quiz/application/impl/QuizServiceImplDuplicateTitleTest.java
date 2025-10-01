package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for QuizServiceImpl duplicate title handling functionality.
 * Tests the ensureUniqueQuizTitle method which appends numeric suffixes
 * when quiz titles already exist for a creator.
 */
@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
class QuizServiceImplDuplicateTitleTest {

    @Mock
    private QuizRepository quizRepository;

    @InjectMocks
    private QuizServiceImpl quizService;

    private User testUser;
    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        testUser = new User();
        testUser.setId(userId);
        testUser.setUsername("testuser");
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title does not exist then return original title")
    void ensureUniqueQuizTitle_whenTitleDoesNotExist_thenReturnOriginalTitle() throws Exception {
        // Given
        String requestedTitle = "My Quiz Title";
        when(quizRepository.existsByCreatorIdAndTitle(userId, requestedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, requestedTitle);

        // Then
        assertThat(result).isEqualTo(requestedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title exists then append suffix -2")
    void ensureUniqueQuizTitle_whenTitleExists_thenAppendSuffix2() throws Exception {
        // Given
        String requestedTitle = "My Quiz Title";
        String expectedTitle = "My Quiz Title-2";
        
        when(quizRepository.existsByCreatorIdAndTitle(userId, requestedTitle)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, requestedTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title and -2 exist then append suffix -3")
    void ensureUniqueQuizTitle_whenTitleAndSuffix2Exist_thenAppendSuffix3() throws Exception {
        // Given
        String requestedTitle = "My Quiz Title";
        String titleWithSuffix2 = "My Quiz Title-2";
        String expectedTitle = "My Quiz Title-3";
        
        when(quizRepository.existsByCreatorIdAndTitle(userId, requestedTitle)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, titleWithSuffix2)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, requestedTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title already has numeric suffix then use as base")
    void ensureUniqueQuizTitle_whenTitleHasNumericSuffix_thenUseAsBase() throws Exception {
        // Given
        String requestedTitle = "My Quiz Title-5";
        String expectedTitle = "My Quiz Title-6";
        
        // The implementation first checks if the original title exists, then checks candidate titles
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, requestedTitle)).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, "My Quiz Title-2")).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, "My Quiz Title-3")).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, "My Quiz Title-4")).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, "My Quiz Title-5")).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, requestedTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title has multiple hyphens then use last numeric suffix")
    void ensureUniqueQuizTitle_whenTitleHasMultipleHyphens_thenUseLastNumericSuffix() throws Exception {
        // Given
        String requestedTitle = "My-Quiz-Title-3";
        String expectedTitle = "My-Quiz-Title-4";
        
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, requestedTitle)).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, "My-Quiz-Title-2")).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, "My-Quiz-Title-3")).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, requestedTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title has non-numeric suffix then append to full title")
    void ensureUniqueQuizTitle_whenTitleHasNonNumericSuffix_thenAppendToFullTitle() throws Exception {
        // Given
        String requestedTitle = "My Quiz Title-abc";
        String expectedTitle = "My Quiz Title-abc-2";
        
        when(quizRepository.existsByCreatorIdAndTitle(userId, requestedTitle)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, requestedTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title is null then use default")
    void ensureUniqueQuizTitle_whenTitleIsNull_thenUseDefault() throws Exception {
        // Given
        String defaultTitle = "Untitled Quiz";
        when(quizRepository.existsByCreatorIdAndTitle(userId, defaultTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, null);

        // Then
        assertThat(result).isEqualTo(defaultTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title is empty then use default")
    void ensureUniqueQuizTitle_whenTitleIsEmpty_thenUseDefault() throws Exception {
        // Given
        String defaultTitle = "Untitled Quiz";
        when(quizRepository.existsByCreatorIdAndTitle(userId, defaultTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, "");

        // Then
        assertThat(result).isEqualTo(defaultTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title is whitespace then use default")
    void ensureUniqueQuizTitle_whenTitleIsWhitespace_thenUseDefault() throws Exception {
        // Given
        String defaultTitle = "Untitled Quiz";
        when(quizRepository.existsByCreatorIdAndTitle(userId, defaultTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, "   ");

        // Then
        assertThat(result).isEqualTo(defaultTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title exceeds max length then truncate")
    void ensureUniqueQuizTitle_whenTitleExceedsMaxLength_thenTruncate() throws Exception {
        // Given
        String longTitle = "A".repeat(200); // Exceeds 100 character limit
        String truncatedTitle = "A".repeat(100);
        when(quizRepository.existsByCreatorIdAndTitle(userId, truncatedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, longTitle);

        // Then
        assertThat(result).isEqualTo(truncatedTitle);
        assertThat(result.length()).isEqualTo(100);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title exceeds max length and needs suffix then truncate base")
    void ensureUniqueQuizTitle_whenTitleExceedsMaxLengthAndNeedsSuffix_thenTruncateBase() throws Exception {
        // Given
        String longTitle = "A".repeat(200); // Exceeds 100 character limit
        String truncatedTitle = "A".repeat(100);
        String expectedTitle = "A".repeat(98) + "-2"; // 98 + "-2" = 100
        
        when(quizRepository.existsByCreatorIdAndTitle(userId, truncatedTitle)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, longTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
        assertThat(result.length()).isEqualTo(100);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title has trailing hyphens then remove them")
    void ensureUniqueQuizTitle_whenTitleHasTrailingHyphens_thenRemoveThem() throws Exception {
        // Given
        String titleWithHyphens = "My Quiz Title---";
        String cleanedTitle = "My Quiz Title";
        String expectedTitle = "My Quiz Title-2";
        
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, titleWithHyphens)).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, cleanedTitle)).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, titleWithHyphens);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when title becomes blank after cleaning then use Quiz")
    void ensureUniqueQuizTitle_whenTitleBecomesBlankAfterCleaning_thenUseQuiz() throws Exception {
        // Given
        String problematicTitle = "---";
        String expectedTitle = "Quiz-2";
        
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, problematicTitle)).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, "Quiz")).thenReturn(true);
        lenient().when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, problematicTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when user is null then throw exception")
    void ensureUniqueQuizTitle_whenUserIsNull_thenThrowException() throws Exception {
        // When & Then
        assertThatThrownBy(() -> invokeEnsureUniqueQuizTitle(null, "Some Title"))
                .isInstanceOf(Exception.class)
                .hasCauseInstanceOf(NullPointerException.class)
                .hasRootCauseMessage("Creator must be provided for unique title generation");
    }

    @Test
    @DisplayName("ensureUniqueQuizTitle: when all suffixes are taken then continue incrementing")
    void ensureUniqueQuizTitle_whenAllSuffixesAreTaken_thenContinueIncrementing() throws Exception {
        // Given
        String requestedTitle = "My Quiz Title";
        String titleWithSuffix2 = "My Quiz Title-2";
        String titleWithSuffix3 = "My Quiz Title-3";
        String titleWithSuffix4 = "My Quiz Title-4";
        String expectedTitle = "My Quiz Title-5";
        
        when(quizRepository.existsByCreatorIdAndTitle(userId, requestedTitle)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, titleWithSuffix2)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, titleWithSuffix3)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, titleWithSuffix4)).thenReturn(true);
        when(quizRepository.existsByCreatorIdAndTitle(userId, expectedTitle)).thenReturn(false);

        // When
        String result = invokeEnsureUniqueQuizTitle(testUser, requestedTitle);

        // Then
        assertThat(result).isEqualTo(expectedTitle);
    }

    /**
     * Helper method to invoke the private ensureUniqueQuizTitle method using reflection.
     * This allows us to test the private method directly.
     */
    private String invokeEnsureUniqueQuizTitle(User user, String requestedTitle) throws Exception {
        Method method = QuizServiceImpl.class.getDeclaredMethod("ensureUniqueQuizTitle", User.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(quizService, user, requestedTitle);
    }
}
