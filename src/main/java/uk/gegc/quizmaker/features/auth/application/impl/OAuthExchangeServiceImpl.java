package uk.gegc.quizmaker.features.auth.application.impl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import lombok.extern.slf4j.Slf4j;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthCodeExchangeRequest;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeMetricsService;
import uk.gegc.quizmaker.features.auth.application.OAuthExchangeService;
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

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class OAuthExchangeServiceImpl implements OAuthExchangeService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int CODE_BYTES = 32;
    private static final int MAX_CLIENT_ID_LENGTH = 64;
    private static final int MAX_REDIRECT_URI_LENGTH = 2048;

    private final OAuthExchangeCodeRepository exchangeCodeRepository;
    private final UserRepository userRepository;
    private final AuthSessionService authSessionService;
    private final OAuthExchangeMetricsService metricsService;
    private final OAuth2ExchangeProperties exchangeProperties;
    private final Clock utcClock;
    private final TransactionTemplate transactionTemplate;

    public OAuthExchangeServiceImpl(
            OAuthExchangeCodeRepository exchangeCodeRepository,
            UserRepository userRepository,
            AuthSessionService authSessionService,
            OAuthExchangeMetricsService metricsService,
            OAuth2ExchangeProperties exchangeProperties,
            @Qualifier("utcClock") Clock utcClock,
            PlatformTransactionManager transactionManager
    ) {
        this.exchangeCodeRepository = exchangeCodeRepository;
        this.userRepository = userRepository;
        this.authSessionService = authSessionService;
        this.metricsService = metricsService;
        this.exchangeProperties = exchangeProperties;
        this.utcClock = utcClock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public String issueCode(UUID userId, OAuthLoginContext loginContext) {
        validateIssuance(userId, loginContext);

        String rawCode = newCode();
        String codeHash = OAuthPkcePolicy.hashRawCode(rawCode);
        LocalDateTime issuedAt = LocalDateTime.now(utcClock);
        OAuthExchangeCode exchangeCode = new OAuthExchangeCode(
                codeHash,
                userId,
                loginContext.clientId(),
                loginContext.redirectUri(),
                loginContext.codeChallenge(),
                issuedAt,
                issuedAt.plus(exchangeProperties.getExchange().getCodeTtl())
        );

        try {
            transactionTemplate.executeWithoutResult(status -> exchangeCodeRepository.saveAndFlush(exchangeCode));
        } catch (DataAccessException | TransactionException exception) {
            throw storeUnavailable(exception);
        }

        recordMetric("code_issued", metricsService::recordCodeIssued);
        return rawCode;
    }

    @Override
    public JwtResponse exchange(OAuthCodeExchangeRequest request) {
        validateRequest(request);
        String codeHash = OAuthPkcePolicy.hashRawCode(request.code());

        try {
            JwtResponse response = Objects.requireNonNull(
                    transactionTemplate.execute(status -> exchangeInTransaction(codeHash, request)),
                    "OAuth exchange transaction returned no token response"
            );
            recordMetric("succeeded", metricsService::recordExchangeSucceeded);
            return response;
        } catch (OAuthExchangeRejectedException exception) {
            recordMetric(
                    "rejected_" + exception.getReason().metricValue(),
                    () -> metricsService.recordExchangeRejected(exception.getReason())
            );
            throw exception;
        } catch (DataAccessException | TransactionException exception) {
            throw storeUnavailable(exception);
        }
    }

    @Override
    public int purgeExpiredCodes() {
        try {
            int count = Objects.requireNonNull(transactionTemplate.execute(status ->
                    exchangeCodeRepository.deleteExpiredAtOrBefore(LocalDateTime.now(utcClock))));
            recordMetric("expired_purged", () -> metricsService.recordExpiredCodesPurged(count));
            return count;
        } catch (DataAccessException | TransactionException exception) {
            throw storeUnavailable(exception);
        }
    }

    private JwtResponse exchangeInTransaction(String codeHash, OAuthCodeExchangeRequest request) {
        OAuthExchangeCode exchangeCode = exchangeCodeRepository.findByCodeHashForUpdate(codeHash)
                .orElseThrow(() -> rejected(OAuthExchangeRejectionReason.UNKNOWN));

        // Read time only after the locking query returns. A waiter must not retain a
        // pre-expiry timestamp while another transaction owns the code row.
        LocalDateTime now = LocalDateTime.now(utcClock);
        if (exchangeCode.getConsumedAt() != null) {
            throw rejected(OAuthExchangeRejectionReason.REPLAYED);
        }
        if (exchangeCode.isExpiredAt(now)) {
            throw rejected(OAuthExchangeRejectionReason.EXPIRED);
        }
        if (!exchangeCode.getClientId().equals(request.clientId())) {
            throw rejected(OAuthExchangeRejectionReason.CLIENT_MISMATCH);
        }
        if (!exchangeCode.getRedirectUri().equals(request.redirectUri())) {
            throw rejected(OAuthExchangeRejectionReason.REDIRECT_MISMATCH);
        }
        requireCurrentBinding(request);
        if (!OAuthPkcePolicy.matches(request.codeVerifier(), exchangeCode.getPkceChallenge())) {
            throw rejected(OAuthExchangeRejectionReason.PKCE_MISMATCH);
        }

        User user = userRepository.findById(exchangeCode.getUserId())
                .filter(this::isAvailableForAuthentication)
                .orElseThrow(() -> rejected(OAuthExchangeRejectionReason.USER_UNAVAILABLE));

        exchangeCode.consume(now);
        exchangeCodeRepository.saveAndFlush(exchangeCode);
        return authSessionService.issueTokensForUser(user);
    }

    private void validateIssuance(UUID userId, OAuthLoginContext loginContext) {
        if (userId == null || loginContext == null || loginContext.mode() != OAuthLoginContext.Mode.CODE_EXCHANGE) {
            throw new OAuthExchangeRequestException();
        }
        try {
            exchangeProperties.validateConfiguration(utcClock.instant());
            exchangeProperties.requireClient(loginContext.clientId(), loginContext.redirectUri());
        } catch (RuntimeException exception) {
            throw new OAuthExchangeRequestException();
        }
        OAuthPkcePolicy.requireChallenge(loginContext.codeChallenge(), "S256");
    }

    private void validateRequest(OAuthCodeExchangeRequest request) {
        if (request == null
                || !isBounded(request.clientId(), MAX_CLIENT_ID_LENGTH)
                || !isBounded(request.redirectUri(), MAX_REDIRECT_URI_LENGTH)) {
            throw new OAuthExchangeRequestException();
        }
        OAuthPkcePolicy.requireCode(request.code());
        OAuthPkcePolicy.requireVerifier(request.codeVerifier());
    }

    private void requireCurrentBinding(OAuthCodeExchangeRequest request) {
        OAuth2ExchangeProperties.Client currentClient =
                exchangeProperties.getExchange().getClients().get(request.clientId());
        if (currentClient == null) {
            throw rejected(OAuthExchangeRejectionReason.CLIENT_MISMATCH);
        }
        if (!request.redirectUri().equals(currentClient.getRedirectUri())) {
            throw rejected(OAuthExchangeRejectionReason.REDIRECT_MISMATCH);
        }
        try {
            exchangeProperties.requireClient(request.clientId(), request.redirectUri());
        } catch (RuntimeException exception) {
            throw rejected(OAuthExchangeRejectionReason.REDIRECT_MISMATCH);
        }
    }

    private boolean isAvailableForAuthentication(User user) {
        return user.getId() != null
                && user.getUsername() != null
                && !user.getUsername().isBlank()
                && user.getPasswordChangedAt() != null
                && user.isActive()
                && !user.isDeleted();
    }

    private boolean isBounded(String value, int maximumLength) {
        return value != null && !value.isBlank() && value.length() <= maximumLength;
    }

    private OAuthExchangeRejectedException rejected(OAuthExchangeRejectionReason reason) {
        return new OAuthExchangeRejectedException(reason);
    }

    private OAuthExchangeStoreUnavailableException storeUnavailable(RuntimeException exception) {
        recordMetric("store_failure", metricsService::recordStoreFailure);
        return new OAuthExchangeStoreUnavailableException(exception);
    }

    private void recordMetric(String metricName, Runnable recorder) {
        try {
            recorder.run();
        } catch (RuntimeException exception) {
            log.warn("Unable to record OAuth exchange metric: {}", metricName);
        }
    }

    private String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
