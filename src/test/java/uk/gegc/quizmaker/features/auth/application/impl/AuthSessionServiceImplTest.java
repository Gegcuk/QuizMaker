package uk.gegc.quizmaker.features.auth.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.auth.api.dto.JwtResponse;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Authentication Session Service")
class AuthSessionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

    @Mock
    private AuthSessionRepository authSessionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenService jwtTokenService;

    @Mock
    private AuthSessionMetricsService authSessionMetricsService;

    @Mock
    private Authentication authentication;

    private AuthSessionServiceImpl service;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new AuthSessionServiceImpl(
                authSessionRepository,
                userRepository,
                jwtTokenService,
                authSessionMetricsService,
                clock
        );
    }

    @Test
    @DisplayName("issues a new session without persisting the raw refresh token")
    void issueTokens_persistsHashedRefreshVerifier() {
        User user = new User();
        user.setId(userId);
        when(authentication.getName()).thenReturn("alice");
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(jwtTokenService.getRefreshTokenValidityInMs()).thenReturn(604_800_000L);
        when(jwtTokenService.getAccessTokenValidityInMs()).thenReturn(43_200_000L);
        when(jwtTokenService.generateAccessToken(eq(authentication), any(UUID.class))).thenReturn("access-token");
        when(jwtTokenService.generateRefreshToken(eq(authentication), any(UUID.class), any(Date.class)))
                .thenReturn("refresh-token");
        when(jwtTokenService.fingerprintRefreshToken("refresh-token")).thenReturn("fingerprint");

        JwtResponse result = service.issueTokens(authentication);

        ArgumentCaptor<AuthSession> sessionCaptor = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionRepository).save(sessionCaptor.capture());
        AuthSession saved = sessionCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getRefreshTokenHash()).isEqualTo("fingerprint").isNotEqualTo("refresh-token");
        assertThat(saved.getExpiresAt()).isEqualTo(now.plusDays(7));
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.refreshExpiresInMs()).isEqualTo(604_800_000L);
        verify(authSessionMetricsService).recordSessionIssued();
    }

    @Test
    @DisplayName("rotates a valid refresh token once and preserves the session expiry")
    void refresh_rotatesCurrentVerifierAndIssuesReplacementPair() {
        LocalDateTime expiresAt = now.plusDays(3);
        AuthSession session = new AuthSession(sessionId, userId, "current-hash", now.minusDays(1), expiresAt);
        ValidatedJwt claims = new ValidatedJwt("alice", userId, sessionId, expiresAt.toInstant(ZoneOffset.UTC));
        when(jwtTokenService.validateRefreshToken("current-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(jwtTokenService.fingerprintRefreshToken("current-refresh")).thenReturn("current-hash");
        when(jwtTokenService.getAuthentication(claims)).thenReturn(authentication);
        when(jwtTokenService.generateAccessToken(authentication, sessionId)).thenReturn("next-access");
        when(jwtTokenService.generateRefreshToken(eq(authentication), eq(sessionId), any(Date.class)))
                .thenReturn("next-refresh");
        when(jwtTokenService.fingerprintRefreshToken("next-refresh")).thenReturn("next-hash");
        when(jwtTokenService.getAccessTokenValidityInMs()).thenReturn(43_200_000L);

        JwtResponse result = service.refresh("current-refresh");

        assertThat(result.accessToken()).isEqualTo("next-access");
        assertThat(result.refreshToken()).isEqualTo("next-refresh");
        assertThat(result.refreshExpiresInMs()).isEqualTo(259_200_000L);
        assertThat(session.getRefreshTokenHash()).isEqualTo("next-hash");
        assertThat(session.getRefreshedAt()).isEqualTo(now);
        verify(authSessionRepository).save(session);
        verify(authSessionMetricsService).recordRefreshSucceeded();
    }

    @Test
    @DisplayName("refresh replay revokes the session and does not issue more tokens")
    void refresh_replayedTokenRevokesSessionAndFailsClosed() {
        AuthSession session = new AuthSession(sessionId, userId, "current-hash", now.minusDays(1), now.plusDays(3));
        ValidatedJwt claims = new ValidatedJwt("alice", userId, sessionId, now.plusDays(3).toInstant(ZoneOffset.UTC));
        when(jwtTokenService.validateRefreshToken("replayed-refresh")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(jwtTokenService.fingerprintRefreshToken("replayed-refresh")).thenReturn("stale-hash");

        assertThatThrownBy(() -> service.refresh("replayed-refresh"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid refresh token");

        assertThat(session.getRevocationReason()).isEqualTo(AuthSessionRevocationReason.REFRESH_TOKEN_REPLAY);
        assertThat(session.getRevokedAt()).isEqualTo(now);
        verify(authSessionRepository).save(session);
        verify(authSessionMetricsService).recordRefreshRejected(AuthSessionRejectionReason.REPLAYED_TOKEN);
        verify(jwtTokenService, never()).generateAccessToken(any(Authentication.class), any(UUID.class));
    }

    @Test
    @DisplayName("logout revokes its current session once and allows an idempotent retry")
    void logout_revokesCurrentSessionExactlyOnce() {
        AuthSession session = new AuthSession(sessionId, userId, "hash", now.minusDays(1), now.plusDays(3));
        ValidatedJwt claims = new ValidatedJwt("alice", userId, sessionId, now.plusHours(1).toInstant(ZoneOffset.UTC));
        when(jwtTokenService.validateAccessToken("access-token")).thenReturn(Optional.of(claims));
        when(authSessionRepository.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));

        service.logout("access-token");
        service.logout("access-token");

        assertThat(session.getRevocationReason()).isEqualTo(AuthSessionRevocationReason.LOGOUT);
        assertThat(session.getRevokedAt()).isEqualTo(now);
        verify(authSessionRepository).save(session);
        verify(authSessionMetricsService, org.mockito.Mockito.times(2)).recordLogoutSucceeded();
    }

    @Test
    @DisplayName("a revoked session cannot authenticate an otherwise valid access token")
    void authenticateAccessToken_revokedSessionReturnsNoAuthentication() {
        ValidatedJwt claims = new ValidatedJwt("alice", userId, sessionId, now.plusHours(1).toInstant(ZoneOffset.UTC));
        when(jwtTokenService.validateAccessToken("access-token")).thenReturn(Optional.of(claims));
        when(authSessionRepository.existsActiveSession(sessionId, userId, now)).thenReturn(false);

        assertThat(service.authenticateAccessToken("access-token")).isNull();
        verify(jwtTokenService, never()).getAuthentication(claims);
        verify(authSessionMetricsService).recordAccessRejected(AuthSessionRejectionReason.INACTIVE_SESSION);
    }

    @Test
    @DisplayName("an active session authenticates only after the session-store check succeeds")
    void authenticateAccessToken_activeSessionReturnsAuthentication() {
        ValidatedJwt claims = new ValidatedJwt("alice", userId, sessionId, now.plusHours(1).toInstant(ZoneOffset.UTC));
        when(jwtTokenService.validateAccessToken("access-token")).thenReturn(Optional.of(claims));
        when(authSessionRepository.existsActiveSession(sessionId, userId, now)).thenReturn(true);
        when(jwtTokenService.getAuthentication(claims)).thenReturn(authentication);

        assertThat(service.authenticateAccessToken("access-token")).isSameAs(authentication);
    }

    @Test
    @DisplayName("cleanup removes only sessions whose refresh lifetime has ended")
    void purgeExpiredSessions_delegatesWithUtcNow() {
        when(authSessionRepository.deleteByExpiresAtBefore(now)).thenReturn(2L);

        assertThat(service.purgeExpiredSessions()).isEqualTo(2);
        verify(authSessionRepository).deleteByExpiresAtBefore(now);
        verify(authSessionMetricsService).recordExpiredSessionsPurged(2);
    }
}
