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
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.RedirectStrategy;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2 authentication failure handler")
class OAuth2AuthenticationFailureHandlerTest {

    private static final String FALLBACK_REDIRECT = "http://localhost:3000/oauth2/redirect";
    private static final String CLIENT_REDIRECT = "https://app.example.com/oauth2/redirect";

    @Mock
    private RedirectStrategy redirectStrategy;

    private OAuth2ExchangeProperties properties;
    private OAuth2AuthenticationFailureHandler handler;

    @BeforeEach
    void setUp() {
        properties = new OAuth2ExchangeProperties();
        properties.setRedirectUri(FALLBACK_REDIRECT);
        OAuth2ExchangeProperties.Client client = new OAuth2ExchangeProperties.Client();
        client.setRedirectUri(CLIENT_REDIRECT);
        properties.getExchange().getClients().put("quizzence-web", client);
        handler = new OAuth2AuthenticationFailureHandler(properties);
        handler.setRedirectStrategy(redirectStrategy);
    }

    @Test
    @DisplayName("provider denial maps to one bounded public error without provider detail")
    void accessDenied_exposesOnlyBoundedError() throws IOException {
        OAuth2AuthenticationException exception = new OAuth2AuthenticationException(
                new OAuth2Error(
                        "access_denied",
                        "student@example.com denied secret provider scope",
                        "https://provider.example.com/internal-error"
                )
        );

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationFailure(new MockHttpServletRequest(), response, exception);

        assertThat(capturedRedirect())
                .isEqualTo(FALLBACK_REDIRECT + "?error="
                        + OAuth2AuthenticationFailureHandler.ACCESS_DENIED_ERROR)
                .doesNotContain("student", "secret", "provider.example.com");
        assertPrivateRedirectHeaders(response);
    }

    @Test
    @DisplayName("internal failures never copy exception messages into the callback URL")
    void internalFailure_exposesOnlyGenericError() throws IOException {
        handler.onAuthenticationFailure(
                new MockHttpServletRequest(),
                new MockHttpServletResponse(),
                new BadCredentialsException("database-host token user@example.com")
        );

        assertThat(capturedRedirect())
                .isEqualTo(FALLBACK_REDIRECT + "?error="
                        + OAuth2AuthenticationFailureHandler.AUTHENTICATION_FAILED_ERROR)
                .doesNotContain("database", "token", "example.com");
    }

    @Test
    @DisplayName("a state-validated client context selects only its allowlisted redirect")
    void validatedCodeContext_usesClientRedirect() throws IOException {
        MockHttpServletRequest request = callbackRequest(OAuthLoginContext.codeExchange(
                "quizzence-web",
                CLIENT_REDIRECT,
                "A".repeat(43)
        ));

        handler.onAuthenticationFailure(
                request,
                new MockHttpServletResponse(),
                new BadCredentialsException("hidden")
        );

        assertThat(capturedRedirect()).startsWith(CLIENT_REDIRECT + "?error=");
    }

    @Test
    @DisplayName("an unapproved callback context falls back instead of becoming an open redirect")
    void unapprovedContext_usesConfiguredFallback() throws IOException {
        MockHttpServletRequest request = callbackRequest(OAuthLoginContext.codeExchange(
                "quizzence-web",
                "https://attacker.example.com/callback",
                "A".repeat(43)
        ));

        handler.onAuthenticationFailure(
                request,
                new MockHttpServletResponse(),
                new BadCredentialsException("hidden")
        );

        assertThat(capturedRedirect())
                .startsWith(FALLBACK_REDIRECT + "?error=")
                .doesNotContain("attacker.example.com");
    }

    @Test
    @DisplayName("a committed failure response is not changed")
    void committedResponse_isNotChanged() throws IOException {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        handler.onAuthenticationFailure(
                request,
                response,
                new BadCredentialsException("hidden")
        );

        verify(response).isCommitted();
        verifyNoInteractions(redirectStrategy, request);
    }

    private String capturedRedirect() throws IOException {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), captor.capture());
        return captor.getValue();
    }

    private MockHttpServletRequest callbackRequest(OAuthLoginContext context) {
        OAuth2AuthorizationRequestContextRepository repository =
                new OAuth2AuthorizationRequestContextRepository();
        MockHttpServletRequest initial = new MockHttpServletRequest();
        repository.saveAuthorizationRequest(
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri("https://provider.example.com/oauth/authorize")
                        .clientId("provider-client")
                        .redirectUri("https://backend.example.com/login/oauth2/code/google")
                        .state("provider-state")
                        .attributes(attributes -> attributes.put(
                                OAuth2AuthorizationRequestContextRepository.AUTHORIZATION_CONTEXT_ATTRIBUTE,
                                context
                        ))
                        .build(),
                initial,
                new MockHttpServletResponse()
        );
        MockHttpServletRequest callback = new MockHttpServletRequest();
        callback.setSession(initial.getSession(false));
        callback.setParameter("state", "provider-state");
        repository.removeAuthorizationRequest(callback, new MockHttpServletResponse());
        return callback;
    }

    private void assertPrivateRedirectHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
    }
}
