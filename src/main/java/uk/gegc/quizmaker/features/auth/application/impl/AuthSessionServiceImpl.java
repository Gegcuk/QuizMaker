package uk.gegc.quizmaker.features.auth.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSession;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRevocationReason;
import uk.gegc.quizmaker.features.auth.domain.repository.AuthSessionRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.auth.infra.security.ValidatedJwt;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthSessionServiceImpl implements AuthSessionService {

    private final AuthSessionRepository authSessionRepository;
    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;

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

        return tokenResponse(accessToken, refreshToken, now, expiresAt);
    }

    @Override
    @Transactional
    public JwtResponse refresh(String refreshToken) {
        ValidatedJwt refreshClaims = jwtTokenService.validateRefreshToken(refreshToken)
                .orElseThrow(this::invalidRefreshToken);
        LocalDateTime now = LocalDateTime.now(utcClock);
        AuthSession session = loadOwnedSessionForUpdate(refreshClaims, "Invalid refresh token");

        if (!session.isActiveAt(now)) {
            throw invalidRefreshToken();
        }

        String presentedHash = jwtTokenService.fingerprintRefreshToken(refreshToken);
        if (!constantTimeEquals(session.getRefreshTokenHash(), presentedHash)) {
            session.revoke(now, AuthSessionRevocationReason.REFRESH_TOKEN_REPLAY);
            authSessionRepository.save(session);
            log.warn("Rejected replayed refresh token");
            throw invalidRefreshToken();
        }

        Authentication authentication = jwtTokenService.getAuthentication(refreshClaims);
        String nextAccessToken = jwtTokenService.generateAccessToken(authentication, session.getId());
        String nextRefreshToken = jwtTokenService.generateRefreshToken(
                authentication,
                session.getId(),
                toDate(session.getExpiresAt())
        );
        session.rotateRefreshToken(jwtTokenService.fingerprintRefreshToken(nextRefreshToken), now);
        authSessionRepository.save(session);

        return tokenResponse(nextAccessToken, nextRefreshToken, now, session.getExpiresAt());
    }

    @Override
    @Transactional
    public void logout(String accessToken) {
        ValidatedJwt accessClaims = jwtTokenService.validateAccessToken(accessToken)
                .orElseThrow(() -> new UnauthorizedException("Invalid access token"));
        AuthSession session = loadOwnedSessionForUpdate(accessClaims, "Invalid access token");
        LocalDateTime now = LocalDateTime.now(utcClock);

        if (session.revoke(now, AuthSessionRevocationReason.LOGOUT)) {
            authSessionRepository.save(session);
            log.info("Authentication session revoked by logout");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Authentication authenticateAccessToken(String accessToken) {
        Optional<ValidatedJwt> accessClaims = jwtTokenService.validateAccessToken(accessToken);
        if (accessClaims.isEmpty()) {
            return null;
        }

        ValidatedJwt claims = accessClaims.get();
        LocalDateTime now = LocalDateTime.now(utcClock);
        if (!authSessionRepository.existsActiveSession(claims.sessionId(), claims.userId(), now)) {
            log.debug("Rejected access token for inactive authentication session");
            return null;
        }

        return jwtTokenService.getAuthentication(claims);
    }

    @Override
    @Transactional
    public int purgeExpiredSessions() {
        return Math.toIntExact(authSessionRepository.deleteByExpiresAtBefore(LocalDateTime.now(utcClock)));
    }

    private AuthSession loadOwnedSessionForUpdate(ValidatedJwt claims, String invalidTokenMessage) {
        AuthSession session = authSessionRepository.findByIdForUpdate(claims.sessionId())
                .orElseThrow(() -> new UnauthorizedException(invalidTokenMessage));
        if (!session.getUserId().equals(claims.userId())) {
            throw new UnauthorizedException(invalidTokenMessage);
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

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
