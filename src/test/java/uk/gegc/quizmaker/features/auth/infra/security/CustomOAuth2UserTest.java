package uk.gegc.quizmaker.features.auth.infra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CustomOAuth2User
 */
@DisplayName("CustomOAuth2User Unit Tests")
class CustomOAuth2UserTest {

    private OAuth2User delegateOAuth2User;
    private UUID userId;
    private String username;
    private Collection<GrantedAuthority> authorities;
    private Map<String, Object> attributes;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        username = "johndoe";
        
        authorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_QUIZ_CREATOR"),
                new SimpleGrantedAuthority("quiz:read"),
                new SimpleGrantedAuthority("quiz:write")
        );
        
        attributes = Map.of(
                "sub", "google123",
                "email", "john@example.com",
                "name", "John Doe",
                "picture", "https://example.com/avatar.jpg"
        );
        
        delegateOAuth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                attributes,
                "sub"
        );
    }

    @Test
    @DisplayName("constructor: when valid parameters then creates instance")
    void constructor_ValidParameters_CreatesInstance() {
        // When
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // Then
        assertThat(customUser).isNotNull();
    }

    @Test
    @DisplayName("getUserId: returns correct user ID")
    void getUserId_ReturnsCorrectUserId() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then
        assertThat(customUser.getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("getUsername: returns correct username")
    void getUsername_ReturnsCorrectUsername() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then
        assertThat(customUser.getUsername()).isEqualTo(username);
    }

    @Test
    @DisplayName("getName: returns username not OAuth2 name")
    void getName_ReturnsUsernameNotOAuth2Name() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then
        assertThat(customUser.getName()).isEqualTo(username);
        assertThat(customUser.getName()).isNotEqualTo(delegateOAuth2User.getName());
    }

    @Test
    @DisplayName("getAuthorities: returns custom authorities")
    void getAuthorities_ReturnsCustomAuthorities() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When
        Collection<? extends GrantedAuthority> result = customUser.getAuthorities();

        // Then
        assertThat(result).hasSize(4);
        assertThat(result)
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_QUIZ_CREATOR", "quiz:read", "quiz:write");
    }

    @Test
    @DisplayName("getAuthorities: returns authorities not from OAuth2User")
    void getAuthorities_ReturnsCustomAuthoritiesNotFromOAuth2User() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then
        assertThat(customUser.getAuthorities()).isNotEqualTo(delegateOAuth2User.getAuthorities());
    }

    @Test
    @DisplayName("getAttributes: delegates to OAuth2User")
    void getAttributes_DelegatesToOAuth2User() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When
        Map<String, Object> result = customUser.getAttributes();

        // Then
        assertThat(result).isEqualTo(attributes);
        assertThat(result.get("sub")).isEqualTo("google123");
        assertThat(result.get("email")).isEqualTo("john@example.com");
        assertThat(result.get("name")).isEqualTo("John Doe");
        assertThat(result.get("picture")).isEqualTo("https://example.com/avatar.jpg");
    }

    @Test
    @DisplayName("getAttributes: returns same instance as delegate")
    void getAttributes_ReturnsSameInstanceAsDelegate() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then
        assertThat(customUser.getAttributes()).isSameAs(delegateOAuth2User.getAttributes());
    }

    @Test
    @DisplayName("constructor: when empty authorities then stores empty collection")
    void constructor_EmptyAuthorities_StoresEmptyCollection() {
        // Given
        Collection<GrantedAuthority> emptyAuthorities = Collections.emptyList();

        // When
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                emptyAuthorities
        );

        // Then
        assertThat(customUser.getAuthorities()).isEmpty();
    }

    @Test
    @DisplayName("constructor: when single authority then stores correctly")
    void constructor_SingleAuthority_StoresCorrectly() {
        // Given
        Collection<GrantedAuthority> singleAuthority = List.of(
                new SimpleGrantedAuthority("ROLE_USER")
        );

        // When
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                singleAuthority
        );

        // Then
        assertThat(customUser.getAuthorities()).hasSize(1);
        assertThat(customUser.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("constructor: when multiple roles and permissions then stores all")
    void constructor_MultipleRolesAndPermissions_StoresAll() {
        // Given
        Collection<GrantedAuthority> multipleAuthorities = List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("ROLE_QUIZ_CREATOR"),
                new SimpleGrantedAuthority("quiz:read"),
                new SimpleGrantedAuthority("quiz:write"),
                new SimpleGrantedAuthority("quiz:delete"),
                new SimpleGrantedAuthority("user:manage")
        );

        // When
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                multipleAuthorities
        );

        // Then
        assertThat(customUser.getAuthorities()).hasSize(7);
        assertThat(customUser.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(
                        "ROLE_USER",
                        "ROLE_ADMIN",
                        "ROLE_QUIZ_CREATOR",
                        "quiz:read",
                        "quiz:write",
                        "quiz:delete",
                        "user:manage"
                );
    }

    @Test
    @DisplayName("getName: when different username and OAuth name then returns internal username")
    void getName_DifferentUsernameAndOAuthName_ReturnsInternalUsername() {
        // Given
        String internalUsername = "internal_user_123";
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                internalUsername,
                authorities
        );

        // When/Then
        assertThat(customUser.getName()).isEqualTo(internalUsername);
        // Delegate OAuth2User has "google123" as name (from "sub" attribute)
        assertThat(delegateOAuth2User.getName()).isEqualTo("google123");
    }

    @Test
    @DisplayName("getOauth2User: returns delegate OAuth2User")
    void getOauth2User_ReturnsDelegateOAuth2User() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then
        assertThat(customUser.getOauth2User()).isSameAs(delegateOAuth2User);
    }

    @Test
    @DisplayName("all getters: when called multiple times then return consistent values")
    void allGetters_CalledMultipleTimes_ReturnConsistentValues() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then - call each getter multiple times
        assertThat(customUser.getUserId()).isEqualTo(userId);
        assertThat(customUser.getUserId()).isEqualTo(userId);
        
        assertThat(customUser.getUsername()).isEqualTo(username);
        assertThat(customUser.getUsername()).isEqualTo(username);
        
        assertThat(customUser.getName()).isEqualTo(username);
        assertThat(customUser.getName()).isEqualTo(username);
        
        assertThat(customUser.getAuthorities()).isEqualTo(authorities);
        assertThat(customUser.getAuthorities()).isEqualTo(authorities);
        
        assertThat(customUser.getAttributes()).isEqualTo(attributes);
        assertThat(customUser.getAttributes()).isEqualTo(attributes);
    }

    @Test
    @DisplayName("implements OAuth2User interface correctly")
    void implementsOAuth2UserInterface() {
        // Given
        CustomOAuth2User customUser = new CustomOAuth2User(
                delegateOAuth2User,
                userId,
                username,
                authorities
        );

        // When/Then - verify it's an OAuth2User instance
        assertThat(customUser).isInstanceOf(OAuth2User.class);
        
        // Verify OAuth2User contract methods work
        OAuth2User oauth2UserInterface = customUser;
        assertThat(oauth2UserInterface.getName()).isEqualTo(username);
        assertThat(oauth2UserInterface.getAttributes()).isEqualTo(attributes);
        assertThat(oauth2UserInterface.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_QUIZ_CREATOR", "quiz:read", "quiz:write");
    }

    @Test
    @DisplayName("constructor: with GitHub OAuth user")
    void constructor_WithGitHubOAuthUser() {
        // Given
        Map<String, Object> githubAttributes = Map.of(
                "id", 12345,
                "login", "johndoe",
                "email", "john@example.com",
                "avatar_url", "https://avatars.githubusercontent.com/u/12345"
        );
        
        OAuth2User githubOAuth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                githubAttributes,
                "id"
        );

        // When
        CustomOAuth2User customUser = new CustomOAuth2User(
                githubOAuth2User,
                userId,
                "johndoe_internal",
                authorities
        );

        // Then
        assertThat(customUser.getAttributes()).isEqualTo(githubAttributes);
        assertThat(customUser.getAttributes().get("id")).isEqualTo(12345);
        assertThat(customUser.getName()).isEqualTo("johndoe_internal");
    }

    @Test
    @DisplayName("constructor: with Facebook OAuth user")
    void constructor_WithFacebookOAuthUser() {
        // Given
        Map<String, Object> facebookAttributes = Map.of(
                "id", "fb123456",
                "name", "John Doe",
                "email", "john@example.com",
                "picture", Map.of("data", Map.of("url", "https://graph.facebook.com/avatar.jpg"))
        );
        
        OAuth2User facebookOAuth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                facebookAttributes,
                "id"
        );

        // When
        CustomOAuth2User customUser = new CustomOAuth2User(
                facebookOAuth2User,
                userId,
                "johndoe",
                authorities
        );

        // Then
        assertThat(customUser.getAttributes()).isEqualTo(facebookAttributes);
        assertThat(customUser.getName()).isEqualTo("johndoe");
    }

    @Test
    @DisplayName("constructor: with Microsoft OAuth user")
    void constructor_WithMicrosoftOAuthUser() {
        // Given
        Map<String, Object> microsoftAttributes = Map.of(
                "sub", "ms-sub-123",
                "email", "john@outlook.com",
                "name", "John Doe"
        );
        
        OAuth2User microsoftOAuth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                microsoftAttributes,
                "sub"
        );

        // When
        CustomOAuth2User customUser = new CustomOAuth2User(
                microsoftOAuth2User,
                userId,
                "johndoe",
                authorities
        );

        // Then
        assertThat(customUser.getAttributes()).isEqualTo(microsoftAttributes);
        assertThat(customUser.getName()).isEqualTo("johndoe");
    }
}

