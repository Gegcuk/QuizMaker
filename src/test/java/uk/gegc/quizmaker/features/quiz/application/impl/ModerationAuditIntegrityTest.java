package uk.gegc.quizmaker.features.quiz.application.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizModerationAuditRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for Moderation Audit Integrity
 * 
 * Validates that moderation actions correctly derive the moderator identity
 * from the authenticated user (via username) instead of accepting a forged
 * moderatorId from the request parameter.
 * 
 * This prevents security vulnerabilities where an attacker could submit
 * a different moderator ID to spoof their identity in audit logs.
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@Execution(ExecutionMode.CONCURRENT)
@DisplayName("Moderation Audit Integrity Tests")
class ModerationAuditIntegrityTest {

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

    @InjectMocks
    private ModerationServiceImpl moderationService;

    private User authenticatedModerator;
    private Quiz testQuiz;

    @BeforeEach
    void setUp() {
        // Given: Create a moderator and a quiz
        authenticatedModerator = createModerator("moderator1", "moderator1@example.com");
        testQuiz = createQuizPendingReview();
        
        setupUserRepositoryMock();
    }

    @Test
    @DisplayName("approveQuiz: derives moderator from username not request parameter")
    void approveQuiz_derivesModerator_fromUsername() {
        // Given
        when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
        when(quizRepository.saveAndFlush(any(Quiz.class))).thenReturn(testQuiz);
        
        ArgumentCaptor<QuizModerationAudit> auditCaptor = ArgumentCaptor.forClass(QuizModerationAudit.class);

        // When: Approve quiz using the authenticated moderator's username
        moderationService.approveQuiz(testQuiz.getId(), authenticatedModerator.getUsername(), "Looks good");

        // Then: The audit record should reference the correct moderator
        verify(auditRepository).save(auditCaptor.capture());
        QuizModerationAudit audit = auditCaptor.getValue();
        
        assertThat(audit.getModerator()).isNotNull();
        assertThat(audit.getModerator().getId()).isEqualTo(authenticatedModerator.getId());
        assertThat(audit.getModerator().getUsername()).isEqualTo(authenticatedModerator.getUsername());
        assertThat(audit.getAction()).isEqualTo(ModerationAction.APPROVE);
        assertThat(audit.getReason()).isEqualTo("Looks good");
    }

    @Test
    @DisplayName("rejectQuiz: derives moderator from username not request parameter")
    void rejectQuiz_derivesModerator_fromUsername() {
        // Given
        when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
        when(quizRepository.saveAndFlush(any(Quiz.class))).thenReturn(testQuiz);
        
        ArgumentCaptor<QuizModerationAudit> auditCaptor = ArgumentCaptor.forClass(QuizModerationAudit.class);

        // When: Reject quiz using the authenticated moderator's username
        moderationService.rejectQuiz(testQuiz.getId(), authenticatedModerator.getUsername(), "Inappropriate content");

        // Then: The audit record should reference the correct moderator
        verify(auditRepository).save(auditCaptor.capture());
        QuizModerationAudit audit = auditCaptor.getValue();
        
        assertThat(audit.getModerator()).isNotNull();
        assertThat(audit.getModerator().getId()).isEqualTo(authenticatedModerator.getId());
        assertThat(audit.getModerator().getUsername()).isEqualTo(authenticatedModerator.getUsername());
        assertThat(audit.getAction()).isEqualTo(ModerationAction.REJECT);
        assertThat(audit.getReason()).isEqualTo("Inappropriate content");
    }

    @Test
    @DisplayName("unpublishQuiz: derives moderator from username not request parameter")
    void unpublishQuiz_derivesModerator_fromUsername() {
        // Given
        testQuiz.setStatus(QuizStatus.PUBLISHED);
        when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
        when(quizRepository.saveAndFlush(any(Quiz.class))).thenReturn(testQuiz);
        
        ArgumentCaptor<QuizModerationAudit> auditCaptor = ArgumentCaptor.forClass(QuizModerationAudit.class);

        // When: Unpublish quiz using the authenticated moderator's username
        moderationService.unpublishQuiz(testQuiz.getId(), authenticatedModerator.getUsername(), "Policy violation");

        // Then: The audit record should reference the correct moderator
        verify(auditRepository).save(auditCaptor.capture());
        QuizModerationAudit audit = auditCaptor.getValue();
        
        assertThat(audit.getModerator()).isNotNull();
        assertThat(audit.getModerator().getId()).isEqualTo(authenticatedModerator.getId());
        assertThat(audit.getModerator().getUsername()).isEqualTo(authenticatedModerator.getUsername());
        assertThat(audit.getAction()).isEqualTo(ModerationAction.UNPUBLISH);
        assertThat(audit.getReason()).isEqualTo("Policy violation");
    }

    @Test
    @DisplayName("approveQuiz: supports lookup by email as well as username")
    void approveQuiz_supportsEmail_lookupFallback() {
        // Given
        when(quizRepository.findById(testQuiz.getId())).thenReturn(Optional.of(testQuiz));
        when(quizRepository.saveAndFlush(any(Quiz.class))).thenReturn(testQuiz);
        
        // When username is not found, should try email
        when(userRepository.findByUsername(authenticatedModerator.getEmail()))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail(authenticatedModerator.getEmail()))
                .thenReturn(Optional.of(authenticatedModerator));
        
        ArgumentCaptor<QuizModerationAudit> auditCaptor = ArgumentCaptor.forClass(QuizModerationAudit.class);

        // When: Approve using email as identifier
        moderationService.approveQuiz(testQuiz.getId(), authenticatedModerator.getEmail(), "Approved via email lookup");

        // Then: Should still correctly resolve the moderator
        verify(auditRepository).save(auditCaptor.capture());
        QuizModerationAudit audit = auditCaptor.getValue();
        
        assertThat(audit.getModerator()).isNotNull();
        assertThat(audit.getModerator().getId()).isEqualTo(authenticatedModerator.getId());
    }

    // =============== Helper Methods ===============

    private User createModerator(String username, String email) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setEmail(email);
        user.setActive(true);
        user.setDeleted(false);
        user.setEmailVerified(true);
        return user;
    }

    private Quiz createQuizPendingReview() {
        Quiz quiz = new Quiz();
        quiz.setId(UUID.randomUUID());
        quiz.setTitle("Test Quiz Pending Review");
        quiz.setDescription("Test Description");
        quiz.setStatus(QuizStatus.PENDING_REVIEW);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setLockedForReview(true);
        return quiz;
    }

    private void setupUserRepositoryMock() {
        when(userRepository.findByUsername(authenticatedModerator.getUsername()))
                .thenReturn(Optional.of(authenticatedModerator));
        when(userRepository.findByEmail(authenticatedModerator.getEmail()))
                .thenReturn(Optional.of(authenticatedModerator));
    }
}

