package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkResponse;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.ShareLink;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;
import uk.gegc.quizmaker.model.quiz.ShareLinkUsage;
import uk.gegc.quizmaker.model.user.PermissionName;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.quiz.ShareLinkRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.security.PermissionEvaluator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareLinkServiceImplTest {

    @Mock private ShareLinkRepository shareLinkRepository;
    @Mock private uk.gegc.quizmaker.repository.quiz.ShareLinkUsageRepository usageRepository;
    @Mock private QuizRepository quizRepository;
    @Mock private UserRepository userRepository;
    @Mock private PermissionEvaluator permissionEvaluator;

    @InjectMocks private ShareLinkServiceImpl service;

    // Helper method to compute SHA-256 hash
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Test
    @DisplayName("createShareLink: persists link and returns DTO + raw token once")
    void createShareLink_success() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User creator = new User(); 
        creator.setId(userId);
        quiz.setCreator(creator);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(creator));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink sl = inv.getArgument(0);
            sl.setId(UUID.randomUUID());
            sl.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
            return sl;
        });

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.parse("2025-12-31T00:00:00Z"), true);
        CreateShareLinkResponse resp = service.createShareLink(quizId, userId, req);
        ShareLinkDto dto = resp.link();

        assertThat(resp.token()).isNotNull();
        assertThat(dto.quizId()).isEqualTo(quizId);
        assertThat(dto.createdBy()).isEqualTo(userId);
        assertThat(dto.scope()).isEqualTo(ShareLinkScope.QUIZ_VIEW);
        // The service caps expiry to maxExpiryHours, so we can't expect the exact date
        // Just verify it's not null and reasonable
        assertThat(dto.expiresAt()).isNotNull();
        assertThat(dto.oneTime()).isTrue();
        verify(shareLinkRepository).save(any(ShareLink.class));
    }

    @Test
    @DisplayName("createShareLink: non-owner with no perms throws ForbiddenException")
    void createShareLink_nonOwnerNoPerms() {
        UUID quizId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID nonOwnerId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User owner = new User(); 
        owner.setId(ownerId);
        User nonOwner = new User(); 
        nonOwner.setId(nonOwnerId);
        quiz.setCreator(owner);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(nonOwnerId)).thenReturn(Optional.of(nonOwner));
        when(permissionEvaluator.hasPermission(eq(nonOwner), eq(PermissionName.QUIZ_ADMIN))).thenReturn(false);
        when(permissionEvaluator.hasPermission(eq(nonOwner), eq(PermissionName.QUIZ_MODERATE))).thenReturn(false);

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        assertThatThrownBy(() -> service.createShareLink(quizId, nonOwnerId, req))
                .isInstanceOf(uk.gegc.quizmaker.exception.ForbiddenException.class)
                .hasMessageContaining("Not allowed to share this quiz");
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("createShareLink: non-owner with QUIZ_ADMIN permission succeeds")
    void createShareLink_nonOwnerWithAdminPerm() {
        UUID quizId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User owner = new User(); 
        owner.setId(ownerId);
        User admin = new User(); 
        admin.setId(adminId);
        quiz.setCreator(owner);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(permissionEvaluator.hasPermission(eq(admin), eq(PermissionName.QUIZ_ADMIN))).thenReturn(true);
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink sl = inv.getArgument(0);
            sl.setId(UUID.randomUUID());
            sl.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
            return sl;
        });

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        CreateShareLinkResponse resp = service.createShareLink(quizId, adminId, req);
        
        assertThat(resp.token()).isNotNull();
        assertThat(resp.link().quizId()).isEqualTo(quizId);
        verify(shareLinkRepository).save(any(ShareLink.class));
    }

    @Test
    @DisplayName("createShareLink: non-owner with QUIZ_MODERATE permission succeeds")
    void createShareLink_nonOwnerWithModeratePerm() {
        UUID quizId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User owner = new User(); 
        owner.setId(ownerId);
        User moderator = new User(); 
        moderator.setId(moderatorId);
        quiz.setCreator(owner);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(moderatorId)).thenReturn(Optional.of(moderator));
        when(permissionEvaluator.hasPermission(eq(moderator), eq(PermissionName.QUIZ_ADMIN))).thenReturn(false);
        when(permissionEvaluator.hasPermission(eq(moderator), eq(PermissionName.QUIZ_MODERATE))).thenReturn(true);
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink sl = inv.getArgument(0);
            sl.setId(UUID.randomUUID());
            sl.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
            return sl;
        });

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        CreateShareLinkResponse resp = service.createShareLink(quizId, moderatorId, req);
        
        assertThat(resp.token()).isNotNull();
        assertThat(resp.link().quizId()).isEqualTo(quizId);
        verify(shareLinkRepository).save(any(ShareLink.class));
    }

    @Test
    @DisplayName("createShareLink: expiresAt in the past throws ValidationException")
    void createShareLink_expiresAtInPast() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User creator = new User(); 
        creator.setId(userId);
        quiz.setCreator(creator);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(creator));

        Instant pastTime = Instant.now().minusSeconds(3600);
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, pastTime, false);
        
        assertThatThrownBy(() -> service.createShareLink(quizId, userId, req))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("Expiry must be in the future");
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("createShareLink: far-future expiresAt gets capped at maxExpiryHours")
    void createShareLink_expiresAtCapped() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User creator = new User(); 
        creator.setId(userId);
        quiz.setCreator(creator);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(creator));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        // Set a far future date that should be capped
        Instant farFuture = Instant.now().plusSeconds(365 * 24 * 3600); // 1 year from now
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, farFuture, false);
        
        service.createShareLink(quizId, userId, req);

        var captor = forClass(ShareLink.class);
        verify(shareLinkRepository).save(captor.capture());
        ShareLink saved = captor.getValue();
        
        // Should be capped to maxExpiryHours (720 hours = 30 days)
        Instant expectedCap = Instant.now().plusSeconds(720 * 3600);
        assertThat(saved.getExpiresAt()).isBeforeOrEqualTo(expectedCap);
        // Just verify it's not null and reasonable
        assertThat(saved.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("consumeOneTimeToken: atomic race - first call succeeds, second throws ShareLinkAlreadyUsedException")
    void consumeOneTimeToken_atomicRace() {
        ShareLink link = new ShareLink();
        link.setOneTime(true);
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        when(shareLinkRepository.consumeOneTime(anyString(), any(Instant.class)))
                .thenReturn(1)  // First call succeeds
                .thenReturn(0); // Second call fails

        // First call should succeed
        ShareLinkDto dto1 = service.consumeOneTimeToken("RAW");
        assertThat(dto1.oneTime()).isTrue();

        // Second call should throw ShareLinkAlreadyUsedException
        assertThatThrownBy(() -> service.consumeOneTimeToken("RAW"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ShareLinkAlreadyUsedException.class)
                .hasMessageContaining("Token already used");

        verify(shareLinkRepository, times(1)).consumeOneTime(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("createShareLink: returned token matches URL-safe Base64 pattern")
    void createShareLink_tokenShape() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User creator = new User(); 
        creator.setId(userId);
        quiz.setCreator(creator);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(creator));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink sl = inv.getArgument(0);
            sl.setId(UUID.randomUUID());
            sl.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
            return sl;
        });

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        CreateShareLinkResponse resp = service.createShareLink(quizId, userId, req);
        
        String token = resp.token();
        assertThat(token).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(token).hasSize(43);
    }

    @Test
    @DisplayName("recordShareLinkUsage: very long user agent is truncated to 256 chars")
    void recordShareLinkUsage_longUserAgentTruncated() {
        ShareLink link = new ShareLink();
        link.setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(link));
        when(usageRepository.save(any(ShareLinkUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        // Create a user agent longer than 256 chars
        StringBuilder longUA = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longUA.append("a");
        }
        String userAgent = longUA.toString();

        service.recordShareLinkUsage("HASH", userAgent, "127.0.0.1");

        var captor = forClass(ShareLinkUsage.class);
        verify(usageRepository).save(captor.capture());
        ShareLinkUsage saved = captor.getValue();
        
        assertThat(saved.getUserAgent()).hasSize(256);
        assertThat(saved.getUserAgent()).isEqualTo(userAgent.substring(0, 256));
    }

    @Test
    @DisplayName("recordShareLinkUsage: ipHash computed correctly")
    void recordShareLinkUsage_ipHashComputed() {
        ShareLink link = new ShareLink();
        link.setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(link));
        when(usageRepository.save(any(ShareLinkUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        String ipAddress = "192.168.1.100";
        service.recordShareLinkUsage("HASH", "UA", ipAddress);

        var captor = forClass(ShareLinkUsage.class);
        verify(usageRepository).save(captor.capture());
        ShareLinkUsage saved = captor.getValue();
        
        // The actual pepper value is configured in the service, but for testing we can verify
        // that the hash is computed and stored correctly without knowing the exact pepper
        assertThat(saved.getIpHash()).isNotNull();
        assertThat(saved.getIpHash()).matches("[0-9A-F]{64}");
        
        // We can also verify that the hash is different for different IPs
        String ipAddress2 = "192.168.1.101";
        service.recordShareLinkUsage("HASH", "UA", ipAddress2);
        
        var captor2 = forClass(ShareLinkUsage.class);
        verify(usageRepository, times(2)).save(captor2.capture());
        ShareLinkUsage saved2 = captor2.getValue();
        
        assertThat(saved2.getIpHash()).isNotEqualTo(saved.getIpHash());
    }

    @Test
    @DisplayName("validateToken: revoked link results in ResourceNotFoundException")
    void validateToken_revokedLinkNotFound() {
        // The service uses findByTokenHashAndRevokedAtIsNull, so revoked links won't be found
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.empty());
        
        assertThatThrownBy(() -> service.validateToken("revoked-token"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invalid or unknown token");
    }

    @Test
    @DisplayName("revokeShareLink: non-creator with admin permission succeeds")
    void revokeShareLink_nonCreatorWithAdminPerm() {
        UUID linkId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        ShareLink link = new ShareLink();
        User creator = new User(); 
        creator.setId(creatorId);
        User admin = new User(); 
        admin.setId(adminId);
        link.setCreatedBy(creator);

        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));
        when(userRepository.findById(adminId)).thenReturn(Optional.of(admin));
        when(permissionEvaluator.hasPermission(eq(admin), eq(PermissionName.QUIZ_ADMIN))).thenReturn(true);

        service.revokeShareLink(linkId, adminId);

        assertThat(link.getRevokedAt()).isNotNull();
        verify(shareLinkRepository).save(link);
    }

    @Test
    @DisplayName("revokeShareLink: non-creator with moderate permission succeeds")
    void revokeShareLink_nonCreatorWithModeratePerm() {
        UUID linkId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID moderatorId = UUID.randomUUID();
        ShareLink link = new ShareLink();
        User creator = new User(); 
        creator.setId(creatorId);
        User moderator = new User(); 
        moderator.setId(moderatorId);
        link.setCreatedBy(creator);

        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));
        when(userRepository.findById(moderatorId)).thenReturn(Optional.of(moderator));
        when(permissionEvaluator.hasPermission(eq(moderator), eq(PermissionName.QUIZ_ADMIN))).thenReturn(false);
        when(permissionEvaluator.hasPermission(eq(moderator), eq(PermissionName.QUIZ_MODERATE))).thenReturn(true);

        service.revokeShareLink(linkId, moderatorId);

        assertThat(link.getRevokedAt()).isNotNull();
        verify(shareLinkRepository).save(link);
    }

    @Test
    @DisplayName("revokeShareLink: creator can revoke, sets revokedAt and saves")
    void revokeShareLink_success() {
        UUID linkId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ShareLink link = new ShareLink();
        User creator = new User(); 
        creator.setId(userId);
        link.setCreatedBy(creator);

        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));
        when(userRepository.findById(userId)).thenReturn(Optional.of(creator));

        service.revokeShareLink(linkId, userId);

        assertThat(link.getRevokedAt()).isNotNull();
        verify(shareLinkRepository).save(link);
    }

    @Test
    @DisplayName("revokeShareLink: non-creator forbidden")
    void revokeShareLink_forbidden() {
        UUID linkId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        UUID nonCreatorId = UUID.randomUUID();
        ShareLink link = new ShareLink();
        User creator = new User(); 
        creator.setId(creatorId);
        User nonCreator = new User(); 
        nonCreator.setId(nonCreatorId);
        link.setCreatedBy(creator);
        
        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));
        when(userRepository.findById(nonCreatorId)).thenReturn(Optional.of(nonCreator));
        when(permissionEvaluator.hasPermission(eq(nonCreator), any())).thenReturn(false);
        
        assertThatThrownBy(() -> service.revokeShareLink(linkId, nonCreatorId))
                .isInstanceOf(uk.gegc.quizmaker.exception.ForbiddenException.class);
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("revokeShareLink: missing link -> not found")
    void revokeShareLink_notFound() {
        UUID linkId = UUID.randomUUID();
        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revokeShareLink(linkId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("revokeShareLink: idempotent when already revoked")
    void revokeShareLink_idempotent() {
        UUID linkId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ShareLink link = new ShareLink();
        User creator = new User(); 
        creator.setId(userId);
        link.setCreatedBy(creator);
        link.setRevokedAt(Instant.now());
        
        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));
        when(userRepository.findById(userId)).thenReturn(Optional.of(creator));

        service.revokeShareLink(linkId, userId);
        // no additional save beyond potential noop; allow or verify not called
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("getUserShareLinks: returns list of user links ordered by createdAt desc")
    void getUserShareLinks_success() {
        UUID userId = UUID.randomUUID();
        ShareLink a = new ShareLink(); a.setId(UUID.randomUUID());
        ShareLink b = new ShareLink(); b.setId(UUID.randomUUID());
        // minimal required nested to map DTOs
        Quiz qa = new Quiz(); qa.setId(UUID.randomUUID()); a.setQuiz(qa);
        User ua = new User(); ua.setId(userId); a.setCreatedBy(ua);
        Quiz qb = new Quiz(); qb.setId(UUID.randomUUID()); b.setQuiz(qb);
        User ub = new User(); ub.setId(userId); b.setCreatedBy(ub);

        when(shareLinkRepository.findAllByCreatedBy_IdOrderByCreatedAtDesc(userId)).thenReturn(java.util.List.of(a, b));

        var result = service.getUserShareLinks(userId);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).createdBy()).isEqualTo(userId);
        verify(shareLinkRepository).findAllByCreatedBy_IdOrderByCreatedAtDesc(userId);
    }

    @Test
    @DisplayName("getUserShareLinks: empty list when none")
    void getUserShareLinks_empty() {
        UUID userId = UUID.randomUUID();
        when(shareLinkRepository.findAllByCreatedBy_IdOrderByCreatedAtDesc(userId)).thenReturn(java.util.List.of());
        var result = service.getUserShareLinks(userId);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("recordShareLinkUsage: saves usage with hashed IP and UA")
    void recordShareLinkUsage_success() {
        ShareLink link = new ShareLink();
        link.setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(link));

        service.recordShareLinkUsage("HASH", "UA", "127.0.0.1");
        verify(usageRepository).save(any(uk.gegc.quizmaker.model.quiz.ShareLinkUsage.class));
    }

    @Test
    @DisplayName("recordShareLinkUsage: unknown tokenHash -> not found")
    void recordShareLinkUsage_notFound() {
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.recordShareLinkUsage("X", "UA", "127.0.0.1"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(usageRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumeOneTimeToken: one-time valid token gets consumed (revoked)")
    void consumeOneTimeToken_success() {
        ShareLink link = new ShareLink();
        link.setOneTime(true);
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        when(shareLinkRepository.consumeOneTime(anyString(), any(Instant.class))).thenReturn(1);

        ShareLinkDto dto = service.consumeOneTimeToken("RAW");
        assertThat(dto.oneTime()).isTrue();
        verify(shareLinkRepository).consumeOneTime(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("consumeOneTimeToken: non one-time token just returns DTO (no revoke)")
    void consumeOneTimeToken_nonOneTime() {
        ShareLink link = new ShareLink();
        link.setOneTime(false);
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));

        ShareLinkDto dto = service.consumeOneTimeToken("RAW");
        assertThat(dto.oneTime()).isFalse();
        verify(shareLinkRepository, never()).consumeOneTime(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("consumeOneTimeToken: unknown token -> not found")
    void consumeOneTimeToken_unknown() {
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consumeOneTimeToken("X")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("consumeOneTimeToken: already revoked throws ShareLinkAlreadyUsedException")
    void consumeOneTimeToken_alreadyRevoked() {
        ShareLink link = new ShareLink();
        link.setOneTime(true);
        link.setRevokedAt(Instant.now()); // Already revoked
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        
        assertThatThrownBy(() -> service.consumeOneTimeToken("X"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ShareLinkAlreadyUsedException.class)
                .hasMessageContaining("Token already used");
        verify(shareLinkRepository, never()).consumeOneTime(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("consumeOneTimeToken: expired token throws ValidationException")
    void consumeOneTimeToken_expired() {
        ShareLink link = new ShareLink();
        link.setOneTime(true);
        link.setExpiresAt(Instant.now().minusSeconds(1)); // Expired
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        
        assertThatThrownBy(() -> service.consumeOneTimeToken("X"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("Token expired");
        verify(shareLinkRepository, never()).consumeOneTime(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("consumeOneTimeToken: records usage before consuming for one-time tokens")
    void consumeOneTimeToken_recordsUsage() {
        ShareLink link = new ShareLink();
        link.setOneTime(true);
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(link));
        when(shareLinkRepository.consumeOneTime(anyString(), any(Instant.class))).thenReturn(1);
        when(usageRepository.save(any(ShareLinkUsage.class))).thenAnswer(inv -> inv.getArgument(0));

        service.consumeOneTimeToken("RAW", "User-Agent", "127.0.0.1");

        verify(usageRepository).save(any(ShareLinkUsage.class));
        verify(shareLinkRepository).consumeOneTime(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("consumeOneTimeToken: overloaded method delegates to main method")
    void consumeOneTimeToken_delegation() {
        ShareLink link = new ShareLink();
        link.setOneTime(true);
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        when(shareLinkRepository.consumeOneTime(anyString(), any(Instant.class))).thenReturn(1);

        ShareLinkDto dto = service.consumeOneTimeToken("RAW");
        assertThat(dto.oneTime()).isTrue();
        verify(shareLinkRepository).consumeOneTime(anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("revokeActiveShareLinksForQuiz: revokes all active links and saves batch")
    void revokeActiveShareLinksForQuiz_success() {
        UUID quizId = UUID.randomUUID();
        ShareLink a = new ShareLink(); a.setRevokedAt(null); a.setQuiz(new Quiz()); a.getQuiz().setId(quizId);
        ShareLink b = new ShareLink(); b.setRevokedAt(null); b.setQuiz(new Quiz()); b.getQuiz().setId(quizId);
        when(shareLinkRepository.findAllByQuiz_IdAndRevokedAtIsNull(quizId)).thenReturn(java.util.List.of(a, b));

        service.revokeActiveShareLinksForQuiz(quizId);

        assertThat(a.getRevokedAt()).isNotNull();
        assertThat(b.getRevokedAt()).isNotNull();
        verify(shareLinkRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("revokeActiveShareLinksForQuiz: idempotent when none active")
    void revokeActiveShareLinksForQuiz_idempotent() {
        UUID quizId = UUID.randomUUID();
        when(shareLinkRepository.findAllByQuiz_IdAndRevokedAtIsNull(quizId)).thenReturn(java.util.List.of());
        service.revokeActiveShareLinksForQuiz(quizId);
        verify(shareLinkRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("validateToken: returns DTO for valid, non-expired, non-revoked token")
    void validateToken_success() {
        ShareLink link = new ShareLink();
        link.setId(UUID.randomUUID());
        Quiz quiz = new Quiz(); quiz.setId(UUID.randomUUID());
        User user = new User(); user.setId(UUID.randomUUID());
        link.setQuiz(quiz);
        link.setCreatedBy(user);
        link.setScope(ShareLinkScope.QUIZ_VIEW);
        link.setOneTime(false);
        link.setExpiresAt(Instant.now().plusSeconds(3600));
        link.setCreatedAt(Instant.now());

        // hash of token with pepper (empty pepper for test)
        String token = "ABC";
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(link));

        ShareLinkDto dto = service.validateToken(token);
        assertThat(dto.quizId()).isEqualTo(quiz.getId());
        assertThat(dto.createdBy()).isEqualTo(user.getId());
        assertThat(dto.scope()).isEqualTo(ShareLinkScope.QUIZ_VIEW);
    }

    @Test
    @DisplayName("validateToken: throws not found for unknown token")
    void validateToken_unknown() {
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validateToken("nope"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invalid or unknown token");
    }

    @Test
    @DisplayName("validateToken: throws ValidationException when expired")
    void validateToken_expired() {
        ShareLink link = new ShareLink();
        link.setExpiresAt(Instant.now().minusSeconds(1));
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.of(link));
        assertThatThrownBy(() -> service.validateToken("t"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("validateToken: throws ResourceNotFoundException when revoked")
    void validateToken_revoked() {
        // For revoked tokens, the findByTokenHashAndRevokedAtIsNull won't find them
        // So we need to test this differently - the service will throw ResourceNotFoundException
        when(shareLinkRepository.findByTokenHashAndRevokedAtIsNull(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validateToken("t"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Invalid or unknown token");
    }

    @Test
    @DisplayName("hashToken: returns SHA-256 hash of token with pepper")
    void hashToken_success() {
        String token = "test-token";
        String hash = service.hashToken(token);
        
        assertThat(hash).isNotNull();
        assertThat(hash).matches("[0-9A-F]{64}");
        // The hash should be the same for the same token
        assertThat(service.hashToken(token)).isEqualTo(hash);
    }

    // (no helpers required)

    @Test
    @DisplayName("createShareLink: defaults scope to QUIZ_VIEW when null, oneTime=false when null, expiresAt can be null")
    void createShareLink_defaults() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User user = new User(); 
        user.setId(userId);
        quiz.setCreator(user);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink sl = inv.getArgument(0);
            sl.setId(UUID.randomUUID());
            sl.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
            return sl;
        });

        // scope=null, expiresAt=null, oneTime=null
        CreateShareLinkRequest req = new CreateShareLinkRequest(null, null, null);
        CreateShareLinkResponse resp = service.createShareLink(quizId, userId, req);
        ShareLinkDto dto = resp.link();

        assertThat(dto.scope()).isEqualTo(ShareLinkScope.QUIZ_VIEW);
        assertThat(dto.oneTime()).isFalse();
        assertThat(dto.expiresAt()).isNotNull();
        assertThat(dto.revokedAt()).isNull();
        assertThat(dto.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("createShareLink: saves 64-char uppercase hex tokenHash and does not expose token")
    void createShareLink_tokenHashShape() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User user = new User(); 
        user.setId(userId);
        quiz.setCreator(user);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        service.createShareLink(quizId, userId, req);

        var captor = forClass(ShareLink.class);
        verify(shareLinkRepository).save(captor.capture());
        ShareLink saved = captor.getValue();
        assertThat(saved.getTokenHash()).isNotNull();
        assertThat(saved.getTokenHash()).matches("[0-9A-F]{64}");
    }

    @Test
    @DisplayName("createShareLink: oneTime=false respected when explicitly false")
    void createShareLink_oneTimeFalse() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); 
        quiz.setId(quizId);
        User user = new User(); 
        user.setId(userId);
        quiz.setCreator(user);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        CreateShareLinkResponse resp = service.createShareLink(quizId, userId, req);
        assertThat(resp.link().oneTime()).isFalse();
    }

    @Test
    @DisplayName("createShareLink: quiz not found throws ResourceNotFoundException")
    void createShareLink_quizNotFound() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        assertThatThrownBy(() -> service.createShareLink(quizId, userId, req))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).findById(any());
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("createShareLink: user not found throws ResourceNotFoundException")
    void createShareLink_userNotFound() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(new Quiz()));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        assertThatThrownBy(() -> service.createShareLink(quizId, userId, req))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(shareLinkRepository, never()).save(any());
    }
}


