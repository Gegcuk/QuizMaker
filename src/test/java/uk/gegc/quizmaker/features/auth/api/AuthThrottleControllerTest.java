package uk.gegc.quizmaker.features.auth.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.features.auth.application.AuthThrottleService;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.domain.exception.AuthRateLimitUnavailableException;
import uk.gegc.quizmaker.features.user.api.dto.AuthenticatedUserDto;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;
import uk.gegc.quizmaker.shared.api.advice.GlobalExceptionHandler;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthThrottleControllerTest {
    AuthService auth;
    AuthThrottleService throttle;
    MockMvc mvc;

    @BeforeEach
    void prepare() {
        auth = mock(AuthService.class);
        throttle = mock(AuthThrottleService.class);
        TrustedProxyUtil proxies = mock(TrustedProxyUtil.class);
        when(proxies.getClientIp(any())).thenReturn("198.51.100.1");
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, throttle, proxies))
                .setControllerAdvice(new AuthThrottleErrorHandler(), new GlobalExceptionHandler()).build();
    }

    @Test
    void knownAndUnknownLoginDenialsHaveSamePublicBodyAndNeverVerifyCredentials() throws Exception {
        doThrow(new RateLimitExceededException("Too many authentication requests. Please retry later.", 13))
                .when(throttle).login(anyString(), anyString());
        for (String identifier : new String[]{"known-user", "unknown-user"}) {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + identifier + "\",\"password\":\"irrelevant\"}"))
                    .andExpect(status().isTooManyRequests()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(header().string("Retry-After", "13")).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.retryAfterSeconds").value(13)).andExpect(jsonPath("$.status").value(429))
                    .andExpect(jsonPath("$.detail").value("Too many authentication requests. Please retry later."));
        }
        verifyNoInteractions(auth);
    }

    @Test
    void refreshDenialDoesNotConsumeOrRotateToken() throws Exception {
        doThrow(new RateLimitExceededException("Too many authentication requests. Please retry later.", 7))
                .when(throttle).refresh(anyString(), anyString());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"synthetic-token\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "7"));
        verifyNoInteractions(auth);
    }

    @Test
    void storeFailureReturnsBounded503WithoutCreatingAccountOrSendingEmail() throws Exception {
        doThrow(new AuthRateLimitUnavailableException(new IllegalStateException("private diagnostic")))
                .when(throttle).registration(anyString(), anyString(), anyString());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"email\":\"student@example.test\",\"password\":\"ValidPass1!\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.detail").value("Authentication protection is temporarily unavailable. Please retry later."));
        verifyNoInteractions(auth);
    }

    @Test
    void oversizedLoginIsRejectedAsValidationErrorBeforeAdmission() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + "a".repeat(255) + "\",\"password\":\"irrelevant\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auth, throttle);
    }

    @Test
    void oversizedPasswordValidationNeverEchoesCredential() throws Exception {
        String password = "synthetic-private-password-".repeat(5);
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/validation-failed"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(password))));
        verifyNoInteractions(auth, throttle);
    }

    @Test
    void malformedCredentialsNeverAppearInParserDiagnostics() throws Exception {
        String credential = "syntheticPrivateCredential";
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"password\":" + credential + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/malformed-json"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(credential))));
        verifyNoInteractions(auth, throttle);
    }

    @Test
    void admittedLoginAndSignupRetainExistingSuccessContracts() throws Exception {
        when(auth.login(any())).thenReturn(new JwtResponse("synthetic-access", "synthetic-refresh", 43_200_000, 345_600_000));
        UUID userId = UUID.randomUUID();
        LocalDateTime created = LocalDateTime.of(2026, 10, 19, 12, 0);
        when(auth.register(any())).thenReturn(new AuthenticatedUserDto(userId, "student", "student@example.test",
                true, Set.of(RoleName.ROLE_USER), created, null, created));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"password\":\"ValidPass1!\"}"))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").value("synthetic-access"))
                .andExpect(jsonPath("$.refreshToken").value("synthetic-refresh"))
                .andExpect(jsonPath("$.accessExpiresInMs").value(43_200_000))
                .andExpect(jsonPath("$.refreshExpiresInMs").value(345_600_000));
        verify(throttle).login("student", "198.51.100.1");
        verify(auth).login(any());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"email\":\"student@example.test\",\"password\":\"ValidPass1!\"}"))
                .andExpect(status().isCreated()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.username").value("student"))
                .andExpect(jsonPath("$.email").value("student@example.test"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.roles[0]").value("ROLE_USER"));
        verify(throttle).registration("student@example.test", "student", "198.51.100.1");
        verify(auth).register(any());
    }
}
