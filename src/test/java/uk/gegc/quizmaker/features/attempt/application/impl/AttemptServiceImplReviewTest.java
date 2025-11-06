package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerReviewDto;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptReviewDto;
import uk.gegc.quizmaker.features.attempt.application.ScoringService;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.attempt.infra.mapping.AttemptMapper;
import uk.gegc.quizmaker.features.question.application.CorrectAnswerExtractor;
import uk.gegc.quizmaker.features.question.application.SafeQuestionContentBuilder;
import uk.gegc.quizmaker.features.question.domain.model.Answer;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.AnswerRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.SafeQuestionMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.ShareLinkRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.AttemptNotCompletedException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("AttemptServiceImpl Review Methods Unit Tests")
class AttemptServiceImplReviewTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private QuizRepository quizRepository;
    @Mock
    private AttemptRepository attemptRepository;
    @Mock
    private AttemptMapper attemptMapper;
    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private QuestionHandlerFactory handlerFactory;
    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private AnswerMapper answerMapper;
    @Mock
    private ScoringService scoringService;
    @Mock
    private SafeQuestionMapper safeQuestionMapper;
    @Mock
    private ShareLinkRepository shareLinkRepository;
    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;
    @Mock
    private CorrectAnswerExtractor correctAnswerExtractor;
    @Mock
    private SafeQuestionContentBuilder safeQuestionContentBuilder;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AttemptServiceImpl service;

    private ObjectMapper realObjectMapper;
    private User testUser;
    private User otherUser;
    private Quiz testQuiz;
    private Attempt testAttempt;
    private Question testQuestion;
    private Answer testAnswer;

    @BeforeEach
    void setUp() {
        realObjectMapper = new ObjectMapper();

        // Create test user
        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setUsername("testuser");

        // Create other user
        otherUser = new User();
        otherUser.setId(UUID.randomUUID());
        otherUser.setUsername("otheruser");

        // Create test quiz
        testQuiz = new Quiz();
        testQuiz.setId(UUID.randomUUID());

        // Create test question
        testQuestion = new Question();
        testQuestion.setId(UUID.randomUUID());
        testQuestion.setType(QuestionType.MCQ_SINGLE);
        testQuestion.setQuestionText("What is the capital of France?");
        testQuestion.setHint("It's a major European city");
        testQuestion.setExplanation("Paris is the capital and largest city of France, located on the Seine River.");
        testQuestion.setAttachmentUrl("http://example.com/image.png");
        testQuestion.setContent("{\"options\":[{\"id\":\"opt_1\",\"text\":\"Paris\",\"correct\":true}]}");

        // Create test answer
        testAnswer = new Answer();
        testAnswer.setId(UUID.randomUUID());
        testAnswer.setQuestion(testQuestion);
        testAnswer.setResponse("{\"selectedOptionId\":\"opt_1\"}");
        testAnswer.setIsCorrect(true);
        testAnswer.setScore(1.0);
        testAnswer.setAnsweredAt(Instant.now());

        // Create test attempt
        testAttempt = new Attempt();
        testAttempt.setId(UUID.randomUUID());
        testAttempt.setUser(testUser);
        testAttempt.setQuiz(testQuiz);
        testAttempt.setStatus(AttemptStatus.COMPLETED);
        testAttempt.setStartedAt(Instant.now().minusSeconds(300));
        testAttempt.setCompletedAt(Instant.now());
        testAttempt.setTotalScore(1.0);
        testAttempt.setAnswers(new ArrayList<>(List.of(testAnswer)));
    }

    @Test
    @DisplayName("getAttemptReview: when owner and completed then returns review")
    void getAttemptReview_ownerAndCompleted_returnsReview() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        // Mock JSON parsing for user response
        JsonNode userResponseJson = realObjectMapper.readTree("{\"selectedOptionId\":\"opt_1\"}");
        when(objectMapper.readTree(testAnswer.getResponse())).thenReturn(userResponseJson);

        JsonNode correctAnswer = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        when(correctAnswerExtractor.extractCorrectAnswer(testQuestion)).thenReturn(correctAnswer);

        JsonNode safeContent = realObjectMapper.readTree("{\"options\":[{\"id\":\"opt_1\",\"text\":\"Paris\"}]}");
        when(safeQuestionContentBuilder.buildSafeContent(eq(QuestionType.MCQ_SINGLE), any(), eq(true)))
                .thenReturn(safeContent);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        );

        // Then
        assertThat(result).isNotNull();
        assertThat(result.attemptId()).isEqualTo(testAttempt.getId());
        assertThat(result.quizId()).isEqualTo(testQuiz.getId());
        assertThat(result.userId()).isEqualTo(testUser.getId());
        assertThat(result.totalScore()).isEqualTo(1.0);
        assertThat(result.correctCount()).isEqualTo(1);
        assertThat(result.totalQuestions()).isEqualTo(1);
        assertThat(result.answers()).hasSize(1);

        AnswerReviewDto answerReview = result.answers().get(0);
        assertThat(answerReview.questionId()).isEqualTo(testQuestion.getId());
        assertThat(answerReview.type()).isEqualTo(QuestionType.MCQ_SINGLE);
        assertThat(answerReview.questionText()).isEqualTo("What is the capital of France?");
        assertThat(answerReview.hint()).isEqualTo("It's a major European city");
        assertThat(answerReview.explanation()).isEqualTo("Paris is the capital and largest city of France, located on the Seine River.");
        assertThat(answerReview.userResponse()).isNotNull();
        assertThat(answerReview.correctAnswer()).isNotNull();
        assertThat(answerReview.questionSafeContent()).isNotNull();

        verify(correctAnswerExtractor).extractCorrectAnswer(testQuestion);
        verify(safeQuestionContentBuilder).buildSafeContent(eq(QuestionType.MCQ_SINGLE), any(), eq(true));
    }

    @Test
    @DisplayName("getAttemptReview: when non-owner then throws AccessDeniedException")
    void getAttemptReview_nonOwner_throwsAccessDenied() {
        // Given
        when(userRepository.findByUsername("otheruser")).thenReturn(Optional.of(otherUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));

        // When & Then
        assertThatThrownBy(() -> service.getAttemptReview(
                "otheruser",
                testAttempt.getId(),
                true,
                true,
                true
        ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("You do not have access to attempt");

        verify(correctAnswerExtractor, never()).extractCorrectAnswer(any());
    }

    @Test
    @DisplayName("getAttemptReview: when attempt not completed then throws AttemptNotCompletedException")
    void getAttemptReview_notCompleted_throwsAttemptNotCompleted() {
        // Given
        testAttempt.setStatus(AttemptStatus.IN_PROGRESS);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));

        // When & Then
        assertThatThrownBy(() -> service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        ))
                .isInstanceOf(AttemptNotCompletedException.class)
                .hasMessageContaining("not completed yet");

        verify(correctAnswerExtractor, never()).extractCorrectAnswer(any());
    }

    @Test
    @DisplayName("getAttemptReview: when attempt is PAUSED then throws AttemptNotCompletedException")
    void getAttemptReview_paused_throwsAttemptNotCompleted() {
        // Given
        testAttempt.setStatus(AttemptStatus.PAUSED);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));

        // When & Then
        assertThatThrownBy(() -> service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        ))
                .isInstanceOf(AttemptNotCompletedException.class)
                .hasMessageContaining("not completed yet");
    }

    @Test
    @DisplayName("getAttemptReview: when attempt is ABANDONED then throws AttemptNotCompletedException")
    void getAttemptReview_abandoned_throwsAttemptNotCompleted() {
        // Given
        testAttempt.setStatus(AttemptStatus.ABANDONED);
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));

        // When & Then
        assertThatThrownBy(() -> service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        ))
                .isInstanceOf(AttemptNotCompletedException.class)
                .hasMessageContaining("not completed yet");
    }

    @Test
    @DisplayName("getAttemptReview: when attempt not found then throws ResourceNotFoundException")
    void getAttemptReview_notFound_throwsResourceNotFound() {
        // Given
        UUID unknownId = UUID.randomUUID();
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(unknownId))
                .thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.getAttemptReview(
                "testuser",
                unknownId,
                true,
                true,
                true
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("getAttemptReview: when user not found then throws ResourceNotFoundException")
    void getAttemptReview_userNotFound_throwsResourceNotFound() {
        // Given
        when(userRepository.findByUsername("unknownuser")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("unknownuser")).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> service.getAttemptReview(
                "unknownuser",
                testAttempt.getId(),
                true,
                true,
                true
        ))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("User unknownuser not found");
    }

    @Test
    @DisplayName("getAttemptReview: includeUserAnswers=false excludes user responses")
    void getAttemptReview_excludeUserAnswers_excludesResponses() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        JsonNode correctAnswer = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        when(correctAnswerExtractor.extractCorrectAnswer(testQuestion)).thenReturn(correctAnswer);

        JsonNode safeContent = realObjectMapper.readTree("{\"options\":[{\"id\":\"opt_1\",\"text\":\"Paris\"}]}");
        when(safeQuestionContentBuilder.buildSafeContent(eq(QuestionType.MCQ_SINGLE), any(), eq(true)))
                .thenReturn(safeContent);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                false,  // includeUserAnswers=false
                true,
                true
        );

        // Then
        assertThat(result.answers()).hasSize(1);
        AnswerReviewDto answerReview = result.answers().get(0);
        assertThat(answerReview.userResponse()).isNull();
        assertThat(answerReview.correctAnswer()).isNotNull();
    }

    @Test
    @DisplayName("getAttemptReview: includeCorrectAnswers=false excludes correct answers")
    void getAttemptReview_excludeCorrectAnswers_excludesCorrectAnswers() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        // Mock JSON parsing for user response (includeUserAnswers=true)
        JsonNode userResponseJson = realObjectMapper.readTree("{\"selectedOptionId\":\"opt_1\"}");
        when(objectMapper.readTree(testAnswer.getResponse())).thenReturn(userResponseJson);

        JsonNode safeContent = realObjectMapper.readTree("{\"options\":[{\"id\":\"opt_1\",\"text\":\"Paris\"}]}");
        when(safeQuestionContentBuilder.buildSafeContent(eq(QuestionType.MCQ_SINGLE), any(), eq(true)))
                .thenReturn(safeContent);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                false,  // includeCorrectAnswers=false
                true
        );

        // Then
        assertThat(result.answers()).hasSize(1);
        AnswerReviewDto answerReview = result.answers().get(0);
        assertThat(answerReview.userResponse()).isNotNull();
        assertThat(answerReview.correctAnswer()).isNull();
        verify(correctAnswerExtractor, never()).extractCorrectAnswer(any());
    }

    @Test
    @DisplayName("getAttemptReview: includeQuestionContext=false excludes question context")
    void getAttemptReview_excludeQuestionContext_excludesContext() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        JsonNode correctAnswer = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        when(correctAnswerExtractor.extractCorrectAnswer(testQuestion)).thenReturn(correctAnswer);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                false  // includeQuestionContext=false
        );

        // Then
        assertThat(result.answers()).hasSize(1);
        AnswerReviewDto answerReview = result.answers().get(0);
        assertThat(answerReview.questionText()).isNull();
        assertThat(answerReview.hint()).isNull();
        assertThat(answerReview.explanation()).isNull();
        assertThat(answerReview.attachmentUrl()).isNull();
        assertThat(answerReview.questionSafeContent()).isNull();
        verify(safeQuestionContentBuilder, never()).buildSafeContent(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("getAttemptReview: all flags false excludes all optional data")
    void getAttemptReview_allFlagsFalse_excludesAll() {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                false,  // includeUserAnswers
                false,  // includeCorrectAnswers
                false   // includeQuestionContext
        );

        // Then
        assertThat(result.answers()).hasSize(1);
        AnswerReviewDto answerReview = result.answers().get(0);
        assertThat(answerReview.userResponse()).isNull();
        assertThat(answerReview.correctAnswer()).isNull();
        assertThat(answerReview.questionText()).isNull();
        assertThat(answerReview.questionSafeContent()).isNull();
        assertThat(answerReview.isCorrect()).isTrue();  // Core fields still present
        assertThat(answerReview.score()).isEqualTo(1.0);

        verify(correctAnswerExtractor, never()).extractCorrectAnswer(any());
        verify(safeQuestionContentBuilder, never()).buildSafeContent(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("getAttemptReview: answers sorted by answeredAt")
    void getAttemptReview_answersSortedByTime() throws Exception {
        // Given
        Instant now = Instant.now();
        Answer answer1 = new Answer();
        answer1.setId(UUID.randomUUID());
        answer1.setQuestion(testQuestion);
        answer1.setResponse("{}");
        answer1.setIsCorrect(true);
        answer1.setScore(1.0);
        answer1.setAnsweredAt(now.plusSeconds(10));  // Later

        Answer answer2 = new Answer();
        answer2.setId(UUID.randomUUID());
        answer2.setQuestion(testQuestion);
        answer2.setResponse("{}");
        answer2.setIsCorrect(false);
        answer2.setScore(0.0);
        answer2.setAnsweredAt(now);  // Earlier

        testAttempt.setAnswers(List.of(answer1, answer2));  // Out of order

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(2L);

        // Mock JSON parsing for user responses
        JsonNode emptyJson = realObjectMapper.readTree("{}");
        when(objectMapper.readTree(anyString())).thenReturn(emptyJson);

        JsonNode correctAnswer = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        when(correctAnswerExtractor.extractCorrectAnswer(any())).thenReturn(correctAnswer);

        JsonNode safeContent = realObjectMapper.readTree("{}");
        when(safeQuestionContentBuilder.buildSafeContent(any(), any(), eq(true)))
                .thenReturn(safeContent);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        );

        // Then
        assertThat(result.answers()).hasSize(2);
        // Should be sorted by answeredAt (earlier first)
        assertThat(result.answers().get(0).answeredAt()).isEqualTo(now);
        assertThat(result.answers().get(1).answeredAt()).isEqualTo(now.plusSeconds(10));
    }

    @Test
    @DisplayName("getAttemptAnswerKey: returns review with no user responses")
    void getAttemptAnswerKey_returnsReviewWithoutUserResponses() throws Exception {
        // Given
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        JsonNode correctAnswer = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        when(correctAnswerExtractor.extractCorrectAnswer(testQuestion)).thenReturn(correctAnswer);

        JsonNode safeContent = realObjectMapper.readTree("{\"options\":[{\"id\":\"opt_1\",\"text\":\"Paris\"}]}");
        when(safeQuestionContentBuilder.buildSafeContent(eq(QuestionType.MCQ_SINGLE), any(), eq(true)))
                .thenReturn(safeContent);

        // When
        AttemptReviewDto result = service.getAttemptAnswerKey("testuser", testAttempt.getId());

        // Then
        assertThat(result.answers()).hasSize(1);
        AnswerReviewDto answerReview = result.answers().get(0);
        assertThat(answerReview.userResponse()).isNull();  // No user responses
        assertThat(answerReview.correctAnswer()).isNotNull();  // Has correct answers
        assertThat(answerReview.questionSafeContent()).isNotNull();  // Has question context
    }

    @Test
    @DisplayName("getAttemptReview: when zero answers then returns empty list")
    void getAttemptReview_zeroAnswers_returnsEmptyList() {
        // Given
        testAttempt.setAnswers(new ArrayList<>());  // No answers
        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(5L);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        );

        // Then
        assertThat(result.answers()).isEmpty();
        assertThat(result.correctCount()).isEqualTo(0);
        assertThat(result.totalQuestions()).isEqualTo(5);
        assertThat(result.totalScore()).isEqualTo(1.0);  // Still has the score from attempt

        verify(correctAnswerExtractor, never()).extractCorrectAnswer(any());
    }

    @Test
    @DisplayName("getAttemptReview: partial answers with mixed correctness")
    void getAttemptReview_partialAnswers_mixedCorrectness() throws Exception {
        // Given
        Answer wrongAnswer = new Answer();
        wrongAnswer.setId(UUID.randomUUID());
        wrongAnswer.setQuestion(testQuestion);
        wrongAnswer.setResponse("{\"selectedOptionId\":\"opt_2\"}");
        wrongAnswer.setIsCorrect(false);
        wrongAnswer.setScore(0.0);
        wrongAnswer.setAnsweredAt(Instant.now().plusSeconds(1));

        testAttempt.setAnswers(List.of(testAnswer, wrongAnswer));  // 1 correct, 1 wrong

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(5L);  // 5 total, only 2 answered

        JsonNode userResponse1 = realObjectMapper.readTree("{\"selectedOptionId\":\"opt_1\"}");
        JsonNode userResponse2 = realObjectMapper.readTree("{\"selectedOptionId\":\"opt_2\"}");
        when(objectMapper.readTree(testAnswer.getResponse())).thenReturn(userResponse1);
        when(objectMapper.readTree(wrongAnswer.getResponse())).thenReturn(userResponse2);

        JsonNode correctAnswer = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        when(correctAnswerExtractor.extractCorrectAnswer(any())).thenReturn(correctAnswer);

        JsonNode safeContent = realObjectMapper.readTree("{}");
        when(safeQuestionContentBuilder.buildSafeContent(any(), any(), eq(true)))
                .thenReturn(safeContent);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        );

        // Then
        assertThat(result.answers()).hasSize(2);  // Partial answers
        assertThat(result.correctCount()).isEqualTo(1);  // Only 1 correct
        assertThat(result.totalQuestions()).isEqualTo(5);  // But quiz has 5 total
        
        // Verify mixed correctness
        assertThat(result.answers().get(0).isCorrect()).isTrue();
        assertThat(result.answers().get(1).isCorrect()).isFalse();
    }

    @Test
    @DisplayName("getAttemptReview: malformed Answer.response JSON gracefully handled")
    void getAttemptReview_malformedAnswerResponse_gracefullyHandled() throws Exception {
        // Given
        testAnswer.setResponse("{invalid json");  // Malformed JSON

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        // Mock ObjectMapper to throw JsonProcessingException for malformed JSON
        when(objectMapper.readTree("{invalid json"))
                .thenThrow(new com.fasterxml.jackson.core.JsonParseException(null, "Unexpected character"));

        JsonNode correctAnswer = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        when(correctAnswerExtractor.extractCorrectAnswer(testQuestion)).thenReturn(correctAnswer);

        JsonNode safeContent = realObjectMapper.readTree("{}");
        when(safeQuestionContentBuilder.buildSafeContent(any(), any(), eq(true)))
                .thenReturn(safeContent);

        // Mock error node creation for graceful error handling
        when(objectMapper.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        );

        // Then
        assertThat(result.answers()).hasSize(1);
        AnswerReviewDto answerReview = result.answers().get(0);
        
        // User response should have error message instead of throwing
        assertThat(answerReview.userResponse()).isNotNull();
        assertThat(answerReview.userResponse().has("error")).isTrue();
        assertThat(answerReview.userResponse().get("error").asText())
                .contains("Failed to parse user response");
        
        // But other fields should still work
        assertThat(answerReview.correctAnswer()).isNotNull();
        assertThat(answerReview.isCorrect()).isTrue();
    }

    @Test
    @DisplayName("getAttemptReview: malformed Question.content JSON gracefully handled")
    void getAttemptReview_malformedQuestionContent_gracefullyHandled() throws Exception {
        // Given
        testQuestion.setContent("{invalid json");  // Malformed JSON

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(1L);

        // Mock JSON parsing for user response
        JsonNode userResponseJson = realObjectMapper.readTree("{\"selectedOptionId\":\"opt_1\"}");
        when(objectMapper.readTree(testAnswer.getResponse())).thenReturn(userResponseJson);

        // Mock extractor to throw for malformed content
        when(correctAnswerExtractor.extractCorrectAnswer(testQuestion))
                .thenThrow(new IllegalArgumentException("Failed to parse question content"));

        // Mock error node creation for graceful error handling
        when(objectMapper.createObjectNode()).thenReturn(realObjectMapper.createObjectNode());

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                false  // Don't include question context to avoid safe content builder issues
        );

        // Then
        assertThat(result.answers()).hasSize(1);
        AnswerReviewDto answerReview = result.answers().get(0);
        
        // Correct answer should have error message instead of throwing
        assertThat(answerReview.correctAnswer()).isNotNull();
        assertThat(answerReview.correctAnswer().has("error")).isTrue();
        assertThat(answerReview.correctAnswer().get("error").asText())
                .contains("Failed to extract correct answer");
        
        // But other fields should still work
        assertThat(answerReview.userResponse()).isNotNull();
        assertThat(answerReview.isCorrect()).isTrue();
    }

    @Test
    @DisplayName("getAttemptReview: handles multiple answers with different question types")
    void getAttemptReview_multipleQuestionTypes() throws Exception {
        // Given
        Question trueFalseQuestion = new Question();
        trueFalseQuestion.setId(UUID.randomUUID());
        trueFalseQuestion.setType(QuestionType.TRUE_FALSE);
        trueFalseQuestion.setQuestionText("Is the sky blue?");
        trueFalseQuestion.setContent("{\"answer\":true}");

        Answer answer2 = new Answer();
        answer2.setId(UUID.randomUUID());
        answer2.setQuestion(trueFalseQuestion);
        answer2.setResponse("{\"answer\":true}");
        answer2.setIsCorrect(true);
        answer2.setScore(1.0);
        answer2.setAnsweredAt(Instant.now().plusSeconds(1));

        testAttempt.setAnswers(List.of(testAnswer, answer2));

        when(userRepository.findByUsername("testuser")).thenReturn(Optional.of(testUser));
        when(attemptRepository.findByIdWithAnswersAndQuestion(testAttempt.getId()))
                .thenReturn(Optional.of(testAttempt));
        when(questionRepository.countByQuizId_Id(testQuiz.getId())).thenReturn(2L);

        // Mock JSON parsing for user responses
        JsonNode mcqResponse = realObjectMapper.readTree("{\"selectedOptionId\":\"opt_1\"}");
        JsonNode tfResponse = realObjectMapper.readTree("{\"answer\":true}");
        when(objectMapper.readTree(testAnswer.getResponse())).thenReturn(mcqResponse);
        when(objectMapper.readTree(answer2.getResponse())).thenReturn(tfResponse);

        JsonNode mcqCorrect = realObjectMapper.readTree("{\"correctOptionId\":\"opt_1\"}");
        JsonNode tfCorrect = realObjectMapper.readTree("{\"answer\":true}");
        when(correctAnswerExtractor.extractCorrectAnswer(testQuestion)).thenReturn(mcqCorrect);
        when(correctAnswerExtractor.extractCorrectAnswer(trueFalseQuestion)).thenReturn(tfCorrect);

        JsonNode safeContent = realObjectMapper.readTree("{}");
        when(safeQuestionContentBuilder.buildSafeContent(any(), any(), eq(true)))
                .thenReturn(safeContent);

        // When
        AttemptReviewDto result = service.getAttemptReview(
                "testuser",
                testAttempt.getId(),
                true,
                true,
                true
        );

        // Then
        assertThat(result.answers()).hasSize(2);
        assertThat(result.correctCount()).isEqualTo(2);
        assertThat(result.totalQuestions()).isEqualTo(2);

        verify(correctAnswerExtractor, times(2)).extractCorrectAnswer(any());
    }
}

