package uk.gegc.quizmaker.features.auth.infra.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeMetricsService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeStoreUnavailableException;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2 authentication success handler")
class OAuth2AuthenticationSuccessHandlerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final String APP_CLIENT_ID = "quizzence-web";
    private static final String APP_REDIRECT_URI = "https://app.example.com/oauth2/redirect";
    private static final String LEGACY_REDIRECT_URI = "http://localhost:3000/oauth2/redirect";
    private static final String CHALLENGE = "A".repeat(43);
    private static final String CODE = "B".repeat(43);

    @Mock
    private AuthSessionService authSessionService;
    @Mock
    private OAuthExchangeService oauthExchangeService;
    @Mock
    private OAuthExchangeMetricsService metricsService;
    @Mock
    private Authentication authentication;
    @Mock
    private RedirectStrategy redirectStrategy;

    private OAuth2ExchangeProperties properties;
    private OAuth2AuthenticationSuccessHandler handler;
    private UUID userId;

    @BeforeEach
    void setUp() {
        properties = properties();
        handler = new OAuth2AuthenticationSuccessHandler(
                authSessionService,
                oauthExchangeService,
                metricsService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        handler.setRedirectStrategy(redirectStrategy);
        userId = UUID.randomUUID();
    }

    @Test
    @DisplayName("secure mode redirects only a one-time code and creates no JWT session yet")
    void codeExchange_redirectsOnlyCode() throws IOException {
        OAuthLoginContext context = codeContext();
        MockHttpServletRequest request = callbackRequest(context);
        MockHttpServletResponse response = new MockHttpServletResponse();
        CustomOAuth2User principal = customPrincipal(userId);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(oauthExchangeService.issueCode(userId, context)).thenReturn(CODE);

        handler.onAuthenticationSuccess(request, response, authentication);

        String redirect = capturedRedirect();
        assertThat(redirect).isEqualTo(APP_REDIRECT_URI + "?code=" + CODE);
        assertThat(redirect).doesNotContain("accessToken", "refreshToken", "email");
        verifyNoInteractions(authSessionService);
        verify(metricsService, never()).recordLegacyRedirect();
        assertPrivateRedirectHeaders(response);
    }

    @Test
    @DisplayName("production redirect strategy never DEBUG-logs the one-time code URL")
    void codeExchange_productionRedirectDoesNotLogCode() throws IOException {
        Logger springRedirectLogger = (Logger) LoggerFactory.getLogger(DefaultRedirectStrategy.class);
        Level previousLevel = springRedirectLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        springRedirectLogger.setLevel(Level.DEBUG);
        springRedirectLogger.addAppender(appender);
        try {
            OAuth2AuthenticationSuccessHandler productionHandler = new OAuth2AuthenticationSuccessHandler(
                    authSessionService,
                    oauthExchangeService,
                    metricsService,
                    properties,
                    Clock.fixed(NOW, ZoneOffset.UTC)
            );
            OAuthLoginContext context = codeContext();
            MockHttpServletResponse response = new MockHttpServletResponse();
            when(authentication.getPrincipal()).thenReturn(customPrincipal(userId));
            when(oauthExchangeService.issueCode(userId, context)).thenReturn(CODE);

            productionHandler.onAuthenticationSuccess(callbackRequest(context), response, authentication);

            assertThat(response.getRedirectedUrl()).isEqualTo(APP_REDIRECT_URI + "?code=" + CODE);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(CODE));
        } finally {
            springRedirectLogger.detachAppender(appender);
            springRedirectLogger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    @DisplayName("a committed response is checked before any code or token write")
    void committedResponse_performsNoCredentialWrite() throws IOException {
        HttpServletRequest request = org.mockito.Mockito.mock(HttpServletRequest.class);
        HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);
        when(response.isCommitted()).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, authentication);

        verify(response).isCommitted();
        verifyNoInteractions(oauthExchangeService, authSessionService, metricsService, redirectStrategy);
    }

    @Test
    @DisplayName("missing validated context fails closed without issuing any credential")
    void missingContext_failsClosed() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertThat(capturedRedirect())
                .isEqualTo(LEGACY_REDIRECT_URI + "?error=" + OAuth2AuthenticationSuccessHandler.ERROR_FLOW_INVALID);
        verifyNoInteractions(oauthExchangeService, authSessionService, metricsService, authentication);
    }

    @Test
    @DisplayName("unexpected principal type cannot mint a code and returns a bounded error")
    void unexpectedPrincipal_failsClosed() throws IOException {
        OAuthLoginContext context = codeContext();
        when(authentication.getPrincipal()).thenReturn("provider-subject");

        handler.onAuthenticationSuccess(
                callbackRequest(context),
                new MockHttpServletResponse(),
                authentication
        );

        assertThat(capturedRedirect())
                .isEqualTo(APP_REDIRECT_URI + "?error=" + OAuth2AuthenticationSuccessHandler.ERROR_LOGIN_FAILED);
        verifyNoInteractions(oauthExchangeService, authSessionService, metricsService);
    }

    @Test
    @DisplayName("exchange-store failures expose no internal detail or credential")
    void codeStoreFailure_returnsSafeTemporaryError() throws IOException {
        OAuthLoginContext context = codeContext();
        when(authentication.getPrincipal()).thenReturn(customPrincipal(userId));
        when(oauthExchangeService.issueCode(userId, context))
                .thenThrow(new OAuthExchangeStoreUnavailableException(
                        new IllegalStateException("database-host-and-code")
                ));

        handler.onAuthenticationSuccess(
                callbackRequest(context),
                new MockHttpServletResponse(),
                authentication
        );

        assertThat(capturedRedirect())
                .isEqualTo(APP_REDIRECT_URI + "?error="
                        + OAuth2AuthenticationSuccessHandler.ERROR_TEMPORARILY_UNAVAILABLE)
                .doesNotContain("database-host", CODE, "Token");
        verifyNoInteractions(authSessionService, metricsService);
    }

    @Test
    @DisplayName("legacy mode is honored only while its absolute deadline is still valid")
    void legacyBeforeDeadline_issuesExistingTokenContract() throws IOException {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW.plusSeconds(60));
        OAuthLoginContext context = OAuthLoginContext.legacy(LEGACY_REDIRECT_URI);
        when(authSessionService.issueTokens(authentication))
                .thenReturn(new JwtResponse("access-token", "refresh-token", 1L, 2L));

        handler.onAuthenticationSuccess(
                callbackRequest(context),
                new MockHttpServletResponse(),
                authentication
        );

        assertThat(capturedRedirect())
                .isEqualTo(LEGACY_REDIRECT_URI
                        + "?accessToken=access-token&refreshToken=refresh-token");
        verify(metricsService).recordLegacyRedirect();
        verifyNoInteractions(oauthExchangeService);
    }

    @Test
    @DisplayName("a legacy metrics failure cannot strand an already-created authentication session")
    void legacyMetricFailure_stillReturnsIssuedTokens() throws IOException {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW.plusSeconds(60));
        OAuthLoginContext context = OAuthLoginContext.legacy(LEGACY_REDIRECT_URI);
        when(authSessionService.issueTokens(authentication))
                .thenReturn(new JwtResponse("access-token", "refresh-token", 1L, 2L));
        org.mockito.Mockito.doThrow(new IllegalStateException("metrics backend"))
                .when(metricsService).recordLegacyRedirect();

        handler.onAuthenticationSuccess(
                callbackRequest(context),
                new MockHttpServletResponse(),
                authentication
        );

        assertThat(capturedRedirect())
                .isEqualTo(LEGACY_REDIRECT_URI
                        + "?accessToken=access-token&refreshToken=refresh-token")
                .doesNotContain("metrics");
        verifyNoInteractions(oauthExchangeService);
    }

    @Test
    @DisplayName("legacy mode is rechecked at callback and cannot issue after expiry")
    void legacyAtDeadline_doesNotIssueTokens() throws IOException {
        properties.getExchange().setLegacyTokenRedirectUntil(NOW);

        handler.onAuthenticationSuccess(
                callbackRequest(OAuthLoginContext.legacy(LEGACY_REDIRECT_URI)),
                new MockHttpServletResponse(),
                authentication
        );

        assertThat(capturedRedirect())
                .isEqualTo(LEGACY_REDIRECT_URI + "?error="
                        + OAuth2AuthenticationSuccessHandler.ERROR_LEGACY_EXPIRED);
        verifyNoInteractions(authSessionService, oauthExchangeService, metricsService, authentication);
    }

    @Test
    @DisplayName("the response commitment check precedes the one-time code write")
    void codeExchange_checksResponseBeforeIssue() throws IOException {
        OAuthLoginContext context = codeContext();
        MockHttpServletResponse response = org.mockito.Mockito.spy(new MockHttpServletResponse());
        when(authentication.getPrincipal()).thenReturn(customPrincipal(userId));
        when(oauthExchangeService.issueCode(userId, context)).thenReturn(CODE);

        handler.onAuthenticationSuccess(callbackRequest(context), response, authentication);

        var order = inOrder(response, oauthExchangeService, redirectStrategy);
        order.verify(response).isCommitted();
        order.verify(oauthExchangeService).issueCode(userId, context);
        order.verify(redirectStrategy).sendRedirect(any(), eq(response), any());
    }

    private String capturedRedirect() throws IOException {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(redirectStrategy).sendRedirect(any(), any(), captor.capture());
        return captor.getValue();
    }

    private OAuth2ExchangeProperties properties() {
        OAuth2ExchangeProperties configured = new OAuth2ExchangeProperties();
        configured.setRedirectUri(LEGACY_REDIRECT_URI);
        OAuth2ExchangeProperties.Client client = new OAuth2ExchangeProperties.Client();
        client.setRedirectUri(APP_REDIRECT_URI);
        configured.getExchange().getClients().put(APP_CLIENT_ID, client);
        return configured;
    }

    private OAuthLoginContext codeContext() {
        return OAuthLoginContext.codeExchange(APP_CLIENT_ID, APP_REDIRECT_URI, CHALLENGE);
    }

    private CustomOAuth2User customPrincipal(UUID id) {
        DefaultOAuth2User delegate = new DefaultOAuth2User(
                List.of(),
                Map.of("sub", "provider-subject"),
                "sub"
        );
        return new CustomOAuth2User(delegate, id, "student", List.of());
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
