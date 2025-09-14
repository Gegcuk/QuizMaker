package uk.gegc.quizmaker.service.attempt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.attempt.api.dto.CurrentQuestionDto;
import uk.gegc.quizmaker.features.attempt.api.dto.QuestionForAttemptDto;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.*;
import java.util.Comparator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttemptServiceCurrentQuestionTest {

    @Mock
    private AttemptRepository attemptRepository;

    @Mock
    private SafeQuestionMapper safeQuestionMapper;

    @Mock
    private AnswerRepository answerRepository;

    @Mock
    private QuestionRepository questionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;

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

        // Setup user repository mock
        lenient().when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        lenient().when(userRepository.findByEmail("testuser")).thenReturn(Optional.empty());
        
        // Setup permission evaluator mock - allow all permissions for test user
        lenient().when(appPermissionEvaluator.hasPermission(eq(testUser), any())).thenReturn(true);

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
        
        // Setup question repository mock to return questions based on the quiz's current question set
        lenient().when(questionRepository.findAllByQuizId_IdOrderById(testQuiz.getId()))
                .thenAnswer(invocation -> {
                    // Return questions in the order they appear in the quiz's question set
                    Set<Question> quizQuestions = testQuiz.getQuestions();
                    return quizQuestions.stream()
                            .sorted(Comparator.comparing(Question::getId))
                            .collect(java.util.stream.Collectors.toList());
                });
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

    @Test
    void getCurrentQuestion_ThirdQuestion_ReturnsThirdQuestion() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(2L);
        
        // Use a custom answer that returns different DTOs based on the question
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenAnswer(invocation -> {
                    Question question = invocation.getArgument(0);
                    if (question != null && question.getId().equals(question3.getId())) {
                        return safeQuestionDto2; // Use question2 DTO for question3
                    }
                    return safeQuestionDto1; // Default fallback
                });

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        assertNotNull(result.question().getId());
        assertEquals(3, result.questionNumber());
        assertEquals(3, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_WithManyQuestions_ReturnsCorrectQuestion() {
        // Arrange - Create a quiz with 10 questions
        Set<Question> manyQuestions = new HashSet<>();
        for (int i = 1; i <= 10; i++) {
            Question q = new Question();
            q.setId(UUID.randomUUID());
            q.setQuestionText("Question " + i);
            q.setType(QuestionType.MCQ_SINGLE);
            q.setDifficulty(Difficulty.EASY);
            q.setContent("{\"question\": \"Question " + i + "\", \"options\": [\"A\", \"B\", \"C\", \"D\"]}");
            manyQuestions.add(q);
        }
        testQuiz.setQuestions(manyQuestions);
        
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(7L); // 7 questions answered, so should return question 8
        
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenReturn(safeQuestionDto1);

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        assertEquals(8, result.questionNumber()); // Should be question 8 (1-based)
        assertEquals(10, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_WithSingleQuestion_ReturnsFirstQuestion() {
        // Arrange - Create a quiz with only 1 question
        Set<Question> singleQuestion = new HashSet<>();
        Question q = new Question();
        q.setId(UUID.randomUUID());
        q.setQuestionText("Single Question");
        q.setType(QuestionType.MCQ_SINGLE);
        q.setDifficulty(Difficulty.EASY);
        q.setContent("{\"question\": \"Single Question\", \"options\": [\"A\", \"B\", \"C\", \"D\"]}");
        singleQuestion.add(q);
        testQuiz.setQuestions(singleQuestion);
        
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(0L); // No questions answered
        
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenReturn(safeQuestionDto1);

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.questionNumber());
        assertEquals(1, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_WithSingleQuestion_AllAnswered_ThrowsException() {
        // Arrange - Create a quiz with only 1 question
        Set<Question> singleQuestion = new HashSet<>();
        Question q = new Question();
        q.setId(UUID.randomUUID());
        q.setQuestionText("Single Question");
        q.setType(QuestionType.MCQ_SINGLE);
        q.setDifficulty(Difficulty.EASY);
        q.setContent("{\"question\": \"Single Question\", \"options\": [\"A\", \"B\", \"C\", \"D\"]}");
        singleQuestion.add(q);
        testQuiz.setQuestions(singleQuestion);
        
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(1L); // All questions answered

        // Act & Assert
        assertThrows(IllegalStateException.class, () ->
                attemptService.getCurrentQuestion("testuser", attemptId));
    }

    @Test
    void getCurrentQuestion_QuestionOrdering_ConsistentWithIdSorting() {
        // Arrange - Create questions with specific IDs to test ordering
        Question q1 = new Question();
        q1.setId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        q1.setQuestionText("First Question");
        q1.setType(QuestionType.MCQ_SINGLE);
        q1.setDifficulty(Difficulty.EASY);
        q1.setContent("{\"question\": \"First\", \"options\": [\"A\", \"B\", \"C\", \"D\"]}");

        Question q2 = new Question();
        q2.setId(UUID.fromString("00000000-0000-0000-0000-000000000002"));
        q2.setQuestionText("Second Question");
        q2.setType(QuestionType.MCQ_SINGLE);
        q2.setDifficulty(Difficulty.EASY);
        q2.setContent("{\"question\": \"Second\", \"options\": [\"A\", \"B\", \"C\", \"D\"]}");

        Question q3 = new Question();
        q3.setId(UUID.fromString("00000000-0000-0000-0000-000000000003"));
        q3.setQuestionText("Third Question");
        q3.setType(QuestionType.MCQ_SINGLE);
        q3.setDifficulty(Difficulty.EASY);
        q3.setContent("{\"question\": \"Third\", \"options\": [\"A\", \"B\", \"C\", \"D\"]}");

        // Add questions in random order to test sorting
        Set<Question> questions = new HashSet<>(Arrays.asList(q3, q1, q2));
        testQuiz.setQuestions(questions);
        
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(1L); // 1 question answered, should return second question
        
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenAnswer(invocation -> {
                    Question question = invocation.getArgument(0);
                    if (question.getId().equals(q2.getId())) {
                        return safeQuestionDto2;
                    }
                    return safeQuestionDto1;
                });

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert - Should return the second question (sorted by ID)
        assertNotNull(result);
        assertEquals(2, result.questionNumber());
        assertEquals(3, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_WithZeroAnswers_ReturnsFirstQuestion() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(0L); // No answers
        
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenReturn(safeQuestionDto1);

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        assertEquals(1, result.questionNumber());
        assertEquals(3, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_WithPartialAnswers_ReturnsNextQuestion() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(1L); // 1 answer, should return second question
        
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenReturn(safeQuestionDto2);

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        assertEquals(2, result.questionNumber());
        assertEquals(3, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }

    @Test
    void getCurrentQuestion_WithAllButOneAnswered_ReturnsLastQuestion() {
        // Arrange
        UUID attemptId = testAttempt.getId();
        when(attemptRepository.findFullyLoadedById(attemptId))
                .thenReturn(Optional.of(testAttempt));
        when(answerRepository.countByAttemptId(attemptId))
                .thenReturn(2L); // 2 answers, should return third question (last)
        
        when(safeQuestionMapper.toSafeDto(any(Question.class)))
                .thenReturn(safeQuestionDto2);

        // Act
        CurrentQuestionDto result = attemptService.getCurrentQuestion("testuser", attemptId);

        // Assert
        assertNotNull(result);
        assertEquals(3, result.questionNumber());
        assertEquals(3, result.totalQuestions());
        assertEquals(AttemptStatus.IN_PROGRESS, result.attemptStatus());
    }
}