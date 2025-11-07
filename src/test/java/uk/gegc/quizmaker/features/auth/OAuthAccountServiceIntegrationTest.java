package uk.gegc.quizmaker.features.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.auth.api.dto.LinkedAccountsResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthAccountDto;
import uk.gegc.quizmaker.features.auth.application.OAuthAccountService;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthAccountRepository;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for OAuth Account Service
 */
@DisplayName("OAuth Account Service Integration Tests")
class OAuthAccountServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OAuthAccountService oauthAccountService;

    @Autowired
    private OAuthAccountRepository oauthAccountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;
    private Authentication authentication;

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

        // Create authentication
        authentication = new UsernamePasswordAuthenticationToken(testUser.getUsername(), null);
    }

    @Test
    @DisplayName("getLinkedAccounts: when no OAuth accounts then returns empty list")
    void getLinkedAccounts_NoAccounts_ReturnsEmptyList() {
        // When
        LinkedAccountsResponse response = oauthAccountService.getLinkedAccounts(authentication);

        // Then
        assertThat(response.accounts()).isEmpty();
    }

    @Test
    @DisplayName("getLinkedAccounts: when OAuth accounts exist then returns them")
    void getLinkedAccounts_WithAccounts_ReturnsAccounts() {
        // Given
        OAuthAccount googleAccount = createOAuthAccount(OAuthProvider.GOOGLE, "google123");
        OAuthAccount githubAccount = createOAuthAccount(OAuthProvider.GITHUB, "github456");

        // When
        LinkedAccountsResponse response = oauthAccountService.getLinkedAccounts(authentication);

        // Then
        assertThat(response.accounts()).hasSize(2);
        assertThat(response.accounts())
                .extracting(OAuthAccountDto::provider, OAuthAccountDto::email)
                .containsExactlyInAnyOrder(
                        tuple(OAuthProvider.GOOGLE, googleAccount.getEmail()),
                        tuple(OAuthProvider.GITHUB, githubAccount.getEmail())
                );
    }

    @Test
    @DisplayName("unlinkAccount: when user has password then unlinks successfully")
    void unlinkAccount_WithPassword_UnlinksSuccessfully() {
        // Given
        OAuthAccount googleAccount = createOAuthAccount(OAuthProvider.GOOGLE, "google123");

        // When
        oauthAccountService.unlinkAccount(authentication, OAuthProvider.GOOGLE);

        // Then
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(
                OAuthProvider.GOOGLE, "google123")).isEmpty();
    }

    @Test
    @DisplayName("unlinkAccount: when user has multiple OAuth accounts then unlinks one successfully")
    void unlinkAccount_MultipleAccounts_UnlinksOneSuccessfully() {
        // Given
        createOAuthAccount(OAuthProvider.GOOGLE, "google123");
        createOAuthAccount(OAuthProvider.GITHUB, "github456");

        // When
        oauthAccountService.unlinkAccount(authentication, OAuthProvider.GOOGLE);

        // Then
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(
                OAuthProvider.GOOGLE, "google123")).isEmpty();
        assertThat(oauthAccountRepository.findByProviderAndProviderUserId(
                OAuthProvider.GITHUB, "github456")).isPresent();
    }

    @Test
    @DisplayName("unlinkAccount: when only OAuth account and no password then throws BadRequest")
    void unlinkAccount_OnlyAuthMethod_ThrowsBadRequest() {
        // Given - remove password
        testUser.setHashedPassword("");
        userRepository.save(testUser);
        
        createOAuthAccount(OAuthProvider.GOOGLE, "google123");

        // When/Then
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> oauthAccountService.unlinkAccount(authentication, OAuthProvider.GOOGLE)
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getReason()).contains("Cannot unlink the only authentication method");
    }

    @Test
    @DisplayName("unlinkAccount: when account not found then throws NotFound")
    void unlinkAccount_AccountNotFound_ThrowsNotFound() {
        // When/Then
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> oauthAccountService.unlinkAccount(authentication, OAuthProvider.GOOGLE)
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getReason()).contains("OAuth account not found");
    }

    @Test
    @DisplayName("unlinkAccount: when user not authenticated then throws Unauthorized")
    void unlinkAccount_UserNotAuthenticated_ThrowsUnauthorized() {
        // Given
        Authentication invalidAuth = new UsernamePasswordAuthenticationToken("nonexistent", null);

        // When/Then
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> oauthAccountService.unlinkAccount(invalidAuth, OAuthProvider.GOOGLE)
        );

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
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

