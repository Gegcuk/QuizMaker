package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.Attempt;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.api.dto.BulkQuizUpdateRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.UpdateQuizRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.features.result.domain.model.QuizAnalyticsSnapshot;
import uk.gegc.quizmaker.features.result.domain.repository.QuizAnalyticsSnapshotRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@DisplayName("Integration Tests QuizController")
class QuizControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository userRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    TagRepository tagRepository;
    @Autowired
    QuestionRepository questionRepository;
    @Autowired
    QuizRepository quizRepository;
    @Autowired
    QuizDefaultsProperties quizDefaultsProperties;
    @Autowired
    AttemptRepository attemptRepository;
    @Autowired
    PermissionRepository permissionRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    QuizAnalyticsSnapshotRepository snapshotRepository;

    private UUID categoryId;
    private UUID tagId;
    private UUID questionId;
    private UserDetails adminUserDetails;
    private UserDetails regularUserDetails;
    private User adminUser;
    private User regularUser;

    @BeforeEach
    void setUp() {

        // Find existing permissions (created by DataInitializer)
        Permission quizCreatePermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_CREATE.name())
                .orElseThrow(() -> new RuntimeException("QUIZ_CREATE permission not found"));
        Permission quizReadPermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_READ.name())
                .orElseThrow(() -> new RuntimeException("QUIZ_READ permission not found"));
        Permission quizUpdatePermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_UPDATE.name())
                .orElseThrow(() -> new RuntimeException("QUIZ_UPDATE permission not found"));
        Permission quizDeletePermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_DELETE.name())
                .orElseThrow(() -> new RuntimeException("QUIZ_DELETE permission not found"));
        Permission quizModeratePermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_MODERATE.name())
                .orElseThrow(() -> new RuntimeException("QUIZ_MODERATE permission not found"));
        Permission quizAdminPermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_ADMIN.name())
                .orElseThrow(() -> new RuntimeException("QUIZ_ADMIN permission not found"));
        Permission questionCreatePermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_CREATE.name())
                .orElseThrow(() -> new RuntimeException("QUESTION_CREATE permission not found"));
        Permission questionReadPermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_READ.name())
                .orElseThrow(() -> new RuntimeException("QUESTION_READ permission not found"));
        Permission categoryReadPermission = permissionRepository.findByPermissionName(PermissionName.CATEGORY_READ.name())
                .orElseThrow(() -> new RuntimeException("CATEGORY_READ permission not found"));
        Permission tagReadPermission = permissionRepository.findByPermissionName(PermissionName.TAG_READ.name())
                .orElseThrow(() -> new RuntimeException("TAG_READ permission not found"));

        // Find existing roles (created by DataInitializer)
        Role adminRole = roleRepository.findByRoleName(RoleName.ROLE_ADMIN.name())
                .orElseThrow(() -> new RuntimeException("ROLE_ADMIN role not found"));
        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new RuntimeException("ROLE_USER role not found"));

        // Create test users with roles
        adminUser = new User();
        adminUser.setUsername("admin");
        adminUser.setEmail("admin@example.com");
        adminUser.setHashedPassword("password");
        adminUser.setActive(true);
        adminUser.setDeleted(false);
        adminUser.setRoles(Set.of(adminRole));
        adminUser = userRepository.save(adminUser);

        regularUser = new User();
        regularUser.setUsername("user");
        regularUser.setEmail("user@example.com");
        regularUser.setHashedPassword("password");
        regularUser.setActive(true);
        regularUser.setDeleted(false);
        regularUser.setRoles(Set.of(userRole));
        regularUser = userRepository.save(regularUser);

        // Create default category
        Category c = new Category();
        c.setName("General");
        c.setDescription("Default");
        categoryRepository.save(c);
        categoryId = c.getId();

        // Create default tag
        Tag t = new Tag();
        t.setName("tag-one");
        t.setDescription("desc");
        tagRepository.save(t);
        tagId = t.getId();

        // Create default question
        Question q = new Question();
        q.setType(QuestionType.MCQ_SINGLE);
        q.setDifficulty(Difficulty.EASY);
        q.setQuestionText("What?");
        q.setContent("{\"options\":[{\"id\":\"1\",\"text\":\"Option A\",\"correct\":true},{\"id\":\"2\",\"text\":\"Option B\",\"correct\":false}]}");
        q.setHint("hint");
        q.setExplanation("explanation");
        questionRepository.save(q);
        questionId = q.getId();

        // Create UserDetails objects with proper authorities
        adminUserDetails = createUserDetails(adminUser);
        regularUserDetails = createUserDetails(regularUser);
    }

    private UserDetails createUserDetails(User user) {
        List<GrantedAuthority> authorities = user.getRoles()
                .stream()
                .flatMap(role -> {
                    // Add role authority
                    var roleAuthority = new SimpleGrantedAuthority(role.getRoleName());
                    // Add permission authorities
                    var permissionAuthorities = role.getPermissions().stream()
                            .map(permission -> new SimpleGrantedAuthority(permission.getPermissionName()));
                    return Stream.concat(
                            Stream.of(roleAuthority),
                            permissionAuthorities
                    );
                })
                .map(authority -> (GrantedAuthority) authority)
                .toList();
        
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getHashedPassword(),
                user.isActive(),
                true,
                true,
                true,
                authorities
        );
    }

    @Test
    @DisplayName("Full CRUD and associations flow as ADMIN → succeeds")
    void fullQuizCrudAndAssociations() throws Exception {
        // --- 1) Create a quiz ---
        CreateQuizRequest create = new CreateQuizRequest(
                "My Quiz", "desc",
                null, null,
                false, false,
                10, 5,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);

        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quizId").exists())
                .andReturn().getResponse().getContentAsString();

        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        // --- 2) GET list, should contain 1 ---
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "0").param("size", "20")
                        .param("scope", "me")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)));

        // --- 3) GET by id ---
        mockMvc.perform(get("/api/v1/quizzes/{id}", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(quizId.toString())))
                .andExpect(jsonPath("$.title", is("My Quiz")))
                .andExpect(jsonPath("$.tagIds[0]", is(tagId.toString())));

        // --- 4) PATCH update title ---
        UpdateQuizRequest update = new UpdateQuizRequest(
                "New Title",
                null, null, null,
                null, null,
                15, 3,
                null, null
        );
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update))
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("New Title")))
                .andExpect(jsonPath("$.estimatedTime", is(15)))
                .andExpect(jsonPath("$.timerDuration", is(3)));

        // --- 5) Add and remove question ---
        mockMvc.perform(post("/api/v1/quizzes/{q}/questions/{ques}", quizId, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/quizzes/{q}/questions/{ques}", quizId, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        // --- 6) Add and remove tag ---
        mockMvc.perform(post("/api/v1/quizzes/{q}/tags/{t}", quizId, tagId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/quizzes/{q}/tags/{t}", quizId, tagId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        // --- 7) Change category ---
        Category c2 = new Category();
        c2.setName("Other");
        c2.setDescription("other");
        categoryRepository.save(c2);

        mockMvc.perform(patch("/api/v1/quizzes/{q}/category/{c}", quizId, c2.getId())
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/quizzes/{id}", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(jsonPath("$.categoryId", is(c2.getId().toString())));

        // --- 8) Delete quiz ---
        mockMvc.perform(delete("/api/v1/quizzes/{id}", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/quizzes/{id}", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with only required fields -> returns 201 CREATED")
    void createQuiz_requiredFields_adminReturns201() throws Exception {
        String json = """
                {
                  "title":"Quick Quiz",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quizId").exists());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes without categoryId uses default category")
    void createQuiz_withoutCategory_usesDefaultCategory() throws Exception {
        String json = """
                {
                  "title":"No Category Quiz",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """;

        var mvcResult = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        UUID quizId = UUID.fromString(response.get("quizId").asText());

        var savedQuiz = quizRepository.findById(quizId).orElseThrow();
        assertEquals(quizDefaultsProperties.getDefaultCategoryId(), savedQuiz.getCategory().getId());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with invalid categoryId uses default category")
    void createQuiz_invalidCategory_usesDefaultCategory() throws Exception {
        String json = """
                {
                  "title":"Bad Category Quiz",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(UUID.randomUUID());

        var mvcResult = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString());
        UUID quizId = UUID.fromString(response.get("quizId").asText());

        var savedQuiz = quizRepository.findById(quizId).orElseThrow();
        assertEquals(quizDefaultsProperties.getDefaultCategoryId(), savedQuiz.getCategory().getId());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes missing title -> returns 400 BAD_REQUEST")
    void createQuiz_missingTitle_returns400() throws Exception {
        String json = """
                {
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title must not be blank"))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes title too short returns 400 BAD_REQUEST")
    void createQuiz_titleTooShort_returns400() throws Exception {
        String json = """
                {
                  "title":"ab",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes title too long returns 400 BAD_REQUEST")
    void createQuiz_titleTooLong_returns400() throws Exception {
        String longTitle = "x".repeat(101);
        String json = """
                {
                  "title":"%s",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(longTitle, categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with description too long -> returns 400 BAD_REQUEST")
    void createQuiz_descriptionTooLong_returns400() throws Exception {
        String longDesc = "d".repeat(1001);
        String json = """
                {
                  "title":"Valid Quiz",
                  "description":"%s",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(longDesc, categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Description must be at most 1000 characters long"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with estimatedTime < 1 -> returns 400 BAD_REQUEST")
    void createQuiz_estimatedTimeTooLow_returns400() throws Exception {
        String json = """
                {
                  "title":"Valid Quiz",
                  "estimatedTime":0,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Estimated time can't be less than 1 minute"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with estimatedTime > 180 -> returns 400 BAD_REQUEST")
    void createQuiz_estimatedTimeTooHigh_returns400() throws Exception {
        String json = """
                {
                  "title":"Valid Quiz",
                  "estimatedTime":181,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Estimated time can't be more than 180 minutes"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with timerDuration < 1 ->  returns 400 BAD_REQUEST")
    void createQuiz_timerDurationTooLow_returns400() throws Exception {
        String json = """
                {
                  "title":"Valid Quiz",
                  "estimatedTime":10,
                  "timerDuration":0,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Timer duration must be at least 1 minute"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with timerDuration > 180 -> returns 400 BAD_REQUEST")
    void createQuiz_timerDurationTooHigh_returns400() throws Exception {
        String json = """
                {
                  "title":"Valid Quiz",
                  "estimatedTime":10,
                  "timerDuration":181,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Timer duration must be at most 180 minutes"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes with unknown tagId -> returns 404 NOT_FOUND")
    void createQuiz_unknownTagId_returns404() throws Exception {
        UUID badTag = UUID.randomUUID();
        String json = """
                {
                  "title":"Valid Quiz",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "tagIds":["%s"],
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId, badTag);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString(
                        "Tag " + badTag + " not found"
                ))));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes anonymous -> returns 401 UNAUTHORIZED")
    void createQuiz_anonymous_returns401() throws Exception {
        String json = """
                {
                  "title":"Quiz",
                  "estimatedTime":10,
                  "timerDuration":5,
                  "categoryId":"%s",
                  "isRepetitionEnabled":false,
                  "timerEnabled":false
                }
                """.formatted(categoryId);

        mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes -> returns empty page when no quizzes present")
    void listQuizzes_emptyDb_returnsEmptyPage() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "0")
                        .param("size", "20")
                        .param("scope", "public")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes?page=1&size=5 -> returns correct page and size")
    void listQuizzes_paginationAndSorting() throws Exception {
        for (int i = 1; i <= 12; i++) {
            CreateQuizRequest req = new CreateQuizRequest(
                    "Quiz " + i,
                    "desc",
                    null, null,
                    false, false,
                    10, 5,
                    categoryId,
                    List.of(tagId)
            );
            mockMvc.perform(post("/api/v1/quizzes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(user(adminUserDetails)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "1")
                        .param("size", "5")
                        .param("scope", "me")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(12)))
                .andExpect(jsonPath("$.size", is(5)))
                .andExpect(jsonPath("$.number", is(1)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes with negative page -> returns 200 with default size")
    void listQuizzes_negativePage_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes")
                        .param("page", "-1")
                        .param("size", "5")
                        .param("scope", "public")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(0)))
                .andExpect(jsonPath("$.size", is(5)))
                .andExpect(jsonPath("$.number", is(0)))
                .andExpect(jsonPath("$.content", hasSize(0)));
        ;
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{id} with non existing id -> returns 404 NOT FOUND")
    void getNonexistingQuiz_returns404() throws Exception {
        UUID dummyId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/quizzes/{id}", dummyId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with all fields returns -> 200 OK with updated JSON")
    void updateQuiz_allFields_adminReturns200() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "Original Title", "Original desc",
                Visibility.PUBLIC, Difficulty.HARD,
                true, true,
                20, 10,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        UpdateQuizRequest update = new UpdateQuizRequest(
                "New Title", "New description",
                Visibility.PRIVATE, Difficulty.EASY,
                false, false,
                30, 15,
                categoryId,
                List.of()
        );
        String updateJson = objectMapper.writeValueAsString(update);

        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("New Title")))
                .andExpect(jsonPath("$.description", is("New description")))
                .andExpect(jsonPath("$.visibility", is("PRIVATE")))
                .andExpect(jsonPath("$.difficulty", is("EASY")))
                .andExpect(jsonPath("$.isRepetitionEnabled", is(false)))
                .andExpect(jsonPath("$.timerEnabled", is(false)))
                .andExpect(jsonPath("$.estimatedTime", is(30)))
                .andExpect(jsonPath("$.timerDuration", is(15)))
                .andExpect(jsonPath("$.categoryId", is(categoryId.toString())))
                .andExpect(jsonPath("$.tagIds", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with subset of fields -> returns 200 OK and leaves others intact")
    void updateQuiz_partialFields_adminReturns200() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "Initial Title", "Initial desc",
                Visibility.PUBLIC, Difficulty.MEDIUM,
                true, true,
                25, 12,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        String partialJson = """
                {
                  "title":"Updated Title",
                  "estimatedTime":15,
                  "timerDuration":7
                }
                """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(partialJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title", is("Updated Title")))
                .andExpect(jsonPath("$.estimatedTime", is(15)))
                .andExpect(jsonPath("$.timerDuration", is(7)))
                .andExpect(jsonPath("$.description", is("Initial desc")))
                .andExpect(jsonPath("$.visibility", is("PUBLIC")))
                .andExpect(jsonPath("$.difficulty", is("MEDIUM")))
                .andExpect(jsonPath("$.isRepetitionEnabled", is(true)))
                .andExpect(jsonPath("$.timerEnabled", is(true)))
                .andExpect(jsonPath("$.categoryId", is(categoryId.toString())))
                .andExpect(jsonPath("$.tagIds[0]", is(tagId.toString())));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with too short title -> returns 400 BAD_REQUEST")
    void updateQuiz_titleTooShort_returns400() throws Exception {
        UUID id = createSampleQuiz();
        String json = """
                { "title":"ab" }
                """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with too long title -> returns 400 BAD_REQUEST")
    void updateQuiz_titleTooLong_returns400() throws Exception {
        UUID id = createSampleQuiz();
        String longTitle = "x".repeat(101);
        String json = """
                { "title":"%s" }
                """.formatted(longTitle);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Title length must be between 3 and 100 characters"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with too long description -> returns 400 BAD_REQUEST")
    void updateQuiz_descriptionTooLong_returns400() throws Exception {
        UUID id = createSampleQuiz();
        String longDesc = "d".repeat(1001);
        String json = """
                { "description":"%s" }
                """.formatted(longDesc);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Description must be at most 1000 characters long"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with estimatedTime < 1 -> returns 400 BAD_REQUEST")
    void updateQuiz_estimatedTimeTooLow_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
                { "estimatedTime": 0 }
                """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Estimated time must be at least 1 minute"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with estimatedTime > 180 -> returns 400 BAD_REQUEST")
    void updateQuiz_estimatedTimeTooHigh_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
                { "estimatedTime": 181 }
                """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Estimated time must be at most 180 minutes"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with timerDuration < 1 -> returns 400 BAD_REQUEST")
    void updateQuiz_timerDurationTooLow_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
                { "timerDuration": 0 }
                """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Timer duration must be at least 1 minute"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with timerDuration > 180 -> returns 400 BAD_REQUEST")
    void updateQuiz_timerDurationTooHigh_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        String json = """
                { "timerDuration": 181 }
                """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details", hasItem(containsString("Timer duration must be at most 180 minutes"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with non-existent ID -> returns 404 NOT_FOUND")
    void updateQuiz_nonexistentId_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        String json = """
                { "title":"Valid Title" }
                """;
        mockMvc.perform(patch("/api/v1/quizzes/{id}", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Quiz " + missing + " not found"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with unknown tagId -> returns 404 NOT_FOUND")
    void updateQuiz_unknownTagId_returns404() throws Exception {
        UUID quizId = createSampleQuiz();
        UUID badTag = UUID.randomUUID();
        String json = """
                { "tagIds":["%s"] }
                """.formatted(badTag);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Tag " + badTag + " not found"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id} with unknown categoryId -> returns 404 NOT_FOUND")
    void updateQuiz_unknownCategoryId_returns404() throws Exception {
        UUID quizId = createSampleQuiz();
        UUID badCat = UUID.randomUUID();
        String json = """
                { "categoryId":"%s" }
                """.formatted(badCat);
        mockMvc.perform(patch("/api/v1/quizzes/{id}", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details", hasItem(containsString("Category " + badCat + " not found"))));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/{id} with non-existent ID -> returns 404 NOT_FOUND")
    void deleteQuiz_nonexistentId_returns404() throws Exception {
        mockMvc.perform(delete("/api/v1/quizzes/{id}", UUID.randomUUID())
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/questions/{questionId} with non-existent quiz -> returns 404 NOT_FOUND")
    void addQuestion_nonexistentQuiz_returns404() throws Exception {
        UUID badQuiz = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/quizzes/{quizId}/questions/{questionId}", badQuiz, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuiz + " not found")));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/questions/{questionId} with non-existent question -> returns 404 NOT_FOUND")
    void addQuestion_nonexistentQuestion_returns404() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "Temp Quiz", "desc",
                null, null,
                false, false,
                5, 2,
                categoryId,
                List.of(tagId)
        );
        String quizJson = objectMapper.writeValueAsString(create);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quizJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID validQuiz = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());

        UUID badQuestion = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/quizzes/{quizId}/questions/{questionId}", validQuiz, badQuestion)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Question " + badQuestion + " not found")));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/{quizId}/tags/{tagId} with non-existent quizId returns 404 NOT_FOUND")
    void removeTagFromQuiz_nonexistentQuiz_returns404() throws Exception {
        UUID badQuizId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/quizzes/{quizId}/tags/{tagId}", badQuizId, tagId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuizId + " not found")));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/{quizId}/tags/{tagId} with non-existent tagId returns 204 NO_CONTENT")
    void removeTagFromQuiz_nonexistentTag_returns204() throws Exception {
        CreateQuizRequest create = new CreateQuizRequest(
                "QuizForRemove", "desc",
                null, null,
                false, false,
                10, 5,
                categoryId,
                List.of(tagId)
        );
        String quizJson = objectMapper.writeValueAsString(create);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quizJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());

        UUID badTagId = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/quizzes/{quizId}/tags/{tagId}", quizId, badTagId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{quizId}/category/{categoryId} with non-existent quizId -> returns 404 NOT_FOUND")
    void changeCategory_nonexistentQuiz_returns404() throws Exception {
        UUID badQuizId = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/quizzes/{quizId}/category/{categoryId}", badQuizId, categoryId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuizId + " not found")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{quizId}/category/{categoryId} with non-existent categoryId returns 404 NOT_FOUND")
    void changeCategory_nonexistentCategory_returns404() throws Exception {
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQuizRequest("Quiz2", null, null, null, false, false, 5, 2, categoryId, List.of())
                        ))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        UUID badCategoryId = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/quizzes/{quizId}/category/{categoryId}", quizId, badCategoryId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Category " + badCategoryId + " not found")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/results with completed attempts returns 200 OK with summary")
    void getQuizResults_withCompletedAttempts_returns200() throws Exception {
        String quizBody = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQuizRequest("ResultsQuiz", "desc", null, null, false, false, 5, 2, categoryId, List.of())
                        ))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(quizBody).get("quizId").asText());

        JsonNode content = objectMapper.readTree("{\"answer\":true}");
        String qBody = mockMvc.perform(post("/api/v1/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateQuestionRequest(QuestionType.TRUE_FALSE,
                                        Difficulty.EASY,
                                        "Question?",
                                        content,
                                        null,
                                        null,
                                        null,
                                        List.of(quizId),
                                        List.of()
                                )
                        ))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(qBody).get("questionId").asText());

        String atBody = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(atBody).get("attemptId").asText());

        AnswerSubmissionRequest sub = new AnswerSubmissionRequest(questionId, content);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sub))
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk());

        // Manually create snapshot for testing (in @Transactional tests, REQUIRES_NEW can't see uncommitted data)
        entityManager.flush();
        Attempt completedAttempt = attemptRepository.findById(attemptId).orElseThrow();
        QuizAnalyticsSnapshot snapshot = new QuizAnalyticsSnapshot();
        snapshot.setQuizId(quizId);
        snapshot.setAttemptsCount(1);
        snapshot.setAverageScore(completedAttempt.getTotalScore());
        snapshot.setBestScore(completedAttempt.getTotalScore());
        snapshot.setWorstScore(completedAttempt.getTotalScore());
        snapshot.setPassRate(100.0); // 1 question, answered correctly
        snapshot.setUpdatedAt(Instant.now());
        snapshotRepository.save(snapshot);
        entityManager.flush();

        mockMvc.perform(get("/api/v1/quizzes/{quizId}/results", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizId", is(quizId.toString())))
                .andExpect(jsonPath("$.attemptsCount", is(1)))
                .andExpect(jsonPath("$.averageScore", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.bestScore", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.worstScore", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.passRate", greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$.questionStats", hasSize(1)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/results with non-existent quizId returns 404 NOT_FOUND")
    void getQuizResults_nonexistentQuiz_returns404() throws Exception {
        UUID badQuizId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/quizzes/{quizId}/results", badQuizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + badQuizId + " not found")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/results when no attempts → returns 200 OK with zeroed summary")
    void getQuizResults_Empty() throws Exception {
        // --- create a quiz first (reuse your existing setup) ---
        CreateQuizRequest create = new CreateQuizRequest(
                "Results Quiz", "desc",
                null, null,
                false, false,
                10, 5,
                categoryId,
                List.of(tagId)
        );
        String createJson = objectMapper.writeValueAsString(create);
        String body = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(body).get("quizId").asText());

        // --- now call the results endpoint ---
        mockMvc.perform(get("/api/v1/quizzes/{quizId}/results", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quizId", is(quizId.toString())))
                .andExpect(jsonPath("$.attemptsCount", is(0)))
                .andExpect(jsonPath("$.averageScore", is(0.0)))
                .andExpect(jsonPath("$.bestScore", is(0.0)))
                .andExpect(jsonPath("$.worstScore", is(0.0)))
                .andExpect(jsonPath("$.passRate", is(0.0)))
                .andExpect(jsonPath("$.questionStats", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility → PUBLIC ▸ returns 200 & updates field")
    void changeVisibility_validPayload_adminReturns200() throws Exception {
        UUID quizId = createSampleQuiz();

        String body = """
                { "isPublic": true }
                """;

        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(quizId.toString())))
                .andExpect(jsonPath("$.visibility", is("PUBLIC")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility with non-existent ID → 404 NOT_FOUND")
    void changeVisibility_nonexistentQuiz_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        String body = """
                { "isPublic": false }
                """;

        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + missing + " not found")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility without ADMIN role → 403 FORBIDDEN")
    void changeVisibility_withoutAdminRole_returns403() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", quizId)
                        .with(user(regularUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"isPublic\": true }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/visibility with malformed body → 400 BAD_REQUEST")
    void changeVisibility_invalidBody_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/visibility", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details",
                        hasItem(containsString("isPublic: must not be null"))));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status publish with question → 200 OK")
    void changeStatus_publishWithQuestion_returns200() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(post("/api/v1/quizzes/{id}/questions/{qId}", quizId, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        String body = "{ \"status\": \"PUBLISHED\" }";

        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("PUBLISHED")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status publish without questions → 400 BAD_REQUEST")
    void changeStatus_publishWithoutQuestion_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"PUBLISHED\" }")
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("Cannot publish quiz: Cannot publish quiz without questions")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status publish with insufficient estimated time → 400 BAD_REQUEST")
    void changeStatus_publishWithInsufficientEstimatedTime_returns400() throws Exception {
        // Create a quiz with valid estimated time first
        CreateQuizRequest req = new CreateQuizRequest(
                "Invalid Time Quiz", "Description",
                null, null,
                false, false,
                5, 2, // Valid estimated time for creation
                categoryId,
                List.of()
        );
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());

        // Add a question to the quiz
        mockMvc.perform(post("/api/v1/quizzes/{id}/questions/{qId}", quizId, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        // Directly update the quiz entity to have 0 estimated time (bypassing DTO validation)
        var quiz = quizRepository.findById(quizId).orElseThrow();
        quiz.setEstimatedTime(0);
        quizRepository.save(quiz);

        // Try to publish - should fail due to insufficient estimated time
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"PUBLISHED\" }")
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("minimum estimated time of 1 minute(s)")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status publish with multiple validation errors → 400 BAD_REQUEST")
    void changeStatus_publishWithMultipleValidationErrors_returns400() throws Exception {
        // Create a quiz with valid estimated time first
        CreateQuizRequest req = new CreateQuizRequest(
                "Multiple Errors Quiz", "Description",
                null, null,
                false, false,
                5, 2, // Valid estimated time for creation
                categoryId,
                List.of()
        );
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());

        // Directly update the quiz entity to have 0 estimated time (bypassing DTO validation)
        var quiz = quizRepository.findById(quizId).orElseThrow();
        quiz.setEstimatedTime(0);
        quizRepository.save(quiz);

        // Try to publish - should fail with multiple errors (no questions + invalid estimated time)
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"PUBLISHED\" }")
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("Cannot publish quiz without questions")))
                .andExpect(jsonPath("$.details[0]", containsString("minimum estimated time of 1 minute(s)")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status publish with invalid question content → 400 BAD_REQUEST")
    void changeStatus_publishWithInvalidQuestionContent_returns400() throws Exception {
        // Create a quiz with valid estimated time
        CreateQuizRequest req = new CreateQuizRequest(
                "Invalid Question Quiz", "Description",
                null, null,
                false, false,
                10, 5, // Valid estimated time
                categoryId,
                List.of()
        );
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID quizId = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());

        // Add the valid question to the quiz
        mockMvc.perform(post("/api/v1/quizzes/{id}/questions/{qId}", quizId, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        // Directly corrupt the question content to make it invalid (MCQ with no correct answers)
        var question = questionRepository.findById(questionId).orElseThrow();
        question.setContent("{\"options\":[{\"id\":\"1\",\"text\":\"Option A\",\"correct\":false},{\"id\":\"2\",\"text\":\"Option B\",\"correct\":false}]}");
        questionRepository.save(question);

        // Try to publish - should fail due to invalid question content
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"PUBLISHED\" }")
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]", containsString("Question 'What?' is invalid: MCQ_SINGLE must have exactly one correct answer")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status revert to draft → 200 OK")
    void changeStatus_revertToDraft_returns200() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(post("/api/v1/quizzes/{id}/questions/{qId}", quizId, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"PUBLISHED\" }")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"DRAFT\" }")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status nonexistent ID → 404 NOT_FOUND")
    void changeStatus_nonexistentQuiz_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", missing)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"DRAFT\" }")
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status without ADMIN role → 403 FORBIDDEN")
    void changeStatus_withoutAdminRole_returns403() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .with(user(regularUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"status\": \"DRAFT\" }"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/{id}/status malformed body → 400 BAD_REQUEST")
    void changeStatus_invalidBody_returns400() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(patch("/api/v1/quizzes/{id}/status", quizId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(user(adminUserDetails)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/public -> returns only PUBLIC quizzes")
    void listOnlyPublic() throws Exception {
        CreateQuizRequest pub = new CreateQuizRequest("Pub", null, Visibility.PUBLIC, null, false, false, 5, 1, categoryId, List.of());
        CreateQuizRequest priv = new CreateQuizRequest("Priv", null, Visibility.PRIVATE, null, false, false, 5, 1, categoryId, List.of());
        mockMvc.perform(post("/api/v1/quizzes").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(pub))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/quizzes").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(priv))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/quizzes/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].visibility", is("PUBLIC")))
                .andExpect(jsonPath("$.content[*].title", not(hasItem("Priv"))));
    }


    @Test
    @DisplayName("GET /api/v1/quizzes/public -> pagination and sorting works")
    void paginationAndSorting() throws Exception {
        for (String title : List.of("Alpha", "Beta", "Gamma")) {
            CreateQuizRequest req = new CreateQuizRequest(title, null, Visibility.PUBLIC, null, false, false, 5, 1, categoryId, List.of());
            mockMvc.perform(post("/api/v1/quizzes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req))
                            .with(user(adminUserDetails)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/quizzes/public")
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "title,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.content[0].title", is("Gamma")))
                .andExpect(jsonPath("$.content[1].title", is("Beta")));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes?ids=... -> deletes existing and ignores missing")
    void bulkDelete_mixedIds_returns204() throws Exception {
        UUID first = createSampleQuiz("Sample1");
        UUID second = createSampleQuiz("Sample2");
        UUID missing = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/quizzes")
                        .param("ids", first + "," + second + "," + missing)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        assertFalse(quizRepository.existsById(first));
        assertFalse(quizRepository.existsById(second));
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes?ids=... without ADMIN role -> 403 FORBIDDEN")
    void bulkDelete_withoutAdmin_returns403() throws Exception {
        UUID id = createSampleQuiz();
        mockMvc.perform(delete("/api/v1/quizzes")
                        .param("ids", id.toString())
                        .with(user(regularUserDetails)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/bulk-update with valid IDs -> returns successes")
    void bulkUpdate_allValid_returns200() throws Exception {
        UUID first = createSampleQuiz("BulkOne");
        UUID second = createSampleQuiz("BulkTwo");

        String body = objectMapper.writeValueAsString(
                new BulkQuizUpdateRequest(List.of(first, second),
                        new UpdateQuizRequest(null, null, null, Difficulty.HARD,
                                null, null, null, null, null, null)));

        mockMvc.perform(patch("/api/v1/quizzes/bulk-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulIds", hasSize(2)))
                .andExpect(jsonPath("$.failures", aMapWithSize(0)));

        assertEquals(Difficulty.HARD, quizRepository.findById(first).get().getDifficulty());
        assertEquals(Difficulty.HARD, quizRepository.findById(second).get().getDifficulty());
    }

    @Test
    @DisplayName("PATCH /api/v1/quizzes/bulk-update with mix of valid and invalid IDs -> returns partial result")
    void bulkUpdate_partialFailure_returns200() throws Exception {
        UUID valid = createSampleQuiz("Valid");
        UUID missing = UUID.randomUUID();

        String body = objectMapper.writeValueAsString(
                new BulkQuizUpdateRequest(List.of(valid, missing),
                        new UpdateQuizRequest(null, null, null, Difficulty.HARD,
                                null, null, null, null, null, null)));

        mockMvc.perform(patch("/api/v1/quizzes/bulk-update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successfulIds", hasItem(valid.toString())))
                .andExpect(jsonPath("$.failures['" + missing + "']", containsString("not found")));

        assertEquals(Difficulty.HARD, quizRepository.findById(valid).get().getDifficulty());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/leaderboard with participants -> returns ordered list")
    void getQuizLeaderboard_basic() throws Exception {
        UUID quizId = createSampleQuiz();

        User alice = new User();
        alice.setUsername("alice");
        alice.setEmail("alice@example.com");
        alice.setHashedPassword("pw");
        alice.setActive(true);
        alice.setDeleted(false);
        userRepository.save(alice);

        User bob = new User();
        bob.setUsername("bob");
        bob.setEmail("bob@example.com");
        bob.setHashedPassword("pw");
        bob.setActive(true);
        bob.setDeleted(false);
        userRepository.save(bob);

        User carol = new User();
        carol.setUsername("carol");
        carol.setEmail("carol@example.com");
        carol.setHashedPassword("pw");
        carol.setActive(true);
        carol.setDeleted(false);
        userRepository.save(carol);

        var quiz = quizRepository.findById(quizId).get();

        Attempt a1 = new Attempt();
        a1.setUser(alice);
        a1.setQuiz(quiz);
        a1.setMode(AttemptMode.ALL_AT_ONCE);
        a1.setStatus(AttemptStatus.COMPLETED);
        a1.setTotalScore(95.0);

        Attempt a2 = new Attempt();
        a2.setUser(bob);
        a2.setQuiz(quiz);
        a2.setMode(AttemptMode.ALL_AT_ONCE);
        a2.setStatus(AttemptStatus.COMPLETED);
        a2.setTotalScore(85.0);

        Attempt a3 = new Attempt();
        a3.setUser(carol);
        a3.setQuiz(quiz);
        a3.setMode(AttemptMode.ALL_AT_ONCE);
        a3.setStatus(AttemptStatus.COMPLETED);
        a3.setTotalScore(75.0);

        attemptRepository.save(a1);
        attemptRepository.save(a2);
        attemptRepository.save(a3);

        mockMvc.perform(get("/api/v1/quizzes/{quizId}/leaderboard", quizId)
                        .param("top", "2")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].userId", is(alice.getId().toString())))
                .andExpect(jsonPath("$[0].bestScore", is(95.0)))
                .andExpect(jsonPath("$[1].userId", is(bob.getId().toString())));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/leaderboard -> handles ties and short list")
    void getQuizLeaderboard_tiesAndShortList() throws Exception {
        UUID quizId = createSampleQuiz();

        User u1 = new User();
        u1.setUsername("u1");
        u1.setEmail("u1@example.com");
        u1.setHashedPassword("pw");
        u1.setActive(true);
        u1.setDeleted(false);
        userRepository.save(u1);

        User u2 = new User();
        u2.setUsername("u2");
        u2.setEmail("u2@example.com");
        u2.setHashedPassword("pw");
        u2.setActive(true);
        u2.setDeleted(false);
        userRepository.save(u2);

        var quiz = quizRepository.findById(quizId).get();

        Attempt t1 = new Attempt();
        t1.setUser(u1);
        t1.setQuiz(quiz);
        t1.setMode(AttemptMode.ALL_AT_ONCE);
        t1.setStatus(AttemptStatus.COMPLETED);
        t1.setTotalScore(50.0);

        Attempt t2 = new Attempt();
        t2.setUser(u2);
        t2.setQuiz(quiz);
        t2.setMode(AttemptMode.ALL_AT_ONCE);
        t2.setStatus(AttemptStatus.COMPLETED);
        t2.setTotalScore(50.0);

        attemptRepository.save(t1);
        attemptRepository.save(t2);

        mockMvc.perform(get("/api/v1/quizzes/{quizId}/leaderboard", quizId)
                        .param("top", "5")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].bestScore", everyItem(is(50.0))))
                .andExpect(jsonPath("$[*].userId", containsInAnyOrder(
                        u1.getId().toString(), u2.getId().toString())));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/leaderboard when no attempts -> returns empty list")
    void getQuizLeaderboard_noAttempts_returnsEmpty() throws Exception {
        UUID quizId = createSampleQuiz();
        mockMvc.perform(get("/api/v1/quizzes/{quizId}/leaderboard", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/leaderboard with top<=0 returns empty list")
    void getQuizLeaderboard_negativeTop_returnsEmpty() throws Exception {
        UUID quizId = createSampleQuiz();
        User def = userRepository.findByUsername("admin").get();
        var quiz = quizRepository.findById(quizId).get();

        Attempt att = new Attempt();
        att.setUser(def);
        att.setQuiz(quiz);
        att.setMode(AttemptMode.ALL_AT_ONCE);
        att.setStatus(AttemptStatus.COMPLETED);
        att.setTotalScore(42.0);
        attemptRepository.save(att);

        mockMvc.perform(get("/api/v1/quizzes/{quizId}/leaderboard", quizId)
                        .param("top", "0")
                        .with(user(adminUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/{quizId}/leaderboard with non-existent quiz -> returns 404")
    void getQuizLeaderboard_nonexistentQuiz_returns404() throws Exception {
        UUID missing = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/quizzes/{quizId}/leaderboard", missing)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details[0]", containsString("Quiz " + missing + " not found")));
    }

    private UUID createSampleQuiz() throws Exception {
        CreateQuizRequest req = new CreateQuizRequest(
                "Sample", null,
                null, null,
                false, false,
                5, 2,
                categoryId,
                List.of()
        );
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());
    }

    private UUID createSampleQuiz(String name) throws Exception {
        CreateQuizRequest req = new CreateQuizRequest(
                name, null,
                null, null,
                false, false,
                5, 2,
                categoryId,
                List.of()
        );
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());
    }


}
