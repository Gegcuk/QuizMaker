package uk.gegc.quizmaker.features.auth.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.application.AuthSessionRefreshService;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSession;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRejectionReason;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRevocationReason;
import uk.gegc.quizmaker.features.auth.domain.repository.AuthSessionRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.auth.infra.security.ValidatedJwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthSessionRefreshServiceImpl implements AuthSessionRefreshService {

    private final AuthSessionRepository authSessionRepository;
    private final JwtTokenService jwtTokenService;

    @Qualifier("utcClock")
    private final Clock utcClock;

    @Override
    @Transactional
    public RefreshResult rotate(String refreshToken) {
        Optional<ValidatedJwt> validatedRefresh = jwtTokenService.validateRefreshToken(refreshToken);
        if (validatedRefresh.isEmpty()) {
            return RefreshResult.rejected(AuthSessionRejectionReason.INVALID_TOKEN);
        }

        ValidatedJwt refreshClaims = validatedRefresh.get();
        Optional<AuthSession> lockedSession = authSessionRepository.findByIdForUpdate(refreshClaims.sessionId());
        if (lockedSession.isEmpty() || !lockedSession.get().getUserId().equals(refreshClaims.userId())) {
            return RefreshResult.rejected(AuthSessionRejectionReason.INACTIVE_SESSION);
        }

        LocalDateTime now = LocalDateTime.now(utcClock);
        AuthSession session = lockedSession.get();
        if (!session.isActiveAt(now)) {
            return RefreshResult.rejected(AuthSessionRejectionReason.INACTIVE_SESSION);
        }

        String presentedHash = jwtTokenService.fingerprintRefreshToken(refreshToken);
        if (!constantTimeEquals(session.getRefreshTokenHash(), presentedHash)) {
            session.revoke(now, AuthSessionRevocationReason.REFRESH_TOKEN_REPLAY);
            authSessionRepository.saveAndFlush(session);
            return RefreshResult.rejected(AuthSessionRejectionReason.REPLAYED_TOKEN);
        }

        Authentication authentication = jwtTokenService.getAuthentication(refreshClaims);
        String nextAccessToken = jwtTokenService.generateAccessToken(authentication, session.getId());
        String nextRefreshToken = jwtTokenService.generateRefreshToken(
                authentication,
                session.getId(),
                toDate(session.getExpiresAt())
        );
        session.rotateRefreshToken(jwtTokenService.fingerprintRefreshToken(nextRefreshToken), now);
        authSessionRepository.saveAndFlush(session);

        return RefreshResult.rotated(tokenResponse(
                nextAccessToken,
                nextRefreshToken,
                now,
                session.getExpiresAt()
        ));
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

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
