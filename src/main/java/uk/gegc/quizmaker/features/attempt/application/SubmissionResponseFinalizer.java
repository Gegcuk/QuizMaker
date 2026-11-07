package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;

/**
 * Server-owned preparation/conversion boundary. Both operations join the terminal transaction.
 * #834 supplies real editable conversion before that capability can be activated. Preparation
 * alone never proves writes; proposals and arbitrary legacy drafts are never accepted implicitly.
 */
public interface SubmissionResponseFinalizer {
    CompletionFacts prepare(AttemptReadFactsService.Facts facts, boolean confirmed);
    void finalizeResponses(AttemptReadFactsService.Facts facts, CompletionFacts.Plan plan);
}
