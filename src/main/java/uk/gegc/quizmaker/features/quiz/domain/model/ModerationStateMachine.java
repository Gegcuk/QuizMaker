package uk.gegc.quizmaker.features.quiz.domain.model;

import java.util.Set;

public enum ModerationStateMachine {
    DRAFT(Set.of(QuizStatus.PENDING_REVIEW)),
    PENDING_REVIEW(Set.of(QuizStatus.PUBLISHED, QuizStatus.REJECTED, QuizStatus.DRAFT)),
    PUBLISHED(Set.of(QuizStatus.PENDING_REVIEW, QuizStatus.DRAFT)),
    REJECTED(Set.of(QuizStatus.PENDING_REVIEW, QuizStatus.DRAFT)),
    ARCHIVED(Set.of());

    private final Set<QuizStatus> allowedTransitions;

    ModerationStateMachine(Set<QuizStatus> allowedTransitions) {
        this.allowedTransitions = allowedTransitions;
    }

    public boolean canTransitionTo(QuizStatus targetStatus) {
        if (targetStatus == null) {
            return false;
        }
        return allowedTransitions.contains(targetStatus);
    }

    public static boolean isValidTransition(QuizStatus from, QuizStatus to) {
        if (from == null || to == null) {
            return false;
        }
        try {
            ModerationStateMachine current = ModerationStateMachine.valueOf(from.name());
            return current.canTransitionTo(to);
        } catch (IllegalArgumentException ex) {
            // from state is not part of moderation state machine (e.g., ARCHIVED)
            return false;
        }
    }
}


