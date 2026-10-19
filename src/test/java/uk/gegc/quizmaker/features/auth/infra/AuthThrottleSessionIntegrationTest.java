package uk.gegc.quizmaker.features.auth.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.RefreshRequest;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.domain.repository.AuthSessionRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real admission, token verification, session rotation, and persistence; no remote provider calls. */
@SpringBootTest(properties = "app.auth.rate-limit.limits.refresh-session.requests=2")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("db-serial")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthThrottleSessionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthSessionService sessions;
    @Autowired AuthSessionRepository sessionRepository;
    @Autowired UserRepository users;
    @Autowired JwtTokenService tokens;
    UUID userId;
    UUID sessionId;
    JwtResponse issued;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM auth_rate_limits");
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("throttle-" + suffix);
        user.setEmail("throttle-" + suffix + "@example.test");
        user.setHashedPassword("synthetic-not-used-for-password-authentication");
        user.setActive(true);
        user.setEmailVerified(true);
        user.setRoles(new HashSet<>());
        user = users.saveAndFlush(user);
        userId = user.getId();
        issued = sessions.issueTokensForUser(user);
        sessionId = tokens.validateRefreshToken(issued.refreshToken()).orElseThrow().sessionId();
    }

    @AfterEach
    void cleanup() {
        if (sessionId != null) sessionRepository.deleteById(sessionId);
        if (userId != null) users.deleteById(userId);
        jdbc.update("DELETE FROM auth_rate_limits");
    }

    @Test
    void rotationAndNetworkChangesDoNotResetBudgetAndThrottledFreshTokenRemainsUsable() throws Exception {
        JwtResponse current = issued;
        for (int i = 0; i < 2; i++) {
            String response = refresh(current.refreshToken(), "198.51.100." + (i + 1))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            JwtResponse next = mapper.readValue(response, JwtResponse.class);
            assertThat(next.refreshToken()).isNotEqualTo(current.refreshToken());
            assertThat(tokens.validateRefreshToken(next.refreshToken()).orElseThrow().sessionId()).isEqualTo(sessionId);
            current = next;
        }

        var before = sessionRepository.findById(sessionId).orElseThrow();
        refresh(current.refreshToken(), "2001:db8::48")
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        var after = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(after.getRefreshTokenHash()).isEqualTo(before.getRefreshTokenHash());
        assertThat(after.getRefreshedAt()).isEqualTo(before.getRefreshedAt());
        assertThat(after.getExpiresAt()).isEqualTo(before.getExpiresAt());
        assertThat(after.getRevokedAt()).isNull();

        // Advance this fixture's session history beyond its rolling window without sleeping or resetting it.
        LocalDateTime databaseNow = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        long expired = databaseNow.toInstant(ZoneOffset.UTC).minusSeconds(61).toEpochMilli();
        assertThat(jdbc.update("UPDATE auth_rate_limits SET admissions = ? WHERE dimension = 'refresh-session'",
                "[" + expired + "," + expired + "]")).isEqualTo(1);
        refresh(current.refreshToken(), "2001:db8::48").andExpect(status().isOk());
    }

    private ResultActions refresh(String token, String network) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh")
                .with(request -> { request.setRemoteAddr(network); return request; })
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new RefreshRequest(token))));
    }
}
