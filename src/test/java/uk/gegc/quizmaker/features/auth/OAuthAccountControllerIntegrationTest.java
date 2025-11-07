package uk.gegc.quizmaker.features.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.auth.api.dto.UnlinkAccountRequest;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthAccountRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for OAuth Account Controller
 */
@DisplayName("OAuth Account Controller Integration Tests")
class OAuthAccountControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenService jwtTokenService;

    private User testUser;
    private String accessToken;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new User();
        testUser.setUsername("testuser_" + UUID.randomUUID().toString().substring(0, 8));
        testUser.setEmail("test_" + UUID.randomUUID().toString().substring(0, 8) + "@test.com");
        testUser.setHashedPassword(passwordEncoder.encode("password123"));
        testUser.setActive(true);
        testUser.setEmailVerified(true);

        // Assign roles
        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found"));
        testUser.setRoles(Set.of(userRole));

        testUser = userRepository.save(testUser);

        // Generate access token
        org.springframework.security.authentication.UsernamePasswordAuthenticationToken authToken =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        testUser.getUsername(),
                        null,
                        testUser.getRoles().stream()
                                .map(role -> new org.springframework.security.core.authority.SimpleGrantedAuthority(role.getRoleName()))
                                .toList()
                );
        accessToken = jwtTokenService.generateAccessToken(authToken);
    }

    @Test
    @DisplayName("GET /api/v1/auth/oauth/accounts: when no OAuth accounts then returns empty list")
    void getLinkedAccounts_NoAccounts_ReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isArray())
                .andExpect(jsonPath("$.accounts", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/v1/auth/oauth/accounts: when OAuth accounts exist then returns them")
    void getLinkedAccounts_WithAccounts_ReturnsAccounts() throws Exception {
        // Given
        createOAuthAccount(OAuthProvider.GOOGLE, "google123");
        createOAuthAccount(OAuthProvider.GITHUB, "github456");

        // When/Then
        mockMvc.perform(get("/api/v1/auth/oauth/accounts")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isArray())
                .andExpect(jsonPath("$.accounts", hasSize(2)))
                .andExpect(jsonPath("$.accounts[*].provider", containsInAnyOrder("GOOGLE", "GITHUB")));
    }

    @Test
    @DisplayName("GET /api/v1/auth/oauth/accounts: when not authenticated then returns 401")
    void getLinkedAccounts_NotAuthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/oauth/accounts: when has password then unlinks successfully")
    void unlinkAccount_WithPassword_Returns204() throws Exception {
        // Given
        createOAuthAccount(OAuthProvider.GOOGLE, "google123");
        UnlinkAccountRequest request = new UnlinkAccountRequest(OAuthProvider.GOOGLE);

        // When/Then
        mockMvc.perform(delete("/api/v1/auth/oauth/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/oauth/accounts: when only auth method then returns 400")
    void unlinkAccount_OnlyAuthMethod_Returns400() throws Exception {
        // Given - remove password
        testUser.setHashedPassword("");
        userRepository.save(testUser);
        
        createOAuthAccount(OAuthProvider.GOOGLE, "google123");
        UnlinkAccountRequest request = new UnlinkAccountRequest(OAuthProvider.GOOGLE);

        // When/Then
        mockMvc.perform(delete("/api/v1/auth/oauth/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/oauth/accounts: when account not found then returns 404")
    void unlinkAccount_AccountNotFound_Returns404() throws Exception {
        // Given
        UnlinkAccountRequest request = new UnlinkAccountRequest(OAuthProvider.GOOGLE);

        // When/Then
        mockMvc.perform(delete("/api/v1/auth/oauth/accounts")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/oauth/accounts: when not authenticated then returns 401")
    void unlinkAccount_NotAuthenticated_Returns401() throws Exception {
        // Given
        UnlinkAccountRequest request = new UnlinkAccountRequest(OAuthProvider.GOOGLE);

        // When/Then
        mockMvc.perform(delete("/api/v1/auth/oauth/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    private OAuthAccount createOAuthAccount(OAuthProvider provider, String providerUserId) {
        OAuthAccount account = new OAuthAccount();
        account.setUser(testUser);
        account.setProvider(provider);
        account.setProviderUserId(providerUserId);
        account.setEmail(testUser.getEmail());
        account.setName("Test User");
        account.setProfileImageUrl("https://example.com/avatar.jpg");
        return oauthAccountRepository.save(account);
    }
}

