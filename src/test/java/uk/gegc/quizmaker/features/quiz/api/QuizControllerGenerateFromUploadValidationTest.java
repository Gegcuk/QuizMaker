package uk.gegc.quizmaker.features.quiz.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.document.application.DocumentValidationService;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.application.ModerationService;
import uk.gegc.quizmaker.features.quiz.application.QuizExportService;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.application.imports.QuizImportService;
import uk.gegc.quizmaker.features.quiz.config.QuizImportProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.infra.ExportMediaTypeResolver;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;
import uk.gegc.quizmaker.shared.validation.GenerationLanguagePolicy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuizController.class)
@DisplayName("Quiz generation request validation")
class QuizControllerGenerateFromUploadValidationTest {

    private static final String USERNAME = "upload-author";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuizService quizService;

    @MockitoBean
    private AttemptService attemptService;

    @MockitoBean
    private DocumentProcessingService documentProcessingService;

    @MockitoBean
    private DocumentValidationService documentValidationService;

    @MockitoBean
    private QuizGenerationJobService quizGenerationJobService;

    @MockitoBean
    private QuizGenerationJobRepository quizGenerationJobRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private ModerationService moderationService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private QuizExportService quizExportService;

    @MockitoBean
    private ExportMediaTypeResolver exportMediaTypeResolver;

    @MockitoBean
    private QuizImportService quizImportService;

    @MockitoBean
    private QuizImportProperties quizImportProperties;

    @ParameterizedTest(name = "maxChunkSize={0}")
    @ValueSource(ints = {999, 100001})
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Rejects out-of-contract chunk sizes before any upload processing")
    void rejectsOutOfContractChunkSizesBeforeMethodBody(int maxChunkSize) throws Exception {
        mockMvc.perform(uploadRequest().param("maxChunkSize", Integer.toString(maxChunkSize)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(documentValidationService, rateLimitService, quizService);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Rejects malformed chunk size before any upload processing")
    void rejectsMalformedChunkSizeBeforeMethodBody() throws Exception {
        mockMvc.perform(uploadRequest().param("maxChunkSize", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(documentValidationService, rateLimitService, quizService);
    }

    @ParameterizedTest(name = "maxChunkSize={0}")
    @ValueSource(ints = {1000, 100000})
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Accepts the documented minimum and maximum chunk sizes")
    void acceptsDocumentedChunkSizeBoundaries(int maxChunkSize) throws Exception {
        stubGenerationResponse();

        mockMvc.perform(uploadRequest().param("maxChunkSize", Integer.toString(maxChunkSize)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        var requestCaptor = ArgumentCaptor.forClass(GenerateQuizFromUploadRequest.class);
        verify(documentValidationService).validateFileUpload(any(MultipartFile.class), isNull(), eq(maxChunkSize));
        verify(quizService).generateQuizFromUpload(
                eq(USERNAME), any(MultipartFile.class), requestCaptor.capture(), isNull());
        assertThat(requestCaptor.getValue().maxChunkSize()).isEqualTo(maxChunkSize);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Applies the existing 100000-character default when chunk size is omitted")
    void omittedChunkSizeUsesExistingDefault() throws Exception {
        stubGenerationResponse();

        mockMvc.perform(uploadRequest())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PROCESSING"));

        var requestCaptor = ArgumentCaptor.forClass(GenerateQuizFromUploadRequest.class);
        verify(documentValidationService).validateFileUpload(any(MultipartFile.class), isNull(), isNull());
        verify(quizService).generateQuizFromUpload(
                eq(USERNAME), any(MultipartFile.class), requestCaptor.capture(), isNull());
        assertThat(requestCaptor.getValue().maxChunkSize()).isEqualTo(100000);
        assertThat(requestCaptor.getValue().language()).isEqualTo("en");
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Defaults omitted document language to English")
    void defaultsOmittedDocumentLanguageToEnglish() throws Exception {
        when(quizService.startQuizGeneration(
                anyString(), any(GenerateQuizFromDocumentRequest.class), isNull()))
                .thenReturn(generationResponse());
        String requestBody = """
                {
                  "documentId": "%s",
                  "questionsPerType": {"MCQ_SINGLE": 1},
                  "difficulty": "MEDIUM"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/quizzes/generate-from-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(csrf()))
                .andExpect(status().isAccepted());

        var requestCaptor = ArgumentCaptor.forClass(GenerateQuizFromDocumentRequest.class);
        verify(quizService).startQuizGeneration(eq(USERNAME), requestCaptor.capture(), isNull());
        assertThat(requestCaptor.getValue().language()).isEqualTo("en");
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Defaults omitted text language to English")
    void defaultsOmittedTextLanguageToEnglish() throws Exception {
        when(quizService.generateQuizFromText(
                anyString(), any(GenerateQuizFromTextRequest.class), isNull()))
                .thenReturn(generationResponse());
        String requestBody = """
                {
                  "text": "A valid local text fixture for default language validation.",
                  "questionsPerType": {"MCQ_SINGLE": 1},
                  "difficulty": "MEDIUM"
                }
                """;

        mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(csrf()))
                .andExpect(status().isAccepted());

        var requestCaptor = ArgumentCaptor.forClass(GenerateQuizFromTextRequest.class);
        verify(quizService).generateQuizFromText(eq(USERNAME), requestCaptor.capture(), isNull());
        assertThat(requestCaptor.getValue().language()).isEqualTo("en");
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Rejects invalid document language before rate limiting or generation")
    void rejectsInvalidDocumentLanguageBeforeDispatch() throws Exception {
        String invalidLanguage = "EN";
        String requestBody = """
                {
                  "documentId": "%s",
                  "questionsPerType": {"MCQ_SINGLE": 1},
                  "difficulty": "MEDIUM",
                  "language": "%s"
                }
                """.formatted(UUID.randomUUID(), invalidLanguage);

        String responseBody = mockMvc.perform(post("/api/v1/quizzes/generate-from-document")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(invalidLanguage);
        verifyNoInteractions(documentValidationService, rateLimitService, quizService);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Rejects invalid text language before rate limiting or generation")
    void rejectsInvalidTextLanguageBeforeDispatch() throws Exception {
        String invalidLanguage = "en-US";
        String requestBody = """
                {
                  "text": "A valid local text fixture for language validation.",
                  "questionsPerType": {"MCQ_SINGLE": 1},
                  "difficulty": "MEDIUM",
                  "language": "%s"
                }
                """.formatted(invalidLanguage);

        String responseBody = mockMvc.perform(post("/api/v1/quizzes/generate-from-text")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(invalidLanguage);
        verifyNoInteractions(documentValidationService, rateLimitService, quizService);
    }

    @Test
    @WithMockUser(username = USERNAME, roles = "ADMIN")
    @DisplayName("Rejects unsafe upload language before reading or validating the file")
    void rejectsUnsafeUploadLanguageBeforeFileProcessing() throws Exception {
        String privateCanary = "PRIVATE_LANGUAGE_CANARY";
        String invalidLanguage = "English\n" + privateCanary;

        String responseBody = mockMvc.perform(uploadRequest().param("language", invalidLanguage))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertThat(responseBody).doesNotContain(privateCanary);
        verifyNoInteractions(documentValidationService, rateLimitService, quizService);
    }

    private MockHttpServletRequestBuilder uploadRequest() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "A valid local text fixture for upload validation.".getBytes(StandardCharsets.UTF_8)
        );
        return MockMvcRequestBuilders.multipart("/api/v1/quizzes/generate-from-upload")
                .file(file)
                .param("questionsPerType", "{\"MCQ_SINGLE\":1}")
                .param("difficulty", "MEDIUM")
                .with(csrf());
    }

    private void stubGenerationResponse() {
        when(quizService.generateQuizFromUpload(
                anyString(),
                any(MultipartFile.class),
                any(GenerateQuizFromUploadRequest.class),
                isNull()
        )).thenReturn(generationResponse());
    }

    private QuizGenerationResponse generationResponse() {
        return new QuizGenerationResponse(
                UUID.randomUUID(),
                GenerationStatus.PROCESSING,
                "Quiz generation started successfully",
                30L
        );
    }
}
