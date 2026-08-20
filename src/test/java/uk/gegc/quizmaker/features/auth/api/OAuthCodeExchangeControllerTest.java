package uk.gegc.quizmaker.features.auth.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthCodeExchangeRequest;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;
import uk.gegc.quizmaker.features.auth.application.OAuthPkcePolicy;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRejectedException;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeStoreUnavailableException;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeRejectionReason;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OAuthCodeExchangeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(OAuthCodeExchangeWebConfiguration.class)
@DisplayName("OAuth code exchange API")
class OAuthCodeExchangeControllerTest {

    private static final String CODE = "c".repeat(43);
    private static final String VERIFIER = "v".repeat(43);
    private static final String CLIENT_ID = "quizzence-web";
    private static final String REDIRECT_URI = "https://www.quizzence.com/oauth2/redirect";
    private static final String CLIENT_IP = "203.0.113.42";

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

    @Test
    @DisplayName("returns tokens once without allowing either response or rate-limit caches to retain credentials")
    void exchange_validRequest_returnsNoStoreTokenResponse() throws Exception {
        OAuthCodeExchangeRequest request = validRequest();
        JwtResponse tokens = new JwtResponse("access-jwt", "refresh-jwt", 43_200_000, 345_600_000);
        when(trustedProxyUtil.getClientIp(any(HttpServletRequest.class))).thenReturn(CLIENT_IP);
        when(oauthExchangeService.exchange(request)).thenReturn(tokens);

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-jwt"));

        verify(rateLimitService).checkRateLimit("oauth-code-exchange-ip", CLIENT_IP, 30);
        verify(rateLimitService).checkRateLimit(
                "oauth-code-exchange-code",
                OAuthPkcePolicy.hashRawCode(CODE),
                5
        );
        verify(oauthExchangeService).exchange(request);
        assertThat(tokens.toString())
                .contains("credentials=redacted")
                .doesNotContain("access-jwt", "refresh-jwt");
    }

    @Test
    @DisplayName("bounds malformed credential traffic by IP without creating a raw-code rate-limit key")
    void exchange_malformedCode_returnsRedacted400() throws Exception {
        OAuthCodeExchangeRequest request = new OAuthCodeExchangeRequest(
                "raw-code-secret",
                CLIENT_ID,
                REDIRECT_URI,
                VERIFIER
        );
        when(trustedProxyUtil.getClientIp(any(HttpServletRequest.class))).thenReturn(CLIENT_IP);

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type")
                        .value("https://quizzence.com/docs/errors/oauth-exchange-invalid-request"))
                .andExpect(jsonPath("$.title").value("Invalid OAuth Exchange Request"))
                .andExpect(jsonPath("$.detail").value("OAuth exchange request is not valid."))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(content().string(not(containsString("raw-code-secret"))))
                .andExpect(content().string(not(containsString(VERIFIER))));

        verify(rateLimitService).checkRateLimit("oauth-code-exchange-ip", CLIENT_IP, 30);
        verify(oauthExchangeService, never()).exchange(any());
    }

    @Test
    @DisplayName("malformed JSON never reflects code or verifier material")
    void exchange_malformedJson_returnsRedacted400() throws Exception {
        String secret = "browser-secret-that-must-not-be-reflected";
        when(trustedProxyUtil.getClientIp(any(HttpServletRequest.class))).thenReturn(CLIENT_IP);

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + secret + "\",\"codeVerifier\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("OAuth exchange request is not valid."))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(content().string(not(containsString(secret))))
                .andExpect(jsonPath("$.parseError").doesNotExist());

        verify(rateLimitService).checkRateLimit("oauth-code-exchange-ip", CLIENT_IP, 30);
        verifyNoInteractions(oauthExchangeService);
    }

    @Test
    @DisplayName("uses one generic 401 response for unknown, expired, or mismatched codes")
    void exchange_invalidCode_returnsGeneric401() throws Exception {
        OAuthCodeExchangeRequest request = validRequest();
        prepareAcceptedBoundary();
        when(oauthExchangeService.exchange(request)).thenThrow(new OAuthExchangeRejectedException(
                OAuthExchangeRejectionReason.PKCE_MISMATCH
        ));

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/oauth-exchange-invalid"))
                .andExpect(jsonPath("$.detail")
                        .value("OAuth sign-in code is invalid or expired. Restart sign-in."))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(content().string(not(containsString(CODE))))
                .andExpect(content().string(not(containsString(VERIFIER))))
                .andExpect(content().string(not(containsString(REDIRECT_URI))));
    }

    @Test
    @DisplayName("reports an already consumed high-entropy code as a conflict without returning credentials")
    void exchange_replayedCode_returns409() throws Exception {
        OAuthCodeExchangeRequest request = validRequest();
        prepareAcceptedBoundary();
        when(oauthExchangeService.exchange(request)).thenThrow(new OAuthExchangeRejectedException(
                OAuthExchangeRejectionReason.REPLAYED
        ));

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/oauth-exchange-replayed"))
                .andExpect(jsonPath("$.detail")
                        .value("OAuth sign-in code has already been used. Restart sign-in."))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(content().string(not(containsString(CODE))))
                .andExpect(content().string(not(containsString(VERIFIER))));
    }

    @Test
    @DisplayName("returns a fixed retryable response when exchange persistence is unavailable")
    void exchange_storeUnavailable_returnsSafe503() throws Exception {
        OAuthCodeExchangeRequest request = validRequest();
        prepareAcceptedBoundary();
        when(oauthExchangeService.exchange(request)).thenThrow(new OAuthExchangeStoreUnavailableException(
                new IllegalStateException("sensitive-database-host")
        ));

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/oauth-exchange-unavailable"))
                .andExpect(jsonPath("$.detail")
                        .value("OAuth exchange is temporarily unavailable. Please retry or restart sign-in."))
                .andExpect(content().string(not(containsString("sensitive-database-host"))))
                .andExpect(content().string(not(containsString(CODE))));
    }

    @Test
    @DisplayName("returns a bounded 429 without calling the exchange service")
    void exchange_ipRateLimited_returnsSafe429() throws Exception {
        OAuthCodeExchangeRequest request = validRequest();
        when(trustedProxyUtil.getClientIp(any(HttpServletRequest.class))).thenReturn(CLIENT_IP);
        doThrow(new RateLimitExceededException("operation and key must not be reflected", 17))
                .when(rateLimitService)
                .checkRateLimit("oauth-code-exchange-ip", CLIENT_IP, 30);

        mockMvc.perform(post("/api/v1/auth/oauth/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "17"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/rate-limit-exceeded"))
                .andExpect(jsonPath("$.detail")
                        .value("Too many OAuth exchange attempts. Please retry later."))
                .andExpect(content().string(not(containsString("operation and key"))))
                .andExpect(content().string(not(containsString(CODE))))
                .andExpect(content().string(not(containsString(VERIFIER))));

        verify(oauthExchangeService, never()).exchange(any());
    }

    private OAuthCodeExchangeRequest validRequest() {
        return new OAuthCodeExchangeRequest(CODE, CLIENT_ID, REDIRECT_URI, VERIFIER);
    }

    private void prepareAcceptedBoundary() {
        when(trustedProxyUtil.getClientIp(any(HttpServletRequest.class))).thenReturn(CLIENT_IP);
    }
}
