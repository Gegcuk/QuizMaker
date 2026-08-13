package uk.gegc.quizmaker.features.ai.application;

import uk.gegc.quizmaker.shared.exception.AiServiceException;

/**
 * Signals that bounded local provider execution cannot accept more work.
 */
public class AiProviderCapacityException extends AiServiceException {

    public static final String MESSAGE = "AI provider execution capacity is temporarily exhausted";

    public AiProviderCapacityException(Throwable cause) {
        super(MESSAGE, cause);
    }
}
