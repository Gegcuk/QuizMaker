package uk.gegc.quizmaker.features.attempt.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;

/**
 * Trusted internal terminal writer, never a participant authorization boundary. Caller must
 * authorize the subject/scope, hold the root lock, validate its command and own a receipt transaction.
 * Completion scopes come from the real server writer, never HTTP. Future timing/section producers
 * must prove their effective cause/deadline and composition; this interface enables none of them.
 */
public interface AttemptTerminalSubmission {
    CommandReceipt.Acknowledgement finish(AttemptReadFactsService.Facts facts, long expectedVersion,
                                         UUID commandId, CompletionFacts completion);
}
