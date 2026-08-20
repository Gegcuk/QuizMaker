package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeMetricsService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.application.OAuthPkcePolicy;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeStoreUnavailableException;

import java.io.IOException;
import java.time.Clock;

/** Completes OAuth login without exposing bearer credentials in code-exchange redirects. */
@Component
@Slf4j
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    static final String ERROR_FLOW_INVALID = "oauth_flow_invalid";
    static final String ERROR_LEGACY_EXPIRED = "oauth_legacy_login_expired";
    static final String ERROR_TEMPORARILY_UNAVAILABLE = "oauth_login_temporarily_unavailable";
    static final String ERROR_LOGIN_FAILED = "oauth_login_failed";

    private final AuthSessionService authSessionService;
    private final OAuthExchangeService oauthExchangeService;
    private final OAuthExchangeMetricsService metricsService;
    private final OAuth2ExchangeProperties properties;
    private final Clock utcClock;

    public OAuth2AuthenticationSuccessHandler(
            AuthSessionService authSessionService,
            OAuthExchangeService oauthExchangeService,
            OAuthExchangeMetricsService metricsService,
            OAuth2ExchangeProperties properties,
            @Qualifier("utcClock") Clock utcClock
    ) {
        this.authSessionService = authSessionService;
        this.oauthExchangeService = oauthExchangeService;
        this.metricsService = metricsService;
        this.properties = properties;
        this.utcClock = utcClock;
        setRedirectStrategy(new SecretSafeRedirectStrategy());
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException {
        // Do not create a code, authentication session, or token for an unusable response.
        if (response.isCommitted()) {
            log.debug("OAuth2 authentication response was already committed before credential issuance");
            return;
        }

        String targetUrl = determineTargetUrl(request, authentication);
        clearAuthenticationAttributes(request);
        preventSensitiveRedirectCaching(response);
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    protected String determineTargetUrl(HttpServletRequest request, Authentication authentication) {
        OAuthLoginContext context = OAuth2AuthorizationRequestContextRepository.callbackContext(request)
                .orElse(null);
        if (context == null) {
            log.warn("OAuth2 authentication completed without a validated flow context");
            return errorTarget(properties.getRedirectUri(), ERROR_FLOW_INVALID);
        }

        try {
            return switch (context.mode()) {
                case CODE_EXCHANGE -> codeExchangeTarget(context, authentication);
                case LEGACY -> legacyTarget(context, authentication);
            };
        } catch (OAuthExchangeStoreUnavailableException exception) {
            log.warn("OAuth2 exchange-code store was unavailable after provider authentication");
            return errorTarget(safeContextRedirect(context), ERROR_TEMPORARILY_UNAVAILABLE);
        } catch (RuntimeException exception) {
            // Never copy provider, persistence, code, token, or account details into a redirect.
            log.warn("OAuth2 login completion failed safely: type={}",
                    exception.getClass().getSimpleName());
            return errorTarget(safeContextRedirect(context), ERROR_LOGIN_FAILED);
        }
    }

    private String codeExchangeTarget(OAuthLoginContext context, Authentication authentication) {
        properties.requireClient(context.clientId(), context.redirectUri());
        OAuthPkcePolicy.requireChallenge(context.codeChallenge(), "S256");
        if (!(authentication.getPrincipal() instanceof CustomOAuth2User principal)) {
            throw new IllegalStateException("OAuth principal is not bound to an application user");
        }

        String code = oauthExchangeService.issueCode(principal.getUserId(), context);
        log.info("OAuth2 authentication completed through one-time code exchange");
        return UriComponentsBuilder.fromUriString(context.redirectUri())
                .queryParam("code", code)
                .build()
                .encode()
                .toUriString();
    }

    private String legacyTarget(OAuthLoginContext context, Authentication authentication) {
        if (!properties.getRedirectUri().equals(context.redirectUri())
                || !properties.isLegacyRedirectAllowed(utcClock.instant())) {
            return errorTarget(properties.getRedirectUri(), ERROR_LEGACY_EXPIRED);
        }

        JwtResponse tokens = authSessionService.issueTokens(authentication);
        recordLegacyRedirectBestEffort();
        log.warn("OAuth2 authentication used the temporary legacy token redirect");
        return UriComponentsBuilder.fromUriString(context.redirectUri())
                .queryParam("accessToken", tokens.accessToken())
                .queryParam("refreshToken", tokens.refreshToken())
                .build()
                .encode()
                .toUriString();
    }

    private void recordLegacyRedirectBestEffort() {
        try {
            metricsService.recordLegacyRedirect();
        } catch (RuntimeException exception) {
            // Token/session issuance has already succeeded; observability must not strand that session.
            log.warn("OAuth2 legacy-redirect metric could not be recorded: type={}",
                    exception.getClass().getSimpleName());
        }
    }

    private String safeContextRedirect(OAuthLoginContext context) {
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
            // Fall back to the configured URI without reflecting untrusted callback input.
        }
        return properties.getRedirectUri();
    }

    private String errorTarget(String redirectUri, String errorCode) {
        return UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", errorCode)
                .build()
                .encode()
                .toUriString();
    }

    private void preventSensitiveRedirectCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
