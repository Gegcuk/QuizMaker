package uk.gegc.quizmaker.features.auth.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for OAuthAccount entity
 */
@DisplayName("OAuthAccount Entity Tests")
class OAuthAccountTest {

    @Test
    @DisplayName("no-arg constructor creates instance with null fields")
    void noArgConstructor_CreatesInstanceWithNullFields() {
        // When
        OAuthAccount account = new OAuthAccount();

        // Then
        assertThat(account.getId()).isNull();
        assertThat(account.getUser()).isNull();
        assertThat(account.getProvider()).isNull();
        assertThat(account.getProviderUserId()).isNull();
        assertThat(account.getEmail()).isNull();
        assertThat(account.getName()).isNull();
        assertThat(account.getProfileImageUrl()).isNull();
        assertThat(account.getAccessToken()).isNull();
        assertThat(account.getRefreshToken()).isNull();
        assertThat(account.getTokenExpiresAt()).isNull();
        assertThat(account.getCreatedAt()).isNull();
        assertThat(account.getUpdatedAt()).isNull();
    }

    @Test
    @DisplayName("all-arg constructor sets all fields correctly")
    void allArgConstructor_SetsAllFieldsCorrectly() {
        // Given
        Long id = 1L;
        User user = new User();
        user.setId(UUID.randomUUID());
        OAuthProvider provider = OAuthProvider.GOOGLE;
        String providerUserId = "google123";
        String email = "user@gmail.com";
        String name = "John Doe";
        String profileImageUrl = "https://example.com/avatar.jpg";
        String accessToken = "encrypted-access-token";
        String refreshToken = "encrypted-refresh-token";
        LocalDateTime tokenExpiresAt = LocalDateTime.now().plusHours(1);
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        LocalDateTime updatedAt = LocalDateTime.now();

        // When
        OAuthAccount account = new OAuthAccount(
            id, user, provider, providerUserId, email, name,
            profileImageUrl, accessToken, refreshToken, tokenExpiresAt,
            createdAt, updatedAt
        );

        // Then
        assertThat(account.getId()).isEqualTo(id);
        assertThat(account.getUser()).isEqualTo(user);
        assertThat(account.getProvider()).isEqualTo(provider);
        assertThat(account.getProviderUserId()).isEqualTo(providerUserId);
        assertThat(account.getEmail()).isEqualTo(email);
        assertThat(account.getName()).isEqualTo(name);
        assertThat(account.getProfileImageUrl()).isEqualTo(profileImageUrl);
        assertThat(account.getAccessToken()).isEqualTo(accessToken);
        assertThat(account.getRefreshToken()).isEqualTo(refreshToken);
        assertThat(account.getTokenExpiresAt()).isEqualTo(tokenExpiresAt);
        assertThat(account.getCreatedAt()).isEqualTo(createdAt);
        assertThat(account.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("setters update fields correctly")
    void setters_UpdateFieldsCorrectly() {
        // Given
        OAuthAccount account = new OAuthAccount();
        User user = new User();
        user.setId(UUID.randomUUID());
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(1);

        // When
        account.setId(1L);
        account.setUser(user);
        account.setProvider(OAuthProvider.GITHUB);
        account.setProviderUserId("github456");
        account.setEmail("user@github.com");
        account.setName("Jane Doe");
        account.setProfileImageUrl("https://github.com/avatar.jpg");
        account.setAccessToken("encrypted-token");
        account.setRefreshToken("encrypted-refresh");
        account.setTokenExpiresAt(expiresAt);

        // Then
        assertThat(account.getId()).isEqualTo(1L);
        assertThat(account.getUser()).isEqualTo(user);
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.GITHUB);
        assertThat(account.getProviderUserId()).isEqualTo("github456");
        assertThat(account.getEmail()).isEqualTo("user@github.com");
        assertThat(account.getName()).isEqualTo("Jane Doe");
        assertThat(account.getProfileImageUrl()).isEqualTo("https://github.com/avatar.jpg");
        assertThat(account.getAccessToken()).isEqualTo("encrypted-token");
        assertThat(account.getRefreshToken()).isEqualTo("encrypted-refresh");
        assertThat(account.getTokenExpiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("@PrePersist onCreate: when createdAt is null then sets both timestamps")
    void onCreate_WhenCreatedAtIsNull_SetsBothTimestamps() {
        // Given
        OAuthAccount account = new OAuthAccount();
        assertThat(account.getCreatedAt()).isNull();
        assertThat(account.getUpdatedAt()).isNull();

        LocalDateTime beforeCreate = LocalDateTime.now().minusSeconds(1);

        // When
        account.onCreate();

        // Then
        LocalDateTime afterCreate = LocalDateTime.now().plusSeconds(1);
        
        assertThat(account.getCreatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isNotNull();
        assertThat(account.getCreatedAt()).isAfterOrEqualTo(beforeCreate);
        assertThat(account.getCreatedAt()).isBeforeOrEqualTo(afterCreate);
        assertThat(account.getUpdatedAt()).isAfterOrEqualTo(beforeCreate);
        assertThat(account.getUpdatedAt()).isBeforeOrEqualTo(afterCreate);
        assertThat(account.getCreatedAt()).isEqualTo(account.getUpdatedAt());
    }

    @Test
    @DisplayName("@PrePersist onCreate: when createdAt is set then preserves it and updates updatedAt")
    void onCreate_WhenCreatedAtIsSet_PreservesCreatedAtAndUpdatesUpdatedAt() {
        // Given
        OAuthAccount account = new OAuthAccount();
        LocalDateTime existingCreatedAt = LocalDateTime.now().minusDays(5);
        account.setCreatedAt(existingCreatedAt);

        // When
        account.onCreate();

        // Then
        assertThat(account.getCreatedAt()).isEqualTo(existingCreatedAt);
        assertThat(account.getUpdatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isAfter(existingCreatedAt);
    }

    @Test
    @DisplayName("@PreUpdate onUpdate: updates updatedAt timestamp")
    void onUpdate_UpdatesUpdatedAtTimestamp() {
        // Given
        OAuthAccount account = new OAuthAccount();
        LocalDateTime oldUpdatedAt = LocalDateTime.now().minusHours(1);
        account.setUpdatedAt(oldUpdatedAt);

        LocalDateTime beforeUpdate = LocalDateTime.now().minusSeconds(1);

        // When
        account.onUpdate();

        // Then
        LocalDateTime afterUpdate = LocalDateTime.now().plusSeconds(1);
        
        assertThat(account.getUpdatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isAfterOrEqualTo(beforeUpdate);
        assertThat(account.getUpdatedAt()).isBeforeOrEqualTo(afterUpdate);
        assertThat(account.getUpdatedAt()).isAfter(oldUpdatedAt);
    }

    @Test
    @DisplayName("basic property mapping holds for all OAuth providers")
    void basicPropertyMapping_HoldsForAllProviders() {
        // Test each OAuth provider
        for (OAuthProvider provider : OAuthProvider.values()) {
            // Given
            OAuthAccount account = new OAuthAccount();
            User user = new User();
            user.setId(UUID.randomUUID());
            user.setUsername("testuser");
            user.setEmail("test@example.com");

            // When
            account.setUser(user);
            account.setProvider(provider);
            account.setProviderUserId(provider.name().toLowerCase() + "123");
            account.setEmail("user@" + provider.name().toLowerCase() + ".com");
            account.setName("Test User");

            // Then
            assertThat(account.getUser()).isEqualTo(user);
            assertThat(account.getProvider()).isEqualTo(provider);
            assertThat(account.getProviderUserId()).isEqualTo(provider.name().toLowerCase() + "123");
            assertThat(account.getEmail()).isEqualTo("user@" + provider.name().toLowerCase() + ".com");
            assertThat(account.getName()).isEqualTo("Test User");
        }
    }

    @Test
    @DisplayName("token fields can be null for initial account creation")
    void tokenFields_CanBeNullForInitialCreation() {
        // Given
        OAuthAccount account = new OAuthAccount();
        User user = new User();
        user.setId(UUID.randomUUID());

        // When
        account.setUser(user);
        account.setProvider(OAuthProvider.GOOGLE);
        account.setProviderUserId("google123");
        // Leave token fields null

        // Then
        assertThat(account.getAccessToken()).isNull();
        assertThat(account.getRefreshToken()).isNull();
        assertThat(account.getTokenExpiresAt()).isNull();
    }

    @Test
    @DisplayName("profile information fields can be updated independently")
    void profileInformationFields_CanBeUpdatedIndependently() {
        // Given
        OAuthAccount account = new OAuthAccount();
        account.setEmail("old@email.com");
        account.setName("Old Name");
        account.setProfileImageUrl("https://old.url/avatar.jpg");

        // When
        account.setEmail("new@email.com");
        account.setName("New Name");
        account.setProfileImageUrl("https://new.url/avatar.jpg");

        // Then
        assertThat(account.getEmail()).isEqualTo("new@email.com");
        assertThat(account.getName()).isEqualTo("New Name");
        assertThat(account.getProfileImageUrl()).isEqualTo("https://new.url/avatar.jpg");
    }

    @Test
    @DisplayName("token expiration can be set and retrieved")
    void tokenExpiration_CanBeSetAndRetrieved() {
        // Given
        OAuthAccount account = new OAuthAccount();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(2);

        // When
        account.setTokenExpiresAt(expiresAt);

        // Then
        assertThat(account.getTokenExpiresAt()).isEqualTo(expiresAt);
        assertThat(account.getTokenExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("encrypted tokens can be stored and retrieved")
    void encryptedTokens_CanBeStoredAndRetrieved() {
        // Given
        OAuthAccount account = new OAuthAccount();
        String encryptedAccessToken = "encrypted-access-token-base64";
        String encryptedRefreshToken = "encrypted-refresh-token-base64";

        // When
        account.setAccessToken(encryptedAccessToken);
        account.setRefreshToken(encryptedRefreshToken);

        // Then
        assertThat(account.getAccessToken()).isEqualTo(encryptedAccessToken);
        assertThat(account.getRefreshToken()).isEqualTo(encryptedRefreshToken);
    }

    @Test
    @DisplayName("user relationship can be established and retrieved")
    void userRelationship_CanBeEstablishedAndRetrieved() {
        // Given
        OAuthAccount account = new OAuthAccount();
        User user = new User();
        UUID userId = UUID.randomUUID();
        user.setId(userId);
        user.setUsername("testuser");
        user.setEmail("test@example.com");

        // When
        account.setUser(user);

        // Then
        assertThat(account.getUser()).isNotNull();
        assertThat(account.getUser()).isEqualTo(user);
        assertThat(account.getUser().getId()).isEqualTo(userId);
        assertThat(account.getUser().getUsername()).isEqualTo("testuser");
        assertThat(account.getUser().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("onCreate: multiple calls don't override createdAt but update updatedAt")
    void onCreate_MultipleCalls_PreservesCreatedAtButUpdatesUpdatedAt() throws InterruptedException {
        // Given
        OAuthAccount account = new OAuthAccount();
        
        // When
        account.onCreate();
        LocalDateTime firstCreatedAt = account.getCreatedAt();
        LocalDateTime firstUpdatedAt = account.getUpdatedAt();
        
        Thread.sleep(10); // Small delay to ensure timestamp difference
        
        account.onCreate();
        LocalDateTime secondCreatedAt = account.getCreatedAt();
        LocalDateTime secondUpdatedAt = account.getUpdatedAt();

        // Then
        assertThat(secondCreatedAt).isEqualTo(firstCreatedAt); // createdAt preserved
        assertThat(secondUpdatedAt).isAfterOrEqualTo(firstUpdatedAt); // updatedAt updated
    }

    @Test
    @DisplayName("onUpdate: updates only updatedAt timestamp")
    void onUpdate_UpdatesOnlyUpdatedAtTimestamp() throws InterruptedException {
        // Given
        OAuthAccount account = new OAuthAccount();
        account.onCreate(); // Set initial timestamps
        LocalDateTime initialCreatedAt = account.getCreatedAt();
        LocalDateTime initialUpdatedAt = account.getUpdatedAt();
        
        Thread.sleep(10); // Small delay to ensure timestamp difference

        // When
        account.onUpdate();

        // Then
        assertThat(account.getCreatedAt()).isEqualTo(initialCreatedAt); // createdAt unchanged
        assertThat(account.getUpdatedAt()).isAfter(initialUpdatedAt); // updatedAt updated
    }

    @Test
    @DisplayName("complete OAuth account can be created with all fields")
    void completeOAuthAccount_CanBeCreatedWithAllFields() {
        // Given
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("testuser");
        user.setEmail("test@example.com");

        // When
        OAuthAccount account = new OAuthAccount();
        account.setId(1L);
        account.setUser(user);
        account.setProvider(OAuthProvider.GOOGLE);
        account.setProviderUserId("google123");
        account.setEmail("user@gmail.com");
        account.setName("John Doe");
        account.setProfileImageUrl("https://lh3.googleusercontent.com/avatar.jpg");
        account.setAccessToken("encrypted-access-token");
        account.setRefreshToken("encrypted-refresh-token");
        account.setTokenExpiresAt(LocalDateTime.now().plusHours(1));
        
        account.onCreate(); // Simulate persistence

        // Then
        assertThat(account.getId()).isEqualTo(1L);
        assertThat(account.getUser()).isEqualTo(user);
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.GOOGLE);
        assertThat(account.getProviderUserId()).isEqualTo("google123");
        assertThat(account.getEmail()).isEqualTo("user@gmail.com");
        assertThat(account.getName()).isEqualTo("John Doe");
        assertThat(account.getProfileImageUrl()).isEqualTo("https://lh3.googleusercontent.com/avatar.jpg");
        assertThat(account.getAccessToken()).isEqualTo("encrypted-access-token");
        assertThat(account.getRefreshToken()).isEqualTo("encrypted-refresh-token");
        assertThat(account.getTokenExpiresAt()).isNotNull();
        assertThat(account.getCreatedAt()).isNotNull();
        assertThat(account.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("GitHub provider with integer ID as string")
    void githubProvider_WithIntegerIdAsString() {
        // Given
        OAuthAccount account = new OAuthAccount();

        // When
        account.setProvider(OAuthProvider.GITHUB);
        account.setProviderUserId("12345");
        account.setEmail("user@github.com");
        account.setProfileImageUrl("https://avatars.githubusercontent.com/u/12345");

        // Then
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.GITHUB);
        assertThat(account.getProviderUserId()).isEqualTo("12345");
        assertThat(account.getEmail()).isEqualTo("user@github.com");
        assertThat(account.getProfileImageUrl()).isEqualTo("https://avatars.githubusercontent.com/u/12345");
    }

    @Test
    @DisplayName("Facebook provider with nested profile picture data")
    void facebookProvider_WithNestedProfilePictureData() {
        // Given
        OAuthAccount account = new OAuthAccount();

        // When
        account.setProvider(OAuthProvider.FACEBOOK);
        account.setProviderUserId("fb123");
        account.setEmail("user@facebook.com");
        account.setProfileImageUrl("https://graph.facebook.com/avatar.jpg");

        // Then
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.FACEBOOK);
        assertThat(account.getProviderUserId()).isEqualTo("fb123");
        assertThat(account.getProfileImageUrl()).isEqualTo("https://graph.facebook.com/avatar.jpg");
    }

    @Test
    @DisplayName("Microsoft provider with standard OAuth fields")
    void microsoftProvider_WithStandardOAuthFields() {
        // Given
        OAuthAccount account = new OAuthAccount();

        // When
        account.setProvider(OAuthProvider.MICROSOFT);
        account.setProviderUserId("ms123");
        account.setEmail("user@outlook.com");
        account.setName("Microsoft User");

        // Then
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.MICROSOFT);
        assertThat(account.getProviderUserId()).isEqualTo("ms123");
        assertThat(account.getEmail()).isEqualTo("user@outlook.com");
        assertThat(account.getName()).isEqualTo("Microsoft User");
    }

    @Test
    @DisplayName("Apple provider without profile image")
    void appleProvider_WithoutProfileImage() {
        // Given
        OAuthAccount account = new OAuthAccount();

        // When
        account.setProvider(OAuthProvider.APPLE);
        account.setProviderUserId("apple123");
        account.setEmail("user@privaterelay.appleid.com");
        account.setName("Apple User");
        account.setProfileImageUrl(null); // Apple doesn't provide profile pictures

        // Then
        assertThat(account.getProvider()).isEqualTo(OAuthProvider.APPLE);
        assertThat(account.getProviderUserId()).isEqualTo("apple123");
        assertThat(account.getEmail()).isEqualTo("user@privaterelay.appleid.com");
        assertThat(account.getName()).isEqualTo("Apple User");
        assertThat(account.getProfileImageUrl()).isNull();
    }

    @Test
    @DisplayName("token refresh updates access token and expires at")
    void tokenRefresh_UpdatesAccessTokenAndExpiresAt() {
        // Given
        OAuthAccount account = new OAuthAccount();
        account.setAccessToken("old-encrypted-token");
        account.setTokenExpiresAt(LocalDateTime.now().plusMinutes(5));

        LocalDateTime newExpiresAt = LocalDateTime.now().plusHours(1);

        // When
        account.setAccessToken("new-encrypted-token");
        account.setTokenExpiresAt(newExpiresAt);

        // Then
        assertThat(account.getAccessToken()).isEqualTo("new-encrypted-token");
        assertThat(account.getTokenExpiresAt()).isEqualTo(newExpiresAt);
        assertThat(account.getTokenExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    @DisplayName("email can be null for providers that don't share email")
    void email_CanBeNullForPrivacyFocusedProviders() {
        // Given
        OAuthAccount account = new OAuthAccount();
        User user = new User();
        user.setId(UUID.randomUUID());

        // When
        account.setUser(user);
        account.setProvider(OAuthProvider.GITHUB);
        account.setProviderUserId("github789");
        account.setEmail(null); // User didn't grant email permission
        account.setName("Privacy User");

        // Then
        assertThat(account.getEmail()).isNull();
        assertThat(account.getProviderUserId()).isNotNull();
        assertThat(account.getName()).isEqualTo("Privacy User");
    }

    @Test
    @DisplayName("name can be null if provider doesn't provide it")
    void name_CanBeNullIfProviderDoesntProvideIt() {
        // Given
        OAuthAccount account = new OAuthAccount();

        // When
        account.setProvider(OAuthProvider.GITHUB);
        account.setProviderUserId("github999");
        account.setEmail("user@github.com");
        account.setName(null); // Some providers don't provide name

        // Then
        assertThat(account.getName()).isNull();
        assertThat(account.getProviderUserId()).isNotNull();
        assertThat(account.getEmail()).isNotNull();
    }

    @Test
    @DisplayName("refresh token can be null if not provided by OAuth flow")
    void refreshToken_CanBeNullIfNotProvided() {
        // Given
        OAuthAccount account = new OAuthAccount();

        // When
        account.setAccessToken("encrypted-access-token");
        account.setRefreshToken(null); // Some OAuth flows don't provide refresh tokens
        account.setTokenExpiresAt(LocalDateTime.now().plusHours(1));

        // Then
        assertThat(account.getAccessToken()).isNotNull();
        assertThat(account.getRefreshToken()).isNull();
        assertThat(account.getTokenExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("lifecycle callbacks work together correctly")
    void lifecycleCallbacks_WorkTogetherCorrectly() throws InterruptedException {
        // Given
        OAuthAccount account = new OAuthAccount();

        // When - Initial creation
        account.onCreate();
        LocalDateTime createdAt = account.getCreatedAt();
        LocalDateTime firstUpdatedAt = account.getUpdatedAt();

        Thread.sleep(10); // Ensure timestamp difference

        // When - Update
        account.onUpdate();
        LocalDateTime secondUpdatedAt = account.getUpdatedAt();

        // Then
        assertThat(account.getCreatedAt()).isEqualTo(createdAt); // Never changes
        assertThat(secondUpdatedAt).isAfter(firstUpdatedAt); // Updated on each update
    }
}

