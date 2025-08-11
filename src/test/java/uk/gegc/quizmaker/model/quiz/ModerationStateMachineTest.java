package uk.gegc.quizmaker.model.quiz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModerationStateMachineTest {

    @Test
    @DisplayName("DRAFT → PENDING_REVIEW is allowed; other transitions from DRAFT are blocked")
    void draftTransitions() {
        assertThat(ModerationStateMachine.DRAFT.canTransitionTo(QuizStatus.PENDING_REVIEW)).isTrue();
        assertThat(ModerationStateMachine.DRAFT.canTransitionTo(QuizStatus.DRAFT)).isFalse();
        assertThat(ModerationStateMachine.DRAFT.canTransitionTo(QuizStatus.PUBLISHED)).isFalse();
        assertThat(ModerationStateMachine.DRAFT.canTransitionTo(QuizStatus.REJECTED)).isFalse();
        assertThat(ModerationStateMachine.isValidTransition(QuizStatus.DRAFT, QuizStatus.PENDING_REVIEW)).isTrue();
        assertThat(ModerationStateMachine.isValidTransition(QuizStatus.DRAFT, QuizStatus.PUBLISHED)).isFalse();
    }

    @Test
    @DisplayName("PENDING_REVIEW → PUBLISHED/REJECTED/DRAFT are allowed; others blocked")
    void pendingReviewTransitions() {
        assertThat(ModerationStateMachine.PENDING_REVIEW.canTransitionTo(QuizStatus.PUBLISHED)).isTrue();
        assertThat(ModerationStateMachine.PENDING_REVIEW.canTransitionTo(QuizStatus.REJECTED)).isTrue();
        assertThat(ModerationStateMachine.PENDING_REVIEW.canTransitionTo(QuizStatus.DRAFT)).isTrue();
        assertThat(ModerationStateMachine.PENDING_REVIEW.canTransitionTo(QuizStatus.PENDING_REVIEW)).isFalse();
    }

    @Test
    @DisplayName("PUBLISHED → PENDING_REVIEW/DRAFT are allowed; others blocked")
    void publishedTransitions() {
        assertThat(ModerationStateMachine.PUBLISHED.canTransitionTo(QuizStatus.PENDING_REVIEW)).isTrue();
        assertThat(ModerationStateMachine.PUBLISHED.canTransitionTo(QuizStatus.DRAFT)).isTrue();
        assertThat(ModerationStateMachine.PUBLISHED.canTransitionTo(QuizStatus.PUBLISHED)).isFalse();
        assertThat(ModerationStateMachine.PUBLISHED.canTransitionTo(QuizStatus.REJECTED)).isFalse();
    }

    @Test
    @DisplayName("REJECTED → PENDING_REVIEW/DRAFT are allowed; others blocked")
    void rejectedTransitions() {
        assertThat(ModerationStateMachine.REJECTED.canTransitionTo(QuizStatus.PENDING_REVIEW)).isTrue();
        assertThat(ModerationStateMachine.REJECTED.canTransitionTo(QuizStatus.DRAFT)).isTrue();
        assertThat(ModerationStateMachine.REJECTED.canTransitionTo(QuizStatus.REJECTED)).isFalse();
        assertThat(ModerationStateMachine.REJECTED.canTransitionTo(QuizStatus.PUBLISHED)).isFalse();
    }

    @Test
    @DisplayName("Invalid inputs and out-of-scope statuses return false")
    void invalidInputs() {
        assertThat(ModerationStateMachine.isValidTransition(null, QuizStatus.DRAFT)).isFalse();
        assertThat(ModerationStateMachine.isValidTransition(QuizStatus.DRAFT, null)).isFalse();
        // ARCHIVED is outside the moderation state machine
        assertThat(ModerationStateMachine.isValidTransition(QuizStatus.ARCHIVED, QuizStatus.PUBLISHED)).isFalse();
        assertThat(ModerationStateMachine.isValidTransition(QuizStatus.PUBLISHED, QuizStatus.ARCHIVED)).isFalse();
    }
}


