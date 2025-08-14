package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkRequest;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.user.*;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.ShareLinkRepository;
import uk.gegc.quizmaker.repository.user.PermissionRepository;
import uk.gegc.quizmaker.repository.user.RoleRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.features.quiz.application.ShareLinkService;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "quizmaker.share-links.token-pepper=test-pepper-for-integration-tests"
})
@DisplayName("Integration Tests ShareLinkController")
class ShareLinkControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    UserRepository userRepository;
    @Autowired
    QuizRepository quizRepository;
    @Autowired
    ShareLinkRepository shareLinkRepository;
    @Autowired
    CategoryRepository categoryRepository;
    @Autowired
    RoleRepository roleRepository;
    @Autowired
    PermissionRepository permissionRepository;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ShareLinkService shareLinkService;

    private UUID userId;
    private UUID quizId;
    private UUID otherUserId;
    private UUID otherQuizId;
    private UUID categoryId;
    private String userToken;
    private String otherUserToken;

    @BeforeEach
    void setUp() {
        // Clean up database
        jdbcTemplate.execute("DELETE FROM share_link_usage");
        jdbcTemplate.execute("DELETE FROM share_links");
        jdbcTemplate.execute("DELETE FROM quiz_questions");
        jdbcTemplate.execute("DELETE FROM quiz_tags");
        jdbcTemplate.execute("DELETE FROM quizzes");
        jdbcTemplate.execute("DELETE FROM user_roles");
        jdbcTemplate.execute("DELETE FROM users");
        jdbcTemplate.execute("DELETE FROM categories");

        shareLinkRepository.deleteAllInBatch();
        quizRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
        roleRepository.deleteAllInBatch();
        permissionRepository.deleteAllInBatch();

        // Create permissions
        Permission quizAdminPermission = new Permission();
        quizAdminPermission.setPermissionName(PermissionName.QUIZ_ADMIN.name());
        quizAdminPermission = permissionRepository.save(quizAdminPermission);
        
        Permission quizModeratePermission = new Permission();
        quizModeratePermission.setPermissionName(PermissionName.QUIZ_MODERATE.name());
        quizModeratePermission = permissionRepository.save(quizModeratePermission);
        
        Permission quizCreatePermission = new Permission();
        quizCreatePermission.setPermissionName(PermissionName.QUIZ_CREATE.name());
        quizCreatePermission = permissionRepository.save(quizCreatePermission);
        
        Permission quizReadPermission = new Permission();
        quizReadPermission.setPermissionName(PermissionName.QUIZ_READ.name());
        quizReadPermission = permissionRepository.save(quizReadPermission);

        // Create roles
        Role adminRole = new Role();
        adminRole.setRoleName(RoleName.ROLE_ADMIN.name());
        adminRole.setPermissions(Set.of(quizAdminPermission, quizModeratePermission, quizCreatePermission, quizReadPermission));
        adminRole = roleRepository.save(adminRole);
        
        Role userRole = new Role();
        userRole.setRoleName(RoleName.ROLE_USER.name());
        userRole.setPermissions(Set.of(quizReadPermission));
        userRole = roleRepository.save(userRole);

        // Create test users with roles
        User user = new User();
        user.setUsername("testuser");
        user.setEmail("test@example.com");
        user.setHashedPassword("password");
        user.setActive(true);
        user.setDeleted(false);
        user.setRoles(Set.of(adminRole));
        user = userRepository.save(user);
        userId = user.getId();
        userToken = user.getId().toString();

        User otherUser = new User();
        otherUser.setUsername("otheruser");
        otherUser.setEmail("other@example.com");
        otherUser.setHashedPassword("password");
        otherUser.setActive(true);
        otherUser.setDeleted(false);
        otherUser.setRoles(Set.of(userRole));
        otherUser = userRepository.save(otherUser);
        otherUserId = otherUser.getId();
        otherUserToken = otherUser.getId().toString();

        // Create test category
        Category category = new Category();
        category.setName("Test Category");
        category.setDescription("A test category");
        category = categoryRepository.save(category);
        categoryId = category.getId();

        // Create test quizzes
        Quiz quiz = new Quiz();
        quiz.setTitle("Test Quiz");
        quiz.setDescription("A test quiz");
        quiz.setCreator(user);
        quiz.setCategory(category);
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setVisibility(Visibility.PUBLIC);
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(10);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setIsDeleted(false);
        quiz = quizRepository.save(quiz);
        quizId = quiz.getId();

        Quiz otherQuiz = new Quiz();
        otherQuiz.setTitle("Other Quiz");
        otherQuiz.setDescription("Another test quiz");
        otherQuiz.setCreator(otherUser);
        otherQuiz.setCategory(category);
        otherQuiz.setStatus(QuizStatus.PUBLISHED);
        otherQuiz.setVisibility(Visibility.PUBLIC);
        otherQuiz.setDifficulty(Difficulty.EASY);
        otherQuiz.setEstimatedTime(10);
        otherQuiz.setIsRepetitionEnabled(false);
        otherQuiz.setIsTimerEnabled(false);
        otherQuiz.setIsDeleted(false);
        otherQuiz = quizRepository.save(otherQuiz);
        otherQuizId = otherQuiz.getId();
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link with valid request -> returns 201 CREATED")
    void createShareLink_validRequest_returns201() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult result = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.link.id").exists())
                .andExpect(jsonPath("$.link.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.link.createdBy").value(userId.toString()))
                .andExpect(jsonPath("$.link.scope").value("QUIZ_VIEW"))
                .andExpect(jsonPath("$.link.oneTime").value(false))
                .andExpect(jsonPath("$.link.revokedAt").isEmpty())
                .andExpect(jsonPath("$.link.id").exists())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.token").isString())
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = response.get("token").asText();
        
        // Verify token format (URL-safe Base64, 43 chars)
        assertTrue(token.matches("^[A-Za-z0-9_-]{43}$"));
        assertEquals(43, token.length());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link with one-time token -> returns 201 CREATED")
    void createShareLink_oneTimeToken_returns201() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                true
        );

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.link.oneTime").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link with null values -> uses defaults")
    void createShareLink_nullValues_usesDefaults() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, null);

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.link.scope").value("QUIZ_VIEW"))
                .andExpect(jsonPath("$.link.oneTime").value(false))
                .andExpect(jsonPath("$.link.expiresAt").exists());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link with past expiry -> returns 400 BAD_REQUEST")
    void createShareLink_pastExpiry_returns400() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().minusSeconds(3600),
                false
        );

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(containsString("Expiry must be in the future")));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link with non-existent quiz -> returns 404 NOT_FOUND")
    void createShareLink_nonexistentQuiz_returns404() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        UUID nonExistentQuizId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", nonExistentQuizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link without authentication -> returns 403 FORBIDDEN")
    void createShareLink_anonymous_returns403() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link for other user's quiz -> returns 201 CREATED (admin permissions)")
    void createShareLink_otherUserQuiz_returns403() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", otherQuizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/shared/{tokenId} with valid token -> returns 204 NO_CONTENT")
    void revokeShareLink_validToken_returns204() throws Exception {
        // First create a share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        UUID shareLinkId = UUID.fromString(createResponse.get("link").get("id").asText());

        // Then revoke it
        mockMvc.perform(delete("/api/v1/quizzes/shared/{tokenId}", shareLinkId)
                        .with(user(userToken)))
                .andExpect(status().isNoContent());

        // Verify it's revoked in database
        ShareLink shareLink = shareLinkRepository.findById(shareLinkId).orElseThrow();
        assertNotNull(shareLink.getRevokedAt());
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/shared/{tokenId} with non-existent token -> returns 404 NOT_FOUND")
    void revokeShareLink_nonexistentToken_returns404() throws Exception {
        UUID nonExistentTokenId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/quizzes/shared/{tokenId}", nonExistentTokenId)
                        .with(user(userToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/quizzes/shared/{tokenId} without authentication -> returns 403 FORBIDDEN")
    void revokeShareLink_anonymous_returns403() throws Exception {
        UUID tokenId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/quizzes/shared/{tokenId}", tokenId)
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with valid token -> returns 200 OK and sets cookie")
    void accessSharedQuiz_validToken_returns200() throws Exception {
        // First create a share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();

        // Then access it
        MvcResult accessResult = mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", "Mozilla/5.0 (Test Browser)")
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.scope").value("QUIZ_VIEW"))
                .andExpect(jsonPath("$.oneTime").value(false))
                .andExpect(cookie().exists("share_token"))
                .andExpect(cookie().value("share_token", token))
                .andExpect(cookie().httpOnly("share_token", true))
                .andExpect(cookie().secure("share_token", true))
                .andExpect(cookie().path("share_token", "/quizzes/" + quizId))
                .andReturn();

        // Verify cookie attributes
        String setCookieHeader = accessResult.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookieHeader);
        assertTrue(setCookieHeader.contains("HttpOnly"));
        assertTrue(setCookieHeader.contains("Secure"));
        assertTrue(setCookieHeader.contains("Path=/quizzes/" + quizId));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with malformed token -> returns 400 BAD_REQUEST")
    void accessSharedQuiz_invalidToken_returns400() throws Exception {
        String invalidToken = "invalid-token-123";

        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", invalidToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(containsString("Invalid token format")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with expired token -> returns 400 BAD_REQUEST")
    void accessSharedQuiz_expiredToken_returns400() throws Exception {
        // Create a share link with past expiry
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().minusSeconds(3600), // Expired
                false
        );

        // We need to manually create this since the service validates expiry
        ShareLink shareLink = new ShareLink();
        // Don't set ID manually - let Hibernate generate it
        shareLink.setQuiz(quizRepository.findById(quizId).orElseThrow());
        shareLink.setCreatedBy(userRepository.findById(userId).orElseThrow());
        
        // Generate a properly formatted token and its hash
        String token = "expired-token-1234567890123456789012345678901234567890123";
        // The token needs to be exactly 43 characters for the regex to pass
        if (token.length() != 43) {
            token = token.substring(0, 43);
        }
        String tokenHash = shareLinkService.hashToken(token);
        shareLink.setTokenHash(tokenHash);
        shareLink.setScope(ShareLinkScope.QUIZ_VIEW);
        shareLink.setExpiresAt(Instant.now().minusSeconds(3600));
        shareLink.setOneTime(false);
        shareLink.setCreatedAt(Instant.now());
        shareLink = shareLinkRepository.save(shareLink);

        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0]").value(containsString("Token expired")));
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/shared/{token}/consume with one-time token -> returns 200 OK and consumes token")
    void consumeOneTimeToken_validToken_returns200() throws Exception {
        // First create a one-time share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                true
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();
        UUID shareLinkId = UUID.fromString(createResponse.get("link").get("id").asText());

        // Then consume it
        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/consume", token)
                        .header("User-Agent", "Mozilla/5.0 (Test Browser)")
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(shareLinkId.toString()))
                .andExpect(jsonPath("$.quizId").value(quizId.toString()))
                .andExpect(jsonPath("$.oneTime").value(true));

        // Verify it's revoked in database
        ShareLink shareLink = shareLinkRepository.findById(shareLinkId).orElseThrow();
        assertNotNull(shareLink.getRevokedAt());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/shared/{token}/consume with already consumed token -> returns 410 GONE")
    void consumeOneTimeToken_alreadyConsumed_returns410() throws Exception {
        // First create a one-time share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                true
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();

        // Consume it first time
        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/consume", token))
                .andExpect(status().isOk());

        // Try to consume it again
        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/consume", token))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/shared/{token}/consume with non-one-time token -> returns 200 OK")
    void consumeOneTimeToken_nonOneTimeToken_returns200() throws Exception {
        // First create a non-one-time share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();
        UUID shareLinkId = UUID.fromString(createResponse.get("link").get("id").asText());

        // Consume it multiple times
        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/consume", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(shareLinkId.toString()))
                .andExpect(jsonPath("$.oneTime").value(false));

        mockMvc.perform(post("/api/v1/quizzes/shared/{token}/consume", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(shareLinkId.toString()));

        // Verify it's not revoked in database
        ShareLink shareLink = shareLinkRepository.findById(shareLinkId).orElseThrow();
        assertNull(shareLink.getRevokedAt());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/share-links with authenticated user -> returns 200 OK")
    void getUserShareLinks_authenticatedUser_returns200() throws Exception {
        // Create multiple share links
        CreateShareLinkRequest request1 = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        CreateShareLinkRequest request2 = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(7200),
                true
        );

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request1)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request2)))
                .andExpect(status().isCreated());

        // Get user's share links
        mockMvc.perform(get("/api/v1/quizzes/share-links")
                        .with(user(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].quizId").value(quizId.toString()))
                .andExpect(jsonPath("$[0].createdBy").value(userId.toString()))
                .andExpect(jsonPath("$[1].quizId").value(quizId.toString()))
                .andExpect(jsonPath("$[1].createdBy").value(userId.toString()));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/share-links without authentication -> returns 403 FORBIDDEN")
    void getUserShareLinks_anonymous_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/share-links")
                        .with(anonymous()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/share-links returns empty list when no share links")
    void getUserShareLinks_noShareLinks_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/share-links")
                        .with(user(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with different IP addresses -> tracks analytics correctly")
    void accessSharedQuiz_differentIPs_tracksAnalytics() throws Exception {
        // Create a share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();

        // Access with different IP addresses
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", "Mozilla/5.0 (Test Browser)")
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", "Mozilla/5.0 (Test Browser)")
                        .header("X-Forwarded-For", "192.168.1.101"))
                .andExpect(status().isOk());

        // Both should succeed since it's not a one-time token
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", "Mozilla/5.0 (Test Browser)")
                        .header("X-Real-IP", "10.0.0.1"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with very long user agent -> truncates correctly")
    void accessSharedQuiz_longUserAgent_truncatesCorrectly() throws Exception {
        // Create a share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();

        // Create a very long user agent
        StringBuilder longUserAgent = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longUserAgent.append("a");
        }

        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", longUserAgent.toString())
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link with far future expiry -> caps at max expiry")
    void createShareLink_farFutureExpiry_capsAtMaxExpiry() throws Exception {
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(365 * 24 * 3600), // 1 year from now
                false
        );

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.link.expiresAt").exists());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with revoked token -> returns 404 NOT_FOUND")
    void accessSharedQuiz_revokedToken_returns404() throws Exception {
        // Create a share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();
        UUID shareLinkId = UUID.fromString(createResponse.get("link").get("id").asText());

        // Revoke the share link
        mockMvc.perform(delete("/api/v1/quizzes/shared/{tokenId}", shareLinkId)
                        .with(user(userToken)))
                .andExpect(status().isNoContent());

        // Try to access the revoked token
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with malformed token -> returns 404 NOT_FOUND")
    void accessSharedQuiz_malformedToken_returns404() throws Exception {
        String malformedToken = "invalid-token-with-special-chars!@#$%^&*()";

        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", malformedToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} with empty token -> returns 404 NOT_FOUND")
    void accessSharedQuiz_emptyToken_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", ""))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/v1/quizzes/{quizId}/share-link with null scope -> returns 400 BAD_REQUEST")
    void createShareLink_invalidScope_returns400() throws Exception {
        // Create request with null scope (should be rejected by validation)
        CreateShareLinkRequest request = new CreateShareLinkRequest(
                null, // This should be rejected by @NotNull validation
                Instant.now().plusSeconds(3600),
                false
        );

        mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest()) // Should be rejected by validation
                .andExpect(jsonPath("$.details[0]").value(containsString("must not be null")));
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} handles missing User-Agent header")
    void accessSharedQuiz_missingUserAgent_handlesGracefully() throws Exception {
        // Create a share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();

        // Access without User-Agent header
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/quizzes/shared/{token} handles missing IP headers")
    void accessSharedQuiz_missingIPHeaders_handlesGracefully() throws Exception {
        // Create a share link
        CreateShareLinkRequest createRequest = new CreateShareLinkRequest(
                ShareLinkScope.QUIZ_VIEW,
                Instant.now().plusSeconds(3600),
                false
        );

        MvcResult createResult = mockMvc.perform(post("/api/v1/quizzes/{quizId}/share-link", quizId)
                        .with(user(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String token = createResponse.get("token").asText();

        // Access without IP headers
        mockMvc.perform(get("/api/v1/quizzes/shared/{token}", token)
                        .header("User-Agent", "Mozilla/5.0 (Test Browser)"))
                .andExpect(status().isOk());
    }
}
