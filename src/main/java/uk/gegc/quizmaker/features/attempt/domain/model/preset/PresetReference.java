package uk.gegc.quizmaker.features.attempt.domain.model.preset;

import static uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException.Reason.INVALID_REFERENCE;

/** Exact identifier/version, without aliases, case folding or a "latest" fallback. */
public record PresetReference(String id, int version) {
    public PresetReference {
        if (id == null || id.length() > 128 || id.isBlank() || version < 1) {
            throw new PresetResolutionException(INVALID_REFERENCE);
        }
    }
}
