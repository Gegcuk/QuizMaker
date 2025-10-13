package uk.gegc.quizmaker.features.attempt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.exception.AttemptNotCompletedException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AttemptController.class)
@DisplayName("AttemptController Review Endpoints Tests")
class AttemptControllerReviewTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AttemptService attemptService;

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: when authorized then returns 200 with review")
    @WithMockUser(username = "testuser")
    void getAttemptReview_authorized_returns200() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        ObjectNode userResponse = objectMapper.createObjectNode();
        userResponse.put("selectedOptionId", "opt_1");

        ObjectNode correctAnswer = objectMapper.createObjectNode();
        correctAnswer.put("correctOptionId", "opt_1");

        ObjectNode safeContent = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        ObjectNode option1 = objectMapper.createObjectNode();
        option1.put("id", "opt_1");
        option1.put("text", "Paris");
        options.add(option1);
        safeContent.set("options", options);

        AnswerReviewDto answerReview = new AnswerReviewDto(
                UUID.randomUUID(),
                QuestionType.MCQ_SINGLE,
                "What is the capital of France?",
                "It's a major European city",
                "http://example.com/image.png",
                safeContent,
                userResponse,
                correctAnswer,
                true,
                1.0,
                Instant.now()
        );

        AttemptReviewDto reviewDto = new AttemptReviewDto(
                attemptId,
                quizId,
                userId,
                Instant.now().minusSeconds(300),
                Instant.now(),
                1.0,
                1,
                1,
                List.of(answerReview)
        );

        when(attemptService.getAttemptReview(
                eq("testuser"),
                eq(attemptId),
                anyBoolean(),
                anyBoolean(),
                anyBoolean()
        )).thenReturn(reviewDto);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.totalScore").value(1.0))
                .andExpect(jsonPath("$.correctCount").value(1))
                .andExpect(jsonPath("$.totalQuestions").value(1))
                .andExpect(jsonPath("$.answers").isArray())
                .andExpect(jsonPath("$.answers.length()").value(1))
                .andExpect(jsonPath("$.answers[0].questionId").exists())
                .andExpect(jsonPath("$.answers[0].type").value("MCQ_SINGLE"))
                .andExpect(jsonPath("$.answers[0].questionText").value("What is the capital of France?"))
                .andExpect(jsonPath("$.answers[0].userResponse").exists())
                .andExpect(jsonPath("$.answers[0].correctAnswer").exists())
                .andExpect(jsonPath("$.answers[0].questionSafeContent").exists())
                .andExpect(jsonPath("$.answers[0].isCorrect").value(true))
                .andExpect(jsonPath("$.answers[0].score").value(1.0));
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: with query params then respects flags")
    @WithMockUser(username = "testuser")
    void getAttemptReview_withQueryParams_respectsFlags() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        AnswerReviewDto answerReview = new AnswerReviewDto(
                UUID.randomUUID(),
                QuestionType.MCQ_SINGLE,
                null,  // No question text (includeQuestionContext=false)
                null,
                null,
                null,
                null,  // No user response (includeUserAnswers=false)
                null,  // No correct answer (includeCorrectAnswers=false)
                true,
                1.0,
                Instant.now()
        );

        AttemptReviewDto reviewDto = new AttemptReviewDto(
                attemptId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().minusSeconds(300),
                Instant.now(),
                1.0,
                1,
                1,
                List.of(answerReview)
        );

        when(attemptService.getAttemptReview(
                eq("testuser"),
                eq(attemptId),
                eq(false),  // includeUserAnswers=false
                eq(false),  // includeCorrectAnswers=false
                eq(false)   // includeQuestionContext=false
        )).thenReturn(reviewDto);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .param("includeUserAnswers", "false")
                        .param("includeCorrectAnswers", "false")
                        .param("includeQuestionContext", "false")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answers[0].userResponse").doesNotExist())
                .andExpect(jsonPath("$.answers[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.answers[0].questionText").doesNotExist())
                .andExpect(jsonPath("$.answers[0].isCorrect").value(true))  // Core fields still present
                .andExpect(jsonPath("$.answers[0].score").value(1.0));
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: when not found then returns 404")
    @WithMockUser(username = "testuser")
    void getAttemptReview_notFound_returns404() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        when(attemptService.getAttemptReview(
                eq("testuser"),
                eq(attemptId),
                anyBoolean(),
                anyBoolean(),
                anyBoolean()
        )).thenThrow(new ResourceNotFoundException("Attempt " + attemptId + " not found"));

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: when not completed then returns 409")
    @WithMockUser(username = "testuser")
    void getAttemptReview_notCompleted_returns409() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        when(attemptService.getAttemptReview(
                eq("testuser"),
                eq(attemptId),
                anyBoolean(),
                anyBoolean(),
                anyBoolean()
        )).thenThrow(new AttemptNotCompletedException(attemptId));

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: when non-owner then returns 403")
    @WithMockUser(username = "otheruser")
    void getAttemptReview_nonOwner_returns403() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        when(attemptService.getAttemptReview(
                eq("otheruser"),
                eq(attemptId),
                anyBoolean(),
                anyBoolean(),
                anyBoolean()
        )).thenThrow(new org.springframework.security.access.AccessDeniedException("You do not have access to attempt " + attemptId));

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/answer-key: when authorized then returns 200 with answer key")
    @WithMockUser(username = "testuser")
    void getAttemptAnswerKey_authorized_returns200() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        ObjectNode correctAnswer = objectMapper.createObjectNode();
        correctAnswer.put("correctOptionId", "opt_1");

        ObjectNode safeContent = objectMapper.createObjectNode();

        AnswerReviewDto answerReview = new AnswerReviewDto(
                UUID.randomUUID(),
                QuestionType.MCQ_SINGLE,
                "What is the capital of France?",
                "It's a major European city",
                null,
                safeContent,
                null,  // No user response for answer key
                correctAnswer,
                true,
                1.0,
                Instant.now()
        );

        AttemptReviewDto reviewDto = new AttemptReviewDto(
                attemptId,
                quizId,
                userId,
                Instant.now().minusSeconds(300),
                Instant.now(),
                1.0,
                1,
                1,
                List.of(answerReview)
        );

        when(attemptService.getAttemptAnswerKey(eq("testuser"), eq(attemptId)))
                .thenReturn(reviewDto);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/answer-key", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.answers").isArray())
                .andExpect(jsonPath("$.answers[0].userResponse").doesNotExist())  // No user responses
                .andExpect(jsonPath("$.answers[0].correctAnswer").exists())  // Has correct answers
                .andExpect(jsonPath("$.answers[0].questionText").exists());  // Has question context
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: multiple question types returns correct JSON shapes")
    @WithMockUser(username = "testuser")
    void getAttemptReview_multipleQuestionTypes_returnsCorrectShapes() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        // MCQ_SINGLE answer
        ObjectNode mcqUserResponse = objectMapper.createObjectNode();
        mcqUserResponse.put("selectedOptionId", "opt_2");

        ObjectNode mcqCorrectAnswer = objectMapper.createObjectNode();
        mcqCorrectAnswer.put("correctOptionId", "opt_1");

        AnswerReviewDto mcqAnswer = new AnswerReviewDto(
                UUID.randomUUID(),
                QuestionType.MCQ_SINGLE,
                "MCQ Question",
                null,
                null,
                null,
                mcqUserResponse,
                mcqCorrectAnswer,
                false,
                0.0,
                Instant.now()
        );

        // TRUE_FALSE answer
        ObjectNode tfUserResponse = objectMapper.createObjectNode();
        tfUserResponse.put("answer", false);

        ObjectNode tfCorrectAnswer = objectMapper.createObjectNode();
        tfCorrectAnswer.put("answer", true);

        AnswerReviewDto tfAnswer = new AnswerReviewDto(
                UUID.randomUUID(),
                QuestionType.TRUE_FALSE,
                "True/False Question",
                null,
                null,
                null,
                tfUserResponse,
                tfCorrectAnswer,
                false,
                0.0,
                Instant.now()
        );

        // FILL_GAP answer
        ObjectNode fillGapUserResponse = objectMapper.createObjectNode();
        ArrayNode userAnswers = objectMapper.createArrayNode();
        ObjectNode userGap1 = objectMapper.createObjectNode();
        userGap1.put("id", 1);
        userGap1.put("text", "Paris");
        userAnswers.add(userGap1);
        fillGapUserResponse.set("answers", userAnswers);

        ObjectNode fillGapCorrectAnswer = objectMapper.createObjectNode();
        ArrayNode correctAnswers = objectMapper.createArrayNode();
        ObjectNode correctGap1 = objectMapper.createObjectNode();
        correctGap1.put("id", 1);
        correctGap1.put("text", "Paris");
        correctAnswers.add(correctGap1);
        fillGapCorrectAnswer.set("answers", correctAnswers);

        AnswerReviewDto fillGapAnswer = new AnswerReviewDto(
                UUID.randomUUID(),
                QuestionType.FILL_GAP,
                "Fill Gap Question",
                null,
                null,
                null,
                fillGapUserResponse,
                fillGapCorrectAnswer,
                true,
                1.0,
                Instant.now()
        );

        AttemptReviewDto reviewDto = new AttemptReviewDto(
                attemptId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().minusSeconds(300),
                Instant.now(),
                1.0,
                1,
                3,
                List.of(mcqAnswer, tfAnswer, fillGapAnswer)
        );

        when(attemptService.getAttemptReview(
                eq("testuser"),
                eq(attemptId),
                anyBoolean(),
                anyBoolean(),
                anyBoolean()
        )).thenReturn(reviewDto);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answers.length()").value(3))
                // MCQ_SINGLE shape
                .andExpect(jsonPath("$.answers[0].type").value("MCQ_SINGLE"))
                .andExpect(jsonPath("$.answers[0].userResponse.selectedOptionId").value("opt_2"))
                .andExpect(jsonPath("$.answers[0].correctAnswer.correctOptionId").value("opt_1"))
                // TRUE_FALSE shape
                .andExpect(jsonPath("$.answers[1].type").value("TRUE_FALSE"))
                .andExpect(jsonPath("$.answers[1].userResponse.answer").value(false))
                .andExpect(jsonPath("$.answers[1].correctAnswer.answer").value(true))
                // FILL_GAP shape
                .andExpect(jsonPath("$.answers[2].type").value("FILL_GAP"))
                .andExpect(jsonPath("$.answers[2].userResponse.answers").isArray())
                .andExpect(jsonPath("$.answers[2].userResponse.answers[0].id").value(1))
                .andExpect(jsonPath("$.answers[2].userResponse.answers[0].text").value("Paris"))
                .andExpect(jsonPath("$.answers[2].correctAnswer.answers").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/review: without authentication then returns 401")
    void getAttemptReview_noAuth_returns401() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/review", attemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/answer-key: without authentication then returns 401")
    void getAttemptAnswerKey_noAuth_returns401() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/answer-key", attemptId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    // ========== Tests verifying existing endpoints remain safe (no leaks) ==========

    @Test
    @DisplayName("GET /api/v1/attempts/{id}/current-question: does not leak correct answers")
    @WithMockUser(username = "testuser")
    void getCurrentQuestion_doesNotLeakCorrectAnswers() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();
        
        ObjectNode safeContent = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        ObjectNode option = objectMapper.createObjectNode();
        option.put("id", "opt_1");
        option.put("text", "Paris");
        // NOTE: No "correct" field in safe content
        options.add(option);
        safeContent.set("options", options);

        QuestionForAttemptDto safeQuestion = new QuestionForAttemptDto();
        safeQuestion.setId(UUID.randomUUID());
        safeQuestion.setType(QuestionType.MCQ_SINGLE);
        safeQuestion.setDifficulty(Difficulty.MEDIUM);
        safeQuestion.setQuestionText("What is the capital of France?");
        safeQuestion.setSafeContent(safeContent);

        CurrentQuestionDto currentQuestion = new CurrentQuestionDto(
                safeQuestion,
                1,
                5,
                AttemptStatus.IN_PROGRESS
        );

        when(attemptService.getCurrentQuestion(eq("testuser"), eq(attemptId)))
                .thenReturn(currentQuestion);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").exists())
                .andExpect(jsonPath("$.question.safeContent").exists())
                .andExpect(jsonPath("$.question.safeContent.options[0].id").value("opt_1"))
                .andExpect(jsonPath("$.question.safeContent.options[0].text").value("Paris"))
                // Verify NO "correct" field is leaked
                .andExpect(jsonPath("$.question.safeContent.options[0].correct").doesNotExist())
                // Verify NO "correctAnswer" or "userResponse" fields
                .andExpect(jsonPath("$.correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.userResponse").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: does not leak correct answers or user response content")
    @WithMockUser(username = "testuser")
    void submitAnswer_doesNotLeakAnswers() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID questionId = UUID.randomUUID();
        
        String requestBody = """
                {
                    "questionId": "%s",
                    "response": {"selectedOptionId": "opt_1"}
                }
                """.formatted(questionId);

        AnswerSubmissionDto submissionDto = new AnswerSubmissionDto(
                UUID.randomUUID(),
                questionId,
                true,
                1.0,
                Instant.now(),
                null  // nextQuestion
        );

        when(attemptService.submitAnswer(eq("testuser"), eq(attemptId), any()))
                .thenReturn(submissionDto);

        // When & Then
        mockMvc.perform(post("/api/v1/attempts/{attemptId}/answers", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerId").exists())
                .andExpect(jsonPath("$.questionId").value(questionId.toString()))
                .andExpect(jsonPath("$.isCorrect").value(true))
                .andExpect(jsonPath("$.score").value(1.0))
                // Verify NO "correctAnswer" field is leaked
                .andExpect(jsonPath("$.correctAnswer").doesNotExist())
                // Verify NO "userResponse" field is leaked (only isCorrect/score)
                .andExpect(jsonPath("$.userResponse").doesNotExist())
                .andExpect(jsonPath("$.response").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/complete: does not leak correct answers or user responses")
    @WithMockUser(username = "testuser")
    void completeAttempt_doesNotLeakAnswers() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        AnswerSubmissionDto answerSubmission = new AnswerSubmissionDto(
                UUID.randomUUID(),
                UUID.randomUUID(),
                true,
                1.0,
                Instant.now(),
                null
        );

        AttemptResultDto resultDto = new AttemptResultDto(
                attemptId,
                quizId,
                userId,
                Instant.now().minusSeconds(300),
                Instant.now(),
                5.0,
                5,
                5,
                List.of(answerSubmission)
        );

        when(attemptService.completeAttempt(eq("testuser"), eq(attemptId)))
                .thenReturn(resultDto);

        // When & Then
        mockMvc.perform(post("/api/v1/attempts/{attemptId}/complete", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.totalScore").value(5.0))
                .andExpect(jsonPath("$.answers").isArray())
                .andExpect(jsonPath("$.answers[0].isCorrect").value(true))
                .andExpect(jsonPath("$.answers[0].score").value(1.0))
                // Verify NO "correctAnswer" or detailed "userResponse" leaked
                .andExpect(jsonPath("$.answers[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.answers[0].userResponse").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id}: does not leak correct answers or user responses")
    @WithMockUser(username = "testuser")
    void getAttemptDetails_doesNotLeakAnswers() throws Exception {
        // Given
        UUID attemptId = UUID.randomUUID();

        AttemptDetailsDto detailsDto = new AttemptDetailsDto(
                attemptId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now(),
                null,
                AttemptStatus.IN_PROGRESS,
                AttemptMode.ALL_AT_ONCE,
                List.of()  // No detailed answers in AttemptDetailsDto
        );

        when(attemptService.getAttemptDetail(eq("testuser"), eq(attemptId)))
                .thenReturn(detailsDto);

        // When & Then
        mockMvc.perform(get("/api/v1/attempts/{attemptId}", attemptId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                // Verify NO "correctAnswer" or "userResponse" fields in response
                .andExpect(jsonPath("$.correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.userResponse").doesNotExist());
    }
}

