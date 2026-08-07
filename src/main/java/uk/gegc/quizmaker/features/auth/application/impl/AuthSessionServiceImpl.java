package uk.gegc.quizmaker.features.auth.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.application.AuthSessionRefreshService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.domain.exception.AuthSessionStoreUnavailableException;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSession;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRejectionReason;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRevocationReason;
import uk.gegc.quizmaker.features.auth.domain.repository.AuthSessionRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.auth.infra.security.ValidatedJwt;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthSessionServiceImpl implements AuthSessionService {

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final AuthSessionRefreshService authSessionRefreshService;
    private final AuthSessionMetricsService authSessionMetricsService;

    @Qualifier("utcClock")
    private final Clock utcClock;

    @Override
    @Transactional
    public JwtResponse issueTokens(Authentication authentication) {
        User user = findUser(authentication.getName());
        UUID sessionId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(utcClock);
        LocalDateTime expiresAt = now.plus(Duration.ofMillis(jwtTokenService.getRefreshTokenValidityInMs()));

        String accessToken = jwtTokenService.generateAccessToken(authentication, sessionId);
        String refreshToken = jwtTokenService.generateRefreshToken(authentication, sessionId, toDate(expiresAt));

        authSessionRepository.save(new AuthSession(
                sessionId,
                user.getId(),
                jwtTokenService.fingerprintRefreshToken(refreshToken),
                now,
                expiresAt
        ));
        authSessionMetricsService.recordSessionIssued();

        return tokenResponse(accessToken, refreshToken, now, expiresAt);
    }

    @Override
    public JwtResponse refresh(String refreshToken) {
        try {
            AuthSessionRefreshService.RefreshResult result = authSessionRefreshService.rotate(refreshToken);
            if (result.isRejected()) {
                if (result.rejectionReason() == AuthSessionRejectionReason.REPLAYED_TOKEN) {
                    log.warn("Rejected replayed refresh token and revoked its authentication session");
                }
                throw rejectRefresh(result.rejectionReason());
            }

            authSessionMetricsService.recordRefreshSucceeded();
            return result.response();
        } catch (DataAccessException | TransactionException exception) {
            authSessionMetricsService.recordSessionStoreFailure();
            log.error("Authentication session refresh could not access the session store");
            throw new AuthSessionStoreUnavailableException(exception);
        }
    }

    @Override
    @Transactional
    public void logout(String accessToken) {
        ValidatedJwt accessClaims = jwtTokenService.validateAccessToken(accessToken)
                .orElseThrow(() -> rejectAccess(AuthSessionRejectionReason.INVALID_TOKEN));
        AuthSession session = loadOwnedSessionForUpdate(
                accessClaims,
                () -> rejectAccess(AuthSessionRejectionReason.INACTIVE_SESSION)
        );
        LocalDateTime now = LocalDateTime.now(utcClock);

        if (session.revoke(now, AuthSessionRevocationReason.LOGOUT)) {
            authSessionRepository.save(session);
            log.info("Authentication session revoked by logout");
        }
        authSessionMetricsService.recordLogoutSucceeded();
    }

    @Override
    @Transactional(readOnly = true)
    public Authentication authenticateAccessToken(String accessToken) {
        Optional<ValidatedJwt> accessClaims = jwtTokenService.validateAccessToken(accessToken);
        if (accessClaims.isEmpty()) {
            authSessionMetricsService.recordAccessRejected(AuthSessionRejectionReason.INVALID_TOKEN);
            return null;
        }

        ValidatedJwt claims = accessClaims.get();
        LocalDateTime now = LocalDateTime.now(utcClock);
        if (!authSessionRepository.existsActiveSession(claims.sessionId(), claims.userId(), now)) {
            log.debug("Rejected access token for inactive authentication session");
            authSessionMetricsService.recordAccessRejected(AuthSessionRejectionReason.INACTIVE_SESSION);
            return null;
        }

        return jwtTokenService.getAuthentication(claims);
    }

    @Override
    @Transactional
    public int purgeExpiredSessions() {
        int purged = Math.toIntExact(authSessionRepository.deleteByExpiresAtBefore(LocalDateTime.now(utcClock)));
        authSessionMetricsService.recordExpiredSessionsPurged(purged);
        return purged;
    }

    private AuthSession loadOwnedSessionForUpdate(
            ValidatedJwt claims,
            Supplier<UnauthorizedException> invalidTokenException
    ) {
        AuthSession session = authSessionRepository.findByIdForUpdate(claims.sessionId())
                .orElseThrow(invalidTokenException);
        if (!session.getUserId().equals(claims.userId())) {
            throw invalidTokenException.get();
        }
        return session;
    }

    private User findUser(String usernameOrEmail) {
        return userRepository.findByUsername(usernameOrEmail)
                .or(() -> userRepository.findByEmail(usernameOrEmail))
                .orElseThrow(() -> new UnauthorizedException("Invalid authenticated user"));
    }

    private JwtResponse tokenResponse(
            String accessToken,
            String refreshToken,
            LocalDateTime now,
            LocalDateTime refreshExpiresAt
    ) {
        long refreshExpiresInMs = Math.max(0, Duration.between(now, refreshExpiresAt).toMillis());
        return new JwtResponse(
                accessToken,
                refreshToken,
                jwtTokenService.getAccessTokenValidityInMs(),
                refreshExpiresInMs
        );
    }

    private Date toDate(LocalDateTime timestamp) {
        Instant instant = timestamp.toInstant(ZoneOffset.UTC);
        return Date.from(instant);
    }

    private UnauthorizedException invalidRefreshToken() {
        return new UnauthorizedException("Invalid refresh token");
    }

    private UnauthorizedException rejectRefresh(AuthSessionRejectionReason reason) {
        authSessionMetricsService.recordRefreshRejected(reason);
        return invalidRefreshToken();
    }

    private UnauthorizedException rejectAccess(AuthSessionRejectionReason reason) {
        authSessionMetricsService.recordAccessRejected(reason);
        return new UnauthorizedException("Invalid access token");
    }

}
