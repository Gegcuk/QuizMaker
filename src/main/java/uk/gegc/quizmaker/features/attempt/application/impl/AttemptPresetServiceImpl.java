package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.AttemptPresetService;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeMapping.*;

import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException.Reason.*;

@Service
public final class AttemptPresetServiceImpl implements AttemptPresetService {
    @Override
    public List<PresetReference> supportedPresets() { return List.of(REFERENCE); }

    @Override
    public BuiltInPracticePreset load(PresetReference reference) {
        if (reference == null) throw new PresetResolutionException(INVALID_REFERENCE);
        if (!REFERENCE.equals(reference)) throw new PresetResolutionException(UNSUPPORTED_PRESET);
        return V1;
    }

    @Override
    public LegacyPracticeMapping mapLegacy(LegacyPracticeRequest request) {
        if (request == null) throw new PresetResolutionException(INVALID_MAPPING_INPUT);
        if (request.mappingVersion() != MAPPING_VERSION) return new Unsupported(Unsupported.Reason.UNKNOWN_MAPPING_VERSION);
        if (!request.authenticated()) return new Unavailable(Unavailable.Reason.UNAUTHENTICATED);
        if (request.authority() == ResourceAuthority.NONE) return new Unavailable(Unavailable.Reason.NO_RESOURCE_AUTHORITY);
        if (!List.of("ALL_AT_ONCE", "ONE_BY_ONE", "TIMED").contains(request.legacyMode())) {
            return new Unsupported(Unsupported.Reason.UNKNOWN_LEGACY_MODE);
        }
        if (request.context() == LaunchContext.ASSIGNMENT) {
            return new LegacyOnly(LegacyOnly.Reason.ASSIGNMENT_REQUIRES_BOUND_POLICY);
        }

        PresetReference preset = REFERENCE;
        UUID policyVersionId = null;
        if (request.selectedPolicyVersionId() != null) {
            var selected = request.authorizedPolicies().stream()
                    .filter(p -> p.policyVersionId().equals(request.selectedPolicyVersionId())).findFirst();
            if (selected.isEmpty()) return new Unavailable(Unavailable.Reason.SELECTED_POLICY_NOT_AUTHORIZED);
            preset = selected.get().preset();
            policyVersionId = selected.get().policyVersionId();
        } else if (request.authority() != ResourceAuthority.OWNER
                && request.authority() != ResourceAuthority.FULL_CONTENT_EDITOR) {
            if (request.authorizedPolicies().isEmpty()) return new Unavailable(Unavailable.Reason.NO_AUTHORIZED_POLICY);
            if (request.authorizedPolicies().size() > 1) return new ChoiceRequired(request.authorizedPolicies());
            preset = request.authorizedPolicies().get(0).preset();
            policyVersionId = request.authorizedPolicies().get(0).policyVersionId();
        }

        // Mode names are confined to this compatibility boundary, never execution decisions.
        if (request.legacyMode().equals("ONE_BY_ONE")) return new LegacyOnly(LegacyOnly.Reason.ONE_BY_ONE_NOT_IMPLEMENTED);
        if (request.legacyMode().equals("TIMED")) {
            if (request.quizTimer() == QuizTimerState.UNKNOWN) return new Unavailable(Unavailable.Reason.UNKNOWN_TIMER_CONFIGURATION);
            if (request.quizTimer() == QuizTimerState.ENABLED) return new LegacyOnly(LegacyOnly.Reason.TIMING_NOT_IMPLEMENTED);
        }
        if (!REFERENCE.equals(preset)) return new Unsupported(Unsupported.Reason.UNSUPPORTED_PRESET);
        return new Mapped(preset, policyVersionId);
    }
}
