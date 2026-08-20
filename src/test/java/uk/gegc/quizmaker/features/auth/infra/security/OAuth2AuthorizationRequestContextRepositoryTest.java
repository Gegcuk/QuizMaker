package uk.gegc.quizmaker.features.auth.infra.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OAuth2 authorization request context repository")
class OAuth2AuthorizationRequestContextRepositoryTest {

    private static final String STATE = "provider-state";
    private static final String REDIRECT_URI = "https://app.example.com/oauth2/redirect";

    private final OAuth2AuthorizationRequestContextRepository repository =
            new OAuth2AuthorizationRequestContextRepository();

    @Test
    @DisplayName("matching provider state exposes the saved code-exchange context after removal")
    void matchingState_exposesContextAfterRemoval() {
        OAuthLoginContext context = OAuthLoginContext.codeExchange(
                "quizzence-web",
                REDIRECT_URI,
                "A".repeat(43)
        );
        MockHttpServletRequest initialRequest = new MockHttpServletRequest();
        repository.saveAuthorizationRequest(
                authorizationRequest(context),
                initialRequest,
                new MockHttpServletResponse()
        );

        MockHttpServletRequest callbackRequest = callbackRequest(initialRequest, STATE);
        OAuth2AuthorizationRequest removed = repository.removeAuthorizationRequest(
                callbackRequest,
                new MockHttpServletResponse()
        );

        assertThat(removed).isNotNull();
        assertThat(OAuth2AuthorizationRequestContextRepository.callbackContext(callbackRequest))
                .contains(context);
        assertThat(repository.loadAuthorizationRequest(callbackRequest)).isNull();
    }

    @Test
    @DisplayName("tampered provider state cannot expose or remove the saved application context")
    void tamperedState_doesNotExposeContext() {
        OAuthLoginContext context = OAuthLoginContext.codeExchange(
                "quizzence-web",
                REDIRECT_URI,
                "A".repeat(43)
        );
        MockHttpServletRequest initialRequest = new MockHttpServletRequest();
        repository.saveAuthorizationRequest(
                authorizationRequest(context),
                initialRequest,
                new MockHttpServletResponse()
        );

        MockHttpServletRequest callbackRequest = callbackRequest(initialRequest, "tampered-state");

        assertThat(repository.removeAuthorizationRequest(callbackRequest, new MockHttpServletResponse()))
                .isNull();
        assertThat(OAuth2AuthorizationRequestContextRepository.callbackContext(callbackRequest))
                .isEmpty();

        callbackRequest.setParameter("state", STATE);
        assertThat(repository.loadAuthorizationRequest(callbackRequest)).isNotNull();
    }

    @Test
    @DisplayName("a request without an explicit code or legacy mode is never saved")
    void missingContext_isRejectedBeforeSave() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        OAuth2AuthorizationRequest requestWithoutContext = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://provider.example.com/oauth/authorize")
                .clientId("provider-client")
                .redirectUri("https://backend.example.com/login/oauth2/code/google")
                .state(STATE)
                .build();

        assertThatThrownBy(() -> repository.saveAuthorizationRequest(
                requestWithoutContext,
                request,
                new MockHttpServletResponse()
        )).isInstanceOf(IllegalStateException.class);

        assertThat(request.getSession(false)).isNull();
    }

    @Test
    @DisplayName("an in-flight request created before this rollout is returned without trusted context")
    void legacyInFlightRequest_withoutExplicitContext_isReturnedWithoutContext() {
        @SuppressWarnings("unchecked")
        AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate =
                mock(AuthorizationRequestRepository.class);
        OAuth2AuthorizationRequestContextRepository compatibilityRepository =
                new OAuth2AuthorizationRequestContextRepository(delegate);
        MockHttpServletRequest callback = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthorizationRequest oldRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://provider.example.com/oauth/authorize")
                .clientId("provider-client")
                .redirectUri("https://backend.example.com/login/oauth2/code/google")
                .state(STATE)
                .build();
        when(delegate.removeAuthorizationRequest(callback, response))
                .thenReturn(oldRequest);

        OAuth2AuthorizationRequest removed = compatibilityRepository.removeAuthorizationRequest(
                callback,
                response
        );

        assertThat(removed).isSameAs(oldRequest);
        assertThat(OAuth2AuthorizationRequestContextRepository.callbackContext(callback)).isEmpty();
    }

    private OAuth2AuthorizationRequest authorizationRequest(OAuthLoginContext context) {
        return OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://provider.example.com/oauth/authorize")
                .clientId("provider-client")
                .redirectUri("https://backend.example.com/login/oauth2/code/google")
                .state(STATE)
                .attributes(attributes -> attributes.put(
                        OAuth2AuthorizationRequestContextRepository.AUTHORIZATION_CONTEXT_ATTRIBUTE,
                        context
                ))
                .build();
    }

    private MockHttpServletRequest callbackRequest(MockHttpServletRequest initialRequest, String state) {
        MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
        callbackRequest.setSession(initialRequest.getSession(false));
        callbackRequest.setParameter("state", state);
        return callbackRequest;
    }
}
