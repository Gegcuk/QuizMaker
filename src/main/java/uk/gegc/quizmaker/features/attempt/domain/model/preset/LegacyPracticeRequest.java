package uk.gegc.quizmaker.features.attempt.domain.model.preset;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException.Reason.*;

/**
 * Pure mapping of a NEW launch, never a request DTO or a way to reinterpret an existing attempt.
 * Authority is resolved server-side for the exact subject/quiz, including organization/membership,
 * visibility and full question-and-answer editing scope. Missing/ambiguous authority is NONE.
 * Legacy mode is its exact symbolic name; parsing an unknown value cannot grant a fallback.
 * Timer state is explicit; an inactive stored duration is deliberately not a preset input.
 */
public record LegacyPracticeRequest(
        int mappingVersion, String legacyMode, LaunchContext context,
        boolean authenticated, ResourceAuthority authority, QuizTimerState quizTimer,
        List<AuthorizedPresetPolicy> authorizedPolicies, UUID selectedPolicyVersionId
) {
    public static final int MAPPING_VERSION = 1;

    public enum LaunchContext { ORDINARY, ASSIGNMENT }
    public enum ResourceAuthority { OWNER, FULL_CONTENT_EDITOR, TAKE_ONLY, NONE }
    public enum QuizTimerState { DISABLED, ENABLED, UNKNOWN }

    public LegacyPracticeRequest {
        if (legacyMode == null || context == null || authority == null || quizTimer == null
                || authorizedPolicies == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
        var ids = new HashSet<UUID>();
        for (var policy : authorizedPolicies) {
            if (policy == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
            if (!ids.add(policy.policyVersionId())) throw new PresetResolutionException(DUPLICATE_POLICY_REFERENCE);
        }
        authorizedPolicies = List.copyOf(authorizedPolicies);
    }
}
