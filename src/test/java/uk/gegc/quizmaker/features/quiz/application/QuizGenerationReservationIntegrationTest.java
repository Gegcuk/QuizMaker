package uk.gegc.quizmaker.features.quiz.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.quiz.api.QuizController;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.application.ModerationService;
import uk.gegc.quizmaker.features.quiz.application.QuizExportService;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.application.DocumentValidationService;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.quiz.application.imports.QuizImportService;
import uk.gegc.quizmaker.features.quiz.config.QuizImportProperties;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(QuizController.class)
@DisplayName("Quiz Generation Reservation Integration Tests")
class QuizGenerationReservationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private QuizService quizService;

    @MockitoBean
    private AttemptService attemptService;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private DocumentValidationService documentValidationService;

    @MockitoBean
    private QuizGenerationJobService jobService;

    @MockitoBean
    private QuizGenerationJobRepository jobRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private QuizExportService quizExportService;

    @MockitoBean
    private uk.gegc.quizmaker.features.quiz.infra.ExportMediaTypeResolver exportMediaTypeResolver;

    @MockitoBean
    private ModerationService moderationService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private QuizImportService quizImportService;

    @MockitoBean
    private QuizImportProperties quizImportProperties;

    private GenerateQuizFromDocumentRequest testRequest;
    private UUID documentId;

    @BeforeEach
    void setUp() {
        // Create test request
        documentId = UUID.randomUUID();
        testRequest = new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.ENTIRE_DOCUMENT,
                null, // chunkIndices
                null, // chapterTitle
                null, // chapterNumber
                "Integration Test Quiz", // quizTitle
                "Integration Test Quiz Description", // quizDescription
                Map.of(
                        uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE, 2,
                        uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE, 1
                ), // questionsPerType
                uk.gegc.quizmaker.features.question.domain.model.Difficulty.MEDIUM, // difficulty
                2, // estimatedTimePerQuestion
                null, // categoryId
                List.of() // tagIds
        );
    }

    @Test
    @WithMockUser(username = "integration-test-user", roles = "ADMIN")
    @DisplayName("POST /api/v1/quizzes/generate-from-document should reserve tokens and create job successfully")
    void generateQuizFromDocument_ShouldReserveTokensAndCreateJob() throws Exception {
        // Mock quiz service response
        QuizGenerationResponse mockResponse = new QuizGenerationResponse(
                UUID.randomUUID(),
                uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus.PROCESSING,
                "Quiz generation started successfully",
                300L // estimatedTimeSeconds
        );
        when(quizService.startQuizGeneration(any(String.class), any(GenerateQuizFromDocumentRequest.class)))
                .thenReturn(mockResponse);

        // When
        mockMvc.perform(post("/api/v1/quizzes/generate-from-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.estimatedTimeSeconds").exists())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
    }

    @Test
    @WithMockUser(username = "integration-test-user", roles = "ADMIN")
    @DisplayName("POST /api/v1/quizzes/generate-from-document should handle double-clicks idempotently")
    void generateQuizFromDocument_ShouldHandleDoubleClicksIdempotently() throws Exception {
        // Mock quiz service response
        QuizGenerationResponse mockResponse = new QuizGenerationResponse(
                UUID.randomUUID(),
                uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus.PROCESSING,
                "Quiz generation started successfully",
                300L // estimatedTimeSeconds
        );
        when(quizService.startQuizGeneration(any(String.class), any(GenerateQuizFromDocumentRequest.class)))
                .thenReturn(mockResponse);

        String requestJson = objectMapper.writeValueAsString(testRequest);

        // When - make the same request twice rapidly (simulating double-click)
        mockMvc.perform(post("/api/v1/quizzes/generate-from-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .with(csrf()))
                .andExpect(status().isAccepted());

        // Second request should be handled idempotently
        mockMvc.perform(post("/api/v1/quizzes/generate-from-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    @WithMockUser(username = "integration-test-user", roles = "ADMIN")
    @DisplayName("POST /api/v1/quizzes/generate-from-document should return INSUFFICIENT_TOKENS when user lacks tokens")
    void generateQuizFromDocument_ShouldReturnInsufficientTokensWhenUserLacksTokens() throws Exception {
        // Mock quiz service to throw InsufficientTokensException
        uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException exception = 
            new uk.gegc.quizmaker.features.billing.domain.exception.InsufficientTokensException(
                "Not enough tokens to reserve: required=2, available=1",
                2L, // estimatedTokens
                1L, // availableTokens
                1L, // shortfall
                java.time.LocalDateTime.now().plusMinutes(30) // reservationTtl
            );
        when(quizService.startQuizGeneration(any(String.class), any(GenerateQuizFromDocumentRequest.class)))
                .thenThrow(exception);

        // When
        mockMvc.perform(post("/api/v1/quizzes/generate-from-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest))
                        .with(csrf()))
                .andExpect(status().isConflict()) // 409 Conflict for insufficient tokens
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/insufficient-tokens"))
                .andExpect(jsonPath("$.title").value("Insufficient Tokens"))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_TOKENS"))
                .andExpect(jsonPath("$.estimatedTokens").exists())
                .andExpect(jsonPath("$.availableTokens").exists())
                .andExpect(jsonPath("$.shortfall").exists())
                .andExpect(jsonPath("$.reservationTtl").exists());
    }

    @Test
    @WithMockUser(username = "integration-test-user", roles = "ADMIN")
    @DisplayName("POST /api/v1/quizzes/generate-from-document should prevent multiple active jobs per user")
    void generateQuizFromDocument_ShouldPreventMultipleActiveJobsPerUser() throws Exception {
        // Mock quiz service to throw ValidationException for active job
        uk.gegc.quizmaker.shared.exception.ValidationException exception = 
            new uk.gegc.quizmaker.shared.exception.ValidationException(
                "User already has an active generation job"
            );
        when(quizService.startQuizGeneration(any(String.class), any(GenerateQuizFromDocumentRequest.class)))
                .thenThrow(exception);

        // When
        mockMvc.perform(post("/api/v1/quizzes/generate-from-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(testRequest))
                        .with(csrf()))
                .andExpect(status().isBadRequest()) // 400 Bad Request
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already has an active generation job")))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
