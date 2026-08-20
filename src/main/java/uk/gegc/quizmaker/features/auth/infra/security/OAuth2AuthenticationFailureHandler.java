package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;

import java.io.IOException;

/** Redirects OAuth failures with a bounded public code and no provider or account details. */
@Component
@Slf4j
public class OAuth2AuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    static final String ACCESS_DENIED_ERROR = "oauth_access_denied";
    static final String AUTHENTICATION_FAILED_ERROR = "oauth_authentication_failed";

    private final OAuth2ExchangeProperties properties;

    public OAuth2AuthenticationFailureHandler(OAuth2ExchangeProperties properties) {
        this.properties = properties;
        setRedirectStrategy(new SecretSafeRedirectStrategy());
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException {
        if (response.isCommitted()) {
            log.debug("OAuth2 failure response was already committed");
            return;
        }

        String publicError = publicError(exception);
        String targetUrl = UriComponentsBuilder.fromUriString(safeRedirect(request))
                .queryParam("error", publicError)
                .build()
                .encode()
                .toUriString();

        log.warn("OAuth2 authentication failed safely: outcome={}", publicError);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private String safeRedirect(HttpServletRequest request) {
        OAuthLoginContext context = OAuth2AuthorizationRequestContextRepository.callbackContext(request)
                .orElse(null);
        if (context == null || context.mode() == null) {
            return properties.getRedirectUri();
        }

        try {
            if (context.mode() == OAuthLoginContext.Mode.CODE_EXCHANGE) {
                properties.requireClient(context.clientId(), context.redirectUri());
                return context.redirectUri();
            }
            if (context.mode() == OAuthLoginContext.Mode.LEGACY
                    && properties.getRedirectUri().equals(context.redirectUri())) {
                return context.redirectUri();
            }
        } catch (RuntimeException ignored) {
            // Do not reflect an untrusted or no-longer-allowed redirect URI.
        }
        return properties.getRedirectUri();
    }

    private String publicError(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauthException
                && "access_denied".equals(oauthException.getError().getErrorCode())) {
            return ACCESS_DENIED_ERROR;
        }
        return AUTHENTICATION_FAILED_ERROR;
    }
}
