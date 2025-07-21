package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.mapper.QuizMapper;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.question.QuestionRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.question.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.service.question.handler.QuestionHandler;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("QuizService Publishing Validation Tests")
class QuizServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private QuizMapper quizMapper;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QuestionHandlerFactory questionHandlerFactory;
    @Mock
    private QuestionHandler questionHandler;

    @InjectMocks
    private QuizServiceImpl quizService;

    @Test
    @DisplayName("setStatus: Publishing quiz without questions should throw IllegalArgumentException")
    void setStatus_publishingEmptyQuiz_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz emptyQuiz = createQuizWithoutQuestions();

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(emptyQuiz));

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with insufficient estimated time should throw IllegalArgumentException")
    void setStatus_publishingQuizWithInsufficientEstimatedTime_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);
        quiz.setEstimatedTime(0); // Invalid time

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with null estimated time should throw IllegalArgumentException")
    void setStatus_publishingQuizWithNullEstimatedTime_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);
        quiz.setEstimatedTime(null); // Null time

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with invalid question content should throw IllegalArgumentException")
    void setStatus_publishingQuizWithInvalidQuestionContent_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);
        doThrow(new ValidationException("MCQ_SINGLE must have exactly one correct answer")).when(questionHandler).validateContent(any());

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'Question 1' is invalid: MCQ_SINGLE must have exactly one correct answer");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with malformed question JSON should throw IllegalArgumentException")
    void setStatus_publishingQuizWithMalformedQuestionJSON_throwsIllegalArgumentException() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithQuestions(1);
        // Set malformed JSON content
        quiz.getQuestions().iterator().next().setContent("invalid json {");

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'Question 1' has malformed content JSON");
    }

    @Test
    @DisplayName("setStatus: Publishing quiz with multiple validation errors should include all errors")
    void setStatus_publishingQuizWithMultipleErrors_includesAllErrors() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createQuizWithoutQuestions();
        quiz.setEstimatedTime(0); // Invalid time

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, quizId, QuizStatus.PUBLISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions")
                .hasMessageContaining("minimum estimated time of 1 minute(s)");
    }

    @Test
    @DisplayName("setStatus: Publishing valid quiz should succeed")
    void setStatus_publishingValidQuiz_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz quiz = createValidQuizForPublishing();
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(quiz));
        when(quizRepository.save(quiz)).thenReturn(quiz);
        when(quizMapper.toDto(quiz)).thenReturn(expectedDto);
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);
        // Mock successful validation - no exception thrown

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(quiz);
        verify(questionHandler, times(2)).validateContent(any()); // Called once for each question
    }

    @Test
    @DisplayName("setStatus: Setting to DRAFT should work regardless of validation rules")
    void setStatus_settingToDraft_worksWithInvalidQuiz() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz invalidQuiz = createQuizWithoutQuestions();
        invalidQuiz.setEstimatedTime(0); // Invalid for publishing, but OK for draft
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(invalidQuiz));
        when(quizRepository.save(invalidQuiz)).thenReturn(invalidQuiz);
        when(quizMapper.toDto(invalidQuiz)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(invalidQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        verify(quizRepository).save(invalidQuiz);
    }

    @Test
    @DisplayName("setStatus: Quiz not found should throw ResourceNotFoundException")
    void setStatus_quizNotFound_throwsResourceNotFoundException() {
        // Given
        UUID nonExistentQuizId = UUID.randomUUID();
        String username = "admin";

        when(quizRepository.findByIdWithQuestions(nonExistentQuizId)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> quizService.setStatus(username, nonExistentQuizId, QuizStatus.PUBLISHED))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Quiz " + nonExistentQuizId + " not found");
    }

    @Test
    @DisplayName("setStatus: Transitioning from PUBLISHED back to DRAFT should work")
    void setStatus_publishedToDraft_succeeds() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz publishedQuiz = createValidQuizForPublishing();
        publishedQuiz.setStatus(QuizStatus.PUBLISHED);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.DRAFT);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(publishedQuiz));
        when(quizRepository.save(publishedQuiz)).thenReturn(publishedQuiz);
        when(quizMapper.toDto(publishedQuiz)).thenReturn(expectedDto);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.DRAFT);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(publishedQuiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        verify(quizRepository).save(publishedQuiz);
    }

    @Test
    @DisplayName("setStatus: Publishing already published quiz should work (idempotent)")
    void setStatus_publishingAlreadyPublishedQuiz_isIdempotent() {
        // Given
        UUID quizId = UUID.randomUUID();
        String username = "admin";
        Quiz alreadyPublishedQuiz = createValidQuizForPublishing();
        alreadyPublishedQuiz.setStatus(QuizStatus.PUBLISHED);
        QuizDto expectedDto = createQuizDto(quizId, QuizStatus.PUBLISHED);

        when(quizRepository.findByIdWithQuestions(quizId)).thenReturn(Optional.of(alreadyPublishedQuiz));
        when(quizRepository.save(alreadyPublishedQuiz)).thenReturn(alreadyPublishedQuiz);
        when(quizMapper.toDto(alreadyPublishedQuiz)).thenReturn(expectedDto);
        when(questionHandlerFactory.getHandler(any())).thenReturn(questionHandler);

        // When
        QuizDto result = quizService.setStatus(username, quizId, QuizStatus.PUBLISHED);

        // Then
        assertThat(result).isEqualTo(expectedDto);
        assertThat(alreadyPublishedQuiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        verify(quizRepository).save(alreadyPublishedQuiz);
        verify(questionHandler, times(2)).validateContent(any()); // Called once for each question
    }

    // Helper methods for creating test data

    private Quiz createQuizWithoutQuestions() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Empty Quiz");
        quiz.setDescription("A quiz without questions");
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(5); // Valid estimated time
        quiz.setCreatedAt(Instant.now());
        quiz.setQuestions(new HashSet<>()); // Empty questions set
        quiz.setCreator(createTestUser());
        quiz.setCategory(createTestCategory());
        return quiz;
    }

    private Quiz createQuizWithQuestions(int questionCount) {
        Quiz quiz = createQuizWithoutQuestions();
        quiz.setTitle("Quiz with " + questionCount + " questions");
        quiz.setEstimatedTime(5); // Valid estimated time

        Set<Question> questions = new HashSet<>();
        for (int i = 0; i < questionCount; i++) {
            Question question = new Question();
            question.setId(UUID.randomUUID());
            question.setQuestionText("Question " + (i + 1));
            question.setType(QuestionType.TRUE_FALSE);
            question.setContent("{\"answer\":true}"); // Valid JSON content
            questions.add(question);
        }
        quiz.setQuestions(questions);
        return quiz;
    }

    private Quiz createValidQuizForPublishing() {
        Quiz quiz = createQuizWithQuestions(2);
        quiz.setEstimatedTime(10); // Valid estimated time
        return quiz;
    }

    private User createTestUser() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        return user;
    }

    private Category createTestCategory() {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Test Category");
        return category;
    }

    private QuizDto createQuizDto(UUID quizId, QuizStatus status) {
        return new QuizDto(
                quizId, // id
                UUID.randomUUID(), // creatorId
                UUID.randomUUID(), // categoryId
                "Test Quiz", // title
                "Test Description", // description
                null, // visibility
                null, // difficulty
                status, // status
                10, // estimatedTime
                false, // isRepetitionEnabled
                false, // timerEnabled
                5, // timerDuration
                List.of(), // tagIds
                Instant.now(), // createdAt
                null // updatedAt
        );
    }
} 