package uk.gegc.quizmaker.features.quiz.application.generation;

import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.GenerationCoverageOutcome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable output of the authoritative generation coverage policy.
 */
public record GenerationCoverageSnapshot(
        GenerationCoverageOutcome outcome,
        int thresholdPercent,
        int requestedTotal,
        int acceptedTotal,
        int missingTotal,
        int discardedTotal,
        List<TypeCoverage> types
) {

    public GenerationCoverageSnapshot {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(types, "types must not be null");
        if (thresholdPercent < 0 || thresholdPercent > 100) {
            throw new IllegalArgumentException("thresholdPercent must be between 0 and 100");
        }
        if (requestedTotal <= 0) {
            throw new IllegalArgumentException("requestedTotal must be positive");
        }
        if (acceptedTotal < 0 || acceptedTotal > requestedTotal) {
            throw new IllegalArgumentException("acceptedTotal must be between 0 and requestedTotal");
        }
        if (missingTotal != requestedTotal - acceptedTotal) {
            throw new IllegalArgumentException("missingTotal must equal requestedTotal minus acceptedTotal");
        }
        if (discardedTotal < 0) {
            throw new IllegalArgumentException("discardedTotal must not be negative");
        }
        if (types.isEmpty()) {
            throw new IllegalArgumentException("types must contain every requested question type");
        }

        List<TypeCoverage> orderedTypes = new ArrayList<>(types);
        orderedTypes.sort(Comparator.comparingInt(type -> type.questionType().ordinal()));
        Set<QuestionType> uniqueTypes = new HashSet<>();
        int typeRequestedTotal = 0;
        int typeAcceptedTotal = 0;
        int typeMissingTotal = 0;
        for (TypeCoverage type : orderedTypes) {
            Objects.requireNonNull(type, "type coverage must not be null");
            if (!uniqueTypes.add(type.questionType())) {
                throw new IllegalArgumentException("question types must be unique");
            }
            typeRequestedTotal = Math.addExact(typeRequestedTotal, type.requested());
            typeAcceptedTotal = Math.addExact(typeAcceptedTotal, type.accepted());
            typeMissingTotal = Math.addExact(typeMissingTotal, type.missing());
        }
        if (typeRequestedTotal != requestedTotal
                || typeAcceptedTotal != acceptedTotal
                || typeMissingTotal != missingTotal) {
            throw new IllegalArgumentException("aggregate coverage must equal the per-type totals");
        }
        if (outcome == GenerationCoverageOutcome.COMPLETE && missingTotal != 0) {
            throw new IllegalArgumentException("COMPLETE coverage must not have missing questions");
        }
        if (outcome != GenerationCoverageOutcome.COMPLETE && missingTotal == 0) {
            throw new IllegalArgumentException("non-COMPLETE coverage must have missing questions");
        }
        types = List.copyOf(orderedTypes);
    }

    public record TypeCoverage(
            QuestionType questionType,
            int requested,
            int accepted,
            int missing
    ) {
        public TypeCoverage {
            Objects.requireNonNull(questionType, "questionType must not be null");
            if (requested <= 0) {
                throw new IllegalArgumentException("requested must be positive");
            }
            if (accepted < 0 || accepted > requested) {
                throw new IllegalArgumentException("accepted must be between 0 and requested");
            }
            if (missing != requested - accepted) {
                throw new IllegalArgumentException("missing must equal requested minus accepted");
            }
        }
    }
}
