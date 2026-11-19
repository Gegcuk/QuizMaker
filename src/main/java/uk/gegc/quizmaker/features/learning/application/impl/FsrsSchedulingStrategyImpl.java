package uk.gegc.quizmaker.features.learning.application.impl;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Scheduler;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.learning.application.LearningSchedulingStrategy;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.require;

@Service
public final class FsrsSchedulingStrategyImpl implements LearningSchedulingStrategy {
    @Override
    public State initial(Identity identity, FsrsManifest manifest, Instant startedAt) {
        require(identity != null && manifest != null && startedAt != null, INVALID_INPUT);
        return new State(1, identity, manifest, 0, Phase.LEARNING, 0, null, null, startedAt, null);
    }

    @Override
    public Transition review(State current, Review review) {
        require(current != null && review != null, INVALID_INPUT);
        require(current.identity().equals(review.identity()), WRONG_SCHEDULE);
        require(current.sequence() == review.expectedSequence() && current.sequence() < Long.MAX_VALUE, STALE_STATE);
        var previous = current.lastReviewAt() == null ? current.dueAt() : current.lastReviewAt();
        require(!review.reviewedAt().isBefore(previous)
                && ChronoUnit.DAYS.between(previous, review.reviewedAt()) <= Integer.MAX_VALUE, INVALID_TIME);
        try {
            // Projection-local constant: upstream never sees or generates the authoritative UUID identity.
            var card = Card.builder().cardId(0)
                    .state(io.github.openspacedrepetition.State.valueOf(current.phase().name()))
                    .step(current.step()).stability(current.stability()).difficulty(current.difficulty())
                    .due(current.dueAt()).lastReview(current.lastReviewAt()).build();
            var manifest = current.manifest();
            // Fresh arrays and scheduler per calculation: upstream exposes mutable getters/copy constructors.
            var scheduler = Scheduler.builder()
                    .parameters(manifest.parameters().stream().mapToDouble(Double::doubleValue).toArray())
                    .desiredRetention(manifest.desiredRetention())
                    .learningSteps(manifest.learningStepsSeconds().stream().map(Duration::ofSeconds).toArray(Duration[]::new))
                    .relearningSteps(manifest.relearningStepsSeconds().stream().map(Duration::ofSeconds).toArray(Duration[]::new))
                    .maximumInterval(manifest.maximumIntervalDays()).enableFuzzing(manifest.fuzz())
                    .randomSeedNumber(42).build(); // Explicit but unused with fuzz disabled.
            var result = scheduler.reviewCard(card,
                    io.github.openspacedrepetition.Rating.valueOf(review.rating().name()), review.reviewedAt(), null).card();
            if (result.getState() == io.github.openspacedrepetition.State.REVIEW) {
                // Upstream narrows this rounded long to int before its ceiling. Reject numeric overflow
                // rather than accepting a wrapped one-day interval or silently replacing the engine.
                long rawDays = Math.round((result.getStability() / scheduler.getFACTOR())
                        * (Math.pow(manifest.desiredRetention(), 1 / scheduler.getDECAY()) - 1));
                require(rawDays <= Integer.MAX_VALUE, CALCULATION_FAILED);
            }
            var next = new State(1, current.identity(), manifest, current.sequence() + 1,
                    Phase.valueOf(result.getState().name()), result.getStep(), result.getStability(), result.getDifficulty(),
                    result.getDue(), result.getLastReview());
            return new Transition(review, next);
        } catch (RuntimeException failure) {
            // Nothing from an external library or malformed calculation escapes into transaction logs.
            throw new LearningScheduleException(CALCULATION_FAILED);
        }
    }
}
