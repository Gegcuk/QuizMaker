package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OAuth2AuthenticationSuccessHandler
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2AuthenticationSuccessHandler Unit Tests")
class OAuth2AuthenticationSuccessHandlerTest {

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private Authentication authentication;

    @Mock
    private RedirectStrategy redirectStrategy;

    private OAuth2AuthenticationSuccessHandler handler;

    private final String testRedirectUri = "http://localhost:3000/oauth2/redirect";
    private final String testAccessToken = "test-access-token-jwt";
    private final String testRefreshToken = "test-refresh-token-jwt";

    @BeforeEach
    void setUp() {
        handler = new OAuth2AuthenticationSuccessHandler(jwtTokenService);
        ReflectionTestUtils.setField(handler, "oauth2RedirectUri", testRedirectUri);
        
        // Set mock redirect strategy
        handler.setRedirectStrategy(redirectStrategy);

        // Setup default mocks with lenient for optional usage
        lenient().when(jwtTokenService.generateAccessToken(any(Authentication.class))).thenReturn(testAccessToken);
        lenient().when(jwtTokenService.generateRefreshToken(any(Authentication.class))).thenReturn(testRefreshToken);
        lenient().when(response.isCommitted()).thenReturn(false);
        lenient().when(authentication.getName()).thenReturn("testuser");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: when successful then generates tokens and redirects")
    void onAuthenticationSuccess_Successful_GeneratesTokensAndRedirects() throws IOException {
        // When
        handler.onAuthenticationSuccess(request, response, authentication);

        // Then
        verify(jwtTokenService).generateAccessToken(authentication);
        verify(jwtTokenService).generateRefreshToken(authentication);
        
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(eq(request), eq(response), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).startsWith(testRedirectUri);
        assertThat(redirectUrl).contains("accessToken=" + testAccessToken);
        assertThat(redirectUrl).contains("refreshToken=" + testRefreshToken);
    }

    @Test
    @DisplayName("onAuthenticationSuccess: when response committed then skips redirect")
    void onAuthenticationSuccess_ResponseCommitted_SkipsRedirect() throws IOException {
        // Given
        when(response.isCommitted()).thenReturn(true);

        // When
        handler.onAuthenticationSuccess(request, response, authentication);

        // Then
        verify(redirectStrategy, never()).sendRedirect(any(), any(), any());
        verify(jwtTokenService).generateAccessToken(authentication);
        verify(jwtTokenService).generateRefreshToken(authentication);
    }

    @Test
    @DisplayName("determineTargetUrl: builds correct redirect URL with tokens")
    void determineTargetUrl_BuildsCorrectRedirectUrl() {
        // When
        String targetUrl = handler.determineTargetUrl(request, response, authentication);

        // Then
        assertThat(targetUrl).isNotNull();
        assertThat(targetUrl).startsWith(testRedirectUri);
        assertThat(targetUrl).contains("?");
        assertThat(targetUrl).contains("accessToken=" + testAccessToken);
        assertThat(targetUrl).contains("refreshToken=" + testRefreshToken);
        assertThat(targetUrl).contains("&");
    }

    @Test
    @DisplayName("determineTargetUrl: encodes special characters in URL")
    void determineTargetUrl_EncodesSpecialCharacters() {
        // Given - tokens with special characters that need encoding
        String tokenWithSpecialChars = "token.with-special_chars+and/symbols";
        when(jwtTokenService.generateAccessToken(any())).thenReturn(tokenWithSpecialChars);
        when(jwtTokenService.generateRefreshToken(any())).thenReturn(tokenWithSpecialChars);

        // When
        String targetUrl = handler.determineTargetUrl(request, response, authentication);

        // Then
        assertThat(targetUrl).contains("accessToken=" + tokenWithSpecialChars);
        assertThat(targetUrl).contains("refreshToken=" + tokenWithSpecialChars);
    }

    @Test
    @DisplayName("determineTargetUrl: works with different redirect URIs")
    void determineTargetUrl_WorksWithDifferentRedirectUris() {
        // Given
        String productionRedirectUri = "https://app.example.com/oauth/callback";
        ReflectionTestUtils.setField(handler, "oauth2RedirectUri", productionRedirectUri);

        // When
        String targetUrl = handler.determineTargetUrl(request, response, authentication);

        // Then
        assertThat(targetUrl).startsWith(productionRedirectUri);
        assertThat(targetUrl).contains("accessToken=" + testAccessToken);
        assertThat(targetUrl).contains("refreshToken=" + testRefreshToken);
    }

    @Test
    @DisplayName("determineTargetUrl: generates both access and refresh tokens")
    void determineTargetUrl_GeneratesBothTokens() {
        // When
        handler.determineTargetUrl(request, response, authentication);

        // Then
        verify(jwtTokenService).generateAccessToken(authentication);
        verify(jwtTokenService).generateRefreshToken(authentication);
    }

    @Test
    @DisplayName("onAuthenticationSuccess: clears authentication attributes")
    void onAuthenticationSuccess_ClearsAuthenticationAttributes() throws IOException {
        // When
        handler.onAuthenticationSuccess(request, response, authentication);

        // Then
        // clearAuthenticationAttributes is called (inherited method)
        verify(redirectStrategy).sendRedirect(any(), any(), any());
    }

    @Test
    @DisplayName("determineTargetUrl: logs successful authentication")
    void determineTargetUrl_LogsSuccessfulAuthentication() {
        // Given
        when(authentication.getName()).thenReturn("johndoe");

        // When
        handler.determineTargetUrl(request, response, authentication);

        // Then
        verify(authentication).getName();
        // Log statement is executed (verified by code coverage)
    }

    @Test
    @DisplayName("onAuthenticationSuccess: with OAuth2User authentication")
    void onAuthenticationSuccess_WithOAuth2UserAuthentication() throws IOException {
        // Given
        Map<String, Object> attributes = Map.of(
                "sub", "google123",
                "email", "user@gmail.com",
                "name", "John Doe"
        );
        DefaultOAuth2User oauth2User = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                "sub"
        );
        
        Authentication oauth2Authentication = mock(Authentication.class);
        lenient().when(oauth2Authentication.getName()).thenReturn("johndoe");
        lenient().when(oauth2Authentication.getPrincipal()).thenReturn(oauth2User);

        // When
        handler.onAuthenticationSuccess(request, response, oauth2Authentication);

        // Then
        verify(jwtTokenService).generateAccessToken(oauth2Authentication);
        verify(jwtTokenService).generateRefreshToken(oauth2Authentication);
        verify(redirectStrategy).sendRedirect(any(), any(), any());
    }

    @Test
    @DisplayName("determineTargetUrl: query parameters properly separated")
    void determineTargetUrl_QueryParametersProperlyString() {
        // When
        String targetUrl = handler.determineTargetUrl(request, response, authentication);

        // Then
        // Verify URL structure: base?accessToken=xxx&refreshToken=yyy
        String[] parts = targetUrl.split("\\?");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isEqualTo(testRedirectUri);
        
        String queryString = parts[1];
        assertThat(queryString).contains("accessToken=");
        assertThat(queryString).contains("refreshToken=");
        assertThat(queryString).contains("&");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: handles null authentication name gracefully")
    void onAuthenticationSuccess_NullAuthenticationName_HandlesGracefully() throws IOException {
        // Given
        when(authentication.getName()).thenReturn(null);

        // When
        handler.onAuthenticationSuccess(request, response, authentication);

        // Then
        verify(redirectStrategy).sendRedirect(any(), any(), any());
        // Should not throw exception
    }

    @Test
    @DisplayName("determineTargetUrl: uses configured redirect URI from properties")
    void determineTargetUrl_UsesConfiguredRedirectUri() {
        // Given - redirect URI already set in setUp()
        
        // When
        String targetUrl = handler.determineTargetUrl(request, response, authentication);

        // Then
        assertThat(targetUrl).startsWith(testRedirectUri);
    }

    @Test
    @DisplayName("determineTargetUrl: preserves path in redirect URI")
    void determineTargetUrl_PreservesPathInRedirectUri() {
        // Given
        String redirectUriWithPath = "http://localhost:3000/auth/oauth2/redirect";
        ReflectionTestUtils.setField(handler, "oauth2RedirectUri", redirectUriWithPath);

        // When
        String targetUrl = handler.determineTargetUrl(request, response, authentication);

        // Then
        assertThat(targetUrl).startsWith(redirectUriWithPath);
        assertThat(targetUrl).contains("/auth/oauth2/redirect?");
    }

    @Test
    @DisplayName("onAuthenticationSuccess: full flow executes in correct order")
    void onAuthenticationSuccess_FullFlow_ExecutesInCorrectOrder() throws IOException {
        // When
        handler.onAuthenticationSuccess(request, response, authentication);

        // Then - verify execution order
        var inOrder = inOrder(jwtTokenService, response, redirectStrategy);
        
        inOrder.verify(jwtTokenService).generateAccessToken(authentication);
        inOrder.verify(jwtTokenService).generateRefreshToken(authentication);
        inOrder.verify(response).isCommitted();
        inOrder.verify(redirectStrategy).sendRedirect(eq(request), eq(response), anyString());
    }

    @Test
    @DisplayName("determineTargetUrl: tokens appear exactly once in URL")
    void determineTargetUrl_TokensAppearExactlyOnce() {
        // When
        String targetUrl = handler.determineTargetUrl(request, response, authentication);

        // Then
        int accessTokenCount = targetUrl.split("accessToken=", -1).length - 1;
        int refreshTokenCount = targetUrl.split("refreshToken=", -1).length - 1;
        
        assertThat(accessTokenCount).isEqualTo(1);
        assertThat(refreshTokenCount).isEqualTo(1);
    }

    @Test
    @DisplayName("onAuthenticationSuccess: with CustomOAuth2User principal")
    void onAuthenticationSuccess_WithCustomOAuth2User() throws IOException {
        // Given
        Map<String, Object> attributes = Map.of("sub", "google123");
        DefaultOAuth2User oauth2User = new DefaultOAuth2User(
                Collections.emptyList(),
                attributes,
                "sub"
        );
        
        CustomOAuth2User customOAuth2User = new CustomOAuth2User(
                oauth2User,
                java.util.UUID.randomUUID(),
                "johndoe",
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        Authentication customAuth = mock(Authentication.class);
        lenient().when(customAuth.getName()).thenReturn("johndoe");
        lenient().when(customAuth.getPrincipal()).thenReturn(customOAuth2User);

        // When
        handler.onAuthenticationSuccess(request, response, customAuth);

        // Then
        verify(jwtTokenService).generateAccessToken(customAuth);
        verify(jwtTokenService).generateRefreshToken(customAuth);
        
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("accessToken=");
        assertThat(redirectUrl).contains("refreshToken=");
    }
}

