package uk.gegc.quizmaker.features.quiz.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Quiz Import Edge Cases Integration Tests")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizImportEdgeCasesIntegrationTest extends BaseIntegrationTest {

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

    @Test
    @DisplayName("importQuizzes: null fields handled gracefully")
    void importQuizzes_nullFields_handlesGracefully() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create quiz with null optional fields
        QuizImportDto quiz = new QuizImportDto(
                null,
                null,
                "Test Quiz",
                null, // null description
                null, // null visibility
                null, // null difficulty
                10, // required: estimatedTime
                null, // null tags
                null, // null category
                null, // null creatorId (ignored)
                null, // null questions
                null, // null createdAt
                null  // null updatedAt
        );

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

        // Verify quiz was created with default values
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        Quiz created = createdQuizzes.get(0);
        assertThat(created.getDescription()).isNull();
        assertThat(created.getVisibility()).isEqualTo(Visibility.PRIVATE); // Default
    }

    @Test
    @DisplayName("importQuizzes: empty arrays handled gracefully")
    void importQuizzes_emptyArrays_handlesGracefully() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create quiz with empty questions array
        QuizImportDto quiz = new QuizImportDto(
                null,
                null,
                "Test Quiz",
                "Description",
                Visibility.PRIVATE,
                Difficulty.EASY,
                10,
                List.of(), // empty tags array
                null,
                null,
                List.of(), // empty questions array
                null,
                null
        );

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

        // Verify quiz was created with empty collections
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        Quiz created = quizRepository.findByIdWithTagsAndQuestions(createdQuizzes.get(0).getId())
                .orElseThrow();
        assertThat(created.getQuestions()).isEmpty();
        assertThat(created.getTags()).isEmpty();
    }

    @Test
    @DisplayName("importQuizzes: missing optional fields handled gracefully")
    void importQuizzes_missingOptionalFields_handlesGracefully() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create minimal quiz with only required fields
        ObjectNode quizJson = objectMapper.createObjectNode();
        quizJson.put("title", "Minimal Quiz");
        quizJson.put("estimatedTime", 10);
        // No description, visibility, difficulty, tags, category, questions

        String payload = "[" + quizJson.toString() + "]";
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
    @DisplayName("importQuizzes: special characters in text preserved")
    void importQuizzes_specialCharactersInText_preserved() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create quiz with special characters
        String title = "Quiz with Special: !@#$%^&*()_+-=[]{}|;:',.<>?";
        String description = "Description with <script> tags & symbols";
        QuizImportDto quiz = new QuizImportDto(
                null, null, title, description, Visibility.PRIVATE, Difficulty.EASY, 10,
                null, null, null, List.of(), null, null
        );

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
                .andExpect(jsonPath("$.created").value(1));

        // Verify special characters are preserved
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        assertThat(createdQuizzes.get(0).getTitle()).isEqualTo(title);
        assertThat(createdQuizzes.get(0).getDescription()).isEqualTo(description);
    }

    @Test
    @DisplayName("importQuizzes: unicode characters preserved")
    void importQuizzes_unicodeCharacters_preserved() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create quiz with unicode characters
        String title = "Quiz with Unicode: 中文, 日本語, العربية, русский, 🎯";
        String description = "Description with emoji: ✅ ❌ ⭐ 🚀";
        QuizImportDto quiz = new QuizImportDto(
                null, null, title, description, Visibility.PRIVATE, Difficulty.EASY, 10,
                null, null, null, List.of(), null, null
        );

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
                .andExpect(jsonPath("$.created").value(1));

        // Verify unicode characters are preserved
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        assertThat(createdQuizzes.get(0).getTitle()).isEqualTo(title);
        assertThat(createdQuizzes.get(0).getDescription()).isEqualTo(description);
    }

    @Test
    @DisplayName("importQuizzes: HTML in text not escaped (stored as-is)")
    void importQuizzes_htmlInText_escaped() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create quiz with HTML in text
        // Note: HTML is stored as-is in the database, not escaped during import
        String title = "Quiz with <b>HTML</b> tags";
        String description = "Description with <script>alert('xss')</script>";
        QuizImportDto quiz = new QuizImportDto(
                null, null, title, description, Visibility.PRIVATE, Difficulty.EASY, 10,
                null, null, null, List.of(), null, null
        );

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
                .andExpect(jsonPath("$.created").value(1));

        // Verify HTML is stored as-is (not escaped during import)
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        assertThat(createdQuizzes.get(0).getTitle()).isEqualTo(title); // Stored as-is
        assertThat(createdQuizzes.get(0).getDescription()).isEqualTo(description); // Stored as-is
    }

    @Test
    @DisplayName("importQuizzes: media with only assetId preserved")
    void importQuizzes_mediaWithOnlyAssetId_preserved() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create MCQ question with media containing only assetId
        UUID assetId = UUID.randomUUID();
        ObjectNode mcqContent = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        ObjectNode option = objectMapper.createObjectNode();
        option.put("id", "1");
        option.put("text", "Option 1");
        option.put("correct", true);
        ObjectNode media = objectMapper.createObjectNode();
        media.put("assetId", assetId.toString());
        // No cdnUrl, width, height, mimeType
        option.set("media", media);
        options.add(option);
        options.add(createMcqOption("2", "Option 2", false));
        mcqContent.set("options", options);

        QuestionImportDto question = new QuestionImportDto(
                null,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                "Question with media",
                mcqContent,
                null, null, null, null
        );

        QuizImportDto quiz = buildQuiz(null, "Media Quiz", List.of(question));

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
                .andExpect(jsonPath("$.created").value(1));

        // Verify media with only assetId is preserved
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        Quiz created = quizRepository.findByIdWithTagsAndQuestions(createdQuizzes.get(0).getId())
                .orElseThrow();
        uk.gegc.quizmaker.features.question.domain.model.Question q = created.getQuestions().iterator().next();
        ObjectNode content = (ObjectNode) objectMapper.readTree(q.getContent());
        ArrayNode importedOptions = (ArrayNode) content.get("options");
        boolean foundMedia = false;
        for (com.fasterxml.jackson.databind.JsonNode opt : importedOptions) {
            if (opt.has("media") && opt.get("media").has("assetId")) {
                assertThat(opt.get("media").get("assetId").asText()).isEqualTo(assetId.toString());
                // Enriched fields should be stripped
                assertThat(opt.get("media").has("cdnUrl")).isFalse();
                assertThat(opt.get("media").has("width")).isFalse();
                foundMedia = true;
            }
        }
        assertThat(foundMedia).isTrue();
    }

    @Test
    @DisplayName("importQuizzes: media with enriched fields stripped")
    void importQuizzes_mediaWithEnrichedFields_stripped() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create MCQ question with media containing enriched fields
        UUID assetId = UUID.randomUUID();
        ObjectNode mcqContent = objectMapper.createObjectNode();
        ArrayNode options = objectMapper.createArrayNode();
        ObjectNode option = objectMapper.createObjectNode();
        option.put("id", "1");
        option.put("text", "Option 1");
        option.put("correct", true);
        ObjectNode media = objectMapper.createObjectNode();
        media.put("assetId", assetId.toString());
        media.put("cdnUrl", "https://cdn.example.com/image.png"); // Should be stripped
        media.put("width", 100); // Should be stripped
        media.put("height", 200); // Should be stripped
        media.put("mimeType", "image/png"); // Should be stripped
        option.set("media", media);
        options.add(option);
        options.add(createMcqOption("2", "Option 2", false));
        mcqContent.set("options", options);

        QuestionImportDto question = new QuestionImportDto(
                null,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                "Question with enriched media",
                mcqContent,
                null, null, null, null
        );

        QuizImportDto quiz = buildQuiz(null, "Enriched Media Quiz", List.of(question));

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
                .andExpect(jsonPath("$.created").value(1));

        // Verify enriched fields are stripped during import
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        Quiz created = quizRepository.findByIdWithTagsAndQuestions(createdQuizzes.get(0).getId())
                .orElseThrow();
        uk.gegc.quizmaker.features.question.domain.model.Question q = created.getQuestions().iterator().next();
        ObjectNode content = (ObjectNode) objectMapper.readTree(q.getContent());
        ArrayNode importedOptions = (ArrayNode) content.get("options");
        for (com.fasterxml.jackson.databind.JsonNode opt : importedOptions) {
            if (opt.has("media")) {
                assertThat(opt.get("media").has("assetId")).isTrue();
                assertThat(opt.get("media").has("cdnUrl")).isFalse();
                assertThat(opt.get("media").has("width")).isFalse();
                assertThat(opt.get("media").has("height")).isFalse();
                assertThat(opt.get("media").has("mimeType")).isFalse();
            }
        }
    }

    @Test
    @DisplayName("importQuizzes: legacy attachmentUrl preserved")
    void importQuizzes_legacyAttachmentUrl_preserved() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create question with legacy attachmentUrl (no attachment object)
        QuestionImportDto question = new QuestionImportDto(
                null,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.OPEN,
                Difficulty.EASY,
                "Question with legacy attachment",
                objectMapper.readTree("{\"answer\":\"Sample answer\"}"),
                null, null,
                "https://cdn.quizzence.com/legacy/attachment.pdf", // Legacy attachmentUrl
                null // No attachment object
        );

        QuizImportDto quiz = buildQuiz(null, "Legacy Attachment Quiz", List.of(question));

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
                .andExpect(jsonPath("$.created").value(1));

        // Verify legacy attachmentUrl is preserved
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        Quiz created = quizRepository.findByIdWithTagsAndQuestions(createdQuizzes.get(0).getId())
                .orElseThrow();
        uk.gegc.quizmaker.features.question.domain.model.Question q = created.getQuestions().iterator().next();
        assertThat(q.getAttachmentUrl()).isEqualTo("https://cdn.quizzence.com/legacy/attachment.pdf");
    }

    @Test
    @DisplayName("importQuizzes: content with null values handled")
    void importQuizzes_contentWithNullValues_handled() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create question with content containing null values
        ObjectNode content = objectMapper.createObjectNode();
        content.put("answer", "Sample answer"); // Required for OPEN questions
        content.put("text", "Question text");
        content.set("optionalField", null); // null field in content

        QuestionImportDto question = new QuestionImportDto(
                null,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.OPEN,
                Difficulty.EASY,
                "Question with null in content",
                content,
                null, null, null, null
        );

        QuizImportDto quiz = buildQuiz(null, "Null Content Quiz", List.of(question));

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
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    @DisplayName("importQuizzes: content with empty objects fails validation for OPEN questions")
    void importQuizzes_contentWithEmptyObjects_failsValidation() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create OPEN question with empty content object (missing required 'answer' field)
        ObjectNode content = objectMapper.createObjectNode();
        // Empty object - OPEN questions require 'answer' field

        QuestionImportDto question = new QuestionImportDto(
                null,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.OPEN,
                Difficulty.EASY,
                "Question with empty content",
                content,
                null, null, null, null
        );

        QuizImportDto quiz = buildQuiz(null, "Empty Content Quiz", List.of(question));

        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // OPEN questions require a non-empty 'answer' field, so this should fail validation
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
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsStringIgnoringCase("answer")));
        
        // Verify quiz was not created
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).isEmpty();
    }

    @Test
    @DisplayName("importQuizzes: content with empty objects fails validation for TRUE_FALSE questions")
    void importQuizzes_contentWithEmptyObjects_failsValidationForTrueFalse() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create TRUE_FALSE question with empty content object (missing required 'answer' boolean field)
        ObjectNode content = objectMapper.createObjectNode();
        // Empty object - TRUE_FALSE questions require 'answer' boolean field

        QuestionImportDto question = new QuestionImportDto(
                null,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE,
                Difficulty.EASY,
                "Question with empty content",
                content,
                null, null, null, null
        );

        QuizImportDto quiz = buildQuiz(null, "Empty Content TRUE_FALSE Quiz", List.of(question));

        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // TRUE_FALSE questions require an 'answer' boolean field, so this should fail validation
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
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsStringIgnoringCase("answer")));
        
        // Verify quiz was not created
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).isEmpty();
    }

    @Test
    @DisplayName("importQuizzes: content with nested structures handled")
    void importQuizzes_contentWithNestedStructures_handled() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Create FILL_GAP question with nested structures
        ObjectNode content = objectMapper.createObjectNode();
        content.put("text", "Java is a {1} language and Python is a {2} language");
        ArrayNode gaps = objectMapper.createArrayNode();
        ObjectNode gap1 = objectMapper.createObjectNode();
        gap1.put("id", 1);
        gap1.put("answer", "compiled");
        ObjectNode gap2 = objectMapper.createObjectNode();
        gap2.put("id", 2);
        gap2.put("answer", "interpreted");
        gaps.add(gap1);
        gaps.add(gap2);
        content.set("gaps", gaps);

        QuestionImportDto question = new QuestionImportDto(
                null,
                uk.gegc.quizmaker.features.question.domain.model.QuestionType.FILL_GAP,
                Difficulty.EASY,
                "Question with nested gaps",
                content,
                null, null, null, null
        );

        QuizImportDto quiz = buildQuiz(null, "Nested Content Quiz", List.of(question));

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
                .andExpect(jsonPath("$.created").value(1));

        // Verify nested structures are preserved
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        Quiz created = quizRepository.findByIdWithTagsAndQuestions(createdQuizzes.get(0).getId())
                .orElseThrow();
        uk.gegc.quizmaker.features.question.domain.model.Question q = created.getQuestions().iterator().next();
        ObjectNode importedContent = (ObjectNode) objectMapper.readTree(q.getContent());
        assertThat(importedContent.has("gaps")).isTrue();
        assertThat(importedContent.get("gaps").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("importQuizzes: strategy with missing data handles correctly")
    void importQuizzes_strategyWithMissingData_handlesCorrectly() throws Exception {
        User user = createUserWithPermission("import_user_" + UUID.randomUUID());
        
        // Test UPSERT_BY_ID with null ID - should fail with error in summary
        QuizImportDto quizWithoutId = buildQuiz(null, "Quiz Without ID", List.of());

        String payload = objectMapper.writeValueAsString(List.of(quizWithoutId));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "UPSERT_BY_ID") // Requires ID
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        // Should return error in summary, not crash
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsStringIgnoringCase("id")));
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

    private ObjectNode createMcqOption(String id, String text, boolean correct) {
        ObjectNode option = objectMapper.createObjectNode();
        option.put("id", id);
        option.put("text", text);
        option.put("correct", correct);
        return option;
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
