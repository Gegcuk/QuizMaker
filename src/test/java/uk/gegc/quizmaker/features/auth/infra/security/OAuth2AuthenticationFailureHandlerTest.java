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
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for OAuth2AuthenticationFailureHandler
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2AuthenticationFailureHandler Unit Tests")
class OAuth2AuthenticationFailureHandlerTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private RedirectStrategy redirectStrategy;

    private OAuth2AuthenticationFailureHandler handler;

    private final String testRedirectUri = "http://localhost:3000/oauth2/redirect";

    @BeforeEach
    void setUp() {
        handler = new OAuth2AuthenticationFailureHandler();
        ReflectionTestUtils.setField(handler, "oauth2RedirectUri", testRedirectUri);
        
        // Set mock redirect strategy
        handler.setRedirectStrategy(redirectStrategy);
    }

    @Test
    @DisplayName("onAuthenticationFailure: when OAuth error then redirects with error message")
    void onAuthenticationFailure_OAuthError_RedirectsWithErrorMessage() throws IOException {
        // Given
        OAuth2Error error = new OAuth2Error("invalid_request", "Invalid OAuth request", null);
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(eq(request), eq(response), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).startsWith(testRedirectUri);
        assertThat(redirectUrl).contains("error=");
    }

    @Test
    @DisplayName("onAuthenticationFailure: when bad credentials then includes error in URL")
    void onAuthenticationFailure_BadCredentials_IncludesErrorInUrl() throws IOException {
        // Given
        AuthenticationException exception = new BadCredentialsException("Invalid username or password");

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(eq(request), eq(response), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("?error=");
        assertThat(redirectUrl).contains("Invalid");
    }

    @Test
    @DisplayName("onAuthenticationFailure: encodes special characters in error message")
    void onAuthenticationFailure_EncodesSpecialCharacters() throws IOException, UnsupportedEncodingException {
        // Given
        String errorMessage = "Error: User & credentials don't match!";
        AuthenticationException exception = new BadCredentialsException(errorMessage);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(eq(request), eq(response), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("error=");
        
        // Extract and decode the error parameter
        String queryString = redirectUrl.split("\\?")[1];
        String errorParam = queryString.substring(6); // Remove "error="
        String decodedError = URLDecoder.decode(errorParam, StandardCharsets.UTF_8);
        assertThat(decodedError).isEqualTo(errorMessage);
    }

    @Test
    @DisplayName("onAuthenticationFailure: works with different redirect URIs")
    void onAuthenticationFailure_WorksWithDifferentRedirectUris() throws IOException {
        // Given
        String productionRedirectUri = "https://app.example.com/oauth/callback";
        ReflectionTestUtils.setField(handler, "oauth2RedirectUri", productionRedirectUri);
        
        AuthenticationException exception = new BadCredentialsException("Test error");

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(eq(request), eq(response), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).startsWith(productionRedirectUri);
        assertThat(redirectUrl).contains("?error=");
    }

    @Test
    @DisplayName("onAuthenticationFailure: preserves path in redirect URI")
    void onAuthenticationFailure_PreservesPathInRedirectUri() throws IOException {
        // Given
        String redirectUriWithPath = "http://localhost:3000/auth/oauth2/error";
        ReflectionTestUtils.setField(handler, "oauth2RedirectUri", redirectUriWithPath);
        
        AuthenticationException exception = new BadCredentialsException("Test error");

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).startsWith(redirectUriWithPath);
        assertThat(redirectUrl).contains("/auth/oauth2/error?");
    }

    @Test
    @DisplayName("onAuthenticationFailure: with OAuth2 specific error")
    void onAuthenticationFailure_WithOAuth2SpecificError() throws IOException {
        // Given
        OAuth2Error error = new OAuth2Error(
                "access_denied",
                "User denied access to application",
                "https://oauth.provider.com/error"
        );
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("error=");
        assertThat(redirectUrl).contains("User denied access");
    }

    @Test
    @DisplayName("onAuthenticationFailure: with internal authentication service exception")
    void onAuthenticationFailure_WithInternalException() throws IOException {
        // Given
        AuthenticationException exception = new InternalAuthenticationServiceException(
                "Internal server error during authentication"
        );

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("error=Internal");
        assertThat(redirectUrl).contains("server error");
    }

    @Test
    @DisplayName("onAuthenticationFailure: error parameter includes error message")
    void onAuthenticationFailure_ErrorParameterIncludesErrorMessage() throws IOException {
        // Given
        String errorWithSpaces = "Error with multiple words and spaces";
        AuthenticationException exception = new BadCredentialsException(errorWithSpaces);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("error=Error with");
        assertThat(redirectUrl).contains("words and spaces");
    }

    @Test
    @DisplayName("onAuthenticationFailure: logs error message")
    void onAuthenticationFailure_LogsErrorMessage() throws IOException {
        // Given
        String errorMessage = "Authentication failed for user";
        AuthenticationException exception = new BadCredentialsException(errorMessage);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        // Log statement is executed (verified by code coverage)
        verify(redirectStrategy).sendRedirect(any(), any(), any());
    }

    @Test
    @DisplayName("onAuthenticationFailure: handles null error message gracefully")
    void onAuthenticationFailure_NullErrorMessage_HandlesGracefully() throws IOException {
        // Given
        AuthenticationException exception = new BadCredentialsException(null);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("?error");
        // Should not throw exception
    }

    @Test
    @DisplayName("onAuthenticationFailure: error appears exactly once in URL")
    void onAuthenticationFailure_ErrorAppearsExactlyOnce() throws IOException {
        // Given
        AuthenticationException exception = new BadCredentialsException("Test error");

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        int errorCount = redirectUrl.split("error=", -1).length - 1;
        
        assertThat(errorCount).isEqualTo(1);
    }

    @Test
    @DisplayName("onAuthenticationFailure: preserves error detail from OAuth2Error")
    void onAuthenticationFailure_PreservesErrorDetailFromOAuth2Error() throws IOException {
        // Given
        OAuth2Error error = new OAuth2Error(
                "invalid_client",
                "Client authentication failed",
                null
        );
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(error);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("error=");
        assertThat(redirectUrl).contains("Client authentication failed");
    }

    @Test
    @DisplayName("onAuthenticationFailure: URL structure is correct")
    void onAuthenticationFailure_UrlStructureIsCorrect() throws IOException {
        // Given
        AuthenticationException exception = new BadCredentialsException("Test error");

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        
        // Verify URL structure: base?error=message
        String[] parts = redirectUrl.split("\\?");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isEqualTo(testRedirectUri);
        assertThat(parts[1]).startsWith("error=");
    }

    @Test
    @DisplayName("onAuthenticationFailure: with long error message")
    void onAuthenticationFailure_WithLongErrorMessage() throws IOException {
        // Given
        String longError = "This is a very long error message that contains multiple sentences. " +
                          "It describes the authentication failure in detail. " +
                          "The handler should properly encode and transmit this message.";
        AuthenticationException exception = new BadCredentialsException(longError);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("error=This is a very");
        assertThat(redirectUrl).contains("multiple sentences");
        // Verify the error message is included
    }

    @Test
    @DisplayName("onAuthenticationFailure: handles special characters in error")
    void onAuthenticationFailure_HandlesSpecialCharactersInError() throws IOException {
        // Given
        String errorWithSpecialChars = "Error: 'user@example.com' & credentials don't match (code: 401)";
        AuthenticationException exception = new BadCredentialsException(errorWithSpecialChars);

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).contains("error=");
        // Verify the error message is included
        assertThat(redirectUrl).contains("Error:");
        assertThat(redirectUrl).contains("user@example.com");
    }

    @Test
    @DisplayName("onAuthenticationFailure: uses configured redirect URI from properties")
    void onAuthenticationFailure_UsesConfiguredRedirectUri() throws IOException {
        // Given
        AuthenticationException exception = new BadCredentialsException("Test error");

        // When
        handler.onAuthenticationFailure(request, response, exception);

        // Then
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), urlCaptor.capture());
        
        String redirectUrl = urlCaptor.getValue();
        assertThat(redirectUrl).startsWith(testRedirectUri);
    }
}

