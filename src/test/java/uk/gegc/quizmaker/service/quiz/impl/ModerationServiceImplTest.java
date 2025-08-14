package uk.gegc.quizmaker.service.quiz.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gegc.quizmaker.features.quiz.api.dto.PendingReviewQuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizModerationAuditDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.features.quiz.application.impl.ModerationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizModerationAudit;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizModerationAuditRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.security.AppPermissionEvaluator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Execution(ExecutionMode.CONCURRENT)
class ModerationServiceImplTest {

    @Mock
    private QuizRepository quizRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private QuizMapper quizMapper;
    @Mock
    private QuizModerationAuditRepository auditRepository;
    @Mock
    private AppPermissionEvaluator appPermissionEvaluator;
    @Mock
    private java.time.Clock clock;

    @InjectMocks
    private ModerationServiceImpl moderationService;

    private UUID quizId;
    private UUID userId;
    private User user;
    private User moderator;

    @BeforeEach
    void setUp() {
        quizId = UUID.randomUUID();
        userId = UUID.randomUUID();
        user = new User();
        user.setId(userId);
        user.setUsername("moderator");
        user.setEmail("m@example.com");

        moderator = new User();
        moderator.setId(UUID.randomUUID());
        moderator.setUsername("reviewer");
        moderator.setEmail("r@example.com");

        // Allow permission guard to pass in submitForReview
        when(appPermissionEvaluator.hasPermission(any(User.class), any(PermissionName.class))).thenReturn(true);
        // Stable clock for Instant.now(clock)
        when(clock.instant()).thenReturn(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("getPendingReviewQuizzes: returns DTOs mapped and ordered by createdAt desc")
    void getPendingReviewQuizzes_returnsList() {
        Quiz q1 = new Quiz();
        q1.setId(UUID.randomUUID());
        q1.setStatus(QuizStatus.PENDING_REVIEW);
        Quiz q2 = new Quiz();
        q2.setId(UUID.randomUUID());
        q2.setStatus(QuizStatus.PENDING_REVIEW);

        when(quizRepository.findAllByStatusOrderByCreatedAtDesc(QuizStatus.PENDING_REVIEW))
                .thenReturn(List.of(q1, q2));
        PendingReviewQuizDto d1 = new PendingReviewQuizDto(q1.getId(), null, null, null);
        PendingReviewQuizDto d2 = new PendingReviewQuizDto(q2.getId(), null, null, null);
        when(quizMapper.toPendingReviewDto(q1)).thenReturn(d1);
        when(quizMapper.toPendingReviewDto(q2)).thenReturn(d2);

        List<PendingReviewQuizDto> result = moderationService.getPendingReviewQuizzes(UUID.randomUUID());
        assertThat(result).containsExactly(d1, d2);
        verify(quizRepository).findAllByStatusOrderByCreatedAtDesc(QuizStatus.PENDING_REVIEW);
        verify(quizMapper).toPendingReviewDto(q1);
        verify(quizMapper).toPendingReviewDto(q2);
    }

    @Test
    @DisplayName("getQuizAuditTrail: returns mapped audit DTOs in desc order")
    void getQuizAuditTrail_returnsList() {
        UUID qid = UUID.randomUUID();
        QuizModerationAudit a1 = new QuizModerationAudit();
        a1.setId(UUID.randomUUID());
        QuizModerationAudit a2 = new QuizModerationAudit();
        a2.setId(UUID.randomUUID());

        when(auditRepository.findAllByQuiz_IdOrderByCreatedAtDesc(qid)).thenReturn(List.of(a1, a2));
        QuizModerationAuditDto d1 = new QuizModerationAuditDto(a1.getId(), qid, null, null, null, null, null);
        QuizModerationAuditDto d2 = new QuizModerationAuditDto(a2.getId(), qid, null, null, null, null, null);
        when(quizMapper.toAuditDto(a1)).thenReturn(d1);
        when(quizMapper.toAuditDto(a2)).thenReturn(d2);

        List<QuizModerationAuditDto> result = moderationService.getQuizAuditTrail(qid);
        assertThat(result).containsExactly(d1, d2);
        verify(auditRepository).findAllByQuiz_IdOrderByCreatedAtDesc(qid);
        verify(quizMapper).toAuditDto(a1);
        verify(quizMapper).toAuditDto(a2);
    }

    // ===================== unpublishQuiz tests =====================

    @Test
    @DisplayName("unpublishQuiz: PUBLISHED → DRAFT sets reviewed fields and saves")
    void unpublish_fromPublished_success() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PUBLISHED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.of(moderator));

        moderationService.unpublishQuiz(quizId, modId, "policy change");

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.DRAFT);
        assertThat(quiz.getReviewedAt()).isNotNull();
        assertThat(quiz.getReviewedBy()).isEqualTo(moderator);
        assertThat(quiz.getRejectionReason()).isNull();
        verify(quizRepository).saveAndFlush(quiz);
    }

    @Test
    @DisplayName("unpublishQuiz: Non-PUBLISHED state throws ValidationException")
    void unpublish_fromInvalidState_throwsValidation() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.DRAFT);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.of(moderator));

        assertThatThrownBy(() -> moderationService.unpublishQuiz(quizId, modId, "r"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("cannot unpublish unless PUBLISHED");
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("unpublishQuiz: Quiz not found")
    void unpublish_quizNotFound() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> moderationService.unpublishQuiz(quizId, modId, "r"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("unpublishQuiz: Moderator not found")
    void unpublish_moderatorNotFound() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PUBLISHED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.unpublishQuiz(quizId, modId, "r"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ResourceNotFoundException.class);
        verify(quizRepository, never()).save(any());
    }

    // ===================== rejectQuiz tests =====================

    @Test
    @DisplayName("rejectQuiz: PENDING_REVIEW → REJECTED sets reviewed fields and rejectionReason")
    void reject_fromPendingReview_success() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PENDING_REVIEW);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.of(moderator));

        moderationService.rejectQuiz(quizId, modId, "Not sufficient quality");

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.REJECTED);
        assertThat(quiz.getReviewedAt()).isNotNull();
        assertThat(quiz.getReviewedBy()).isEqualTo(moderator);
        assertThat(quiz.getRejectionReason()).isEqualTo("Not sufficient quality");
        verify(quizRepository).saveAndFlush(quiz);
    }

    @Test
    @DisplayName("rejectQuiz: Non-PENDING_REVIEW state throws ValidationException")
    void reject_fromInvalidState_throwsValidation() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.DRAFT);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.of(moderator));

        assertThatThrownBy(() -> moderationService.rejectQuiz(quizId, modId, "r"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("cannot reject");
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("rejectQuiz: Quiz not found")
    void reject_quizNotFound() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> moderationService.rejectQuiz(quizId, modId, "r")).isInstanceOf(uk.gegc.quizmaker.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("rejectQuiz: Moderator not found")
    void reject_moderatorNotFound() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PENDING_REVIEW);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.rejectQuiz(quizId, modId, "r"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ResourceNotFoundException.class);
        verify(quizRepository, never()).saveAndFlush(any());
    }

    // ===================== approveQuiz tests =====================

    @Test
    @DisplayName("approveQuiz: PENDING_REVIEW → PUBLISHED sets reviewed fields and saves")
    void approve_fromPendingReview_success() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PENDING_REVIEW);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.of(moderator));
        when(quizRepository.saveAndFlush(any(Quiz.class))).thenAnswer(inv -> inv.getArgument(0));

        moderationService.approveQuiz(quizId, modId, "Looks good");

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PUBLISHED);
        assertThat(quiz.getReviewedAt()).isNotNull();
        assertThat(quiz.getReviewedBy()).isEqualTo(moderator);
        assertThat(quiz.getRejectionReason()).isNull();
        verify(quizRepository).saveAndFlush(quiz);
    }

    @Test
    @DisplayName("approveQuiz: Non-PENDING_REVIEW state throws ValidationException")
    void approve_fromInvalidState_throwsValidation() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.DRAFT);

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.of(moderator));

        assertThatThrownBy(() -> moderationService.approveQuiz(quizId, modId, "reason"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ValidationException.class)
                .hasMessageContaining("cannot approve");
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("approveQuiz: Quiz not found")
    void approve_quizNotFound() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> moderationService.approveQuiz(quizId, modId, "r")).isInstanceOf(uk.gegc.quizmaker.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("approveQuiz: Moderator not found")
    void approve_moderatorNotFound() {
        UUID quizId = this.quizId;
        UUID modId = moderator.getId();
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PENDING_REVIEW);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(modId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.approveQuiz(quizId, modId, "r"))
                .isInstanceOf(uk.gegc.quizmaker.exception.ResourceNotFoundException.class);
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("submitForReview: DRAFT → PENDING_REVIEW succeeds and clears review fields")
    void submitFromDraft_success() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setReviewedAt(Instant.now());
        quiz.setReviewedBy(new User());
        quiz.setRejectionReason("Because");

        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(quizRepository.saveAndFlush(any(Quiz.class))).thenAnswer(inv -> inv.getArgument(0));

        moderationService.submitForReview(quizId, userId);

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
        assertThat(quiz.getReviewedAt()).isNull();
        assertThat(quiz.getReviewedBy()).isNull();
        assertThat(quiz.getRejectionReason()).isNull();
        verify(quizRepository).saveAndFlush(quiz);
    }

    @Test
    @DisplayName("submitForReview: REJECTED → PENDING_REVIEW succeeds")
    void submitFromRejected_success() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.REJECTED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        moderationService.submitForReview(quizId, userId);

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
        verify(quizRepository).saveAndFlush(quiz);
    }

    @Test
    @DisplayName("submitForReview: PUBLISHED → PENDING_REVIEW succeeds (allowed by state machine)")
    void submitFromPublished_success() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.PUBLISHED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        moderationService.submitForReview(quizId, userId);

        assertThat(quiz.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
        verify(quizRepository).saveAndFlush(quiz);
    }

    @Test
    @DisplayName("submitForReview: ARCHIVED → PENDING_REVIEW is not allowed")
    void submitFromArchived_throwsValidation() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.ARCHIVED);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> moderationService.submitForReview(quizId, userId))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cannot transition to PENDING_REVIEW");
        verify(quizRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("submitForReview: Quiz not found")
    void submit_quizNotFound() {
        when(quizRepository.findById(quizId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> moderationService.submitForReview(quizId, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Quiz " + quizId + " not found");
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("submitForReview: User not found")
    void submit_userNotFound() {
        Quiz quiz = new Quiz();
        quiz.setId(quizId);
        quiz.setStatus(QuizStatus.DRAFT);
        when(quizRepository.findById(quizId)).thenReturn(Optional.of(quiz));
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.submitForReview(quizId, userId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User " + userId + " not found");
        verify(quizRepository, never()).save(any());
    }
}


