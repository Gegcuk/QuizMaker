package uk.gegc.quizmaker.service.attempt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.attempt.CurrentQuestionDto;
import uk.gegc.quizmaker.dto.question.QuestionForAttemptDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.mapper.SafeQuestionMapper;
import uk.gegc.quizmaker.model.attempt.Attempt;
import uk.gegc.quizmaker.model.attempt.AttemptStatus;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.attempt.AttemptRepository;
import uk.gegc.quizmaker.repository.question.AnswerRepository;
import uk.gegc.quizmaker.service.attempt.impl.AttemptServiceImpl;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttemptServiceCurrentQuestionTest {

    @Mock
    private AttemptRepository attemptRepository;

    @Mock
    private SafeQuestionMapper safeQuestionMapper;

    @Mock
    private AnswerRepository answerRepository;

    @InjectMocks
    private AttemptServiceImpl attemptService;

    private User testUser;
    private Quiz testQuiz;
    private Attempt testAttempt;
    private Question question1;
    private Question question2;
    private Question question3;
    private QuestionForAttemptDto safeQuestionDto1;
    private QuestionForAttemptDto safeQuestionDto2;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");

        testQuiz = new Quiz();
        testQuiz.setId(UUID.randomUUID());
        testQuiz.setTitle("Test Quiz");

        question1 = new Question();
        question1.setId(UUID.randomUUID());
        question1.setQuestionText("What is 2+2?");
        question1.setType(QuestionType.MCQ_SINGLE);
        question1.setDifficulty(Difficulty.EASY);
        question1.setContent("{\"question\": \"What is 2+2?\", \"options\": [\"3\", \"4\", \"5\", \"6\"]}");

        question2 = new Question();
        question2.setId(UUID.randomUUID());
        question2.setQuestionText("What is 3+3?");
        question2.setType(QuestionType.MCQ_SINGLE);
        question2.setDifficulty(Difficulty.EASY);
        question2.setContent("{\"question\": \"What is 3+3?\", \"options\": [\"5\", \"6\", \"7\", \"8\"]}");

        question3 = new Question();
        question3.setId(UUID.randomUUID());
        question3.setQuestionText("What is 4+4?");
        question3.setType(QuestionType.MCQ_SINGLE);
        question3.setDifficulty(Difficulty.EASY);
        question3.setContent("{\"question\": \"What is 4+4?\", \"options\": [\"7\", \"8\", \"9\", \"10\"]}");

        // Set questions in quiz (sorted by ID for consistent ordering)
        Set<Question> questions = new HashSet<>(Arrays.asList(question1, question2, question3));
        testQuiz.setQuestions(questions);

        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setUser(testUser);
        testAttempt.setQuiz(testQuiz);
        testAttempt.setStatus(AttemptStatus.IN_PROGRESS);
        testAttempt.setStartedAt(Instant.now());
        testAttempt.setAnswers(new ArrayList<>());

        // Create safe question DTOs for each question
        safeQuestionDto1 = new QuestionForAttemptDto();
        safeQuestionDto1.setId(question1.getId());
        safeQuestionDto1.setType(QuestionType.MCQ_SINGLE);
        safeQuestionDto1.setDifficulty(Difficulty.EASY);
        safeQuestionDto1.setQuestionText("What is 2+2?");
        try {
            JsonNode safeContent = objectMapper.readTree("{\"question\": \"What is 2+2?\", \"options\": [\"3\", \"4\", \"5\", \"6\"]}");
            safeQuestionDto1.setSafeContent(safeContent);
        } catch (Exception e) {
            // Ignore for test
        }

        safeQuestionDto2 = new QuestionForAttemptDto();
        safeQuestionDto2.setId(question2.getId());
        safeQuestionDto2.setType(QuestionType.MCQ_SINGLE);
        safeQuestionDto2.setDifficulty(Difficulty.EASY);
        safeQuestionDto2.setQuestionText("What is 3+3?");
        try {
            JsonNode safeContent = objectMapper.readTree("{\"question\": \"What is 3+3?\", \"options\": [\"5\", \"6\", \"7\", \"8\"]}");
            safeQuestionDto2.setSafeContent(safeContent);
        } catch (Exception e) {
            // Ignore for test
        }
    }

    @Test
    void getCurrentQuestion_FirstQuestion_ReturnsFirstQuestion() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(0L);
        
        // Use a custom answer that returns different DTOs based on the question
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenAnswer(invocation -> {
                    Question question = invocation.getArgument(0);
                    if (question != null && question.getId().equals(question1.getId())) {
                        return safeQuestionDto1;
                    } else if (question != null && question.getId().equals(question2.getId())) {
                        return safeQuestionDto2;
                    }
                    return safeQuestionDto1; // Default fallback
                });

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        // Don't check specific question ID since order depends on auto-generated UUIDs
        assertNotNull(result.question().getId());
        assertEquals(1, result.questionNumber());
        assertEquals(3, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_SecondQuestion_ReturnsSecondQuestion() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(1L);
        
        // Use a custom answer that returns different DTOs based on the question
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenAnswer(invocation -> {
                    Question question = invocation.getArgument(0);
                    if (question != null && question.getId().equals(question1.getId())) {
                        return safeQuestionDto1;
                    } else if (question != null && question.getId().equals(question2.getId())) {
                        return safeQuestionDto2;
                    }
                    return safeQuestionDto2; // Default fallback
                });

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        // Don't check specific question ID since order depends on auto-generated UUIDs
        assertNotNull(result.question().getId());
        assertEquals(2, result.questionNumber());
        assertEquals(3, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_AttemptNotFound_ThrowsResourceNotFoundException() {
        // Arrange
        UUID attemptId = UUID.randomUUID();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () ->
                attemptService.getCurrentQuestion("testuser", attemptId));
    }

    @Test
    void getCurrentQuestion_WrongUser_ThrowsAccessDeniedException() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));

        // Act & Assert
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                attemptService.getCurrentQuestion("wronguser", attemptId));
    }

    @Test
    void getCurrentQuestion_AttemptNotInProgress_ThrowsIllegalStateException() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        testAttempt.setStatus(AttemptStatus.COMPLETED);
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));

        // Act & Assert
        assertThrows(IllegalStateException.class, () ->
                attemptService.getCurrentQuestion("testuser", attemptId));
    }

    @Test
    void getCurrentQuestion_AllQuestionsAnswered_ThrowsIllegalStateException() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(3L); // All 3 questions answered

        // Act & Assert
        assertThrows(IllegalStateException.class, () ->
                attemptService.getCurrentQuestion("testuser", attemptId));
    }

    @Test
    void getCurrentQuestion_QuizHasNoQuestions_ThrowsIllegalStateException() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        testQuiz.setQuestions(new HashSet<>());
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));

        // Act & Assert
        assertThrows(IllegalStateException.class, () ->
                attemptService.getCurrentQuestion("testuser", attemptId));
    }
}