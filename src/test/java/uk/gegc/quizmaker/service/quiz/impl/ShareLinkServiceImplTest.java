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


