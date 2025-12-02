package uk.gegc.quizmaker.features.auth.infra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.context.ApplicationEventPublisher;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthAccountRepository;
import uk.gegc.quizmaker.features.auth.domain.service.OAuthUsernameGenerator;
import uk.gegc.quizmaker.features.user.domain.model.Permission;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.application.BillingService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CustomOAuth2UserService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CustomOAuth2UserService Unit Tests")
class CustomOAuth2UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OAuthAccountRepository oauthAccountRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private OAuthTokenCryptoService tokenCryptoService;

    @Mock
    private OAuthUsernameGenerator usernameGenerator;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private CustomOAuth2UserService service;

    private Role userRole;
    private Role quizCreatorRole;
    private OAuth2AccessToken accessToken;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        // Manually inject dependencies into the spy
        service = new CustomOAuth2UserService(
                userRepository,
                oauthAccountRepository,
                roleRepository,
                passwordEncoder,
                tokenCryptoService,
                usernameGenerator,
                eventPublisher
        );
        service = spy(service);
        
        // Mock TransactionTemplate to execute callbacks immediately (for REQUIRES_NEW transaction)
        when(transactionTemplate.getTransactionManager()).thenReturn(transactionManager);
        doAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallbackWithoutResult callback = invocation.getArgument(0);
            callback.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
        
        // Set @Value field that Spring would inject in real context
        org.springframework.test.util.ReflectionTestUtils.setField(service, "registrationBonusTokens", 100L);

        // Setup common test data
        userRole = new Role();
        userRole.setRoleName(RoleName.ROLE_USER.name());
        userRole.setPermissions(new HashSet<>());

        quizCreatorRole = new Role();
        quizCreatorRole.setRoleName(RoleName.ROLE_QUIZ_CREATOR.name());
        quizCreatorRole.setPermissions(new HashSet<>());

        accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "test-token",
                Instant.now(),
                Instant.now().plusSeconds(3600)
        );

        // Setup default mocks with lenient for optional usage
        lenient().when(roleRepository.findByRoleName(RoleName.ROLE_USER.name()))
                .thenReturn(Optional.of(userRole));
        lenient().when(roleRepository.findByRoleName(RoleName.ROLE_QUIZ_CREATOR.name()))
                .thenReturn(Optional.of(quizCreatorRole));
        lenient().when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        lenient().when(tokenCryptoService.encrypt(anyString())).thenReturn("encrypted-token");
    }

    @Test
    @DisplayName("loadUser: when new Google user then creates user and OAuth account")
    void loadUser_NewGoogleUser_CreatesUserAndOAuthAccount() throws Exception {
        // Given
        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe",
                "picture", "https://lh3.googleusercontent.com/avatar.jpg"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        // Mock the parent class loadUser to return our oauth2User directly
        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@gmail.com")).thenReturn(Optional.empty());
        when(usernameGenerator.generate("user@gmail.com", "John Doe")).thenReturn("johndoe");

        User savedUser = createUser("johndoe", "user@gmail.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        CustomOAuth2User result = (CustomOAuth2User) service.loadUser(userRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("johndoe");
        assertThat(result.getUserId()).isEqualTo(savedUser.getId());

        // Verify user creation (save is called twice: once to create user, once to update last login)
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        // Get the first save call (user creation)
        User createdUser = userCaptor.getAllValues().get(0);
        
        assertThat(createdUser.getUsername()).isEqualTo("johndoe");
        assertThat(createdUser.getEmail()).isEqualTo("user@gmail.com");
        assertThat(createdUser.getDisplayName()).isEqualTo("John Doe");
        assertThat(createdUser.getAvatarUrl()).isEqualTo("https://lh3.googleusercontent.com/avatar.jpg");
        assertThat(createdUser.isEmailVerified()).isTrue();
        assertThat(createdUser.isActive()).isTrue();
        assertThat(createdUser.getRoles()).containsExactlyInAnyOrder(userRole, quizCreatorRole);

        // Verify OAuth account creation
        ArgumentCaptor<OAuthAccount> oauthCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).save(oauthCaptor.capture());
        OAuthAccount createdOAuthAccount = oauthCaptor.getValue();
        
        assertThat(createdOAuthAccount.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(createdOAuthAccount.getProviderUserId()).isEqualTo("google123");
        assertThat(createdOAuthAccount.getEmail()).isEqualTo("user@gmail.com");
        assertThat(createdOAuthAccount.getName()).isEqualTo("John Doe");
        assertThat(createdOAuthAccount.getProfileImageUrl()).isEqualTo("https://lh3.googleusercontent.com/avatar.jpg");
    }

    @Test
    @DisplayName("loadUser: when GitHub user then extracts correct provider user ID")
    void loadUser_GitHubUser_ExtractsCorrectProviderId() throws Exception {
        // Given
        attributes = Map.of(
                "id", 12345,
                "email", "user@example.com",
                "name", "Jane Doe",
                "avatar_url", "https://avatars.githubusercontent.com/u/12345"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "id");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("github", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GITHUB, "12345")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@example.com")).thenReturn(Optional.empty());
        when(usernameGenerator.generate("user@example.com", "Jane Doe")).thenReturn("janedoe");

        User savedUser = createUser("janedoe", "user@example.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.loadUser(userRequest);

        // Then
        ArgumentCaptor<OAuthAccount> oauthCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).save(oauthCaptor.capture());
        OAuthAccount createdOAuthAccount = oauthCaptor.getValue();
        
        assertThat(createdOAuthAccount.getProvider()).isEqualTo(OAuthProvider.GITHUB);
        assertThat(createdOAuthAccount.getProviderUserId()).isEqualTo("12345");
        assertThat(createdOAuthAccount.getProfileImageUrl()).isEqualTo("https://avatars.githubusercontent.com/u/12345");
    }

    @Test
    @DisplayName("loadUser: when existing OAuth account with verified email then updates tokens")
    void loadUser_ExistingOAuthAccount_UpdatesTokens() throws Exception {
        // Given
        User existingUser = createUser("johndoe", "user@gmail.com");
        existingUser.setEmailVerified(true); // Already verified
        OAuthAccount existingOAuthAccount = new OAuthAccount();
        existingOAuthAccount.setUser(existingUser);
        existingOAuthAccount.setProvider(OAuthProvider.GOOGLE);
        existingOAuthAccount.setProviderUserId("google123");

        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.of(existingOAuthAccount));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        CustomOAuth2User result = (CustomOAuth2User) service.loadUser(userRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(existingUser.getId());

        // Verify OAuth account is updated with encrypted token
        verify(oauthAccountRepository).save(existingOAuthAccount);
        verify(tokenCryptoService).encrypt("test-token");
        // Should save user once for last login update (but not for email verification since already verified)
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("loadUser: when existing OAuth account with unverified email then auto-verifies")
    void loadUser_ExistingOAuthAccountWithUnverifiedEmail_AutoVerifiesEmail() throws Exception {
        // Given
        User existingUser = createUser("johndoe", "user@gmail.com");
        existingUser.setEmailVerified(false); // Not verified
        existingUser.setEmailVerifiedAt(null);
        
        OAuthAccount existingOAuthAccount = new OAuthAccount();
        existingOAuthAccount.setUser(existingUser);
        existingOAuthAccount.setProvider(OAuthProvider.GOOGLE);
        existingOAuthAccount.setProviderUserId("google123");

        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.of(existingOAuthAccount));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        CustomOAuth2User result = (CustomOAuth2User) service.loadUser(userRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(existingUser.getId());

        // Verify OAuth account tokens are updated
        verify(oauthAccountRepository).save(existingOAuthAccount);
        verify(tokenCryptoService).encrypt("test-token");
        
        // Verify email was auto-verified
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        
        // First save for email verification, second for last login
        User verifiedUser = userCaptor.getAllValues().get(0);
        assertThat(verifiedUser.isEmailVerified()).isTrue();
        assertThat(verifiedUser.getEmailVerifiedAt()).isNotNull();
        assertThat(verifiedUser.getEmailVerifiedAt()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    @DisplayName("loadUser: when existing user by email then links OAuth account")
    void loadUser_ExistingUserByEmail_LinksOAuthAccount() throws Exception {
        // Given
        User existingUser = createUser("johndoe", "user@gmail.com");
        
        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@gmail.com")).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        CustomOAuth2User result = (CustomOAuth2User) service.loadUser(userRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(existingUser.getId());

        // Verify OAuth account is created and linked to existing user
        ArgumentCaptor<OAuthAccount> oauthCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).save(oauthCaptor.capture());
        OAuthAccount linkedOAuthAccount = oauthCaptor.getValue();
        
        assertThat(linkedOAuthAccount.getUser()).isEqualTo(existingUser);
        assertThat(linkedOAuthAccount.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(linkedOAuthAccount.getProviderUserId()).isEqualTo("google123");
    }

    @Test
    @DisplayName("loadUser: when no email provided then generates placeholder email")
    void loadUser_NoEmailProvided_GeneratesPlaceholderEmail() throws Exception {
        // Given
        attributes = Map.of(
                "sub", "google123",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        // No email means findByEmailWithRoles won't be called, but stub it as lenient just in case
        lenient().when(userRepository.findByEmailWithRoles(any())).thenReturn(Optional.empty());
        when(usernameGenerator.generate(any(), eq("John Doe"))).thenReturn("johndoe");

        User savedUser = createUser("johndoe", "oauth_12345678@oauth.placeholder");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.loadUser(userRequest);

        // Then (save is called twice: once to create user, once to update last login)
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        User createdUser = userCaptor.getAllValues().get(0);
        
        assertThat(createdUser.getEmail()).matches("oauth_[a-z0-9]{8}@oauth\\.placeholder");
        assertThat(createdUser.isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("loadUser: when Facebook user then extracts profile picture correctly")
    void loadUser_FacebookUser_ExtractsProfilePictureCorrectly() throws Exception {
        // Given
        Map<String, Object> pictureData = Map.of(
                "data", Map.of("url", "https://graph.facebook.com/avatar.jpg")
        );
        attributes = Map.of(
                "id", "fb123",
                "email", "user@facebook.com",
                "name", "John Doe",
                "picture", pictureData
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "id");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("facebook", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.FACEBOOK, "fb123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@facebook.com")).thenReturn(Optional.empty());
        when(usernameGenerator.generate("user@facebook.com", "John Doe")).thenReturn("johndoe");

        User savedUser = createUser("johndoe", "user@facebook.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.loadUser(userRequest);

        // Then
        ArgumentCaptor<OAuthAccount> oauthCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).save(oauthCaptor.capture());
        OAuthAccount createdOAuthAccount = oauthCaptor.getValue();
        
        assertThat(createdOAuthAccount.getProfileImageUrl()).isEqualTo("https://graph.facebook.com/avatar.jpg");
    }

    @Test
    @DisplayName("loadUser: when Microsoft user then uses correct user ID field")
    void loadUser_MicrosoftUser_UsesCorrectUserIdField() throws Exception {
        // Given
        attributes = Map.of(
                "sub", "ms123",
                "email", "user@outlook.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("microsoft", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.MICROSOFT, "ms123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@outlook.com")).thenReturn(Optional.empty());
        when(usernameGenerator.generate("user@outlook.com", "John Doe")).thenReturn("johndoe");

        User savedUser = createUser("johndoe", "user@outlook.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.loadUser(userRequest);

        // Then
        ArgumentCaptor<OAuthAccount> oauthCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).save(oauthCaptor.capture());
        OAuthAccount createdOAuthAccount = oauthCaptor.getValue();
        
        assertThat(createdOAuthAccount.getProvider()).isEqualTo(OAuthProvider.MICROSOFT);
        assertThat(createdOAuthAccount.getProviderUserId()).isEqualTo("ms123");
    }

    @Test
    @DisplayName("loadUser: when name not in attributes then constructs from given/family name")
    void loadUser_NameNotInAttributes_ConstructsFromGivenFamilyName() throws Exception {
        // Given
        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "given_name", "John",
                "family_name", "Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@gmail.com")).thenReturn(Optional.empty());
        when(usernameGenerator.generate("user@gmail.com", "John Doe")).thenReturn("johndoe");

        User savedUser = createUser("johndoe", "user@gmail.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.loadUser(userRequest);

        // Then
        ArgumentCaptor<OAuthAccount> oauthCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).save(oauthCaptor.capture());
        OAuthAccount createdOAuthAccount = oauthCaptor.getValue();
        
        assertThat(createdOAuthAccount.getName()).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("loadUser: when token has refresh token then encrypts and stores it")
    void loadUser_TokenHasRefreshToken_EncryptsAndStoresIt() throws Exception {
        // Given
        User existingUser = createUser("johndoe", "user@gmail.com");
        OAuthAccount existingOAuthAccount = new OAuthAccount();
        existingOAuthAccount.setUser(existingUser);
        existingOAuthAccount.setProvider(OAuthProvider.GOOGLE);
        existingOAuthAccount.setProviderUserId("google123");

        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        
        // Create OAuth2UserRequest with additional parameters including refresh_token
        Map<String, Object> additionalParameters = Map.of("refresh_token", "refresh-token-value");
        OAuth2UserRequest userRequest = createOAuth2UserRequestWithParams("google", oauth2User, additionalParameters);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.of(existingOAuthAccount));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenCryptoService.encrypt("refresh-token-value")).thenReturn("encrypted-refresh-token");

        // When
        service.loadUser(userRequest);

        // Then
        verify(tokenCryptoService).encrypt("test-token");
        verify(tokenCryptoService).encrypt("refresh-token-value");
        verify(oauthAccountRepository).save(existingOAuthAccount);
    }

    @Test
    @DisplayName("loadUser: when user has roles and permissions then includes them in authorities")
    void loadUser_UserHasRolesAndPermissions_IncludesThemInAuthorities() throws Exception {
        // Given
        Permission permission1 = new Permission();
        permission1.setPermissionName("quiz:read");
        Permission permission2 = new Permission();
        permission2.setPermissionName("quiz:write");
        
        userRole.setPermissions(Set.of(permission1));
        quizCreatorRole.setPermissions(Set.of(permission2));

        User existingUser = createUser("johndoe", "user@gmail.com");
        existingUser.setRoles(Set.of(userRole, quizCreatorRole));
        
        OAuthAccount existingOAuthAccount = new OAuthAccount();
        existingOAuthAccount.setUser(existingUser);
        existingOAuthAccount.setProvider(OAuthProvider.GOOGLE);
        existingOAuthAccount.setProviderUserId("google123");

        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.of(existingOAuthAccount));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        CustomOAuth2User result = (CustomOAuth2User) service.loadUser(userRequest);

        // Then
        Collection<? extends GrantedAuthority> authorities = result.getAuthorities();
        assertThat(authorities).hasSize(4); // 2 roles + 2 permissions
        assertThat(authorities)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_USER",
                        "ROLE_QUIZ_CREATOR",
                        "quiz:read",
                        "quiz:write"
                );
    }

    @Test
    @DisplayName("loadUser: when unsupported provider then throws IllegalArgumentException")
    void loadUser_UnsupportedProvider_ThrowsException() throws Exception {
        // Given
        attributes = Map.of(
                "sub", "unsupported123",
                "email", "user@example.com"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("unsupported-provider", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        // When/Then
        assertThatThrownBy(() -> service.loadUser(userRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported OAuth provider");
    }

    @Test
    @DisplayName("loadUser: when ROLE_USER not found then throws IllegalStateException")
    void loadUser_RoleUserNotFound_ThrowsException() throws Exception {
        // Given
        when(roleRepository.findByRoleName(RoleName.ROLE_USER.name()))
                .thenReturn(Optional.empty());

        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@gmail.com")).thenReturn(Optional.empty());
        when(usernameGenerator.generate("user@gmail.com", "John Doe")).thenReturn("johndoe");

        // When/Then
        assertThatThrownBy(() -> service.loadUser(userRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROLE_USER not found");
    }

    @Test
    @DisplayName("loadUser: when existing user with unverified email links OAuth then auto-verifies email")
    void loadUser_ExistingUserWithUnverifiedEmail_AutoVerifiesEmail() throws Exception {
        // Given
        User existingUser = createUser("johndoe", "user@gmail.com");
        existingUser.setEmailVerified(false); // User registered but never verified email
        existingUser.setEmailVerifiedAt(null);
        
        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@gmail.com")).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        CustomOAuth2User result = (CustomOAuth2User) service.loadUser(userRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(existingUser.getId());

        // Verify email was auto-verified (save is called twice: once for verification, once for last login)
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        
        // First save should be the email verification update
        User verifiedUser = userCaptor.getAllValues().get(0);
        assertThat(verifiedUser.isEmailVerified()).isTrue();
        assertThat(verifiedUser.getEmailVerifiedAt()).isNotNull();
        assertThat(verifiedUser.getEmailVerifiedAt()).isBeforeOrEqualTo(LocalDateTime.now());

        // Verify OAuth account is created and linked
        ArgumentCaptor<OAuthAccount> oauthCaptor = ArgumentCaptor.forClass(OAuthAccount.class);
        verify(oauthAccountRepository).save(oauthCaptor.capture());
        OAuthAccount linkedOAuthAccount = oauthCaptor.getValue();
        
        assertThat(linkedOAuthAccount.getUser()).isEqualTo(existingUser);
        assertThat(linkedOAuthAccount.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
    }

    @Test
    @DisplayName("loadUser: when existing user with already verified email links OAuth then keeps verified status")
    void loadUser_ExistingUserWithVerifiedEmail_KeepsVerifiedStatus() throws Exception {
        // Given
        User existingUser = createUser("johndoe", "user@gmail.com");
        existingUser.setEmailVerified(true); // Already verified
        LocalDateTime originalVerifiedAt = LocalDateTime.now().minusDays(5);
        existingUser.setEmailVerifiedAt(originalVerifiedAt);
        
        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@gmail.com")).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any(User.class))).thenReturn(existingUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        CustomOAuth2User result = (CustomOAuth2User) service.loadUser(userRequest);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.getUserId()).isEqualTo(existingUser.getId());

        // Verify email verification status wasn't changed (only one save for last login update)
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(1)).save(userCaptor.capture());
        
        // Should be the last login update, not email verification
        User savedUser = userCaptor.getValue();
        assertThat(savedUser.isEmailVerified()).isTrue();
        assertThat(savedUser.getEmailVerifiedAt()).isEqualTo(originalVerifiedAt);

        // Verify OAuth account is created and linked
        verify(oauthAccountRepository).save(any(OAuthAccount.class));
    }

    @Test
    @DisplayName("loadUser: when email verified then sets emailVerifiedAt")
    void loadUser_EmailVerified_SetsEmailVerifiedAt() throws Exception {
        // Given
        attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        OAuth2User oauth2User = new DefaultOAuth2User(Collections.emptyList(), attributes, "sub");
        OAuth2UserRequest userRequest = createOAuth2UserRequest("google", oauth2User);

        doReturn(oauth2User).when(service).callSuperLoadUser(userRequest);

        when(oauthAccountRepository.findByProviderAndProviderUserIdWithUser(
                OAuthProvider.GOOGLE, "google123")).thenReturn(Optional.empty());
        when(userRepository.findByEmailWithRoles("user@gmail.com")).thenReturn(Optional.empty());
        when(usernameGenerator.generate("user@gmail.com", "John Doe")).thenReturn("johndoe");

        User savedUser = createUser("johndoe", "user@gmail.com");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(oauthAccountRepository.save(any(OAuthAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        // When
        service.loadUser(userRequest);

        // Then (save is called twice: once to create user, once to update last login)
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        User createdUser = userCaptor.getAllValues().get(0);
        
        assertThat(createdUser.isEmailVerified()).isTrue();
        assertThat(createdUser.getEmailVerifiedAt()).isNotNull();
    }

    // Helper methods

    private OAuth2UserRequest createOAuth2UserRequest(String registrationId, OAuth2User oauth2User) {
        return createOAuth2UserRequestWithParams(registrationId, oauth2User, Collections.emptyMap());
    }

    private OAuth2UserRequest createOAuth2UserRequestWithParams(
            String registrationId, 
            OAuth2User oauth2User,
            Map<String, Object> additionalParameters
    ) {
        ClientRegistration clientRegistration = ClientRegistration
                .withRegistrationId(registrationId)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId("test-client-id")
                .clientSecret("test-client-secret")
                .redirectUri("http://localhost:8080/login/oauth2/code/" + registrationId)
                .authorizationUri("https://provider.com/oauth/authorize")
                .tokenUri("https://provider.com/oauth/token")
                .userInfoUri("https://provider.com/oauth/userinfo")
                .userNameAttributeName("sub")
                .build();

        return new OAuth2UserRequest(clientRegistration, accessToken, additionalParameters);
    }

    private User createUser(String username, String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(email);
        user.setHashedPassword("encoded-password");
        user.setActive(true);
        user.setEmailVerified(false);
        user.setRoles(Set.of(userRole, quizCreatorRole));
        return user;
    }
}

