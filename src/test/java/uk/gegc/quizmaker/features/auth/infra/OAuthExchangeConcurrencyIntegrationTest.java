package uk.gegc.quizmaker.features.auth.infra;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthCodeExchangeRequest;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeMetricsService;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.application.OAuthPkcePolicy;
import uk.gegc.quizmaker.features.auth.application.impl.OAuthExchangeServiceImpl;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRejectedException;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeStoreUnavailableException;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeRejectionReason;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthExchangeCodeRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("db-serial")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("OAuth exchange-code concurrency")
class OAuthExchangeConcurrencyIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final String CLIENT_ID = "quizzence-web";
    private static final String REDIRECT_URI = "https://www.quizzence.com/oauth2/redirect";
    private static final String VERIFIER = "v".repeat(43);
    private static final JwtResponse TOKENS = new JwtResponse("access", "refresh", 1L, 2L);

    @Autowired
    private OAuthExchangeCodeRepository exchangeCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private AuthSessionService authSessionService;

    @MockitoBean
    private OAuthExchangeMetricsService metricsService;

    private TransactionTemplate transactionTemplate;
    private OAuthExchangeServiceImpl exchangeService;
    private ExecutorService executor;
    private User user;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(2);
        user = transactionTemplate.execute(status -> userRepository.saveAndFlush(newUser()));

        OAuth2ExchangeProperties properties = new OAuth2ExchangeProperties();
        properties.setRedirectUri(REDIRECT_URI);
        OAuth2ExchangeProperties.Client client = new OAuth2ExchangeProperties.Client();
        client.setRedirectUri(REDIRECT_URI);
        properties.getExchange().getClients().put(CLIENT_ID, client);
        exchangeService = new OAuthExchangeServiceImpl(
                exchangeCodeRepository,
                userRepository,
                authSessionService,
                metricsService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionManager
        );
    }

    @AfterEach
    void cleanUp() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (transactionTemplate != null && user != null) {
            transactionTemplate.executeWithoutResult(status -> {
                exchangeCodeRepository.deleteAllInBatch();
                userRepository.deleteById(user.getId());
            });
        }
    }

    @Test
    @DisplayName("two simultaneous exchanges issue exactly one session and the loser observes replay")
    void exchangeSameCodeConcurrently_onlyOneSucceeds() throws Exception {
        String rawCode = issueCode();
        OAuthCodeExchangeRequest request = request(rawCode);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch tokenIssuanceEntered = new CountDownLatch(1);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        when(authSessionService.issueTokensForUser(any())).thenAnswer(invocation -> {
            tokenIssuanceEntered.countDown();
            await(releaseWinner);
            return TOKENS;
        });

        Future<ExchangeOutcome> first = executor.submit(() -> exchangeAfter(start, request));
        Future<ExchangeOutcome> second = executor.submit(() -> exchangeAfter(start, request));
        start.countDown();

        assertThat(tokenIssuanceEntered.await(5, TimeUnit.SECONDS)).isTrue();
        releaseWinner.countDown();

        List<ExchangeOutcome> outcomes = List.of(
                first.get(5, TimeUnit.SECONDS),
                second.get(5, TimeUnit.SECONDS)
        );
        assertThat(outcomes).containsExactlyInAnyOrder(ExchangeOutcome.SUCCESS, ExchangeOutcome.REPLAYED);
        verify(authSessionService, times(1)).issueTokensForUser(any());
        assertThat(exchangeCodeRepository.findById(OAuthPkcePolicy.hashRawCode(rawCode)).orElseThrow().getConsumedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("session issuance failure rolls code consumption back so a safe retry remains possible")
    void exchangeSessionFailure_rollsBackConsumption() {
        String rawCode = issueCode();
        when(authSessionService.issueTokensForUser(any()))
                .thenThrow(new DataAccessResourceFailureException("session store unavailable"));

        assertThatThrownBy(() -> exchangeService.exchange(request(rawCode)))
                .isInstanceOf(OAuthExchangeStoreUnavailableException.class);

        assertThat(exchangeCodeRepository.findById(OAuthPkcePolicy.hashRawCode(rawCode)).orElseThrow().getConsumedAt())
                .isNull();
    }

    private String issueCode() {
        return exchangeService.issueCode(
                user.getId(),
                OAuthLoginContext.codeExchange(CLIENT_ID, REDIRECT_URI, OAuthPkcePolicy.challenge(VERIFIER))
        );
    }

    private OAuthCodeExchangeRequest request(String code) {
        return new OAuthCodeExchangeRequest(code, CLIENT_ID, REDIRECT_URI, VERIFIER);
    }

    private ExchangeOutcome exchangeAfter(CountDownLatch start, OAuthCodeExchangeRequest request) {
        await(start);
        try {
            exchangeService.exchange(request);
            return ExchangeOutcome.SUCCESS;
        } catch (OAuthExchangeRejectedException exception) {
            assertThat(exception.getReason()).isEqualTo(OAuthExchangeRejectionReason.REPLAYED);
            return ExchangeOutcome.REPLAYED;
        }
    }

    private User newUser() {
        String suffix = UUID.randomUUID().toString();
        User created = new User();
        created.setUsername("oauth_exchange_" + suffix.substring(0, 8));
        created.setEmail("oauth_exchange_" + suffix + "@example.com");
        created.setHashedPassword("not-used-by-oauth");
        created.setPasswordChangedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(1));
        created.setActive(true);
        created.setDeleted(false);
        return created;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent OAuth exchange");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent OAuth exchange", exception);
        }
    }

    private enum ExchangeOutcome {
        SUCCESS,
        REPLAYED
    }
}
