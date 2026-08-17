package uk.gegc.quizmaker.features.ai.application;

import uk.gegc.quizmaker.shared.exception.AiServiceException;

/**
 * Signals that a logical generation task has no provider dispatch permits left.
 */
public class ProviderAttemptBudgetExhaustedException extends AiServiceException {

    public static final String MESSAGE = "AI provider attempt budget is exhausted";

    public ProviderAttemptBudgetExhaustedException() {
        super(MESSAGE);
    }
}
