package uk.gegc.quizmaker.features.learning.domain.model;

import java.time.Instant;
import java.util.UUID;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

/**
 * Project-owned calculation evidence, not enrollment, a session, an access grant or a receipt store.
 * #649 owns durable reviews/CAS/replay and one active cycle; #575 owns session identity. Custom
 * schedulers may reuse Identity, but their state/configuration and calendar mapping are separate.
 */
public final class LearningSchedule {
    private LearningSchedule() { }

    public enum Rating { AGAIN, HARD, GOOD, EASY }
    public enum Phase { LEARNING, REVIEW, RELEARNING }

    /** Same item through another entry point retains this identity; a new revision/cycle does not. */
    public record Identity(UUID learnerId, UUID quizId, UUID quizRevisionId, UUID questionRevisionId, UUID cycleId) {
        public Identity {
            require(learnerId != null && quizId != null && quizRevisionId != null
                    && questionRevisionId != null && cycleId != null, INVALID_INPUT);
        }
        @Override public String toString() { return "LearningScheduleIdentity[redacted]"; }
    }

    public record State(int version, Identity identity, FsrsManifest manifest, long sequence, Phase phase,
                        Integer step, Double stability, Double difficulty, Instant dueAt, Instant lastReviewAt) {
        public State {
            require(version == 1, UNSUPPORTED_VERSION);
            require(identity != null && manifest != null && phase != null && dueAt != null && sequence >= 0,
                    INVALID_STATE);
            if (sequence == 0) {
                require(phase == Phase.LEARNING && Integer.valueOf(0).equals(step)
                        && stability == null && difficulty == null && lastReviewAt == null, INVALID_STATE);
            } else {
                require(lastReviewAt != null && dueAt.isAfter(lastReviewAt)
                        && stability != null && Double.isFinite(stability) && stability >= 0.001
                        && difficulty != null && Double.isFinite(difficulty) && difficulty >= 1 && difficulty <= 10,
                        INVALID_STATE);
                require(switch (phase) {
                    case LEARNING -> step != null && step >= 0 && step < manifest.learningStepsSeconds().size();
                    case RELEARNING -> Integer.valueOf(0).equals(step);
                    case REVIEW -> step == null;
                }, INVALID_STATE);
            }
        }
        @Override public String toString() { return "FsrsState[version=" + version + ",phase=" + phase + "]"; }
    }

    /** Time and identity are captured by the trusted caller, never inferred from a rating or JSON. */
    public record Review(UUID reviewId, Identity identity, long expectedSequence, Rating rating, Instant reviewedAt) {
        public Review {
            require(reviewId != null && identity != null && expectedSequence >= 0 && rating != null
                    && reviewedAt != null, INVALID_INPUT);
        }
        @Override public String toString() { return "LearningReview[rating=" + rating + "]"; }
    }

    public record Transition(Review review, State state) {
        public Transition {
            require(review != null && state != null && review.identity().equals(state.identity())
                    && review.expectedSequence() < Long.MAX_VALUE && state.sequence() == review.expectedSequence() + 1
                    && review.reviewedAt().equals(state.lastReviewAt()), INVALID_STATE);
        }
        @Override public String toString() { return "LearningTransition[redacted]"; }
    }
}
