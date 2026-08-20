package uk.gegc.quizmaker.features.auth.infra.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.application.OAuthPkcePolicy;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRequestException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Adds the first-party client binding to Spring's provider authorization request.
 *
 * <p>The application PKCE challenge is stored only in server-side attributes. It is
 * deliberately not an OAuth additional parameter and therefore is not forwarded to the
 * upstream provider.</p>
 */
@Component
public class OAuth2LoginAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    static final String CLIENT_ID_PARAMETER = "client_id";
    static final String REDIRECT_URI_PARAMETER = "redirect_uri";
    static final String CODE_CHALLENGE_PARAMETER = "code_challenge";
    static final String CODE_CHALLENGE_METHOD_PARAMETER = "code_challenge_method";

    private static final List<String> CLIENT_CONTEXT_PARAMETERS = List.of(
            CLIENT_ID_PARAMETER,
            REDIRECT_URI_PARAMETER,
            CODE_CHALLENGE_PARAMETER,
            CODE_CHALLENGE_METHOD_PARAMETER
    );

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final OAuth2ExchangeProperties properties;
    private final Clock utcClock;

    public OAuth2LoginAuthorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2ExchangeProperties properties,
            @Qualifier("utcClock") Clock utcClock
    ) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI
        );
        this.properties = properties;
        this.utcClock = utcClock;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return attachContext(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return attachContext(request, delegate.resolve(request, clientRegistrationId));
    }

    private OAuth2AuthorizationRequest attachContext(
            HttpServletRequest request,
            OAuth2AuthorizationRequest authorizationRequest
    ) {
        if (authorizationRequest == null) {
            return null;
        }

        OAuthLoginContext context = resolveContext(request);
        return OAuth2AuthorizationRequest.from(authorizationRequest)
                .attributes(attributes -> attributes.put(
                        OAuth2AuthorizationRequestContextRepository.AUTHORIZATION_CONTEXT_ATTRIBUTE,
                        context
                ))
                .build();
    }

    private OAuthLoginContext resolveContext(HttpServletRequest request) {
        Instant now = utcClock.instant();
        properties.validateConfiguration(now);
        boolean anyContextParameter = CLIENT_CONTEXT_PARAMETERS.stream()
                .anyMatch(name -> request.getParameterMap().containsKey(name));
        if (!anyContextParameter) {
            if (!properties.isLegacyRedirectAllowed(now)) {
                throw new OAuthExchangeRequestException();
            }
            return OAuthLoginContext.legacy(properties.getRedirectUri());
        }

        if (!hasExactlyOneValue(request, CLIENT_ID_PARAMETER)
                || !hasExactlyOneValue(request, REDIRECT_URI_PARAMETER)
                || !hasExactlyOneValue(request, CODE_CHALLENGE_PARAMETER)
                || !hasExactlyOneValue(request, CODE_CHALLENGE_METHOD_PARAMETER)) {
            throw new OAuthExchangeRequestException();
        }

        String clientId = request.getParameter(CLIENT_ID_PARAMETER);
        String redirectUri = request.getParameter(REDIRECT_URI_PARAMETER);
        String codeChallenge = request.getParameter(CODE_CHALLENGE_PARAMETER);
        String codeChallengeMethod = request.getParameter(CODE_CHALLENGE_METHOD_PARAMETER);

        OAuthPkcePolicy.requireChallenge(codeChallenge, codeChallengeMethod);
        try {
            properties.requireClient(clientId, redirectUri);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new OAuthExchangeRequestException();
        }

        return OAuthLoginContext.codeExchange(clientId, redirectUri, codeChallenge);
    }

    private boolean hasExactlyOneValue(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        return values != null
                && values.length == 1
                && StringUtils.hasText(values[0]);
    }
}
