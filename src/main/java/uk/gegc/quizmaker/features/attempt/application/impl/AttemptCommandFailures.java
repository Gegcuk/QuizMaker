package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.SubjectResolutionException;
import uk.gegc.quizmaker.features.attempt.infra.receipt.ReceiptDatabaseFailure;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import static uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.*;

/** Shared bounded classification for canonical response/draft command collaborators. */
final class AttemptCommandFailures {
    private AttemptCommandFailures() { }
    static ResponseAcceptanceException translate(RuntimeException failure) {
        if (failure instanceof ResponseAcceptanceException known) return new ResponseAcceptanceException(known.reason());
        if (failure instanceof SubjectResolutionException identity) return new ResponseAcceptanceException(switch (identity.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED; case UNAVAILABLE -> UNAVAILABLE; default -> NOT_FOUND;
        });
        if (failure instanceof AttemptAccessException denied) return new ResponseAcceptanceException(switch (denied.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED; case DENIED -> NOT_FOUND; case IN_PROGRESS -> IN_PROGRESS;
            case RETRYABLE -> RETRYABLE; default -> UNAVAILABLE;
        });
        if (failure instanceof AttemptDeliveryException invalid) return new ResponseAcceptanceException(switch (invalid.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED; case NOT_FOUND -> NOT_FOUND; case INACTIVE -> INACTIVE;
            case BUSY -> IN_PROGRESS; case UNSUPPORTED -> UNSUPPORTED; case CORRUPT -> CORRUPT; case UNAVAILABLE -> UNAVAILABLE;
        });
        if (failure instanceof ReceiptException receipt) return new ResponseAcceptanceException(switch (receipt.reason()) {
            case CONFLICT -> CONFLICT; case IN_PROGRESS -> IN_PROGRESS; case RETRYABLE -> RETRYABLE;
            case EXPIRED -> EXPIRED; case UNKNOWN_OUTCOME -> UNKNOWN_OUTCOME; case ACCESS_DENIED -> NOT_FOUND;
            case INVALID_INPUT -> INVALID_INPUT; case UNSUPPORTED_VERSION -> UNSUPPORTED; default -> UNAVAILABLE;
        });
        if (failure instanceof QuestionContractException) return new ResponseAcceptanceException(INVALID_INPUT);
        if (failure instanceof FormMaterializationException material) return new ResponseAcceptanceException(switch (material.reason()) {
            case UNSUPPORTED_FAMILY, UNSUPPORTED_MEDIA -> UNSUPPORTED;
            case PERSISTENCE_UNAVAILABLE -> UNAVAILABLE;
            default -> CORRUPT;
        });
        if (failure instanceof ContractResolutionException) return new ResponseAcceptanceException(UNSUPPORTED);
        if (failure instanceof DataAccessException || failure instanceof TransactionException || failure instanceof DatabaseConflictException)
            return new ResponseAcceptanceException(switch (ReceiptDatabaseFailure.reason(failure)) {
                case RETRYABLE -> RETRYABLE; case IN_PROGRESS -> IN_PROGRESS; default -> UNAVAILABLE;
            });
        return new ResponseAcceptanceException(UNAVAILABLE);
    }
}
