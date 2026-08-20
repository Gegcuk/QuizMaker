package uk.gegc.quizmaker.features.auth.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.application.AuthSessionRefreshService;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSession;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRejectionReason;
import uk.gegc.quizmaker.features.auth.domain.model.AuthSessionRevocationReason;
import uk.gegc.quizmaker.features.auth.domain.repository.AuthSessionRepository;
import uk.gegc.quizmaker.features.auth.infra.security.JwtTokenService;
import uk.gegc.quizmaker.features.auth.infra.security.ValidatedJwt;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Authentication Session Refresh Transaction")
class AuthSessionRefreshServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Mock
    private AuthSessionRepository authSessionRepository;

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private Authentication authentication;

    private AuthSessionRefreshServiceImpl service;
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new AuthSessionRefreshServiceImpl(
                authSessionRepository,
                jwtTokenService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("Invalid refresh material is rejected without reading session state")
    void rotate_invalidTokenRejectsBeforeSessionLookup() {
        when(jwtTokenService.validateRefreshToken("invalid-refresh")).thenReturn(Optional.empty());

        AuthSessionRefreshService.RefreshResult result = service.rotate("invalid-refresh");

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).isEqualTo(AuthSessionRejectionReason.INVALID_TOKEN);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    @DisplayName("Current refresh material atomically renews the rolling four-day window")
    void rotate_currentTokenPersistsRenewedDeadlineBeforeReturningIt() {
        LocalDateTime previousExpiresAt = now.plusDays(2);
        AuthSession session = new AuthSession(
                sessionId,
                userId,
                "current-hash",
                now.minusDays(5),
                previousExpiresAt
        );
        ValidatedJwt claims = claims(previousExpiresAt);
        when(jwtTokenService.validateRefreshToken("current-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(jwtTokenService.fingerprintRefreshToken("current-refresh")).thenReturn("current-hash");
        when(jwtTokenService.getAuthentication(claims)).thenReturn(authentication);
        when(jwtTokenService.getRefreshTokenValidityInMs()).thenReturn(345_600_000L);
        when(jwtTokenService.generateAccessToken(authentication, sessionId)).thenReturn("next-access");
        when(jwtTokenService.generateRefreshToken(eq(authentication), eq(sessionId), any(Date.class)))
                .thenReturn("next-refresh");
        when(jwtTokenService.fingerprintRefreshToken("next-refresh")).thenReturn("next-hash");
        when(jwtTokenService.getAccessTokenValidityInMs()).thenReturn(43_200_000L);

        AuthSessionRefreshService.RefreshResult result = service.rotate("current-refresh");

        assertThat(result.isRejected()).isFalse();
        assertThat(result.response()).isEqualTo(
                new JwtResponse("next-access", "next-refresh", 43_200_000L, 345_600_000L));
        assertThat(session.getRefreshTokenHash()).isEqualTo("next-hash");
        assertThat(session.getRefreshedAt()).isEqualTo(now);
        assertThat(session.getExpiresAt()).isEqualTo(now.plusDays(4));

        ArgumentCaptor<Date> expiryCaptor = ArgumentCaptor.forClass(Date.class);
        verify(jwtTokenService).generateRefreshToken(
                eq(authentication),
                eq(sessionId),
                expiryCaptor.capture()
        );
        assertThat(expiryCaptor.getValue().toInstant()).isEqualTo(NOW.plusSeconds(4 * 24 * 60 * 60));
        verify(authSessionRepository).saveAndFlush(session);
    }

    @Test
    @DisplayName("Stale refresh material durably revokes the whole session before rejection")
    void rotate_replayedTokenFlushesSessionRevocation() {
        LocalDateTime existingDeadline = now.plusDays(3);
        AuthSession session = new AuthSession(sessionId, userId, "current-hash", now.minusDays(1), existingDeadline);
        ValidatedJwt claims = claims(now.plusDays(3));
        when(jwtTokenService.validateRefreshToken("replayed-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(jwtTokenService.fingerprintRefreshToken("replayed-refresh")).thenReturn("stale-hash");

        AuthSessionRefreshService.RefreshResult result = service.rotate("replayed-refresh");

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).isEqualTo(AuthSessionRejectionReason.REPLAYED_TOKEN);
        assertThat(session.getRevokedAt()).isEqualTo(now);
        assertThat(session.getRevocationReason()).isEqualTo(AuthSessionRevocationReason.REFRESH_TOKEN_REPLAY);
        assertThat(session.getExpiresAt()).isEqualTo(existingDeadline);
        verify(authSessionRepository).saveAndFlush(session);
        verify(jwtTokenService, never()).getAuthentication(any(ValidatedJwt.class));
    }

    @Test
    @DisplayName("Replay is not reported as rejected when its revocation cannot be persisted")
    void rotate_replayRevocationPersistenceFailurePropagatesStoreFailure() {
        AuthSession session = new AuthSession(sessionId, userId, "current-hash", now.minusDays(1), now.plusDays(3));
        ValidatedJwt claims = claims(now.plusDays(3));
        DataAccessResourceFailureException storeFailure =
                new DataAccessResourceFailureException("session store unavailable");
        when(jwtTokenService.validateRefreshToken("replayed-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(jwtTokenService.fingerprintRefreshToken("replayed-refresh")).thenReturn("stale-hash");
        when(authSessionRepository.saveAndFlush(session)).thenThrow(storeFailure);

        assertThatThrownBy(() -> service.rotate("replayed-refresh"))
                .isSameAs(storeFailure);

        assertThat(session.getRevocationReason()).isEqualTo(AuthSessionRevocationReason.REFRESH_TOKEN_REPLAY);
        verify(jwtTokenService, never()).getAuthentication(any(ValidatedJwt.class));
    }

    @Test
    @DisplayName("A revoked session is rejected without renewing its inactivity deadline")
    void rotate_revokedSessionRejectsWithoutExtension() {
        LocalDateTime existingDeadline = now.plusDays(2);
        AuthSession session = new AuthSession(
                sessionId,
                userId,
                "current-hash",
                now.minusDays(2),
                existingDeadline
        );
        session.revoke(now.minusHours(1), AuthSessionRevocationReason.LOGOUT);
        when(jwtTokenService.validateRefreshToken("revoked-session-refresh"))
                .thenReturn(Optional.of(claims(now.plusDays(1))));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        AuthSessionRefreshService.RefreshResult result = service.rotate("revoked-session-refresh");

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).isEqualTo(AuthSessionRejectionReason.INACTIVE_SESSION);
        assertThat(session.getExpiresAt()).isEqualTo(existingDeadline);
        verify(authSessionRepository, never()).saveAndFlush(any(AuthSession.class));
        verify(jwtTokenService, never()).fingerprintRefreshToken(any());
    }

    @Test
    @DisplayName("Refresh material for another user cannot renew the locked session")
    void rotate_wrongOwnerRejectsWithoutExtension() {
        LocalDateTime existingDeadline = now.plusDays(2);
        AuthSession session = new AuthSession(
                sessionId,
                userId,
                "current-hash",
                now.minusDays(2),
                existingDeadline
        );
        ValidatedJwt claims = new ValidatedJwt(
                "mallory",
                UUID.randomUUID(),
                sessionId,
                NOW.plusSeconds(86_400)
        );
        when(jwtTokenService.validateRefreshToken("wrong-owner-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        AuthSessionRefreshService.RefreshResult result = service.rotate("wrong-owner-refresh");

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).isEqualTo(AuthSessionRejectionReason.INACTIVE_SESSION);
        assertThat(session.getExpiresAt()).isEqualTo(existingDeadline);
        verify(authSessionRepository, never()).saveAndFlush(any(AuthSession.class));
        verify(jwtTokenService, never()).fingerprintRefreshToken(any());
    }

    @Test
    @DisplayName("A renewal store failure is propagated instead of returning replacement tokens")
    void rotate_renewalPersistenceFailurePropagatesStoreFailure() {
        AuthSession session = new AuthSession(
                sessionId,
                userId,
                "current-hash",
                now.minusDays(5),
                now.plusDays(2)
        );
        DataAccessResourceFailureException storeFailure =
                new DataAccessResourceFailureException("session store unavailable");
        ValidatedJwt claims = claims(now.plusDays(2));
        when(jwtTokenService.validateRefreshToken("current-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(jwtTokenService.fingerprintRefreshToken("current-refresh")).thenReturn("current-hash");
        when(jwtTokenService.getAuthentication(claims)).thenReturn(authentication);
        when(jwtTokenService.getRefreshTokenValidityInMs()).thenReturn(345_600_000L);
        when(jwtTokenService.generateAccessToken(authentication, sessionId)).thenReturn("next-access");
        when(jwtTokenService.generateRefreshToken(eq(authentication), eq(sessionId), any(Date.class)))
                .thenReturn("next-refresh");
        when(jwtTokenService.fingerprintRefreshToken("next-refresh")).thenReturn("next-hash");
        when(authSessionRepository.saveAndFlush(session)).thenThrow(storeFailure);

        assertThatThrownBy(() -> service.rotate("current-refresh"))
                .isSameAs(storeFailure);

        verify(jwtTokenService, never()).getAccessTokenValidityInMs();
    }

    @Test
    @DisplayName("A session at the exact inactivity deadline is rejected without extension")
    void rotate_inactiveSessionRejectsWithoutWriting() {
        AuthSession session = new AuthSession(sessionId, userId, "current-hash", now.minusDays(4), now);
        ValidatedJwt claims = claims(now.plusDays(1));
        when(jwtTokenService.validateRefreshToken("expired-session-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        AuthSessionRefreshService.RefreshResult result = service.rotate("expired-session-refresh");

        assertThat(result.isRejected()).isTrue();
        assertThat(result.rejectionReason()).isEqualTo(AuthSessionRejectionReason.INACTIVE_SESSION);
        verify(authSessionRepository, never()).saveAndFlush(any(AuthSession.class));
        verify(jwtTokenService, never()).fingerprintRefreshToken(any());
        assertThat(session.getExpiresAt()).isEqualTo(now);
    }

    private ValidatedJwt claims(LocalDateTime expiry) {
        return new ValidatedJwt("alice", userId, sessionId, expiry.toInstant(ZoneOffset.UTC));
    }
}
