package uk.gegc.quizmaker.service.quiz.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.dto.quiz.PendingReviewQuizDto;
import uk.gegc.quizmaker.dto.quiz.QuizModerationAuditDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.mapper.QuizMapper;
import uk.gegc.quizmaker.model.quiz.ModerationAction;
import uk.gegc.quizmaker.model.quiz.ModerationStateMachine;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.QuizModerationAudit;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.quiz.QuizModerationAuditRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ModerationServiceImpl implements uk.gegc.quizmaker.service.quiz.ModerationService {

    private final QuizRepository quizRepository;
    private final UserRepository userRepository;
    private final QuizMapper quizMapper;
    private final QuizModerationAuditRepository auditRepository;

    @Override
    @Transactional
    public void submitForReview(UUID quizId, UUID userId) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + userId + " not found"));

        // Only DRAFT or REJECTED can move to PENDING_REVIEW
        if (!ModerationStateMachine.isValidTransition(quiz.getStatus(), QuizStatus.PENDING_REVIEW)) {
            throw new ValidationException("Quiz status " + quiz.getStatus() + " cannot transition to PENDING_REVIEW");
        }

        quiz.setStatus(QuizStatus.PENDING_REVIEW);
        quiz.setReviewedAt(null);
        quiz.setReviewedBy(null);
        quiz.setRejectionReason(null);

        // Create audit row (in-memory; persisted with cascade or explicit repo when added later)
        QuizModerationAudit audit = new QuizModerationAudit();
        audit.setQuiz(quiz);
        audit.setModerator(user);
        audit.setAction(ModerationAction.SUBMIT);
        audit.setReason("Submitted for review");
        audit.setCorrelationId(null);
        audit.setCreatedAt(Instant.now());

        quizRepository.save(quiz);
        // Persisting audit would require a repository; per scope, we only implement submitForReview method behavior on Quiz
    }

    @Override
    @Transactional
    public void approveQuiz(UUID quizId, UUID moderatorId, String reason) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User moderator = userRepository.findById(moderatorId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + moderatorId + " not found"));

        // Only PENDING_REVIEW can move to PUBLISHED
        if (quiz.getStatus() != QuizStatus.PENDING_REVIEW) {
            throw new ValidationException("Quiz status " + quiz.getStatus() + " cannot approve from non PENDING_REVIEW state");
        }

        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setReviewedAt(Instant.now());
        quiz.setReviewedBy(moderator);
        quiz.setRejectionReason(null);

        QuizModerationAudit audit = new QuizModerationAudit();
        audit.setQuiz(quiz);
        audit.setModerator(moderator);
        audit.setAction(ModerationAction.APPROVE);
        audit.setReason(reason);
        audit.setCorrelationId(null);
        audit.setCreatedAt(Instant.now());

        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void rejectQuiz(UUID quizId, UUID moderatorId, String reason) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User moderator = userRepository.findById(moderatorId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + moderatorId + " not found"));

        // Only PENDING_REVIEW can move to REJECTED
        if (quiz.getStatus() != QuizStatus.PENDING_REVIEW) {
            throw new ValidationException("Quiz status " + quiz.getStatus() + " cannot reject from non PENDING_REVIEW state");
        }

        quiz.setStatus(QuizStatus.REJECTED);
        quiz.setReviewedAt(Instant.now());
        quiz.setReviewedBy(moderator);
        quiz.setRejectionReason(reason);

        QuizModerationAudit audit = new QuizModerationAudit();
        audit.setQuiz(quiz);
        audit.setModerator(moderator);
        audit.setAction(ModerationAction.REJECT);
        audit.setReason(reason);
        audit.setCorrelationId(null);
        audit.setCreatedAt(Instant.now());

        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void unpublishQuiz(UUID quizId, UUID moderatorId, String reason) {
        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        User moderator = userRepository.findById(moderatorId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + moderatorId + " not found"));

        // Only PUBLISHED can move to DRAFT via unpublish per plan
        if (quiz.getStatus() != QuizStatus.PUBLISHED) {
            throw new ValidationException("Quiz status " + quiz.getStatus() + " cannot unpublish unless PUBLISHED");
        }

        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setReviewedAt(Instant.now());
        quiz.setReviewedBy(moderator);
        quiz.setRejectionReason(reason);

        QuizModerationAudit audit = new QuizModerationAudit();
        audit.setQuiz(quiz);
        audit.setModerator(moderator);
        audit.setAction(ModerationAction.UNPUBLISH);
        audit.setReason(reason);
        audit.setCorrelationId(null);
        audit.setCreatedAt(Instant.now());

        quizRepository.save(quiz);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PendingReviewQuizDto> getPendingReviewQuizzes(UUID orgId) {
        // No org scoping in entity yet; return all pending review mapped to DTOs
        return quizRepository.findAllByStatusOrderByCreatedAtDesc(QuizStatus.PENDING_REVIEW)
                .stream()
                .map(quizMapper::toPendingReviewDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<QuizModerationAuditDto> getQuizAuditTrail(UUID quizId) {
        // Fetch latest entries in descending order and map to DTOs
        return auditRepository.findAllByQuiz_IdOrderByCreatedAtDesc(quizId)
                .stream()
                .map(quizMapper::toAuditDto)
                .collect(Collectors.toList());
    }
}


