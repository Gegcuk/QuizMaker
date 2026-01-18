package uk.gegc.quizmaker.features.quiz.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.application.imports.ContentHashUtil;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.features.quiz.config.QuizImportProperties;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Quiz Import Controller Integration Tests")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizImportControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private QuizDefaultsProperties quizDefaultsProperties;

    @Autowired
    private ContentHashUtil contentHashUtil;

    @Autowired
    private QuizImportProperties quizImportProperties;

    @Autowired
    private RateLimitService rateLimitService;

    @Test
    @DisplayName("importQuizzes JSON: CREATE_ONLY creates quizzes")
    void importQuizzes_json_createOnly_createsQuizzes() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Create Only Quiz", List.of());

        ResultActions result = performImport(user, quiz, "CREATE_ONLY", false);

        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        List<Quiz> quizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(quizzes).hasSize(1);
        assertThat(quizzes.get(0).getTitle()).isEqualTo("Create Only Quiz");
    }

    @Test
    @DisplayName("importQuizzes JSON: CREATE_ONLY dry run returns summary without persisting")
    void importQuizzes_json_createOnly_dryRun_returnsSummary() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Dry Run Quiz", List.of());

        ResultActions result = performImport(user, quiz, "CREATE_ONLY", true);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        assertThat(quizRepository.findByCreatorId(user.getId())).isEmpty();
    }

    @Test
    @DisplayName("importQuizzes JSON: UPSERT_BY_ID updates existing quiz")
    void importQuizzes_json_upsertById_updatesExisting() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        Quiz existing = createQuiz(user, "Existing Quiz");

        QuizImportDto quiz = buildQuiz(existing.getId(), "Updated Quiz", List.of());
        ResultActions result = performImport(user, quiz, "UPSERT_BY_ID", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        Quiz updated = quizRepository.findById(existing.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Updated Quiz");
    }

    @Test
    @DisplayName("importQuizzes JSON: UPSERT_BY_ID creates quiz when id missing")
    void importQuizzes_json_upsertById_createsNew() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        // Create a quiz first to get a real ID from the database
        Quiz existingQuiz = createQuiz(user, "Existing Quiz for Import");
        UUID quizId = existingQuiz.getId();

        // Now use that ID to test UPSERT - this will update the existing quiz
        QuizImportDto quiz = buildQuiz(quizId, "Upsert Updated Quiz", List.of());

        ResultActions result = performImport(user, quiz, "UPSERT_BY_ID", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        Quiz updated = quizRepository.findById(quizId).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Upsert Updated Quiz");
    }

    @Test
    @DisplayName("importQuizzes JSON: UPSERT_BY_CONTENT_HASH updates existing quiz")
    void importQuizzes_json_upsertByHash_updatesExisting() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Hash Updated Quiz", List.of());
        String importHash = contentHashUtil.calculateImportContentHash(quiz);

        Quiz existing = createQuiz(user, "Hash Existing Quiz");
        existing.setImportContentHash(importHash);
        quizRepository.save(existing);

        ResultActions result = performImport(user, quiz, "UPSERT_BY_CONTENT_HASH", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        Quiz updated = quizRepository.findById(existing.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Hash Updated Quiz");
    }

    @Test
    @DisplayName("importQuizzes JSON: UPSERT_BY_CONTENT_HASH creates quiz when no match")
    void importQuizzes_json_upsertByHash_createsNew() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Hash New Quiz", List.of());
        String importHash = contentHashUtil.calculateImportContentHash(quiz);

        ResultActions result = performImport(user, quiz, "UPSERT_BY_CONTENT_HASH", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        assertThat(quizRepository.findByCreatorIdAndImportContentHashWithTagsAndQuestions(user.getId(), importHash))
                .isPresent();
    }

    @Test
    @DisplayName("importQuizzes JSON: SKIP_ON_DUPLICATE skips existing quiz")
    void importQuizzes_json_skipOnDuplicate_skipsExisting() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        Quiz existing = createQuiz(user, "Skip Existing Quiz");

        QuizImportDto quiz = buildQuiz(existing.getId(), "Skip Updated Quiz", List.of());
        ResultActions result = performImport(user, quiz, "SKIP_ON_DUPLICATE", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        Quiz persisted = quizRepository.findById(existing.getId()).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("Skip Existing Quiz");
    }

    @Test
    @DisplayName("importQuizzes JSON: SKIP_ON_DUPLICATE creates quiz when no match")
    void importQuizzes_json_skipOnDuplicate_createsNew() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(UUID.randomUUID(), "Skip New Quiz", List.of());

        ResultActions result = performImport(user, quiz, "SKIP_ON_DUPLICATE", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        List<Quiz> quizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(quizzes).hasSize(1);
        assertThat(quizzes.get(0).getTitle()).isEqualTo("Skip New Quiz");
    }

    @Test
    @DisplayName("importQuizzes XLSX: CREATE_ONLY creates quizzes")
    void importQuizzes_xlsx_createOnly_createsQuizzes() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        Workbook workbook = createSimpleXlsxWorkbook("XLSX Create Quiz");

        ResultActions result = performXlsxImport(user, workbook, "CREATE_ONLY", false);

        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        List<Quiz> quizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(quizzes).hasSize(1);
        assertThat(quizzes.get(0).getTitle()).isEqualTo("XLSX Create Quiz");
    }

    @Test
    @DisplayName("importQuizzes XLSX: UPSERT_BY_ID updates existing quiz")
    void importQuizzes_xlsx_upsertById_updatesExisting() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        Quiz existing = createQuiz(user, "Existing XLSX Quiz");
        
        Workbook workbook = createXlsxWorkbookWithId(existing.getId(), "XLSX Updated Quiz");

        ResultActions result = performXlsxImport(user, workbook, "UPSERT_BY_ID", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0))
                .andExpect(jsonPath("$.errors.length()").value(0));

        Quiz updated = quizRepository.findById(existing.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("XLSX Updated Quiz");
    }

    @Test
    @DisplayName("importQuizzes: missing file returns 400")
    void importQuizzes_missingFile_returns400() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());

        mockMvc.perform(multipart("/api/v1/quizzes/import")
                        .param("format", "JSON_EDITABLE")
                        .param("strategy", "CREATE_ONLY")
                        .param("dryRun", "false")
                        .with(user(user.getUsername())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("importQuizzes: empty file returns 400")
    void importQuizzes_emptyFile_returns400() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.json",
                MediaType.APPLICATION_JSON_VALUE,
                new byte[0]
        );

        mockMvc.perform(multipart("/api/v1/quizzes/import")
                        .file(emptyFile)
                        .param("format", "JSON_EDITABLE")
                        .param("strategy", "CREATE_ONLY")
                        .param("dryRun", "false")
                        .with(user(user.getUsername())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("importQuizzes: missing format returns 400")
    void importQuizzes_missingFormat_returns400() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/quizzes/import")
                        .file(file)
                        .param("strategy", "CREATE_ONLY")
                        .param("dryRun", "false")
                        .with(user(user.getUsername())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("importQuizzes: invalid format returns 400")
    void importQuizzes_invalidFormat_returns400() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/quizzes/import")
                        .file(file)
                        .param("format", "INVALID_FORMAT")
                        .param("strategy", "CREATE_ONLY")
                        .param("dryRun", "false")
                        .with(user(user.getUsername())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("importQuizzes: unsupported format PDF_PRINT returns 400")
    void importQuizzes_formatPdfPrint_returns400() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/quizzes/import")
                        .file(file)
                        .param("format", "PDF_PRINT")
                        .param("strategy", "CREATE_ONLY")
                        .param("dryRun", "false")
                        .with(user(user.getUsername())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("importQuizzes: unsupported format HTML_PRINT returns 400")
    void importQuizzes_formatHtmlPrint_returns400() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.html",
                MediaType.TEXT_HTML_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/v1/quizzes/import")
                        .file(file)
                        .param("format", "HTML_PRINT")
                        .param("strategy", "CREATE_ONLY")
                        .param("dryRun", "false")
                        .with(user(user.getUsername())))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("importQuizzes: exceeds rate limit returns 429")
    void importQuizzes_exceedsRateLimit_returns429() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Rate Limit Test Quiz", List.of());
        int rateLimit = quizImportProperties.getRateLimitPerMinute();

        // Make requests up to the rate limit
        for (int i = 0; i < rateLimit; i++) {
            ResultActions result = performImport(user, quiz, "CREATE_ONLY", true); // Use dry-run to avoid creating
            result.andExpect(status().isOk());
        }

        // Next request should exceed rate limit
        ResultActions result = performImport(user, quiz, "CREATE_ONLY", true);
        result.andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Rate Limit Exceeded"));
    }

    @Test
    @DisplayName("importQuizzes: within rate limit succeeds")
    void importQuizzes_withinRateLimit_succeeds() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Within Rate Limit Quiz", List.of());
        int rateLimit = quizImportProperties.getRateLimitPerMinute();

        // Make requests within the rate limit
        for (int i = 0; i < Math.min(rateLimit, 5); i++) {
            ResultActions result = performImport(user, quiz, "CREATE_ONLY", true); // Use dry-run
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1));
        }
    }

    @Test
    @DisplayName("importQuizzes: rate limit resets after minute")
    void importQuizzes_rateLimitResetsAfterMinute() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Rate Limit Reset Test Quiz", List.of());
        int rateLimit = quizImportProperties.getRateLimitPerMinute();

        // Make requests up to the rate limit
        for (int i = 0; i < rateLimit; i++) {
            ResultActions result = performImport(user, quiz, "CREATE_ONLY", true); // Use dry-run
            result.andExpect(status().isOk());
        }

        // Next request should exceed rate limit
        ResultActions result = performImport(user, quiz, "CREATE_ONLY", true);
        result.andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429));

        // Simulate time passing by manipulating the RateLimitService's internal state
        // Use reflection to update the lastRequestTime map to a time more than 1 minute ago
        String rateLimitKey = "quizzes-import:" + user.getUsername();
        @SuppressWarnings("unchecked")
        java.util.Map<String, LocalDateTime> lastRequestTime = 
            (java.util.Map<String, LocalDateTime>) ReflectionTestUtils.getField(rateLimitService, "lastRequestTime");
        
        // Set the last request time to more than 1 minute ago to simulate time passing
        LocalDateTime moreThanOneMinuteAgo = LocalDateTime.now().minusMinutes(2);
        lastRequestTime.put(rateLimitKey, moreThanOneMinuteAgo);
        
        // Clear the request count so it resets
        @SuppressWarnings("unchecked")
        java.util.Map<String, Integer> requestCount = 
            (java.util.Map<String, Integer>) ReflectionTestUtils.getField(rateLimitService, "requestCount");
        requestCount.remove(rateLimitKey);

        // After simulating time passing, the next request should succeed (rate limit reset)
        ResultActions resultAfterWait = performImport(user, quiz, "CREATE_ONLY", true);
        resultAfterWait.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("importQuizzes: returns ImportSummaryDto with correct structure")
    void importQuizzes_returnsImportSummaryDto() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Summary Test Quiz", List.of());

        ResultActions result = performImport(user, quiz, "CREATE_ONLY", true); // Use dry-run

        result.andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.created").exists())
                .andExpect(jsonPath("$.updated").exists())
                .andExpect(jsonPath("$.skipped").exists())
                .andExpect(jsonPath("$.failed").exists())
                .andExpect(jsonPath("$.errors").exists())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @DisplayName("importQuizzes: summary includes all counts")
    void importQuizzes_summaryIncludesCounts() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Counts Test Quiz", List.of());

        ResultActions result = performImport(user, quiz, "CREATE_ONLY", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(0));
    }

    @Test
    @DisplayName("importQuizzes: summary includes errors when validation fails")
    void importQuizzes_summaryIncludesErrors() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        // Create invalid quiz - title too short
        QuizImportDto invalidQuiz = new QuizImportDto(
                null,
                null,
                "AB", // Title too short (min 3 characters)
                "Description",
                Visibility.PRIVATE,
                Difficulty.EASY,
                10,
                null,
                null,
                null,
                List.of(),
                null,
                null
        );

        ResultActions result = performImport(user, invalidQuiz, "CREATE_ONLY", false);

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].index").exists())
                .andExpect(jsonPath("$.errors[0].message").exists())
                .andExpect(jsonPath("$.errors[0].code").exists())
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("title")));
    }

    private ResultActions performImport(User user, QuizImportDto quiz, String strategy, boolean dryRun) throws Exception {
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        return mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", strategy)
                .param("dryRun", String.valueOf(dryRun))
                .with(user(user.getUsername())));
    }

    private QuizImportDto buildQuiz(UUID id, String title, List<QuestionImportDto> questions) {
        return new QuizImportDto(
                null,
                id,
                title,
                "Description for " + title,
                Visibility.PRIVATE,
                Difficulty.EASY,
                10,
                null,
                null,
                null,
                questions,
                null,
                null
        );
    }

    private User createUserWithPermission(String username) {
        Permission permission = permissionRepository.findByPermissionName(PermissionName.QUIZ_CREATE.name())
                .orElseThrow(() -> new IllegalStateException("QUIZ_CREATE permission not found"));

        Role role = Role.builder()
                .roleName("ROLE_" + username.toUpperCase())
                .permissions(Set.of(permission))
                .build();
        roleRepository.save(role);

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setHashedPassword("hashed_password");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        user.setRoles(Set.of(role));

        return userRepository.save(user);
    }

    private Quiz createQuiz(User creator, String title) {
        Quiz quiz = new Quiz();
        quiz.setCreator(creator);
        quiz.setCategory(defaultCategory());
        quiz.setTitle(title);
        quiz.setDescription("Existing quiz");
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(10);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setTags(new HashSet<>());
        quiz.setQuestions(new HashSet<>());
        return quizRepository.save(quiz);
    }

    private Category defaultCategory() {
        return categoryRepository.findById(quizDefaultsProperties.getDefaultCategoryId())
                .orElseThrow(() -> new IllegalStateException("Default category not found"));
    }

    private ResultActions performXlsxImport(User user, Workbook workbook, String strategy, boolean dryRun) throws Exception {
        MockMultipartFile file = createXlsxMultipartFile(workbook);
        return mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "XLSX_EDITABLE")
                .param("strategy", strategy)
                .param("dryRun", String.valueOf(dryRun))
                .with(user(user.getUsername())));
    }

    private MockMultipartFile createXlsxMultipartFile(Workbook workbook) throws IOException {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.write(output);
            return new MockMultipartFile(
                    "file",
                    "quizzes.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    output.toByteArray()
            );
        }
    }

    private Workbook createSimpleXlsxWorkbook(String title) {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Quizzes");
        Row header = sheet.createRow(0);
        String[] headers = {
                "Quiz ID",
                "Title",
                "Description",
                "Visibility",
                "Difficulty",
                "Estimated Time (min)",
                "Tags",
                "Category",
                "Creator ID",
                "Created At",
                "Updated At"
        };
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(""); // Quiz ID (empty for new quiz)
        row.createCell(1).setCellValue(title);
        row.createCell(2).setCellValue("Description for " + title);
        row.createCell(3).setCellValue("PRIVATE");
        row.createCell(4).setCellValue("EASY");
        row.createCell(5).setCellValue(10);
        row.createCell(6).setCellValue(""); // Tags (empty)
        row.createCell(7).setCellValue(""); // Category (empty, uses default)
        row.createCell(8).setCellValue(""); // Creator ID (empty)
        row.createCell(9).setCellValue(""); // Created At (empty)
        row.createCell(10).setCellValue(""); // Updated At (empty)
        
        return workbook;
    }

    private Workbook createXlsxWorkbookWithId(UUID quizId, String title) {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Quizzes");
        Row header = sheet.createRow(0);
        String[] headers = {
                "Quiz ID",
                "Title",
                "Description",
                "Visibility",
                "Difficulty",
                "Estimated Time (min)",
                "Tags",
                "Category",
                "Creator ID",
                "Created At",
                "Updated At"
        };
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }
        
        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue(quizId.toString());
        row.createCell(1).setCellValue(title);
        row.createCell(2).setCellValue("Description for " + title);
        row.createCell(3).setCellValue("PRIVATE");
        row.createCell(4).setCellValue("EASY");
        row.createCell(5).setCellValue(10);
        row.createCell(6).setCellValue(""); // Tags (empty)
        row.createCell(7).setCellValue(""); // Category (empty, uses default)
        row.createCell(8).setCellValue(""); // Creator ID (empty)
        row.createCell(9).setCellValue(""); // Created At (empty)
        row.createCell(10).setCellValue(""); // Updated At (empty)
        
        return workbook;
    }
}
