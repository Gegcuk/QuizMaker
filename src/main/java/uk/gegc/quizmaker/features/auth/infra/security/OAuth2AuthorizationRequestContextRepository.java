package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;

import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the browser-client binding beside Spring Security's provider request.
 *
 * <p>Spring removes the provider request before invoking an authentication handler. This
 * wrapper exposes the already state-validated application context as a request attribute
 * during that removal. A context is never recovered from callback query parameters.</p>
 */
@Component
public class OAuth2AuthorizationRequestContextRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String AUTHORIZATION_CONTEXT_ATTRIBUTE =
            OAuth2AuthorizationRequestContextRepository.class.getName() + ".AUTHORIZATION_CONTEXT";
    private static final String CALLBACK_CONTEXT_ATTRIBUTE =
            OAuth2AuthorizationRequestContextRepository.class.getName() + ".CALLBACK_CONTEXT";

    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate;

    public OAuth2AuthorizationRequestContextRepository() {
        this(new HttpSessionOAuth2AuthorizationRequestRepository());
    }

    OAuth2AuthorizationRequestContextRepository(
            AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate
    ) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authorizationRequest != null) {
            requireExplicitContext(authorizationRequest);
        }
        delegate.saveAuthorizationRequest(authorizationRequest, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        OAuth2AuthorizationRequest authorizationRequest =
                delegate.removeAuthorizationRequest(request, response);
        if (authorizationRequest == null) {
            return null;
        }

        Object value = authorizationRequest.getAttribute(AUTHORIZATION_CONTEXT_ATTRIBUTE);
        if (value instanceof OAuthLoginContext context && context.mode() != null) {
            request.setAttribute(CALLBACK_CONTEXT_ATTRIBUTE, context);
        }
        return authorizationRequest;
    }

    public static Optional<OAuthLoginContext> callbackContext(HttpServletRequest request) {
        Object value = request.getAttribute(CALLBACK_CONTEXT_ATTRIBUTE);
        return value instanceof OAuthLoginContext context
                ? Optional.of(context)
                : Optional.empty();
    }

    private OAuthLoginContext requireExplicitContext(OAuth2AuthorizationRequest authorizationRequest) {
        Object value = authorizationRequest.getAttribute(AUTHORIZATION_CONTEXT_ATTRIBUTE);
        if (!(value instanceof OAuthLoginContext context) || context.mode() == null) {
            throw new IllegalStateException("OAuth authorization request is missing its flow context");
        }
        return context;
    }
}
