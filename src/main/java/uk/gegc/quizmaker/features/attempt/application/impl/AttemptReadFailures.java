package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

final class AttemptReadFailures {
    private AttemptReadFailures() { }

    static AttemptDeliveryException translate(RuntimeException failure) {
        if (failure instanceof AttemptDeliveryException delivery) return new AttemptDeliveryException(delivery.reason());
        if (failure instanceof AttemptAccessException denied) return new AttemptDeliveryException(switch (denied.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED;
            case DENIED -> NOT_FOUND;
            case IN_PROGRESS -> BUSY;
            case RETRYABLE, UNAVAILABLE -> UNAVAILABLE;
        });
        if (failure instanceof uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException subject)
            return new AttemptDeliveryException(switch (subject.reason()) {
                case UNAUTHENTICATED -> UNAUTHENTICATED;
                case UNAVAILABLE -> UNAVAILABLE;
                default -> NOT_FOUND;
            });
        if (failure instanceof uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException timed)
            return new AttemptDeliveryException(switch (timed.reason()) {
                case UNAVAILABLE -> UNAVAILABLE; case UNSUPPORTED -> UNSUPPORTED; case CLOSED -> INACTIVE;
                default -> CORRUPT;
            });
        if (failure instanceof PolicySnapshotException snapshot) return new AttemptDeliveryException(switch (snapshot.reason()) {
            case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
            case UNSUPPORTED_VERSION, UNSUPPORTED_HASH_ALGORITHM -> UNSUPPORTED;
            default -> CORRUPT;
        });
        if (failure instanceof ContractResolutionException contract) return new AttemptDeliveryException(
                contract.reason() == ContractResolutionException.Reason.PERSISTENCE_UNAVAILABLE ? UNAVAILABLE : UNSUPPORTED);
        if (failure instanceof FixedFormException form && form.reason() == FixedFormException.Reason.PERSISTENCE_UNAVAILABLE
                || failure instanceof OptionOrderException order && order.reason() == OptionOrderException.Reason.PERSISTENCE_UNAVAILABLE)
            return new AttemptDeliveryException(UNAVAILABLE);
        if (failure instanceof DataAccessException || failure instanceof TransactionException || failure instanceof DatabaseConflictException) {
            var conflict = DatabaseConflictException.from(failure);
            return new AttemptDeliveryException(conflict.isPresent()
                    && conflict.get().kind() == DatabaseConflictException.Kind.LOCK_TIMEOUT ? BUSY : UNAVAILABLE);
        }
        return new AttemptDeliveryException(CORRUPT);
    }
}
