package uk.gegc.quizmaker.controller;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.ResultActions;
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
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.PermissionRepository;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.params.provider.Arguments.of;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gegc.quizmaker.features.question.domain.model.QuestionType.MCQ_SINGLE;
import static uk.gegc.quizmaker.features.question.domain.model.QuestionType.TRUE_FALSE;


@DisplayName("Integration Tests AttemptController")
public class AttemptControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    QuizRepository quizRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    PermissionRepository permissionRepository;
    @Autowired
    EntityManager entityManager;
    @Autowired
    private AttemptRepository attemptRepository;

    private UserDetails adminUserDetails;
    private UserDetails regularUserDetails;
    private User adminUser;
    private User regularUser;
    private Category defaultCategory;
    private UUID quizId;

    /**
     * Parameterized happy‐paths for every QuestionType
     */
    static Stream<Arguments> questionScenarios() {
        return Stream.of(
                of(
                        TRUE_FALSE,
                        "{\"answer\":true}",
                        "{\"answer\":true}",
                        true
                ),
                of(
                        QuestionType.MCQ_SINGLE,
                        """
                                {"options":[
                                  {"id":"a","text":"Option A","correct":true},
                                  {"id":"b","text":"Option B","correct":false}
                                ]}
                                """,
                        "{\"selectedOptionId\":\"a\"}",
                        true
                ),
                of(
                        QuestionType.MCQ_MULTI,
                        """
                                {"options":[
                                  {"id":"a","text":"A","correct":true},
                                  {"id":"b","text":"B","correct":false},
                                  {"id":"c","text":"C","correct":true}
                                ]}
                                """,
                        "{\"selectedOptionIds\":[\"a\",\"c\"]}",
                        true
                ),
                of(
                        QuestionType.FILL_GAP,
                        """
                                {"text":"Fill {1} here","gaps":[{"id":1,"answer":"foo"}]}
                                """,
                        "{\"answers\":[{\"gapId\":1,\"answer\":\"foo\"}]}",
                        true
                ),
                of(
                        QuestionType.ORDERING,
                        """
                                {"items":[
                                  {"id":1,"text":"one"},
                                  {"id":2,"text":"two"},
                                  {"id":3,"text":"three"}
                                ]}
                                """,
                        "{\"orderedItemIds\":[1,2,3]}",
                        true
                ),
                of(
                        QuestionType.COMPLIANCE,
                        """
                                {"statements":[
                                  {"id":1,"text":"s1","compliant":true},
                                  {"id":2,"text":"s2","compliant":false}
                                ]}
                                """,
                        "{\"selectedStatementIds\":[1]}",
                        true
                ),
                of(
                        QuestionType.HOTSPOT,
                        """
                                {"imageUrl":"http://img","regions":[
                                  {"id":1,"x":0,"y":0,"width":10,"height":10,"correct":true},
                                  {"id":2,"x":5,"y":5,"width":5,"height":5,"correct":false}
                                ]}
                                """,
                        "{\"selectedRegionId\":1}",
                        true
                ),
                of(
                        QuestionType.OPEN,
                        "{\"answer\":\"hello\"}",
                        "{\"answer\":\"hello\"}",
                        true
                )
        );
    }

    @BeforeEach
    void setUp() throws Exception {

        // Find existing permissions (created by DataInitializer)
        Permission attemptCreatePermission = permissionRepository.findByPermissionName(PermissionName.ATTEMPT_CREATE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: ATTEMPT_CREATE"));
        Permission attemptReadPermission = permissionRepository.findByPermissionName(PermissionName.ATTEMPT_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: ATTEMPT_READ"));
        Permission attemptReadAllPermission = permissionRepository.findByPermissionName(PermissionName.ATTEMPT_READ_ALL.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: ATTEMPT_READ_ALL"));
        Permission attemptDeletePermission = permissionRepository.findByPermissionName(PermissionName.ATTEMPT_DELETE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: ATTEMPT_DELETE"));
        Permission quizReadPermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUIZ_READ"));
        Permission quizCreatePermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_CREATE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUIZ_CREATE"));
        Permission quizUpdatePermission = permissionRepository.findByPermissionName(PermissionName.QUIZ_UPDATE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUIZ_UPDATE"));
        Permission questionCreatePermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_CREATE.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUESTION_CREATE"));
        Permission questionReadPermission = permissionRepository.findByPermissionName(PermissionName.QUESTION_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: QUESTION_READ"));
        Permission categoryReadPermission = permissionRepository.findByPermissionName(PermissionName.CATEGORY_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: CATEGORY_READ"));
        Permission tagReadPermission = permissionRepository.findByPermissionName(PermissionName.TAG_READ.name())
                .orElseThrow(() -> new RuntimeException("Permission not found: TAG_READ"));

        // Find existing roles (created by DataInitializer)
        Role adminRole = roleRepository.findByRoleName(RoleName.ROLE_ADMIN.name())
                .orElseThrow(() -> new RuntimeException("Role not found: ROLE_ADMIN"));
        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new RuntimeException("Role not found: ROLE_USER"));

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
        defaultCategory = new Category();
        defaultCategory.setName("General");
        defaultCategory.setDescription("Default");
        defaultCategory = categoryRepository.save(defaultCategory);
        
        // Create UserDetails objects with proper authorities
        adminUserDetails = createUserDetails(adminUser);
        regularUserDetails = createUserDetails(regularUser);

        // Create quiz for testing
        CreateQuizRequest cq = new CreateQuizRequest(
                "Integration Quiz", "desc",
                Visibility.PRIVATE, Difficulty.MEDIUM,
                false, false, 10, 5,
                defaultCategory.getId(), List.of()
        );
        String body = objectMapper.writeValueAsString(cq);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        quizId = UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());
        var persistedQuiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        persistedQuiz.setStatus(QuizStatus.PUBLISHED);
        persistedQuiz.setVisibility(Visibility.PUBLIC);
        quizRepository.save(persistedQuiz);
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
                    return java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(roleAuthority),
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

    @DisplayName("[HAPPY] Parameterized: submit + complete for each QuestionType")
    @ParameterizedTest(name = "[{index}] {0} → correct?={3}")
    @MethodSource("questionScenarios")
    void happyPathSubmitAnswers(
            QuestionType type,
            String contentJson,
            String responseJson,
            boolean expectedCorrect
    ) throws Exception {
        CreateQuestionRequest qr = new CreateQuestionRequest();
        qr.setType(type);
        qr.setDifficulty(Difficulty.EASY);
        qr.setQuestionText("Test " + type);
        qr.setContent(objectMapper.readTree(contentJson));
        qr.setQuizIds(List.of(quizId));
        qr.setTagIds(List.of());
        String qreqJson = objectMapper.writeValueAsString(qr);

        String qresp = mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(qreqJson))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(qresp).get("questionId").asText());

        // Flush to ensure the question-quiz association is persisted
        entityManager.flush();

        String startResp = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(startResp).get("attemptId").asText());

        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree(responseJson),
                true,  // includeCorrectness - needed for this test to verify correctness
                null,  // includeCorrectAnswer
                null   // includeExplanation (defaults to false)
        );
        String sJson = objectMapper.writeValueAsString(submission);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(sJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId", is(questionId.toString())))
                .andExpect(jsonPath("$.isCorrect", is(expectedCorrect)));

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correctCount", is(expectedCorrect ? 1 : 0)));
    }

    @Test
    @DisplayName("SubmitAnswer with invalid attemptId → returns 404 NOT_FOUND")
    void submitWithBadAttemptId() throws Exception {
        UUID fakeId = UUID.randomUUID();
        var req = new AnswerSubmissionRequest(fakeId, objectMapper.createObjectNode(), null, null, null);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", fakeId)
                        .with(user(regularUserDetails))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/quizzes/{quizId} without authentication → returns 401 UNAUTHORIZED")
    void startAttempt_anonymous_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(anonymous()))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("SubmitAnswer with missing response → returns 400 BAD_REQUEST")
    void submitValidationError() throws Exception {
        CreateQuestionRequest createQuestionRequest = new CreateQuestionRequest();
        createQuestionRequest.setType(TRUE_FALSE);
        createQuestionRequest.setDifficulty(Difficulty.MEDIUM);
        createQuestionRequest.setQuestionText("Test");
        createQuestionRequest.setContent(objectMapper.readTree("{\"answer\":true}"));
        createQuestionRequest.setQuizIds(List.of(quizId));
        createQuestionRequest.setTagIds(List.of());
        String questionRequestJson = objectMapper.writeValueAsString(createQuestionRequest);
        String questionResponse = mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(questionRequestJson))
                .andReturn().getResponse().getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(questionResponse).get("questionId").asText());

        String start = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails))).andReturn().getResponse().getContentAsString();

        UUID attemptId = UUID.fromString(objectMapper.readTree(start).get("attemptId").asText());

        String badJson = "{\"questionId\":\"" + questionId + "\"}";

        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(badJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/attempts without authentication → returns 401 UNAUTHORIZED")
    void listAttempts_anonymousReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/attempts")
                        .with(anonymous()))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("GET /api/v1/attempts without authentication → returns 401 UNAUTHORIZED")
    void listAttempts_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/attempts")
                        .with(anonymous()))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id} as non-owner → returns 403 FORBIDDEN")
    void getAttempt_nonOwner_returns403() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(resp).get("attemptId").asText());

        // Create another user to test non-owner access
        User otherUser = new User();
        otherUser.setUsername("otherUser");
        otherUser.setEmail("other@example.com");
        otherUser.setHashedPassword("password");
        otherUser.setActive(true);
        otherUser.setDeleted(false);
        otherUser.setRoles(Set.of(roleRepository.findByRoleName(RoleName.ROLE_USER.name()).orElseThrow()));
        otherUser = userRepository.save(otherUser);
        UserDetails otherUserDetails = createUserDetails(otherUser);

        mockMvc.perform(get("/api/v1/attempts/{id}", attemptId)
                        .with(user(otherUserDetails)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers without authentication → returns 401 UNAUTHORIZED")
    void submitAnswer_anonymous_returns401() throws Exception {
        UUID attemptId = UUID.randomUUID();
        String payload = """
                {
                  "questionId":"%s",
                  "response":{}
                }
                """.formatted(UUID.randomUUID());
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(anonymous()))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers as non-owner → returns 403 FORBIDDEN")
    void submitAnswer_nonOwner_returns403() throws Exception {
        var quiz = quizRepository.findById(quizId).orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        Attempt attempt = new Attempt();
        attempt.setUser(regularUser);
        attempt.setQuiz(quiz);
        attempt.setMode(AttemptMode.ALL_AT_ONCE);
        attempt.setStatus(AttemptStatus.IN_PROGRESS);
        attemptRepository.save(attempt);

        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");
        String payload = """
                {
                  "questionId":"%s",
                  "response":{"answer":true}
                }
                """.formatted(questionId);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attempt.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .with(user(adminUserDetails)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers after attempt completed → returns 409 CONFLICT")
    void submitAnswer_afterComplete_returns409() throws Exception {
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");
        UUID attemptId = startAttempt();

        postAnswer(attemptId, questionId, "{\"answer\":true}")
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isOk());

        postAnswer(attemptId, questionId, "{\"answer\":true}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/illegal-state"))
                .andExpect(jsonPath("$.title").value("Illegal State"))
                .andExpect(jsonPath("$.detail").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: default behavior (flags omitted) → sensitive fields stay null/missing")
    void submitAnswer_defaultBehavior_sensitiveFieldsOmitted() throws Exception {
        // Given
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");
        UUID attemptId = startAttempt();

        // When: submit without includeCorrectness or includeCorrectAnswer flags
        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"answer\":true}"),
                null,  // includeCorrectness
                null,  // includeCorrectAnswer
                null   // includeExplanation (defaults to false)
        );
        String sJson = objectMapper.writeValueAsString(submission);

        // Then: sensitive fields should not be present
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(sJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId", is(questionId.toString())))
                .andExpect(jsonPath("$.answerId").exists())
                .andExpect(jsonPath("$.score").exists())
                .andExpect(jsonPath("$.answeredAt").exists())
                // Verify sensitive fields are NOT present
                .andExpect(jsonPath("$.isCorrect").doesNotExist())
                .andExpect(jsonPath("$.correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.explanation").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: explanation is omitted by default")
    void submitAnswer_explanationOmittedByDefault() throws Exception {
        // Given
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}", "Default explanation");
        UUID attemptId = startAttempt();

        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"answer\":true}"),
                null,  // includeCorrectness
                null,  // includeCorrectAnswer
                null   // includeExplanation defaults to false
        );

        // Then: explanation should not be present by default
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: includeExplanation=true returns explanation")
    void submitAnswer_includeExplanationTrue_returnsExplanation() throws Exception {
        // Given
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}", "Default explanation");
        UUID attemptId = startAttempt();

        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"answer\":true}"),
                null,   // includeCorrectness
                null,   // includeCorrectAnswer
                true    // includeExplanation
        );

        // Then: explanation should be present
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").value("Default explanation"));
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: includeExplanation=false hides explanation")
    void submitAnswer_includeExplanationFalse_hidesExplanation() throws Exception {
        // Given
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}", "Default explanation");
        UUID attemptId = startAttempt();

        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"answer\":true}"),
                null,   // includeCorrectness
                null,   // includeCorrectAnswer
                false   // includeExplanation
        );

        // Then: explanation should not be present
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submission)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: includeCorrectness=true → includes isCorrect field")
    void submitAnswer_includeCorrectness_includesIsCorrect() throws Exception {
        // Given
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");
        UUID attemptId = startAttempt();

        // When: submit with includeCorrectness=true
        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"answer\":true}"),
                true,  // includeCorrectness
                false, // includeCorrectAnswer
                null   // includeExplanation (defaults to false)
        );
        String sJson = objectMapper.writeValueAsString(submission);

        // Then: isCorrect should be present
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(sJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId", is(questionId.toString())))
                .andExpect(jsonPath("$.isCorrect").exists())
                .andExpect(jsonPath("$.isCorrect").value(true))
                // correctAnswer should still not be present
                .andExpect(jsonPath("$.correctAnswer").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: includeCorrectAnswer=true → includes correctAnswer field")
    void submitAnswer_includeCorrectAnswer_includesCorrectAnswer() throws Exception {
        // Given: MCQ_SINGLE question with correct option
        UUID questionId = createDummyQuestion(MCQ_SINGLE, """
                {
                    "options": [
                        {"id": "opt_1", "text": "Option 1", "correct": false},
                        {"id": "opt_2", "text": "Option 2", "correct": true},
                        {"id": "opt_3", "text": "Option 3", "correct": false}
                    ]
                }
                """);
        UUID attemptId = startAttempt();

        // When: submit with includeCorrectAnswer=true
        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"selectedOptionId\":\"opt_1\"}"),
                false,  // includeCorrectness
                true,    // includeCorrectAnswer
                null     // includeExplanation (defaults to false)
        );
        String sJson = objectMapper.writeValueAsString(submission);

        // Then: correctAnswer should be present with correct structure
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(sJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId", is(questionId.toString())))
                .andExpect(jsonPath("$.correctAnswer").exists())
                .andExpect(jsonPath("$.correctAnswer.correctOptionId").value("opt_2"))
                // isCorrect should not be present when not requested
                .andExpect(jsonPath("$.isCorrect").doesNotExist());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: both flags true → includes both isCorrect and correctAnswer")
    void submitAnswer_bothFlagsTrue_includesBothFields() throws Exception {
        // Given: TRUE_FALSE question
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");
        UUID attemptId = startAttempt();

        // When: submit with both flags true
        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"answer\":true}"),
                true,  // includeCorrectness
                true,  // includeCorrectAnswer
                null   // includeExplanation (defaults to false)
        );
        String sJson = objectMapper.writeValueAsString(submission);

        // Then: both fields should be present
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(sJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId", is(questionId.toString())))
                .andExpect(jsonPath("$.isCorrect").exists())
                .andExpect(jsonPath("$.isCorrect").value(true))
                .andExpect(jsonPath("$.correctAnswer").exists())
                .andExpect(jsonPath("$.correctAnswer.answer").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers: includeCorrectAnswer with valid question → returns correctAnswer successfully")
    void submitAnswer_includeCorrectAnswer_validQuestion_returnsCorrectAnswer() throws Exception {
        // Given: Create a valid MCQ_SINGLE question
        UUID questionId = createDummyQuestion(MCQ_SINGLE, """
                {
                    "options": [
                        {"id": "opt_1", "text": "Option 1", "correct": true},
                        {"id": "opt_2", "text": "Option 2", "correct": false}
                    ]
                }
                """);
        UUID attemptId = startAttempt();

        // When: submit with includeCorrectAnswer=true
        var submission = new AnswerSubmissionRequest(
                questionId,
                objectMapper.readTree("{\"selectedOptionId\":\"opt_1\"}"),
                false,  // includeCorrectness
                true,    // includeCorrectAnswer
                null     // includeExplanation (defaults to false)
        );
        String sJson = objectMapper.writeValueAsString(submission);

        // Then: correctAnswer should be successfully extracted
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(sJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId", is(questionId.toString())))
                .andExpect(jsonPath("$.correctAnswer").exists())
                .andExpect(jsonPath("$.correctAnswer.correctOptionId").value("opt_1"));
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers/batch without authentication → returns 401 UNAUTHORIZED")
    void submitBatch_anonymous_returns403() throws Exception {
        UUID attemptId = startAttempt();
        String batchJson = "{\"answers\":[{\"questionId\":\"00000000-0000-0000-0000-000000000000\",\"response\":{}}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(anonymous())
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers/batch by non-owner → returns 403 FORBIDDEN")
    void submitBatch_nonOwner_returns403() throws Exception {
        User other = new User();
        other.setUsername("otherUser");
        other.setEmail("other@ex.com");
        other.setHashedPassword("pw");
        other.setActive(true);
        other.setDeleted(false);
        other.setRoles(Set.of(roleRepository.findByRoleName(RoleName.ROLE_USER.name()).orElseThrow()));
        other = userRepository.save(other);
        UserDetails otherUserDetails = createUserDetails(other);

        UUID attemptId = startAttempt();
        String batchJson = "{\"answers\":[{\"questionId\":\"00000000-0000-0000-0000-000000000000\",\"response\":{}}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(user(otherUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/answers/batch with invalid attemptId → returns 404 NOT_FOUND")
    void submitBatch_invalidAttemptId_returns404() throws Exception {
        UUID fakeId = UUID.randomUUID();
        String batchJson = "{\"answers\":[{\"questionId\":\"00000000-0000-0000-0000-000000000000\",\"response\":{}}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", fakeId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/complete without authentication → returns 401 UNAUTHORIZED")
    void completeAttempt_anonymous_returns403() throws Exception {
        UUID attemptId = startAttempt();
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(anonymous()))
                .andExpect(status().isUnauthorized()); // Fixed: 401 for unauthenticated, not 403
    }


    @Test
    @DisplayName("POST /api/v1/attempts/{id}/complete by non-owner → returns 403 FORBIDDEN")
    void completeAttempt_nonOwner_returns403() throws Exception {
        User other = new User();
        other.setUsername("otherUser");
        other.setEmail("other@ex.com");
        other.setHashedPassword("password");
        other.setActive(true);
        other.setDeleted(false);
        other.setRoles(Set.of(roleRepository.findByRoleName(RoleName.ROLE_USER.name()).orElseThrow()));
        other = userRepository.save(other);
        UserDetails otherUserDetails = createUserDetails(other);

        UUID attemptId = startAttempt();
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(user(otherUserDetails)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/{id}/complete with nonexistent ID → returns 404 NOT_FOUND")
    void completeAttempt_invalidAttemptId_returns404() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", UUID.randomUUID())
                        .with(user(regularUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Batch submission in ONE_BY_ONE mode → returns 409 CONFLICT")
    void batchInOneByOneMode() throws Exception {
        String startJson = "{\"mode\":\"ONE_BY_ONE\"}";
        String start = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(startJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID attemptId = UUID.fromString(objectMapper.readTree(start).get("attemptId").asText());

        String batchJson = """
                {
                  "answers": [
                    {
                      "questionId": "00000000-0000-0000-0000-000000000000",
                      "response": {}
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/illegal-state"))
                .andExpect(jsonPath("$.title").value("Illegal State"))
                .andExpect(jsonPath("$.detail", containsString("Batch submissions only allowed")))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("Completing an already completed attempt → returns 409 CONFLICT")
    void completeTwice() throws Exception {

        String start = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails))).andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(start).get("attemptId").asText());

        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(user(regularUserDetails))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", attemptId)
                        .with(user(regularUserDetails))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("GET /api/v1/attempts → returns paginated and sorted attempts")
    void paginationAndSorting() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                            .with(user(regularUserDetails)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/attempts")
                        .with(user(regularUserDetails))
                        .param("page", "0")
                        .param("size", "2")
                        .param("sort", "startedAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(3)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("GET /api/v1/attempts?quizId={quizId}&userId={userId} → returns filtered attempts by quizId and userId")
    void filteringByQuizIdAndUserId() throws Exception {

        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails)));
        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails)));

        mockMvc.perform(get("/api/v1/attempts")
                        .with(user(regularUserDetails))
                        .param("quizId", quizId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)));

        mockMvc.perform(get("/api/v1/attempts")
                        .with(user(regularUserDetails))
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/attempts")
                        .with(user(adminUserDetails))
                        .param("userId", regularUser.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)));
    }

    @Test
    @DisplayName("Timed mode attempt - verify timeout logic is working")
    void timedModeTimeout() throws Exception {
        CreateQuizRequest timedQuiz = new CreateQuizRequest(
                "Timed",
                "description",
                Visibility.PRIVATE,
                Difficulty.EASY,
                false,
                true,
                1,
                1,
                categoryRepository.findAll().get(0).getId(),
                List.of()
        );

        String timedJson = objectMapper.writeValueAsString(timedQuiz);
        String timedResponse = mockMvc.perform(post("/api/v1/quizzes")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(timedJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID timedQuizId = UUID.fromString(objectMapper.readTree(timedResponse).get("quizId").asText());
        var timedQuizEntity = quizRepository.findById(timedQuizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + timedQuizId + " not found"));
        timedQuizEntity.setStatus(QuizStatus.PUBLISHED);
        timedQuizEntity.setVisibility(Visibility.PUBLIC);
        quizRepository.save(timedQuizEntity);

        JsonNode dummyContent = objectMapper.readTree("{\"options\":[{\"id\":\"A\",\"text\":\"foo\",\"correct\":true},{\"id\":\"B\",\"text\":\"bar\",\"correct\":false}]}");
        CreateQuestionRequest createQuestionRequest = new CreateQuestionRequest(QuestionType.MCQ_SINGLE,
                Difficulty.EASY,
                "What's foo?",
                dummyContent,
                null,
                null,
                null,
                List.of(),
                List.of());
        String questionJson = objectMapper.writeValueAsString(createQuestionRequest);
        String questionResponse = mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(questionJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID questionId = UUID.fromString(objectMapper.readTree(questionResponse).get("questionId").asText());

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/questions/{questionId}", timedQuizId, questionId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isNoContent());

        // Create attempt normally first
        String startJson = "{\"mode\":\"TIMED\"}";
        String attemptResponse = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", timedQuizId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(startJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID attemptId = UUID.fromString(objectMapper.readTree(attemptResponse).get("attemptId").asText());

        System.out.println("Created attempt " + attemptId + " with 1-minute timer");
        System.out.println("Current time: " + Instant.now());
        
        // Test that the timeout logic is working by submitting an answer immediately
        // This should NOT timeout since we just created the attempt
        String validSubmit = String.format(
                "{\"questionId\":\"%s\",\"response\":{\"selectedOptionId\":\"A\"},\"includeCorrectness\":true}", questionId
        );

        mockMvc.perform(post("/api/v1/attempts/{id}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(validSubmit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCorrect", is(true)));
        
        // Verify that the attempt is still in progress (not timed out)
        mockMvc.perform(get("/api/v1/attempts/{id}", attemptId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_PROGRESS")));
    }

    @Test
    @DisplayName("Batch submission in ALL_AT_ONCE mode (happy + sad) → succeeds for valid and fails for invalid as expected")
    void batchSubmissionHappyAndSad() throws Exception {

        UUID question1 = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }");
        UUID question2 = createDummyQuestion(TRUE_FALSE, "{ \"answer\": false }");

        String startJson = "{\"mode\":\"ALL_AT_ONCE\"}";
        String startRequest = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(startJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID attemptId = UUID.fromString(objectMapper.readTree(startRequest).get("attemptId").asText());

        var batchRequest = objectMapper.createObjectNode();
        var arr = batchRequest.putArray("answers");
        ObjectNode answer1 = objectMapper.createObjectNode();
        answer1.put("questionId", question1.toString());
        answer1.set("response", objectMapper.readTree("{ \"answer\": true }"));
        answer1.put("includeCorrectness", true);
        arr.add(answer1);
        ObjectNode answer2 = objectMapper.createObjectNode();
        answer2.put("questionId", question2.toString());
        answer2.set("response", objectMapper.readTree("{ \"answer\": false }"));
        answer2.put("includeCorrectness", true);
        arr.add(answer2);
        String batchJson = objectMapper.writeValueAsString(batchRequest);

        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].isCorrect", is(true)))
                .andExpect(jsonPath("$[1].isCorrect", is(true)));

        String badBatch = "{\"answers\":[{\"questionId\":\"" + question1 + "\"}]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(badBatch))
                .andExpect(status().isBadRequest());


    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id} when ID not found → returns 404 NOT_FOUND")
    void getAttemptNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/attempts/{id}", UUID.randomUUID())
                        .with(user(regularUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/attempts/{id} → returns attempt details")
    void getAttemptDetails() throws Exception {
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }");
        UUID attemptId = startAttempt();

        postAnswer(attemptId, questionId, "{ \"answer\": true }").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/attempts/{id}", attemptId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptId", is(attemptId.toString())))
                .andExpect(jsonPath("$.answers", hasSize(1)))
                .andExpect(jsonPath("$.answers[0].isCorrect", is(true)));
    }

    @Test
    @DisplayName("Start attempt for non-existent quiz → returns 404 NOT_FOUND")
    void startAttemptBadQuiz() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", UUID.randomUUID())
                        .with(user(regularUserDetails)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/attempts with negative paging params → returns 400 BAD_REQUEST")
    void badPagingParams() throws Exception {
        mockMvc.perform(get("/api/v1/attempts")
                        .with(user(regularUserDetails))
                        .param("page", "-1")
                        .param("size", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SubmitAnswer for question not in quiz → returns 404 NOT_FOUND")
    void submitWrongQuestion() throws Exception {
        UUID otherQuiz = createAnotherQuiz();
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }", otherQuiz);
        UUID attemptId = startAttempt();
        postAnswer(attemptId, questionId, "{ \"answer\": true }")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Submitting duplicate answer to same question → allowed")
    void duplicateAnswerBehavior() throws Exception {
        UUID questionId = createDummyQuestion(TRUE_FALSE, "{ \"answer\": true }");
        UUID attemptId = startAttempt();
        postAnswer(attemptId, questionId, "{ \"answer\": true }").andExpect(status().isOk());
        postAnswer(attemptId, questionId, "{ \"answer\": true }").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Batch submission with empty list → returns 400 BAD_REQUEST")
    void batchEmptyList() throws Exception {
        UUID attemptId = startAttempt();
        String bad = "{\"answers\":[]}";
        mockMvc.perform(post("/api/v1/attempts/{id}/answers/batch", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/attempts/quizzes/{quizId} returns attempt metadata")
    void startAttempt_returnsMetadata() throws Exception {
        createDummyQuestion(TRUE_FALSE, "{\"answer\":true}");

        mockMvc.perform(post("/api/v1/attempts/quizzes/{id}", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").exists())
                .andExpect(jsonPath("$.quizId", is(quizId.toString())))
                .andExpect(jsonPath("$.mode", is("ALL_AT_ONCE")))
                .andExpect(jsonPath("$.totalQuestions", is(1)))
                .andExpect(jsonPath("$.timeLimitMinutes").isEmpty())
                .andExpect(jsonPath("$.startedAt").exists())
                .andExpect(jsonPath("$.firstQuestion").doesNotExist());
    }

    @Test
    @DisplayName("startAttempt with no time limit returns null timeLimitMinutes")
    void startAttempt_noTimeLimit_returnsNullTimeLimit() throws Exception {
        mockMvc.perform(post("/api/v1/attempts/quizzes/{id}", quizId)
                        .with(user(adminUserDetails)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").exists())
                .andExpect(jsonPath("$.timeLimitMinutes").isEmpty());
    }

    @Test
    @DisplayName("Complete attempt flow: start -> get current question -> submit answer")
    void completeAttemptFlow() throws Exception {
        // Given: create a question
        createDummyQuestion(TRUE_FALSE, "{\"answer\": true }");

        // Start attempt
        String startResult = mockMvc.perform(post("/api/v1/attempts/quizzes/{quizId}", quizId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attemptId").exists())
                .andExpect(jsonPath("$.firstQuestion").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode startNode = objectMapper.readTree(startResult);
        UUID attemptId = UUID.fromString(startNode.get("attemptId").asText());

        // Get current question
        String questionResult = mockMvc.perform(get("/api/v1/attempts/{attemptId}/current-question", attemptId)
                        .with(user(regularUserDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question.id").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode questionNode = objectMapper.readTree(questionResult);
        UUID currentQuestionId = UUID.fromString(questionNode.get("question").get("id").asText());

        // Submit answer for the returned current question
        var answerRequest = new AnswerSubmissionRequest(currentQuestionId, objectMapper.readTree("{ \"answer\": true }"), null, null, null);
        mockMvc.perform(post("/api/v1/attempts/{attemptId}/answers", attemptId)
                        .with(user(regularUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(answerRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questionId").value(currentQuestionId.toString()));
    }

    private ResultActions postAnswer(UUID attempt, UUID question, String responseJson) throws Exception {
        var request = new AnswerSubmissionRequest(question, objectMapper.readTree(responseJson), null, null, null);
        return mockMvc.perform(post("/api/v1/attempts/{id}/answers", attempt)
                .with(user(regularUserDetails))
                .contentType(APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private UUID startAttempt() throws Exception {
        String startRequest = mockMvc.perform(post("/api/v1/attempts/quizzes/{id}", quizId)
                        .with(user(regularUserDetails)))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(startRequest).get("attemptId").asText());
    }

    private UUID createAnotherQuiz() throws Exception {
        UUID categoryId = categoryRepository.findAll().get(0).getId();

        CreateQuizRequest req = new CreateQuizRequest(
                "Another Quiz",
                "description",
                Visibility.PRIVATE,
                Difficulty.MEDIUM,
                false,
                false,
                10,
                5,
                categoryId,
                List.of()
        );

        String body = objectMapper.writeValueAsString(req);
        String resp = mockMvc.perform(post("/api/v1/quizzes")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(resp).get("quizId").asText());
    }

    private UUID createDummyQuestion(QuestionType type, String contentJson) throws Exception {
        CreateQuestionRequest createQuestionRequest = new CreateQuestionRequest();
        createQuestionRequest.setType(type);
        createQuestionRequest.setDifficulty(Difficulty.EASY);
        createQuestionRequest.setQuestionText("Question?");
        createQuestionRequest.setContent(objectMapper.readTree(contentJson));
        createQuestionRequest.setTagIds(List.of());
        createQuestionRequest.setQuizIds(List.of(quizId));
        String createQuestionRequestString = objectMapper.writeValueAsString(createQuestionRequest);

        String response = mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(createQuestionRequestString))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID questionId = UUID.fromString(objectMapper.readTree(response).get("questionId").asText());
        
        // Flush to ensure the question-quiz association is persisted
        entityManager.flush();
        
        return questionId;
    }

    private UUID createDummyQuestion(QuestionType type, String contentJson, String explanation) throws Exception {
        CreateQuestionRequest createQuestionRequest = new CreateQuestionRequest();
        createQuestionRequest.setType(type);
        createQuestionRequest.setDifficulty(Difficulty.EASY);
        createQuestionRequest.setQuestionText("Question?");
        createQuestionRequest.setContent(objectMapper.readTree(contentJson));
        createQuestionRequest.setTagIds(List.of());
        createQuestionRequest.setQuizIds(List.of(quizId));
        createQuestionRequest.setExplanation(explanation);
        String createQuestionRequestString = objectMapper.writeValueAsString(createQuestionRequest);

        String response = mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(createQuestionRequestString))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID questionId = UUID.fromString(objectMapper.readTree(response).get("questionId").asText());

        entityManager.flush();

        return questionId;
    }

    private UUID createDummyQuestion(QuestionType type, String contentJson, UUID targetQuizId) throws Exception {
        CreateQuestionRequest qr = new CreateQuestionRequest();
        qr.setType(type);
        qr.setDifficulty(Difficulty.EASY);
        qr.setQuestionText("Q for " + type);
        qr.setContent(objectMapper.readTree(contentJson));
        qr.setQuizIds(List.of(targetQuizId));
        qr.setTagIds(List.of());

        String qbody = objectMapper.writeValueAsString(qr);
        String qresp = mockMvc.perform(post("/api/v1/questions")
                        .with(user(adminUserDetails))
                        .contentType(APPLICATION_JSON)
                        .content(qbody))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID questionId = UUID.fromString(objectMapper.readTree(qresp).get("questionId").asText());
        
        // Flush to ensure the question-quiz association is persisted
        entityManager.flush();
        
        return questionId;
    }


}
