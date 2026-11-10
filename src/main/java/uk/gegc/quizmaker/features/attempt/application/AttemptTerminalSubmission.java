package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

/**
 * Trusted internal terminal writer, never a participant authorization boundary. Caller must
 * authorize the subject/scope, hold the root lock, validate its command and own a receipt transaction.
 * Completion scopes come from the real server writer, never HTTP. System expiry additionally
 * proves captured subject/scope bindings and the overall deadline through #702. Section and
 * editable producers retain their own integration gates; this interface activates neither.
 */
public interface AttemptTerminalSubmission {
    CommandReceipt.Acknowledgement finish(AttemptReadFactsService.Facts facts, long expectedVersion,
                                         UUID commandId, CompletionFacts completion);

    /** Caller owns the system receipt and proves the saved overall deadline under the root lock. */
    CommandReceipt.Acknowledgement expire(AttemptReadFactsService.Facts facts, long expectedVersion,
            UUID commandId, CompletionFacts completion, java.time.Instant deadline);
}
