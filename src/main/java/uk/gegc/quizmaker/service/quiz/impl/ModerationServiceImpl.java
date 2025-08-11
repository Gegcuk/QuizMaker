package uk.gegc.quizmaker.service.quiz.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.exception.ValidationException;
import uk.gegc.quizmaker.model.quiz.ModerationAction;
import uk.gegc.quizmaker.model.quiz.ModerationStateMachine;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.QuizModerationAudit;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.quiz.QuizRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ModerationServiceImpl implements uk.gegc.quizmaker.service.quiz.ModerationService {

    private final QuizRepository quizRepository;
    private final UserRepository userRepository;

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
}


