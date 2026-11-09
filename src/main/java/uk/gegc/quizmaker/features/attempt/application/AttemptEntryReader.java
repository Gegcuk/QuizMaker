package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility;

/** Current versioned entry facts, read under the caller's transaction after authorization. */
public interface AttemptEntryReader {
    record Entry(int version, RetakeEligibility.Entry state) { }
    Entry read(RetakeEligibility.Scope scope);
}
