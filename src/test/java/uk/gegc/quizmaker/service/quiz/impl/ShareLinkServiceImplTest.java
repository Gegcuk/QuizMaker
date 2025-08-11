package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.dto.quiz.CreateShareLinkRequest;
import uk.gegc.quizmaker.dto.quiz.ShareLinkDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.quiz.ShareLink;
import uk.gegc.quizmaker.model.quiz.ShareLinkScope;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.ShareLinkRepository;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareLinkServiceImplTest {

    @Mock private ShareLinkRepository shareLinkRepository;
    @Mock private uk.gegc.quizmaker.repository.quiz.ShareLinkUsageRepository usageRepository;
    @Mock private QuizRepository quizRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private ShareLinkServiceImpl service;

    @Test
    @DisplayName("createShareLink: persists link and returns DTO without exposing raw token")
    void createShareLink_success() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); quiz.setId(quizId);
        User user = new User(); user.setId(userId);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> {
            ShareLink sl = inv.getArgument(0);
            sl.setId(UUID.randomUUID());
            sl.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
            return sl;
        });

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, Instant.parse("2025-12-31T00:00:00Z"), true);

        ShareLinkDto dto = service.createShareLink(quizId, userId, req);

        assertThat(dto.quizId()).isEqualTo(quizId);
        assertThat(dto.createdBy()).isEqualTo(userId);
        assertThat(dto.scope()).isEqualTo(ShareLinkScope.QUIZ_VIEW);
        assertThat(dto.expiresAt()).isEqualTo(Instant.parse("2025-12-31T00:00:00Z"));
        assertThat(dto.oneTime()).isTrue();
        verify(shareLinkRepository).save(any(ShareLink.class));
    }

    @Test
    @DisplayName("revokeShareLink: creator can revoke, sets revokedAt and saves")
    void revokeShareLink_success() {
        UUID linkId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ShareLink link = new ShareLink();
        User creator = new User(); creator.setId(userId);
        link.setCreatedBy(creator);

        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));

        service.revokeShareLink(linkId, userId);

        assertThat(link.getRevokedAt()).isNotNull();
        verify(shareLinkRepository).save(link);
    }

    @Test
    @DisplayName("revokeShareLink: non-creator forbidden")
    void revokeShareLink_forbidden() {
        UUID linkId = UUID.randomUUID();
        ShareLink link = new ShareLink();
        User creator = new User(); creator.setId(UUID.randomUUID());
        link.setCreatedBy(creator);
        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));
        assertThatThrownBy(() -> service.revokeShareLink(linkId, UUID.randomUUID()))
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
        User creator = new User(); creator.setId(userId);
        link.setCreatedBy(creator);
        link.setRevokedAt(Instant.now());
        when(shareLinkRepository.findById(linkId)).thenReturn(Optional.of(link));

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
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));

        service.recordShareLinkUsage("HASH", "UA", "127.0.0.1");
        verify(usageRepository).save(any(uk.gegc.quizmaker.model.quiz.ShareLinkUsage.class));
    }

    @Test
    @DisplayName("recordShareLinkUsage: unknown tokenHash -> not found")
    void recordShareLinkUsage_notFound() {
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
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

        ShareLinkDto dto = service.consumeOneTimeToken("RAW");
        assertThat(dto.oneTime()).isTrue();
        verify(shareLinkRepository).save(link);
        assertThat(link.getRevokedAt()).isNotNull();
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
        verify(shareLinkRepository, never()).save(any());
    }

    @Test
    @DisplayName("consumeOneTimeToken: unknown token -> not found")
    void consumeOneTimeToken_unknown() {
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.consumeOneTimeToken("X")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("consumeOneTimeToken: expired or revoked -> ValidationException")
    void consumeOneTimeToken_invalidStates() {
        ShareLink expired = new ShareLink();
        expired.setOneTime(true);
        expired.setExpiresAt(Instant.now().minusSeconds(1));
        expired.setQuiz(new Quiz()); expired.getQuiz().setId(UUID.randomUUID());
        expired.setCreatedBy(new User()); expired.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.consumeOneTimeToken("X")).isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class);

        ShareLink revoked = new ShareLink();
        revoked.setOneTime(true);
        revoked.setRevokedAt(Instant.now());
        revoked.setQuiz(new Quiz()); revoked.getQuiz().setId(UUID.randomUUID());
        revoked.setCreatedBy(new User()); revoked.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> service.consumeOneTimeToken("Y")).isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class);
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
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));

        ShareLinkDto dto = service.validateToken(token);
        assertThat(dto.quizId()).isEqualTo(quiz.getId());
        assertThat(dto.createdBy()).isEqualTo(user.getId());
        assertThat(dto.scope()).isEqualTo(ShareLinkScope.QUIZ_VIEW);
    }

    @Test
    @DisplayName("validateToken: throws not found for unknown token")
    void validateToken_unknown() {
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());
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
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        assertThatThrownBy(() -> service.validateToken("t"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("validateToken: throws ValidationException when revoked")
    void validateToken_revoked() {
        ShareLink link = new ShareLink();
        link.setRevokedAt(Instant.now());
        link.setQuiz(new Quiz()); link.getQuiz().setId(UUID.randomUUID());
        link.setCreatedBy(new User()); link.getCreatedBy().setId(UUID.randomUUID());
        when(shareLinkRepository.findByTokenHash(anyString())).thenReturn(Optional.of(link));
        assertThatThrownBy(() -> service.validateToken("t"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("revoked");
    }

    // (no helpers required)

    @Test
    @DisplayName("createShareLink: defaults scope to QUIZ_VIEW when null, oneTime=false when null, expiresAt can be null")
    void createShareLink_defaults() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); quiz.setId(quizId);
        User user = new User(); user.setId(userId);

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
        ShareLinkDto dto = service.createShareLink(quizId, userId, req);

        assertThat(dto.scope()).isEqualTo(ShareLinkScope.QUIZ_VIEW);
        assertThat(dto.oneTime()).isFalse();
        assertThat(dto.expiresAt()).isNull();
        assertThat(dto.revokedAt()).isNull();
        assertThat(dto.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("createShareLink: saves 64-char uppercase hex tokenHash and does not expose token")
    void createShareLink_tokenHashShape() {
        UUID quizId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Quiz quiz = new Quiz(); quiz.setId(quizId);
        User user = new User(); user.setId(userId);

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
        Quiz quiz = new Quiz(); quiz.setId(quizId);
        User user = new User(); user.setId(userId);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(shareLinkRepository.save(any(ShareLink.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateShareLinkRequest req = new CreateShareLinkRequest(ShareLinkScope.QUIZ_VIEW, null, false);
        ShareLinkDto dto = service.createShareLink(quizId, userId, req);
        assertThat(dto.oneTime()).isFalse();
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


