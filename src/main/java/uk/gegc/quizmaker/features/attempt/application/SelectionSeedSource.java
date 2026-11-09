package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.SeedMaterial;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.SeedRequest;

/** Server-owned selection-seed/v1 boundary. No caller-supplied seed or key is accepted. */
public interface SelectionSeedSource {
    SeedMaterial derive(SeedRequest request);
}
