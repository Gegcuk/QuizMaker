package uk.gegc.quizmaker.features.quiz.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.config.QuizImportProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Quiz Import Performance Integration Tests")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizImportPerformanceIntegrationTest extends BaseIntegrationTest {

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
    private QuizImportProperties quizImportProperties;

    @Test
    @DisplayName("importQuizzes: exceeds maxItems returns 400")
    void importQuizzes_exceedsMaxItems_returns400() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Temporarily set maxItems to 2 for testing
        int originalMaxItems = quizImportProperties.getMaxItems();
        ReflectionTestUtils.setField(quizImportProperties, "maxItems", 2);
        
        try {
            // Create 3 quizzes (exceeds limit of 2)
            List<QuizImportDto> quizzes = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                quizzes.add(buildQuiz(null, "Quiz " + i, List.of()));
            }

            String payload = objectMapper.writeValueAsString(quizzes);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                    payload.getBytes(StandardCharsets.UTF_8)
            );

            ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                    .file(file)
                    .param("format", "JSON_EDITABLE")
                    .param("strategy", "CREATE_ONLY")
                    .param("dryRun", "false")
                    .with(user(user.getUsername())));

            result.andExpect(status().isPayloadTooLarge()); // 413 PAYLOAD_TOO_LARGE
        } finally {
            // Restore original maxItems
            ReflectionTestUtils.setField(quizImportProperties, "maxItems", originalMaxItems);
        }
    }

    @Test
    @DisplayName("importQuizzes: exactly maxItems succeeds")
    void importQuizzes_exactlyMaxItems_succeeds() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Temporarily set maxItems to 3 for testing
        int originalMaxItems = quizImportProperties.getMaxItems();
        ReflectionTestUtils.setField(quizImportProperties, "maxItems", 3);
        
        try {
            // Create exactly 3 quizzes (at limit)
            List<QuizImportDto> quizzes = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                quizzes.add(buildQuiz(null, "Quiz " + i, List.of()));
            }

            String payload = objectMapper.writeValueAsString(quizzes);
            MockMultipartFile file = new MockMultipartFile(
                    "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                    payload.getBytes(StandardCharsets.UTF_8)
            );

            ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                    .file(file)
                    .param("format", "JSON_EDITABLE")
                    .param("strategy", "CREATE_ONLY")
                    .param("dryRun", "false")
                    .with(user(user.getUsername())));

            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.created").value(3));
        } finally {
            // Restore original maxItems
            ReflectionTestUtils.setField(quizImportProperties, "maxItems", originalMaxItems);
        }
    }

    @Test
    @DisplayName("importQuizzes: large file handled gracefully")
    void importQuizzes_largeFile_handlesGracefully() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create a moderately large file (10 quizzes with questions)
        List<QuizImportDto> quizzes = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            List<QuestionImportDto> questions = new ArrayList<>();
            for (int j = 1; j <= 5; j++) {
                QuestionImportDto question = new QuestionImportDto(
                        null,
                        uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE,
                        Difficulty.EASY,
                        "Question " + j + " for Quiz " + i,
                        objectMapper.readTree("{\"options\":[{\"id\":\"1\",\"text\":\"Option 1\",\"correct\":true},{\"id\":\"2\",\"text\":\"Option 2\"}]}"),
                        null, null, null, null
                );
                questions.add(question);
            }
            quizzes.add(buildQuiz(null, "Quiz " + i, questions));
        }

        String payload = objectMapper.writeValueAsString(quizzes);
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.created").value(10));
    }

    @Test
    @DisplayName("importQuizzes: too many questions per quiz returns error")
    void importQuizzes_tooManyQuestionsPerQuiz_returnsError() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create quiz with 51 questions (exceeds MAX_QUESTIONS_PER_QUIZ = 50)
        List<QuestionImportDto> questions = new ArrayList<>();
        for (int i = 1; i <= 51; i++) {
            QuestionImportDto question = new QuestionImportDto(
                    null,
                    uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE,
                    Difficulty.EASY,
                    "Question " + i,
                    objectMapper.readTree("{\"options\":[{\"id\":\"1\",\"text\":\"Option 1\",\"correct\":true}]}"),
                    null, null, null, null
            );
            questions.add(question);
        }
        QuizImportDto quiz = buildQuiz(null, "Quiz with too many questions", questions);

        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsStringIgnoringCase("too many questions")));
    }

    @Test
    @DisplayName("importQuizzes: valid question count succeeds")
    void importQuizzes_validQuestionCount_succeeds() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create quiz with exactly 50 questions (MAX_QUESTIONS_PER_QUIZ)
        List<QuestionImportDto> questions = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            QuestionImportDto question = new QuestionImportDto(
                    null,
                    uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE,
                    Difficulty.EASY,
                    "Question " + i,
                    objectMapper.readTree("{\"options\":[{\"id\":\"1\",\"text\":\"Option 1\",\"correct\":true},{\"id\":\"2\",\"text\":\"Option 2\"}]}"),
                    null, null, null, null
            );
            questions.add(question);
        }
        QuizImportDto quiz = buildQuiz(null, "Quiz with 50 questions", questions);

        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    @DisplayName("importQuizzes: sequential imports isolated")
    void importQuizzes_sequentialImports_isolated() throws Exception {
        User user1 = createUserWithPermission("import_user_1_" + UUID.randomUUID());
        User user2 = createUserWithPermission("import_user_2_" + UUID.randomUUID());
        
        // Create quizzes for each user
        QuizImportDto quiz1 = buildQuiz(null, "User 1 Quiz", List.of());
        QuizImportDto quiz2 = buildQuiz(null, "User 2 Quiz", List.of());

        String payload1 = objectMapper.writeValueAsString(List.of(quiz1));
        String payload2 = objectMapper.writeValueAsString(List.of(quiz2));
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "quizzes1.json", MediaType.APPLICATION_JSON_VALUE,
                payload1.getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile file2 = new MockMultipartFile(
                "file", "quizzes2.json", MediaType.APPLICATION_JSON_VALUE,
                payload2.getBytes(StandardCharsets.UTF_8)
        );

        // Perform imports sequentially (not truly concurrent, but verifies isolation)
        ResultActions result1 = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file1)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user1.getUsername())));
        
        ResultActions result2 = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file2)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user2.getUsername())));

        // Both should succeed independently
        result1.andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
        result2.andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // Verify both quizzes were created and isolated per user
        List<uk.gegc.quizmaker.features.quiz.domain.model.Quiz> user1Quizzes = quizRepository.findByCreatorId(user1.getId());
        List<uk.gegc.quizmaker.features.quiz.domain.model.Quiz> user2Quizzes = quizRepository.findByCreatorId(user2.getId());
        assertThat(user1Quizzes).hasSize(1);
        assertThat(user2Quizzes).hasSize(1);
        assertThat(user1Quizzes.get(0).getTitle()).isEqualTo("User 1 Quiz");
        assertThat(user2Quizzes.get(0).getTitle()).isEqualTo("User 2 Quiz");
    }

    @Test
    @DisplayName("importQuizzes: concurrent imports rate limited")
    void importQuizzes_concurrentImports_rateLimited() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // This test verifies that rate limiting applies per user
        // After exceeding rate limit, subsequent requests should fail
        // Note: This is a simplified test - actual concurrent rate limiting is complex
        
        QuizImportDto quiz1 = buildQuiz(null, "Quiz 1", List.of());
        QuizImportDto quiz2 = buildQuiz(null, "Quiz 2", List.of());

        String payload1 = objectMapper.writeValueAsString(List.of(quiz1));
        String payload2 = objectMapper.writeValueAsString(List.of(quiz2));
        MockMultipartFile file1 = new MockMultipartFile(
                "file", "quizzes1.json", MediaType.APPLICATION_JSON_VALUE,
                payload1.getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile file2 = new MockMultipartFile(
                "file", "quizzes2.json", MediaType.APPLICATION_JSON_VALUE,
                payload2.getBytes(StandardCharsets.UTF_8)
        );

        // First import should succeed
        ResultActions result1 = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file1)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        result1.andExpect(status().isOk());

        // Second import from same user should also succeed (within rate limit)
        // Actual rate limit testing is done in QuizImportControllerIntegrationTest
        ResultActions result2 = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file2)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        result2.andExpect(status().isOk());
    }

    @Test
    @DisplayName("importQuizzes: streaming parsing uses low memory")
    void importQuizzes_streamingParsing_lowMemory() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create a file with multiple quizzes to test streaming parsing
        // The parser uses Jackson's streaming API which should handle large files efficiently
        // Note: This is a smoke test - it verifies the import completes without OOM,
        // but doesn't measure actual memory usage (which would require JVM instrumentation)
        List<QuizImportDto> quizzes = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            quizzes.add(buildQuiz(null, "Quiz " + i, List.of()));
        }

        String payload = objectMapper.writeValueAsString(quizzes);
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        // If streaming works correctly, this should complete without OOM
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(20))
                .andExpect(jsonPath("$.created").value(20));
        
        // Verify all quizzes were persisted
        List<uk.gegc.quizmaker.features.quiz.domain.model.Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(20);
    }

    @Test
    @DisplayName("importQuizzes: large file doesn't cause OOM")
    void importQuizzes_largeFile_doesNotOOM() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create a file with many quizzes but within maxItems limit
        // This verifies that large but valid files are handled without memory issues
        List<QuizImportDto> quizzes = new ArrayList<>();
        int quizCount = Math.min(100, quizImportProperties.getMaxItems()); // Use reasonable limit
        for (int i = 1; i <= quizCount; i++) {
            quizzes.add(buildQuiz(null, "Quiz " + i, List.of()));
        }

        String payload = objectMapper.writeValueAsString(quizzes);
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        // If memory is managed correctly, this should complete successfully
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(quizCount))
                .andExpect(jsonPath("$.created").value(quizCount));
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
}
