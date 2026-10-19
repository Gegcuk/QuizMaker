package uk.gegc.quizmaker.features.auth.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.features.auth.application.AuthThrottleService;
import uk.gegc.quizmaker.features.auth.domain.exception.AuthRateLimitUnavailableException;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

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
                .setControllerAdvice(new AuthThrottleErrorHandler()).build();
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
    void admittedLoginAndSignupRetainExistingSuccessContracts() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"password\":\"ValidPass1!\"}"))
                .andExpect(status().isOk());
        verify(throttle).login("student", "198.51.100.1");
        verify(auth).login(any());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"student\",\"email\":\"student@example.test\",\"password\":\"ValidPass1!\"}"))
                .andExpect(status().isCreated());
        verify(throttle).registration("student@example.test", "student", "198.51.100.1");
        verify(auth).register(any());
    }
}
