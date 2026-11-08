package uk.gegc.quizmaker.features.result.application.impl;

import uk.gegc.quizmaker.features.attempt.application.SubmittedAttemptSource;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.TraceException;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

final class ResultReleaseFailures {
    private ResultReleaseFailures() { }
    static ResultReleaseException safe(RuntimeException failure) {
        if (failure instanceof ResultReleaseException known) return new ResultReleaseException(known.reason());
        if (failure instanceof SubmittedAttemptSource.Failure known)
            return new ResultReleaseException(ResultReleaseException.Reason.valueOf(known.reason().name()));
        if (failure instanceof AutomaticResultException known)
            return new ResultReleaseException(ResultReleaseException.Reason.valueOf(known.reason().name()));
        if (failure instanceof uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException snapshot)
            return new ResultReleaseException(switch (snapshot.reason()) {
                case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
                case UNSUPPORTED_VERSION, UNSUPPORTED_HASH_ALGORITHM -> UNSUPPORTED;
                default -> CORRUPT;
            });
        if (failure instanceof uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException contract)
            return new ResultReleaseException(switch (contract.reason()) {
                case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
                case UNSUPPORTED_CONTRACT -> UNSUPPORTED;
                default -> CORRUPT;
            });
        var conflict = DatabaseConflictException.from(failure);
        if (conflict.isPresent()) return new ResultReleaseException(
                conflict.get().kind() == DatabaseConflictException.Kind.DEADLOCK ? RETRYABLE : BUSY);
        if (failure instanceof TraceException known) return new ResultReleaseException(
                known.reason() == TraceException.Reason.PERSISTENCE_UNAVAILABLE ? UNAVAILABLE : CORRUPT);
        if (failure instanceof IllegalArgumentException
                || failure instanceof uk.gegc.quizmaker.features.question.application.QuestionContractException)
            return new ResultReleaseException(CORRUPT);
        return new ResultReleaseException(UNAVAILABLE);
    }
}
