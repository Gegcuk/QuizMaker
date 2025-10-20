package uk.gegc.quizmaker.features.quiz.application.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for QuizPublishValidator.
 * 
 * <p>Tests verify all publishing validation rules:
 * - Quiz must have at least one question
 * - Quiz must have minimum estimated time (1 minute)
 * - All questions must have valid content (validated via handlers)
 * - Error aggregation and message formatting
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuizPublishValidator Tests")
class QuizPublishValidatorTest {

    @Mock
    private QuestionHandlerFactory questionHandlerFactory;
    
    @Mock
    private QuestionHandler questionHandler;
    
    private ObjectMapper objectMapper = new ObjectMapper(); // Use real ObjectMapper
    
    @InjectMocks
    private QuizPublishValidator validator;
    
    private Quiz quiz;
    
    @BeforeEach
    void setUp() {
        // Create a base quiz for testing
        quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz");
        quiz.setDescription("Test description");
        quiz.setEstimatedTime(10);
        quiz.setQuestions(new HashSet<>());
        
        // Inject real ObjectMapper after @InjectMocks
        validator = new QuizPublishValidator(questionHandlerFactory, objectMapper);
    }
    
    // =============== Happy Path Tests ===============
    
    @Nested
    @DisplayName("Valid Quiz Scenarios")
    class ValidQuizScenarios {
        
        @Test
        @DisplayName("Valid quiz with one question passes validation")
        void validQuiz_withOneQuestion_passesValidation() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(5);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
            
            verify(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
        }
        
        @Test
        @DisplayName("Valid quiz with multiple questions passes validation")
        void validQuiz_withMultipleQuestions_passesValidation() {
            // Given
            Question q1 = createValidQuestion("Question 1");
            Question q2 = createValidQuestion("Question 2");
            Question q3 = createValidQuestion("Question 3");
            quiz.setQuestions(Set.of(q1, q2, q3));
            quiz.setEstimatedTime(15);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
            
            verify(questionHandler, times(3)).validateContent(any(EntityQuestionContentRequest.class));
        }
        
        @Test
        @DisplayName("Valid quiz with minimum estimated time (1 minute) passes validation")
        void validQuiz_withMinimumEstimatedTime_passesValidation() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(QuizPublishValidator.MINIMUM_ESTIMATED_TIME_MINUTES);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
        }
    }
    
    // =============== No Questions Tests ===============
    
    @Nested
    @DisplayName("No Questions Validation")
    class NoQuestionsValidation {
        
        @Test
        @DisplayName("Quiz with null questions fails validation")
        void quizWithNullQuestions_failsValidation() {
            // Given
            quiz.setQuestions(null);
            quiz.setEstimatedTime(10);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz: Cannot publish quiz without questions");
        }
        
        @Test
        @DisplayName("Quiz with empty questions collection fails validation")
        void quizWithEmptyQuestions_failsValidation() {
            // Given
            quiz.setQuestions(new HashSet<>());
            quiz.setEstimatedTime(10);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions");
        }
    }
    
    // =============== Estimated Time Tests ===============
    
    @Nested
    @DisplayName("Estimated Time Validation")
    class EstimatedTimeValidation {
        
        @Test
        @DisplayName("Quiz with null estimated time fails validation")
        void quizWithNullEstimatedTime_failsValidation() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(null);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quiz must have a minimum estimated time of 1 minute(s)");
        }
        
        @Test
        @DisplayName("Quiz with zero estimated time fails validation")
        void quizWithZeroEstimatedTime_failsValidation() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(0);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quiz must have a minimum estimated time of 1 minute(s)");
        }
        
        @Test
        @DisplayName("Quiz with negative estimated time fails validation")
        void quizWithNegativeEstimatedTime_failsValidation() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(-5);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quiz must have a minimum estimated time of 1 minute(s)");
        }
    }
    
    // =============== Question Content Validation Tests ===============
    
    @Nested
    @DisplayName("Question Content Validation")
    class QuestionContentValidation {
        
        @Test
        @DisplayName("Quiz with invalid question content fails validation")
        void quizWithInvalidQuestionContent_failsValidation() {
            // Given
            Question question = createValidQuestion("Invalid Question");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(10);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doThrow(new ValidationException("Question must have at least one correct answer"))
                .when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz: Question 'Invalid Question' is invalid: Question must have at least one correct answer");
        }
        
        @Test
        @DisplayName("Quiz with malformed question JSON fails validation")
        void quizWithMalformedQuestionJSON_failsValidation() {
            // Given
            Question question = new Question();
            question.setId(UUID.randomUUID());
            question.setType(QuestionType.MCQ_SINGLE);
            question.setQuestionText("Malformed Question");
            question.setContent("{invalid json"); // Malformed JSON
            
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(10);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz: Question 'Malformed Question' failed validation");
        }
        
        @Test
        @DisplayName("Quiz with multiple invalid questions aggregates all errors")
        void quizWithMultipleInvalidQuestions_aggregatesAllErrors() {
            // Given
            Question q1 = createValidQuestion("Question 1");
            Question q2 = createValidQuestion("Question 2");
            Question q3 = createValidQuestion("Question 3");
            
            quiz.setQuestions(Set.of(q1, q2, q3));
            quiz.setEstimatedTime(10);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            
            // All three questions fail validation with different errors
            doThrow(new ValidationException("Missing correct answer"))
                .doThrow(new ValidationException("Invalid option format"))
                .doThrow(new ValidationException("Empty question text"))
                .when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz:")
                .hasMessageContaining("Question 'Question")
                .hasMessageContaining("is invalid:");
            
            verify(questionHandler, times(3)).validateContent(any(EntityQuestionContentRequest.class));
        }
        
        @Test
        @DisplayName("Quiz with mix of valid and invalid questions fails validation")
        void quizWithMixOfValidAndInvalidQuestions_failsValidation() {
            // Given
            Question validQ = createValidQuestion("Valid Question");
            Question invalidQ = createValidQuestion("Invalid Question");
            
            quiz.setQuestions(Set.of(validQ, invalidQ));
            quiz.setEstimatedTime(10);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            
            // First question passes, second fails
            doNothing()
                .doThrow(new ValidationException("No correct answers"))
                .when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz:")
                .hasMessageContaining("is invalid: No correct answers");
            
            verify(questionHandler, times(2)).validateContent(any(EntityQuestionContentRequest.class));
        }
    }
    
    // =============== Multiple Validation Failures Tests ===============
    
    @Nested
    @DisplayName("Multiple Validation Failures")
    class MultipleValidationFailures {
        
        @Test
        @DisplayName("Quiz with no questions AND no estimated time aggregates both errors")
        void quizWithNoQuestionsAndNoEstimatedTime_aggregatesBothErrors() {
            // Given
            quiz.setQuestions(new HashSet<>());
            quiz.setEstimatedTime(null);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz:")
                .hasMessageContaining("Cannot publish quiz without questions")
                .hasMessageContaining("Quiz must have a minimum estimated time of 1 minute(s)");
        }
        
        @Test
        @DisplayName("Quiz with no questions AND insufficient time AND invalid questions aggregates all errors")
        void quizWithAllTypesOfErrors_aggregatesAllErrors() {
            // Given - Create quiz with one invalid question, no estimated time
            Question invalidQuestion = createValidQuestion("Bad Question");
            quiz.setQuestions(Set.of(invalidQuestion));
            quiz.setEstimatedTime(0); // Invalid time
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doThrow(new ValidationException("Invalid content"))
                .when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz:")
                .hasMessageContaining("Quiz must have a minimum estimated time of 1 minute(s)")
                .hasMessageContaining("Question 'Bad Question' is invalid: Invalid content");
        }
    }
    
    // =============== Edge Cases and Defensive Programming Tests ===============
    
    @Nested
    @DisplayName("Edge Cases and Defensive Programming")
    class EdgeCases {
        
        @Test
        @DisplayName("Quiz with exactly 1 minute estimated time passes validation")
        void quizWithExactlyOneMinute_passesValidation() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(1);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("Error message format is consistent with legacy behavior")
        void errorMessageFormat_isConsistent() {
            // Given
            quiz.setQuestions(new HashSet<>());
            quiz.setEstimatedTime(null);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot publish quiz: Cannot publish quiz without questions; Quiz must have a minimum estimated time of 1 minute(s)");
        }
        
        @Test
        @DisplayName("Validation continues for all questions even if first one fails")
        void validation_continuesForAllQuestions_evenIfFirstFails() {
            // Given - Multiple questions, all invalid
            Question q1 = createValidQuestion("Question 1");
            Question q2 = createValidQuestion("Question 2");
            
            quiz.setQuestions(Set.of(q1, q2));
            quiz.setEstimatedTime(10);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doThrow(new ValidationException("Error 1"))
                .doThrow(new ValidationException("Error 2"))
                .when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then - Both errors should be in the message
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> {
                    String message = ex.getMessage();
                    assertThat(message).contains("Question 'Question 1' is invalid:");
                    assertThat(message).contains("Question 'Question 2' is invalid:");
                });
            
            // Should validate both questions
            verify(questionHandler, times(2)).validateContent(any(EntityQuestionContentRequest.class));
        }
        
        @Test
        @DisplayName("Handler throws unexpected exception - validation fails gracefully")
        void handlerThrowsUnexpectedException_failsGracefully() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(10);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doThrow(new RuntimeException("Unexpected error"))
                .when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz:")
                .hasMessageContaining("Question 'Question 1' failed validation: Unexpected error");
        }
    }
    
    // =============== Different Question Types Tests ===============
    
    @Nested
    @DisplayName("Different Question Types")
    class DifferentQuestionTypes {
        
        @Test
        @DisplayName("Quiz with TRUE_FALSE question validates correctly")
        void quizWithTrueFalseQuestion_validatesCorrectly() {
            // Given
            Question question = new Question();
            question.setId(UUID.randomUUID());
            question.setType(QuestionType.TRUE_FALSE);
            question.setQuestionText("Is the sky blue?");
            question.setContent("{\"answer\":true}");
            
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(5);
            
            QuestionHandler trueFalseHandler = mock(QuestionHandler.class);
            when(questionHandlerFactory.getHandler(QuestionType.TRUE_FALSE))
                .thenReturn(trueFalseHandler);
            doNothing().when(trueFalseHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
            
            verify(trueFalseHandler).validateContent(any(EntityQuestionContentRequest.class));
        }
        
        @Test
        @DisplayName("Quiz with OPEN question validates correctly")
        void quizWithOpenQuestion_validatesCorrectly() {
            // Given
            Question question = new Question();
            question.setId(UUID.randomUUID());
            question.setType(QuestionType.OPEN);
            question.setQuestionText("Explain photosynthesis");
            question.setContent("{\"expectedKeywords\":[\"light\",\"chlorophyll\"]}");
            
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(10);
            
            QuestionHandler openHandler = mock(QuestionHandler.class);
            when(questionHandlerFactory.getHandler(QuestionType.OPEN))
                .thenReturn(openHandler);
            doNothing().when(openHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
            
            verify(openHandler).validateContent(any(EntityQuestionContentRequest.class));
        }
        
        @Test
        @DisplayName("Quiz with mixed question types validates all correctly")
        void quizWithMixedQuestionTypes_validatesAll() {
            // Given
            Question mcqQuestion = createValidQuestion("MCQ Question");
            
            Question tfQuestion = new Question();
            tfQuestion.setId(UUID.randomUUID());
            tfQuestion.setType(QuestionType.TRUE_FALSE);
            tfQuestion.setQuestionText("TF Question");
            tfQuestion.setContent("{\"answer\":false}");
            
            quiz.setQuestions(Set.of(mcqQuestion, tfQuestion));
            quiz.setEstimatedTime(10);
            
            QuestionHandler mcqHandler = mock(QuestionHandler.class);
            QuestionHandler tfHandler = mock(QuestionHandler.class);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(mcqHandler);
            when(questionHandlerFactory.getHandler(QuestionType.TRUE_FALSE))
                .thenReturn(tfHandler);
            
            doNothing().when(mcqHandler).validateContent(any(EntityQuestionContentRequest.class));
            doNothing().when(tfHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
            
            verify(mcqHandler).validateContent(any(EntityQuestionContentRequest.class));
            verify(tfHandler).validateContent(any(EntityQuestionContentRequest.class));
        }
    }
    
    // =============== Error Aggregation Tests ===============
    
    @Nested
    @DisplayName("Error Aggregation and Message Formatting")
    class ErrorAggregation {
        
        @Test
        @DisplayName("Errors are joined with semicolon separator")
        void errors_joinedWithSemicolon() {
            // Given
            quiz.setQuestions(new HashSet<>());
            quiz.setEstimatedTime(0);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz: Cannot publish quiz without questions; Quiz must have a minimum estimated time of 1 minute(s)");
        }
        
        @Test
        @DisplayName("Error message includes question text for identification")
        void errorMessage_includesQuestionText() {
            // Given
            Question question = createValidQuestion("My Specific Question Text");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(10);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doThrow(new ValidationException("Content is invalid"))
                .when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Question 'My Specific Question Text' is invalid: Content is invalid");
        }
        
        @Test
        @DisplayName("Exception type is IllegalArgumentException")
        void exceptionType_isIllegalArgumentException() {
            // Given
            quiz.setQuestions(new HashSet<>());
            quiz.setEstimatedTime(10);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        }
    }
    
    // =============== Real-World Scenarios Tests ===============
    
    @Nested
    @DisplayName("Real-World Publishing Scenarios")
    class RealWorldScenarios {
        
        @Test
        @DisplayName("Newly created draft quiz without questions cannot be published")
        void newDraftQuiz_cannotBePublished() {
            // Given - Fresh quiz with no questions
            quiz.setQuestions(new HashSet<>());
            quiz.setEstimatedTime(10);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions");
        }
        
        @Test
        @DisplayName("Quiz with questions but zero estimated time cannot be published")
        void quizWithQuestionsButZeroTime_cannotBePublished() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(0);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Quiz must have a minimum estimated time of 1 minute(s)");
        }
        
        @Test
        @DisplayName("Complete valid quiz ready for publishing passes all validations")
        void completeValidQuiz_passesAllValidations() {
            // Given - A fully valid quiz with multiple questions
            Question q1 = createValidQuestion("Question 1");
            Question q2 = createValidQuestion("Question 2");
            Question q3 = createValidQuestion("Question 3");
            
            quiz.setQuestions(Set.of(q1, q2, q3));
            quiz.setEstimatedTime(15);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
            
            verify(questionHandler, times(3)).validateContent(any(EntityQuestionContentRequest.class));
        }
    }
    
    // =============== Boundary Tests ===============
    
    @Nested
    @DisplayName("Boundary Conditions")
    class BoundaryConditions {
        
        @Test
        @DisplayName("Quiz with large estimated time (999999) passes validation")
        void quizWithLargeEstimatedTime_passesValidation() {
            // Given
            Question question = createValidQuestion("Question 1");
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(999999);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("Quiz with many questions (50) validates all")
        void quizWithManyQuestions_validatesAll() {
            // Given
            Set<Question> questions = new HashSet<>();
            for (int i = 1; i <= 50; i++) {
                questions.add(createValidQuestion("Question " + i));
            }
            quiz.setQuestions(questions);
            quiz.setEstimatedTime(100);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When & Then
            assertThatCode(() -> validator.ensurePublishable(quiz))
                .doesNotThrowAnyException();
            
            // Should validate all 50 questions
            verify(questionHandler, times(50)).validateContent(any(EntityQuestionContentRequest.class));
        }
    }
    
    // =============== Integration with QuestionHandler Tests ===============
    
    @Nested
    @DisplayName("Integration with QuestionHandler")
    class QuestionHandlerIntegration {
        
        @Test
        @DisplayName("Calls correct handler for each question type")
        void callsCorrectHandler_forEachQuestionType() {
            // Given
            Question mcqQuestion = createValidQuestion("MCQ");
            mcqQuestion.setType(QuestionType.MCQ_SINGLE);
            
            Question tfQuestion = new Question();
            tfQuestion.setId(UUID.randomUUID());
            tfQuestion.setType(QuestionType.TRUE_FALSE);
            tfQuestion.setQuestionText("TF");
            tfQuestion.setContent("{\"answer\":true}");
            
            quiz.setQuestions(Set.of(mcqQuestion, tfQuestion));
            quiz.setEstimatedTime(10);
            
            QuestionHandler mcqHandler = mock(QuestionHandler.class);
            QuestionHandler tfHandler = mock(QuestionHandler.class);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE)).thenReturn(mcqHandler);
            when(questionHandlerFactory.getHandler(QuestionType.TRUE_FALSE)).thenReturn(tfHandler);
            
            doNothing().when(mcqHandler).validateContent(any(EntityQuestionContentRequest.class));
            doNothing().when(tfHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When
            validator.ensurePublishable(quiz);
            
            // Then
            verify(questionHandlerFactory).getHandler(QuestionType.MCQ_SINGLE);
            verify(questionHandlerFactory).getHandler(QuestionType.TRUE_FALSE);
            verify(mcqHandler).validateContent(any(EntityQuestionContentRequest.class));
            verify(tfHandler).validateContent(any(EntityQuestionContentRequest.class));
        }
        
        @Test
        @DisplayName("Passes correct content to handler for validation")
        void passesCorrectContent_toHandler() {
            // Given
            String jsonContent = "{\"options\":[{\"id\":1,\"text\":\"A\",\"isCorrect\":true}]}";
            Question question = new Question();
            question.setId(UUID.randomUUID());
            question.setType(QuestionType.MCQ_SINGLE);
            question.setQuestionText("Question");
            question.setContent(jsonContent);
            
            quiz.setQuestions(Set.of(question));
            quiz.setEstimatedTime(10);
            
            when(questionHandlerFactory.getHandler(QuestionType.MCQ_SINGLE))
                .thenReturn(questionHandler);
            doNothing().when(questionHandler).validateContent(any(EntityQuestionContentRequest.class));
            
            // When
            validator.ensurePublishable(quiz);
            
            // Then - Verify the content was parsed and passed correctly
            verify(questionHandler).validateContent(argThat(request -> 
                request.getType() == QuestionType.MCQ_SINGLE &&
                request.getContent() != null &&
                request.getContent().has("options")
            ));
        }
    }
    
    // =============== Null Safety Tests ===============
    
    @Nested
    @DisplayName("Null Safety")
    class NullSafety {
        
        @Test
        @DisplayName("Quiz with null questions collection fails validation")
        void nullQuestionsCollection_failsValidation() {
            // Given
            quiz.setQuestions(null);
            quiz.setEstimatedTime(10);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot publish quiz without questions");
            
            // Should not attempt to validate questions
            verify(questionHandlerFactory, never()).getHandler(any());
        }
        
        @Test
        @DisplayName("Validation skips question content check if no questions present")
        void noQuestions_skipsQuestionValidation() {
            // Given
            quiz.setQuestions(new HashSet<>());
            quiz.setEstimatedTime(10);
            
            // When & Then
            assertThatThrownBy(() -> validator.ensurePublishable(quiz))
                .isInstanceOf(IllegalArgumentException.class);
            
            // Should not call handler factory if there are no questions
            verify(questionHandlerFactory, never()).getHandler(any());
        }
    }
    
    // =============== Helper Methods ===============
    
    private Question createValidQuestion(String text) {
        Question question = new Question();
        question.setId(UUID.randomUUID());
        question.setType(QuestionType.MCQ_SINGLE);
        question.setQuestionText(text);
        question.setContent("{\"options\":[{\"id\":1,\"text\":\"Option A\",\"isCorrect\":true},{\"id\":2,\"text\":\"Option B\",\"isCorrect\":false}]}");
        return question;
    }
}

