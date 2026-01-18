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
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
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

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Quiz Import Security Integration Tests")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuizImportSecurityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private QuizDefaultsProperties quizDefaultsProperties;

    @Test
    @DisplayName("importQuizzes: unauthenticated returns 401")
    void importQuizzes_unauthenticated_returns401() throws Exception {
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // No authentication - should return 401
        mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("importQuizzes: authenticated succeeds")
    void importQuizzes_authenticated_succeeds() throws Exception {
        User user = createUserWithPermission("auth_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // Authenticated with QUIZ_CREATE permission - should succeed
        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        result.andExpect(status().isOk());
    }

    @Test
    @DisplayName("importQuizzes: without QUIZ_CREATE returns 403")
    void importQuizzes_withoutQuizCreate_returns403() throws Exception {
        // Create user without QUIZ_CREATE permission
        User user = createUserWithoutQuizCreatePermission("no_permission_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // Authenticated but without QUIZ_CREATE permission - should return 403
        mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("importQuizzes: with QUIZ_CREATE succeeds")
    void importQuizzes_withQuizCreate_succeeds() throws Exception {
        User user = createUserWithPermission("with_permission_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "Test Quiz", List.of());
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // Authenticated with QUIZ_CREATE permission - should succeed
        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        result.andExpect(status().isOk());
    }

    @Test
    @DisplayName("importQuizzes: PUBLIC visibility requires QUIZ_MODERATE")
    void importQuizzes_publicVisibility_requiresModerate() throws Exception {
        // Create user with QUIZ_CREATE and QUIZ_MODERATE permissions
        User user = createUserWithModeratePermission("moderate_user_" + UUID.randomUUID());
        QuizImportDto quiz = new QuizImportDto(
                null,
                null,
                "Public Quiz",
                "Description",
                Visibility.PUBLIC, // PUBLIC visibility
                Difficulty.EASY,
                10,
                null,
                null,
                null,
                List.of(),
                null,
                null
        );
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // User has QUIZ_CREATE and QUIZ_MODERATE - should succeed
        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "CREATE_ONLY")
                .param("dryRun", "false")
                .with(user(user.getUsername())));

        // Should succeed because user has QUIZ_MODERATE permission
        result.andExpect(status().isOk());
    }

    @Test
    @DisplayName("importQuizzes: PUBLIC visibility without QUIZ_MODERATE returns 403")
    void importQuizzes_publicVisibility_withoutModerate_returns403() throws Exception {
        // Create user with QUIZ_CREATE but not QUIZ_MODERATE
        User user = createUserWithPermission("create_only_user_" + UUID.randomUUID());
        QuizImportDto quiz = new QuizImportDto(
                null,
                null,
                "Public Quiz",
                "Description",
                Visibility.PUBLIC, // PUBLIC visibility
                Difficulty.EASY,
                10,
                null,
                null,
                null,
                List.of(),
                null,
                null
        );
        String payload = objectMapper.writeValueAsString(List.of(quiz));
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quizzes.json",
                MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // User has QUIZ_CREATE but not QUIZ_MODERATE - validation error is caught and returned in summary
        // The import service catches ForbiddenException and adds it to errors (returns 200, not 403)
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
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsStringIgnoringCase("moderator")));
    }

    private QuizImportDto buildQuiz(UUID id, String title, List<uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto> questions) {
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

    private User createUserWithoutQuizCreatePermission(String username) {
        // Create role with no permissions (or some other permission, not QUIZ_CREATE)
        Role role = Role.builder()
                .roleName("ROLE_" + username.toUpperCase())
                .permissions(Set.of())
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

    private User createUserWithModeratePermission(String username) {
        Permission quizCreate = permissionRepository.findByPermissionName(PermissionName.QUIZ_CREATE.name())
                .orElseThrow(() -> new IllegalStateException("QUIZ_CREATE permission not found"));
        Permission quizModerate = permissionRepository.findByPermissionName(PermissionName.QUIZ_MODERATE.name())
                .orElseThrow(() -> new IllegalStateException("QUIZ_MODERATE permission not found"));

        Role role = Role.builder()
                .roleName("ROLE_" + username.toUpperCase())
                .permissions(Set.of(quizCreate, quizModerate))
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

    @Test
    @DisplayName("importQuizzes: UPSERT_BY_ID with owned quiz updates")
    void importQuizzes_upsertById_ownedQuiz_updates() throws Exception {
        User owner = createUserWithPermission("owner_user_" + UUID.randomUUID());
        // Create an existing quiz owned by the user
        Quiz existingQuiz = createQuiz(owner, "Original Title");

        QuizImportDto updateDto = buildQuiz(existingQuiz.getId(), "Updated Title", List.of());
        String payload = objectMapper.writeValueAsString(List.of(updateDto));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "UPSERT_BY_ID")
                .param("dryRun", "false")
                .with(user(owner.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.updated").value(1));

        // Verify quiz was updated
        Quiz updated = quizRepository.findById(existingQuiz.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Updated Title");
        assertThat(updated.getCreator().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("importQuizzes: UPSERT_BY_ID with unowned quiz returns error")
    void importQuizzes_upsertById_unownedQuiz_returns403() throws Exception {
        User owner = createUserWithPermission("owner_user_" + UUID.randomUUID());
        User otherUser = createUserWithPermission("other_user_" + UUID.randomUUID());
        // Create quiz owned by another user
        Quiz existingQuiz = createQuiz(owner, "Owned Quiz");

        QuizImportDto updateDto = buildQuiz(existingQuiz.getId(), "Updated Title", List.of());
        String payload = objectMapper.writeValueAsString(List.of(updateDto));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // Other user tries to update - should fail (returns 200 with error, not 403)
        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "UPSERT_BY_ID")
                .param("dryRun", "false")
                .with(user(otherUser.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsStringIgnoringCase("owner")));
    }

    @Test
    @DisplayName("importQuizzes: UPSERT_BY_ID with QUIZ_MODERATE updates any quiz")
    void importQuizzes_upsertById_withModerate_updatesAny() throws Exception {
        User owner = createUserWithPermission("owner_user_" + UUID.randomUUID());
        User moderator = createUserWithModeratePermission("moderator_user_" + UUID.randomUUID());
        // Create quiz owned by another user
        Quiz existingQuiz = createQuiz(owner, "Owned Quiz");

        QuizImportDto updateDto = buildQuiz(existingQuiz.getId(), "Updated by Moderator", List.of());
        String payload = objectMapper.writeValueAsString(List.of(updateDto));
        MockMultipartFile file = new MockMultipartFile(
                "file", "quizzes.json", MediaType.APPLICATION_JSON_VALUE,
                payload.getBytes(StandardCharsets.UTF_8)
        );

        // Moderator can update any quiz
        ResultActions result = mockMvc.perform(multipart("/api/v1/quizzes/import")
                .file(file)
                .param("format", "JSON_EDITABLE")
                .param("strategy", "UPSERT_BY_ID")
                .param("dryRun", "false")
                .with(user(moderator.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.updated").value(1));

        // Verify quiz was updated
        Quiz updated = quizRepository.findById(existingQuiz.getId()).orElseThrow();
        assertThat(updated.getTitle()).isEqualTo("Updated by Moderator");
        // Creator remains the original owner
        assertThat(updated.getCreator().getId()).isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("importQuizzes: creator always set to authenticated user")
    void importQuizzes_creatorSetToAuthenticatedUser() throws Exception {
        User user = createUserWithPermission("creator_user_" + UUID.randomUUID());
        QuizImportDto quiz = buildQuiz(null, "New Quiz", List.of());
        // Set creatorId in DTO to something else - should be ignored
        quiz = new QuizImportDto(
                null,
                null,
                "New Quiz",
                "Description",
                Visibility.PRIVATE,
                Difficulty.EASY,
                10,
                null,
                null,
                UUID.randomUUID(), // This should be ignored - creator will be set to authenticated user
                List.of(),
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
                .andExpect(jsonPath("$.created").value(1));

        // Verify creator is set to authenticated user, not the DTO creatorId
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        assertThat(createdQuizzes.get(0).getCreator().getId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("importQuizzes: non-moderator with PRIVATE visibility sets DRAFT")
    void importQuizzes_nonModerator_privateVisibility_setsDraft() throws Exception {
        User user = createUserWithPermission("non_moderator_" + UUID.randomUUID());
        QuizImportDto quiz = new QuizImportDto(
                null, null, "Private Quiz", "Description", Visibility.PRIVATE, Difficulty.EASY, 10,
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

        // Verify status is DRAFT
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(user.getId());
        assertThat(createdQuizzes).hasSize(1);
        assertThat(createdQuizzes.get(0).getStatus()).isEqualTo(QuizStatus.DRAFT);
        assertThat(createdQuizzes.get(0).getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    @DisplayName("importQuizzes: moderator can set PUBLIC")
    void importQuizzes_moderator_canSetPublic() throws Exception {
        User moderator = createUserWithModeratePermission("moderator_" + UUID.randomUUID());
        QuizImportDto quiz = new QuizImportDto(
                null, null, "Public Quiz", "Description", Visibility.PUBLIC, Difficulty.EASY, 10,
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
                .with(user(moderator.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // Verify visibility is PUBLIC
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(moderator.getId());
        assertThat(createdQuizzes).hasSize(1);
        assertThat(createdQuizzes.get(0).getVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    @DisplayName("importQuizzes: moderator PUBLIC sets status to PUBLISHED")
    void importQuizzes_moderator_publicSetsPublished() throws Exception {
        User moderator = createUserWithModeratePermission("moderator_" + UUID.randomUUID());
        QuizImportDto quiz = new QuizImportDto(
                null, null, "Public Quiz", "Description", Visibility.PUBLIC, Difficulty.EASY, 10,
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
                .with(user(moderator.getUsername())));

        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // Verify status is PUBLISHED when visibility is PUBLIC
        List<Quiz> createdQuizzes = quizRepository.findByCreatorId(moderator.getId());
        assertThat(createdQuizzes).hasSize(1);
        assertThat(createdQuizzes.get(0).getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        assertThat(createdQuizzes.get(0).getVisibility()).isEqualTo(Visibility.PUBLIC);
    }

    private Quiz createQuiz(User creator, String title) {
        Category category = categoryRepository.findById(quizDefaultsProperties.getDefaultCategoryId())
                .orElseThrow(() -> new IllegalStateException("Default category not found"));

        Quiz quiz = new Quiz();
        quiz.setCreator(creator);
        quiz.setCategory(category);
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
}
