package uk.gegc.quizmaker.features.auth.infra.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRequestException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OAuth2 login authorization request resolver")
class OAuth2LoginAuthorizationRequestResolverTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String APP_CLIENT_ID = "quizzence-web";
    private static final String APP_REDIRECT_URI = "https://app.example.com/oauth2/redirect";
    private static final String CHALLENGE = "A".repeat(43);

    private OAuth2ExchangeProperties properties;
    private OAuth2LoginAuthorizationRequestResolver resolver;

    @BeforeEach
    void setUp() {
        properties = properties();
        resolver = new OAuth2LoginAuthorizationRequestResolver(
                new InMemoryClientRegistrationRepository(providerRegistration()),
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("complete S256 metadata is bound server-side and not forwarded to the provider")
    void completePkceMetadata_createsCodeExchangeContextOnlyInAttributes() {
        MockHttpServletRequest request = authorizationRequest();
        addCompleteCodeExchangeParameters(request);

        OAuth2AuthorizationRequest resolved = resolver.resolve(request);

        OAuthLoginContext context = resolved.getAttribute(
                OAuth2AuthorizationRequestContextRepository.AUTHORIZATION_CONTEXT_ATTRIBUTE
        );
        assertThat(context).isEqualTo(OAuthLoginContext.codeExchange(
                APP_CLIENT_ID,
                APP_REDIRECT_URI,
                CHALLENGE
        ));
        assertThat(resolved.getAdditionalParameters())
                .doesNotContainKeys("code_challenge", "code_challenge_method", "redirect_uri");
        assertThat(resolved.getClientId()).isEqualTo("provider-client-id");
    }

    @Test
    @DisplayName("metadata-free clients receive an explicit legacy context only before the deadline")
    void noMetadata_beforeDeadline_createsExplicitLegacyContext() {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW.plusSeconds(60));

        OAuth2AuthorizationRequest resolved = resolver.resolve(authorizationRequest());

        assertThat(resolved.<OAuthLoginContext>getAttribute(
                OAuth2AuthorizationRequestContextRepository.AUTHORIZATION_CONTEXT_ATTRIBUTE
        )).isEqualTo(OAuthLoginContext.legacy(properties.getRedirectUri()));
    }

    @Test
    @DisplayName("metadata-free clients fail closed after the compatibility deadline")
    void noMetadata_afterDeadline_isRejected() {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW);

        assertThatThrownBy(() -> resolver.resolve(authorizationRequest()))
                .isInstanceOf(OAuthExchangeRequestException.class);
    }

    @Test
    @DisplayName("partial or blank PKCE metadata never falls back to legacy")
    void partialMetadata_withLegacyAllowed_isRejected() {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW.plusSeconds(60));
        MockHttpServletRequest request = authorizationRequest();
        request.setParameter("client_id", APP_CLIENT_ID);
        request.setParameter("redirect_uri", APP_REDIRECT_URI);
        request.setParameter("code_challenge", "");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(OAuthExchangeRequestException.class);
    }

    @Test
    @DisplayName("duplicate binding parameters are rejected instead of choosing one")
    void duplicateClientId_isRejected() {
        MockHttpServletRequest request = authorizationRequest();
        addCompleteCodeExchangeParameters(request);
        request.setParameter("client_id", APP_CLIENT_ID, "different-client");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(OAuthExchangeRequestException.class);
    }

    @Test
    @DisplayName("an unapproved redirect is rejected without a legacy fallback")
    void unapprovedRedirect_isRejected() {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW.plusSeconds(60));
        MockHttpServletRequest request = authorizationRequest();
        addCompleteCodeExchangeParameters(request);
        request.setParameter("redirect_uri", "https://attacker.example.com/callback");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(OAuthExchangeRequestException.class);
    }

    @Test
    @DisplayName("plain PKCE is rejected even when every field is present")
    void nonS256Challenge_isRejected() {
        MockHttpServletRequest request = authorizationRequest();
        addCompleteCodeExchangeParameters(request);
        request.setParameter("code_challenge_method", "plain");

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(OAuthExchangeRequestException.class);
    }

    @Test
    @DisplayName("unsafe rollout configuration fails before selecting even the secure flow")
    void invalidConfiguration_isRejectedBeforeFlowSelection() {
        properties.setRedirectUri("https://fallback.example.com/oauth2/redirect?unsafe=true");
        MockHttpServletRequest request = authorizationRequest();
        addCompleteCodeExchangeParameters(request);

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact HTTPS URIs");
    }

    private OAuth2ExchangeProperties properties() {
        OAuth2ExchangeProperties configured = new OAuth2ExchangeProperties();
        configured.setRedirectUri("http://localhost:3000/oauth2/redirect");
        OAuth2ExchangeProperties.Client client = new OAuth2ExchangeProperties.Client();
        client.setRedirectUri(APP_REDIRECT_URI);
        configured.getExchange().getClients().put(APP_CLIENT_ID, client);
        return configured;
    }

    private ClientRegistration providerRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("provider-client-id")
                .clientSecret("provider-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("profile", "email")
                .authorizationUri("https://provider.example.com/oauth/authorize")
                .tokenUri("https://provider.example.com/oauth/token")
                .userInfoUri("https://provider.example.com/userinfo")
                .userNameAttributeName("sub")
                .clientName("Provider")
                .build();
    }

    private MockHttpServletRequest authorizationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/oauth2/authorization/google"
        );
        request.setServletPath("/oauth2/authorization/google");
        request.setScheme("https");
        request.setServerName("backend.example.com");
        request.setServerPort(443);
        return request;
    }

    private void addCompleteCodeExchangeParameters(MockHttpServletRequest request) {
        request.setParameter("client_id", APP_CLIENT_ID);
        request.setParameter("redirect_uri", APP_REDIRECT_URI);
        request.setParameter("code_challenge", CHALLENGE);
        request.setParameter("code_challenge_method", "S256");
    }
}
