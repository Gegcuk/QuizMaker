package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.Input;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.Result;

/**
 * Pure internal selection for already authorized server callers. It neither resolves eligibility
 * nor grants access, creates forms/attempts, reads storage, or activates a policy capability.
 * Empty selection is a valid result; start eligibility belongs to the consuming use case.
 * Random strategies require a server-owned operation context and configured SelectionSeedSource;
 * this internal input must never be bound directly from a client request.
 */
public interface QuestionSelectionService {
    Result select(Input input);
}
