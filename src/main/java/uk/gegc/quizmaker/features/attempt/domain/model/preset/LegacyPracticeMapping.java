package uk.gegc.quizmaker.features.attempt.domain.model.preset;

import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException.Reason.INVALID_MAPPING_INPUT;

/** Classification only: no outcome authorizes routing, starts, clocks, allowances or disclosure. */
public sealed interface LegacyPracticeMapping {
    /** Resolver input; a null policyVersionId identifies the owner's/full-editor's built-in default. */
    record Mapped(PresetReference preset, UUID policyVersionId) implements LegacyPracticeMapping {
        public Mapped {
            if (preset == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
        }
    }

    record ChoiceRequired(List<AuthorizedPresetPolicy> alternatives) implements LegacyPracticeMapping {
        public ChoiceRequired {
            if (alternatives == null || alternatives.size() < 2 || alternatives.stream().anyMatch(p -> p == null)) {
                throw new PresetResolutionException(INVALID_MAPPING_INPUT);
            }
            alternatives = List.copyOf(alternatives);
        }
    }

    record Unavailable(Reason reason) implements LegacyPracticeMapping {
        public enum Reason { UNAUTHENTICATED, NO_RESOURCE_AUTHORITY, NO_AUTHORIZED_POLICY,
            SELECTED_POLICY_NOT_AUTHORIZED, UNKNOWN_TIMER_CONFIGURATION }
        public Unavailable {
            if (reason == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
        }
    }

    /** A future caller must separately validate retained-engine eligibility; this is not a fallback grant. */
    record LegacyOnly(Reason reason) implements LegacyPracticeMapping {
        public enum Reason { ASSIGNMENT_REQUIRES_BOUND_POLICY, ONE_BY_ONE_NOT_IMPLEMENTED, TIMING_NOT_IMPLEMENTED }
        public LegacyOnly {
            if (reason == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
        }
    }

    record Unsupported(Reason reason) implements LegacyPracticeMapping {
        public enum Reason { UNKNOWN_MAPPING_VERSION, UNKNOWN_LEGACY_MODE, UNSUPPORTED_PRESET }
        public Unsupported {
            if (reason == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
        }
    }
}
