package uk.gegc.quizmaker.features.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.cors.CorsConfigurationSource;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthCodeExchangeRequest;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeMetricsService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;
import uk.gegc.quizmaker.features.auth.infra.security.CustomOAuth2UserService;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationFailureHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthenticationSuccessHandler;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2AuthorizationRequestContextRepository;
import uk.gegc.quizmaker.features.auth.infra.security.OAuth2LoginAuthorizationRequestResolver;
import uk.gegc.quizmaker.shared.config.SecurityConfig;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OAuthCodeExchangeController.class)
@Import({
        SecurityConfig.class,
        OAuth2LoginAuthorizationRequestResolver.class,
        OAuth2AuthorizationRequestContextRepository.class,
        OAuth2AuthenticationSuccessHandler.class,
        OAuth2AuthenticationFailureHandler.class,
        OAuthCodeExchangeWebConfiguration.class
})
@EnableConfigurationProperties(OAuth2ExchangeProperties.class)
@TestPropertySource(properties = {
        "app.oauth2.redirect-uri=https://www.quizzence.com/oauth2/redirect",
        "app.oauth2.exchange.clients.quizzence-web.redirect-uri=https://www.quizzence.com/oauth2/redirect"
})
@DisplayName("OAuth code exchange route security")
class OAuthCodeExchangeSecurityTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String REDIRECT_URI = "https://www.quizzence.com/oauth2/redirect";
    private static final String CHALLENGE = "A".repeat(43);
    private static final OAuthCodeExchangeRequest REQUEST = new OAuthCodeExchangeRequest(
            "c".repeat(43),
            "quizzence-web",
            REDIRECT_URI,
            "v".repeat(43)
    );

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OAuthExchangeService oauthExchangeService;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private TrustedProxyUtil trustedProxyUtil;

    @MockitoBean
    private AuthSessionService authSessionService;

    @MockitoBean
    private AuthSessionMetricsService authSessionMetricsService;

    @MockitoBean
    private OAuthExchangeMetricsService oauthExchangeMetricsService;

    @MockitoBean(name = "corsConfigurationSource")
    private CorsConfigurationSource corsConfigurationSource;

    @MockitoBean
    private CustomOAuth2UserService customOAuth2UserService;

    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    @MockitoBean(name = "utcClock")
    private Clock utcClock;

    @BeforeEach
    void setUpOAuthProvider() {
        when(utcClock.instant()).thenReturn(NOW);
        when(clientRegistrationRepository.findByRegistrationId("google"))
                .thenReturn(providerRegistration());
    }

    @Test
    @DisplayName("anonymous browsers can POST a PKCE-bound code without a bearer token")
    void anonymousPost_isPermittedByRealFilterChain() throws Exception {
        when(trustedProxyUtil.getClientIp(any(HttpServletRequest.class))).thenReturn("203.0.113.42");
        when(oauthExchangeService.exchange(REQUEST))
                .thenReturn(new JwtResponse("access", "refresh", 60_000, 120_000));

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(REQUEST)))
                .andExpect(status().isOk());

        verify(oauthExchangeService).exchange(REQUEST);
    }

    @Test
    @DisplayName("anonymous GET is not broadened by the public POST registration")
    void anonymousGet_remainsProtected() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/exchange"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(oauthExchangeService);
    }

    @Test
    @DisplayName("anonymous PUT is not broadened by the public POST registration")
    void anonymousPut_remainsProtected() throws Exception {
        mockMvc.perform(put("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(REQUEST)))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(oauthExchangeService);
    }

    @Test
    @DisplayName("real OAuth filter chain redirects valid S256 initiation to the provider")
    void validPkceInitiation_reachesProviderThroughRealFilterChain() throws Exception {
        MvcResult result = mockMvc.perform(validAuthorizationRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("https://provider.example.com/oauth/authorize?")
                .contains("client_id=provider-client-id", "state=")
                .doesNotContain("quizzence-web", CHALLENGE, "redirect_uri=" + REDIRECT_URI);
        assertThat(result.getRequest().getSession(false)).isNotNull();
        verifyNoInteractions(oauthExchangeService, authSessionService);
    }

    @Test
    @DisplayName("real OAuth filter chain rejects incomplete PKCE without contacting the provider")
    void incompletePkceInitiation_redirectsBoundedError() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google")
                        .param("client_id", "quizzence-web"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        REDIRECT_URI + "?error=oauth_authentication_failed"
                ));

        verifyNoInteractions(oauthExchangeService, authSessionService);
    }

    @Test
    @DisplayName("real OAuth filter chain rejects plain PKCE without contacting the provider")
    void plainPkceInitiation_redirectsBoundedError() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google")
                        .param("client_id", "quizzence-web")
                        .param("redirect_uri", REDIRECT_URI)
                        .param("code_challenge", CHALLENGE)
                        .param("code_challenge_method", "plain"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        REDIRECT_URI + "?error=oauth_authentication_failed"
                ));

        verifyNoInteractions(oauthExchangeService, authSessionService);
    }

    @Test
    @DisplayName("tampered provider state cannot reach token or code issuance")
    void tamperedProviderState_failsBeforeProviderExchange() throws Exception {
        MvcResult initiation = mockMvc.perform(validAuthorizationRequest())
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) initiation.getRequest().getSession(false);

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .param("code", "provider-code")
                        .param("state", "tampered-provider-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl(
                        REDIRECT_URI + "?error=oauth_authentication_failed"
                ));

        verifyNoInteractions(oauthExchangeService, authSessionService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validAuthorizationRequest() {
        return get("/oauth2/authorization/google")
                .param("client_id", "quizzence-web")
                .param("redirect_uri", REDIRECT_URI)
                .param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256");
    }

    private ClientRegistration providerRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("provider-client-id")
                .clientSecret("provider-client-secret")
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
}
