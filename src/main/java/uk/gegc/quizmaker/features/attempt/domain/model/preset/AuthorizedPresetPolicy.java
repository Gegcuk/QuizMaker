package uk.gegc.quizmaker.features.attempt.domain.model.preset;

import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException.Reason.INVALID_MAPPING_INPUT;

/**
 * Trusted reference to an authorized, unmodified preset policy version for this subject/resource.
 * Overridden/managed policy definitions require #517/#665; they must not masquerade as this input.
 * A client-supplied policy identifier or role is not evidence of authorization.
 */
public record AuthorizedPresetPolicy(UUID policyVersionId, PresetReference preset) {
    public AuthorizedPresetPolicy {
        if (policyVersionId == null || preset == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
    }
}
