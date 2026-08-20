package uk.gegc.quizmaker.features.auth.application.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.RefreshRequest;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSession;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRevocationReason;
import uk.gegc.quizmaker.features.auth.domain.repository.AuthSessionRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@Tag("db-serial")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test-mysql")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=update",
        "quizmaker.features.billing=false",
        "billing.pack-sync.enabled=false",
        "stripe.secret-key=",
        "stripe.price-small=",
        "stripe.price-medium=",
        "stripe.price-large=",
        "app.email.provider=noop"
})
@DisplayName("Authentication Session Refresh Concurrency")
class AuthSessionRefreshConcurrencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthSessionService authSessionService;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;
    private UUID sessionId;
    private JwtResponse issuedTokens;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        User user = new User();
        user.setUsername("refresh-concurrency-" + suffix);
        user.setEmail("refresh-concurrency-" + suffix + "@example.test");
        user.setHashedPassword("not-used-by-this-test");
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        user.setRoles(new HashSet<>());
        user = userRepository.saveAndFlush(user);
        userId = user.getId();

        issuedTokens = authSessionService.issueTokens(new UsernamePasswordAuthenticationToken(
                user.getUsername(),
                null,
                List.of()
        ));
        sessionId = jwtTokenService.validateRefreshToken(issuedTokens.refreshToken())
                .orElseThrow()
                .sessionId();
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            authSessionRepository.deleteById(sessionId);
        }
        if (userId != null) {
            userRepository.deleteById(userId);
        }
    }

    @Test
    @DisplayName("Concurrent use returns one replacement, rejects the replay, and durably revokes the session")
    void concurrentRefresh_exactlyOneSucceedsAndReplayRevocationCommits() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<MvcResult> results;

        try {
            Future<MvcResult> first = executor.submit(() -> refreshAfterBarrier(start, issuedTokens.refreshToken()));
            Future<MvcResult> second = executor.submit(() -> refreshAfterBarrier(start, issuedTokens.refreshToken()));
            results = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }

        assertThat(results)
                .extracting(result -> result.getResponse().getStatus())
                .containsExactlyInAnyOrder(200, 401);

        MvcResult successfulResult = results.stream()
                .filter(result -> result.getResponse().getStatus() == 200)
                .findFirst()
                .orElseThrow();
        MvcResult replayedResult = results.stream()
                .filter(result -> result.getResponse().getStatus() == 401)
                .findFirst()
                .orElseThrow();
        JsonNode replacement = objectMapper.readTree(successfulResult.getResponse().getContentAsString());
        String replacementAccessToken = replacement.path("accessToken").asText();
        String replacementRefreshToken = replacement.path("refreshToken").asText();

        assertThat(replacementRefreshToken).isNotBlank().isNotEqualTo(issuedTokens.refreshToken());
        assertThat(replacement.path("refreshExpiresInMs").asLong()).isEqualTo(345_600_000L);
        assertThat(objectMapper.readTree(replayedResult.getResponse().getContentAsString()).path("detail").asText())
                .isEqualTo("Invalid refresh token");

        AuthSession persistedSession = authSessionRepository.findById(sessionId).orElseThrow();
        assertThat(Duration.between(persistedSession.getRefreshedAt(), persistedSession.getExpiresAt()))
                .isEqualTo(Duration.ofDays(4));
        assertThat(persistedSession.getRevokedAt()).isNotNull();
        assertThat(persistedSession.getRevocationReason())
                .isEqualTo(AuthSessionRevocationReason.REFRESH_TOKEN_REPLAY);
        assertThat(authSessionService.authenticateAccessToken(replacementAccessToken)).isNull();
        assertThat(refresh(replacementRefreshToken).getResponse().getStatus()).isEqualTo(401);
        assertThat(refresh(issuedTokens.refreshToken()).getResponse().getStatus()).isEqualTo(401);
    }

    private MvcResult refreshAfterBarrier(CyclicBarrier barrier, String refreshToken) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return refresh(refreshToken);
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(refreshToken))))
                .andReturn();
    }
}
