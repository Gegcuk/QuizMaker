package uk.gegc.quizmaker.features.auth.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthCodeExchangeRequest;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeMetricsService;
import uk.gegc.quizmaker.features.auth.application.OAuthLoginContext;
import uk.gegc.quizmaker.features.auth.application.OAuthPkcePolicy;
import uk.gegc.quizmaker.features.auth.config.OAuth2ExchangeProperties;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRejectedException;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeRequestException;
import uk.gegc.quizmaker.features.auth.domain.exception.OAuthExchangeStoreUnavailableException;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeCode;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthExchangeRejectionReason;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthExchangeCodeRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth one-time-code exchange service")
class OAuthExchangeServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final String CLIENT_ID = "quizzence-web";
    private static final String REDIRECT_URI = "https://www.quizzence.com/oauth2/redirect";
    private static final String RAW_CODE = "c".repeat(43);
    private static final String VERIFIER = "v".repeat(43);

    @Mock
    private OAuthExchangeCodeRepository exchangeCodeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private OAuthExchangeMetricsService metricsService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private TransactionStatus transactionStatus;

    private OAuth2ExchangeProperties properties;
    private OAuthExchangeServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new OAuth2ExchangeProperties();
        OAuth2ExchangeProperties.Client client = new OAuth2ExchangeProperties.Client();
        client.setRedirectUri(REDIRECT_URI);
        properties.getExchange().getClients().put(CLIENT_ID, client);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(transactionStatus);
        service = new OAuthExchangeServiceImpl(
                exchangeCodeRepository,
                userRepository,
                authSessionService,
                metricsService,
                properties,
                Clock.fixed(NOW, ZoneOffset.UTC),
                transactionManager
        );
    }

    @Test
    @DisplayName("issues a 256-bit opaque code while persisting only its digest and validated binding")
    void issueCode_persistsOnlyDigestAndBinding() {
        String challenge = OAuthPkcePolicy.challenge(VERIFIER);

        String rawCode = service.issueCode(
                UUID.randomUUID(),
                OAuthLoginContext.codeExchange(CLIENT_ID, REDIRECT_URI, challenge)
        );

        ArgumentCaptor<OAuthExchangeCode> captor = ArgumentCaptor.forClass(OAuthExchangeCode.class);
        verify(exchangeCodeRepository).saveAndFlush(captor.capture());
        OAuthExchangeCode persisted = captor.getValue();
        assertThat(rawCode).matches("[A-Za-z0-9_-]{43}");
        assertThat(persisted.getCodeHash())
                .isEqualTo(OAuthPkcePolicy.hashRawCode(rawCode))
                .doesNotContain(rawCode);
        assertThat(persisted.getPkceChallenge()).isEqualTo(challenge);
        assertThat(persisted.getClientId()).isEqualTo(CLIENT_ID);
        assertThat(persisted.getRedirectUri()).isEqualTo(REDIRECT_URI);
        assertThat(persisted.getIssuedAt()).isEqualTo(NOW_UTC);
        assertThat(persisted.getExpiresAt()).isEqualTo(NOW_UTC.plusMinutes(2));
        verify(metricsService).recordCodeIssued();
    }

    @Test
    @DisplayName("metrics failure cannot turn a committed code issuance into callback failure")
    void issueCode_metricsFailure_stillReturnsCode() {
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(metricsService).recordCodeIssued();

        String rawCode = service.issueCode(
                UUID.randomUUID(),
                OAuthLoginContext.codeExchange(
                        CLIENT_ID,
                        REDIRECT_URI,
                        OAuthPkcePolicy.challenge(VERIFIER)
                )
        );

        assertThat(rawCode).matches("[A-Za-z0-9_-]{43}");
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    @DisplayName("rejects legacy or unvalidated issuance context before opening a transaction")
    void issueCode_legacyContext_isRejected() {
        assertThatThrownBy(() -> service.issueCode(
                UUID.randomUUID(),
                OAuthLoginContext.legacy(REDIRECT_URI)
        )).isInstanceOf(OAuthExchangeRequestException.class);

        verify(transactionManager, never()).commit(any());
        verify(exchangeCodeRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("valid exchange consumes once and issues the session from the already resolved user")
    void exchange_validCode_consumesAndIssuesSession() {
        OAuthExchangeCode code = activeCode();
        User user = availableUser(code.getUserId());
        JwtResponse expected = new JwtResponse("access", "refresh", 1L, 2L);
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));
        when(userRepository.findById(code.getUserId())).thenReturn(Optional.of(user));
        when(authSessionService.issueTokensForUser(user)).thenReturn(expected);

        JwtResponse actual = service.exchange(validRequest());

        assertThat(actual).isEqualTo(expected);
        assertThat(code.getConsumedAt()).isEqualTo(NOW_UTC);
        verify(exchangeCodeRepository).saveAndFlush(code);
        verify(authSessionService).issueTokensForUser(user);
        verify(transactionManager).commit(transactionStatus);
        verify(metricsService).recordExchangeSucceeded();
    }

    @Test
    @DisplayName("metrics failure cannot hide tokens after exchange transaction commits")
    void exchange_successMetricFailure_stillReturnsCommittedTokens() {
        OAuthExchangeCode code = activeCode();
        User user = availableUser(code.getUserId());
        JwtResponse expected = new JwtResponse("access", "refresh", 1L, 2L);
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));
        when(userRepository.findById(code.getUserId())).thenReturn(Optional.of(user));
        when(authSessionService.issueTokensForUser(user)).thenReturn(expected);
        doThrow(new IllegalStateException("metrics unavailable"))
                .when(metricsService).recordExchangeSucceeded();

        assertThat(service.exchange(validRequest())).isEqualTo(expected);

        assertThat(code.getConsumedAt()).isEqualTo(NOW_UTC);
        verify(transactionManager).commit(transactionStatus);
    }

    @Test
    @DisplayName("wrong PKCE verifier cannot consume or issue tokens")
    void exchange_wrongVerifier_rejectsWithoutConsumption() {
        OAuthExchangeCode code = activeCode();
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));
        OAuthCodeExchangeRequest request = new OAuthCodeExchangeRequest(
                RAW_CODE,
                CLIENT_ID,
                REDIRECT_URI,
                "x".repeat(43)
        );

        assertThatThrownBy(() -> service.exchange(request))
                .isInstanceOfSatisfying(OAuthExchangeRejectedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(OAuthExchangeRejectionReason.PKCE_MISMATCH));

        assertThat(code.getConsumedAt()).isNull();
        verify(authSessionService, never()).issueTokensForUser(any());
        verify(metricsService).recordExchangeRejected(OAuthExchangeRejectionReason.PKCE_MISMATCH);
    }

    @Test
    @DisplayName("replay is rejected after the first committed consumption")
    void exchange_consumedCode_isReplay() {
        OAuthExchangeCode code = activeCode();
        code.consume(NOW_UTC.minusSeconds(1));
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.exchange(validRequest()))
                .isInstanceOfSatisfying(OAuthExchangeRejectedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(OAuthExchangeRejectionReason.REPLAYED));

        verify(metricsService).recordExchangeRejected(OAuthExchangeRejectionReason.REPLAYED);
        verify(authSessionService, never()).issueTokensForUser(any());
    }

    @Test
    @DisplayName("expiry is inclusive and cannot issue a session")
    void exchange_codeAtExpiry_isExpired() {
        OAuthExchangeCode code = new OAuthExchangeCode(
                OAuthPkcePolicy.hashRawCode(RAW_CODE),
                UUID.randomUUID(),
                CLIENT_ID,
                REDIRECT_URI,
                OAuthPkcePolicy.challenge(VERIFIER),
                NOW_UTC.minusMinutes(2),
                NOW_UTC
        );
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.exchange(validRequest()))
                .isInstanceOfSatisfying(OAuthExchangeRejectedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(OAuthExchangeRejectionReason.EXPIRED));

        verify(metricsService).recordExchangeRejected(OAuthExchangeRejectionReason.EXPIRED);
        verify(authSessionService, never()).issueTokensForUser(any());
    }

    @Test
    @DisplayName("client and redirect bindings are exact")
    void exchange_wrongBinding_isRejected() {
        OAuthExchangeCode code = activeCode();
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));
        OAuthCodeExchangeRequest request = new OAuthCodeExchangeRequest(
                RAW_CODE,
                CLIENT_ID,
                REDIRECT_URI + "/different",
                VERIFIER
        );

        assertThatThrownBy(() -> service.exchange(request))
                .isInstanceOfSatisfying(OAuthExchangeRejectedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(OAuthExchangeRejectionReason.REDIRECT_MISMATCH));

        assertThat(code.getConsumedAt()).isNull();
        verify(authSessionService, never()).issueTokensForUser(any());
    }

    @Test
    @DisplayName("current allowlist revocation invalidates an otherwise matching live code")
    void exchange_clientRemovedAfterIssuance_isRejected() {
        OAuthExchangeCode code = activeCode();
        properties.getExchange().getClients().remove(CLIENT_ID);
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));

        assertThatThrownBy(() -> service.exchange(validRequest()))
                .isInstanceOfSatisfying(OAuthExchangeRejectedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(OAuthExchangeRejectionReason.CLIENT_MISMATCH));

        assertThat(code.getConsumedAt()).isNull();
        verify(authSessionService, never()).issueTokensForUser(any());
    }

    @Test
    @DisplayName("inactive, deleted, or incomplete users cannot receive a session")
    void exchange_unavailableUser_isRejected() {
        OAuthExchangeCode code = activeCode();
        User inactive = availableUser(code.getUserId());
        inactive.setActive(false);
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));
        when(userRepository.findById(code.getUserId())).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.exchange(validRequest()))
                .isInstanceOfSatisfying(OAuthExchangeRejectedException.class, exception ->
                        assertThat(exception.getReason()).isEqualTo(OAuthExchangeRejectionReason.USER_UNAVAILABLE));

        assertThat(code.getConsumedAt()).isNull();
        verify(authSessionService, never()).issueTokensForUser(any());
    }

    @Test
    @DisplayName("commit failure is translated after rolling the transaction back")
    void exchange_commitFailure_isStoreUnavailable() {
        OAuthExchangeCode code = activeCode();
        User user = availableUser(code.getUserId());
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenReturn(Optional.of(code));
        when(userRepository.findById(code.getUserId())).thenReturn(Optional.of(user));
        when(authSessionService.issueTokensForUser(user))
                .thenReturn(new JwtResponse("access", "refresh", 1L, 2L));
        TransactionSystemException commitFailure = new TransactionSystemException("commit failed");
        doThrow(commitFailure).when(transactionManager).commit(transactionStatus);

        assertThatThrownBy(() -> service.exchange(validRequest()))
                .isInstanceOf(OAuthExchangeStoreUnavailableException.class)
                .hasCause(commitFailure);

        verify(metricsService).recordStoreFailure();
        verify(metricsService, never()).recordExchangeSucceeded();
    }

    @Test
    @DisplayName("repository outage is a retryable store failure, not credential rejection")
    void exchange_repositoryFailure_isStoreUnavailable() {
        DataAccessResourceFailureException storeFailure =
                new DataAccessResourceFailureException("database unavailable");
        when(exchangeCodeRepository.findByCodeHashForUpdate(OAuthPkcePolicy.hashRawCode(RAW_CODE)))
                .thenThrow(storeFailure);

        assertThatThrownBy(() -> service.exchange(validRequest()))
                .isInstanceOf(OAuthExchangeStoreUnavailableException.class)
                .hasCause(storeFailure);

        verify(metricsService).recordStoreFailure();
        verify(metricsService, never()).recordExchangeRejected(any());
    }

    @Test
    @DisplayName("cleanup uses UTC now and reports only the committed deletion count")
    void purgeExpiredCodes_deletesByUtcExpiry() {
        when(exchangeCodeRepository.deleteExpiredAtOrBefore(NOW_UTC)).thenReturn(3);

        assertThat(service.purgeExpiredCodes()).isEqualTo(3);

        verify(exchangeCodeRepository).deleteExpiredAtOrBefore(NOW_UTC);
        verify(transactionManager).commit(transactionStatus);
        verify(metricsService).recordExpiredCodesPurged(3);
    }

    @Test
    @DisplayName("malformed material is rejected without touching persistence")
    void exchange_malformedCode_isRequestFailure() {
        OAuthCodeExchangeRequest malformed = new OAuthCodeExchangeRequest(
                "short",
                CLIENT_ID,
                REDIRECT_URI,
                VERIFIER
        );

        assertThatThrownBy(() -> service.exchange(malformed))
                .isInstanceOf(OAuthExchangeRequestException.class);

        verify(exchangeCodeRepository, never()).findByCodeHashForUpdate(any());
        verify(transactionManager, never()).commit(any());
    }

    private OAuthExchangeCode activeCode() {
        return new OAuthExchangeCode(
                OAuthPkcePolicy.hashRawCode(RAW_CODE),
                UUID.randomUUID(),
                CLIENT_ID,
                REDIRECT_URI,
                OAuthPkcePolicy.challenge(VERIFIER),
                NOW_UTC.minusMinutes(1),
                NOW_UTC.plusMinutes(1)
        );
    }

    private OAuthCodeExchangeRequest validRequest() {
        return new OAuthCodeExchangeRequest(RAW_CODE, CLIENT_ID, REDIRECT_URI, VERIFIER);
    }

    private User availableUser(UUID userId) {
        User user = new User();
        user.setId(userId);
        user.setUsername("oauth-user");
        user.setPasswordChangedAt(NOW_UTC.minusDays(1));
        user.setActive(true);
        user.setDeleted(false);
        return user;
    }
}
