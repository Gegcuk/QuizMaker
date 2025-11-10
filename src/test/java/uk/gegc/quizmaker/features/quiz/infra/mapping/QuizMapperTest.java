package uk.gegc.quizmaker.features.quiz.infra.mapping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for QuizMapper, specifically testing question count handling
 */
@DisplayName("QuizMapper Tests")
class QuizMapperTest {

    private QuizMapper mapper;
    private User creator;
    private Category category;

    @BeforeEach
    void setUp() {
        mapper = new QuizMapper();
        
        creator = new User();
        creator.setId(UUID.randomUUID());
        creator.setUsername("testuser");
        
        category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Test Category");
    }

    @Test
    @DisplayName("toDto(quiz, questionCount) includes provided question count")
    void toDto_withQuestionCount_includesCount() {
        // Given
        Quiz quiz = createQuiz();
        int questionCount = 42;
        
        // When
        QuizDto result = mapper.toDto(quiz, questionCount);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.questionCount()).isEqualTo(42);
        assertThat(result.id()).isEqualTo(quiz.getId());
        assertThat(result.title()).isEqualTo(quiz.getTitle());
    }

    @Test
    @DisplayName("toDto(quiz) with loaded questions returns correct count")
    void toDto_withLoadedQuestions_countsFromCollection() {
        // Given
        Quiz quiz = createQuiz();
        
        // Add 5 questions to the quiz
        Set<Question> questions = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            questions.add(q);
        }
        quiz.setQuestions(questions);
        
        // When
        QuizDto result = mapper.toDto(quiz);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.questionCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("toDto(quiz) without loaded questions returns 0")
    void toDto_withoutLoadedQuestions_returnsZero() {
        // Given
        Quiz quiz = createQuiz();
        quiz.setQuestions(null); // Questions not loaded
        
        // When
        QuizDto result = mapper.toDto(quiz);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.questionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("toDto(quiz) with empty questions collection returns 0")
    void toDto_withEmptyQuestions_returnsZero() {
        // Given
        Quiz quiz = createQuiz();
        quiz.setQuestions(new HashSet<>()); // Empty collection
        
        // When
        QuizDto result = mapper.toDto(quiz);
        
        // Then
        assertThat(result).isNotNull();
        assertThat(result.questionCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("toDto maps all quiz fields correctly including questionCount")
    void toDto_mapsAllFieldsCorrectly() {
        // Given
        Quiz quiz = createQuiz();
        quiz.setTitle("Test Quiz Title");
        quiz.setDescription("Test Description");
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.HARD);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setEstimatedTime(30);
        quiz.setIsRepetitionEnabled(true);
        quiz.setIsTimerEnabled(true);
        quiz.setTimerDuration(45);
        
        Tag tag1 = new Tag();
        tag1.setId(UUID.randomUUID());
        Tag tag2 = new Tag();
        tag2.setId(UUID.randomUUID());
        quiz.setTags(Set.of(tag1, tag2));
        
        int questionCount = 15;
        
        // When
        QuizDto result = mapper.toDto(quiz, questionCount);
        
        // Then
        assertThat(result.id()).isEqualTo(quiz.getId());
        assertThat(result.creatorId()).isEqualTo(creator.getId());
        assertThat(result.categoryId()).isEqualTo(category.getId());
        assertThat(result.title()).isEqualTo("Test Quiz Title");
        assertThat(result.description()).isEqualTo("Test Description");
        assertThat(result.visibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(result.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(result.status()).isEqualTo(QuizStatus.PUBLISHED);
        assertThat(result.estimatedTime()).isEqualTo(30);
        assertThat(result.isRepetitionEnabled()).isTrue();
        assertThat(result.timerEnabled()).isTrue();
        assertThat(result.timerDuration()).isEqualTo(45);
        assertThat(result.tagIds()).hasSize(2);
        assertThat(result.questionCount()).isEqualTo(15);
        // createdAt and updatedAt are null until entity is persisted (not tested here)
    }

    private Quiz createQuiz() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Test Description");
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setTags(new HashSet<>());
        return quiz;
    }
}

