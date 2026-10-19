package uk.gegc.quizmaker.features.auth.infra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.auth.application.AuthService;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP admission and MySQL, with credential effects replaced by an offline fake. */
@SpringBootTest(properties = {
        "app.auth.rate-limit.limits.login-account.requests=2",
        "app.auth.rate-limit.limits.login-account-network.requests=2"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("db-serial")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthThrottleHttpIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuthService credentials;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM auth_rate_limits");
        when(credentials.login(any())).thenThrow(new UnauthorizedException("Invalid username or password"));
    }

    @AfterEach
    void cleanup() { jdbc.update("DELETE FROM auth_rate_limits"); }

    @Test
    void failedLoginsPersistAcrossHttpRequestsAndChangingNetworkCannotBypassAccountLimit() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/auth/login").with(request -> { request.setRemoteAddr("198.51.100.1"); return request; })
                            .contentType(MediaType.APPLICATION_JSON).content(payload()))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/v1/auth/login").with(request -> { request.setRemoteAddr("2001:db8::2"); return request; })
                        .header("Origin", "http://localhost:3000")
                        .contentType(MediaType.APPLICATION_JSON).content(payload()))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Retry-After")))
                .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/rate-limit-exceeded"));
        verify(credentials, times(2)).login(any());
    }

    @Test
    void missingLimiterStoreReturns503BeforeCredentialVerification() throws Exception {
        jdbc.execute("RENAME TABLE auth_rate_limits TO auth_rate_limits_unavailable");
        try {
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(payload()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.type").value("https://quizzence.com/docs/errors/auth-rate-limit-unavailable"))
                    .andExpect(jsonPath("$.detail").value("Authentication protection is temporarily unavailable. Please retry later."));
            verifyNoInteractions(credentials);
        } finally {
            jdbc.execute("RENAME TABLE auth_rate_limits_unavailable TO auth_rate_limits");
        }
    }

    private String payload() { return "{\"username\":\"synthetic-missing-user-457\",\"password\":\"irrelevant\"}"; }
}
