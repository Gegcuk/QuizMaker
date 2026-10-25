package uk.gegc.quizmaker.features.attempt.preset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.application.AttemptPresetService;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeMapping.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.preset.BuiltInPracticePresetTest.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.preset.PresetResolutionException.Reason.*;

class LegacyPracticeMappingTest {
    private final AttemptPresetService presets = new AttemptPresetServiceImpl();
    private static final PresetReference V1 = new PresetReference("FREE_PRACTICE", 1);
    private static final AuthorizedPresetPolicy FIRST = new AuthorizedPresetPolicy(new UUID(0, 1), V1);
    private static final AuthorizedPresetPolicy SECOND = new AuthorizedPresetPolicy(new UUID(0, 2), V1);

    @ParameterizedTest
    @EnumSource(value = ResourceAuthority.class, names = {"OWNER", "FULL_CONTENT_EDITOR"})
    void fullRightsOrdinaryDefaultNeedsNoSeparateGrantAndDoesNotBecomeAmbiguous(ResourceAuthority authority) {
        for (var candidates : List.of(List.<AuthorizedPresetPolicy>of(), List.of(FIRST), List.of(FIRST, SECOND))) {
            assertThat(presets.mapLegacy(request("ALL_AT_ONCE", authority, QuizTimerState.DISABLED, candidates, null)))
                    .isEqualTo(new Mapped(V1, null));
        }
    }

    @Test
    void takeOnlyNeedsExactlyOneAuthorizedPolicyUnlessAnExplicitSelectionResolvesAmbiguity() {
        assertThat(presets.mapLegacy(takeOnly(List.of(), null)))
                .isEqualTo(new Unavailable(Unavailable.Reason.NO_AUTHORIZED_POLICY));
        assertThat(presets.mapLegacy(takeOnly(List.of(FIRST), null))).isEqualTo(new Mapped(V1, FIRST.policyVersionId()));
        assertThat(presets.mapLegacy(takeOnly(List.of(FIRST, SECOND), null)))
                .isEqualTo(new ChoiceRequired(List.of(FIRST, SECOND)));
        assertThat(presets.mapLegacy(takeOnly(List.of(FIRST, SECOND), SECOND.policyVersionId())))
                .isEqualTo(new Mapped(V1, SECOND.policyVersionId()));
    }

    @ParameterizedTest
    @EnumSource(ResourceAuthority.class)
    void missingIdentityCannotGrantEvenOwnerDefaults(ResourceAuthority authority) {
        assertThat(presets.mapLegacy(new LegacyPracticeRequest(1, "ALL_AT_ONCE", LaunchContext.ORDINARY,
                false, authority, QuizTimerState.DISABLED, List.of(FIRST), null)))
                .isEqualTo(new Unavailable(Unavailable.Reason.UNAUTHENTICATED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL_AT_ONCE", "ONE_BY_ONE", "TIMED"})
    void missingOrAmbiguousResourceScopeNeverElevatesThroughModeOrSelectedPolicy(String mode) {
        // Real auth adapters must normalize wrong organization/resource, partial/metadata-only rights
        // and role names without effective resource authority to NONE (or TAKE_ONLY, if established).
        assertThat(presets.mapLegacy(request(mode, ResourceAuthority.NONE, QuizTimerState.DISABLED,
                List.of(FIRST), FIRST.policyVersionId())))
                .isEqualTo(new Unavailable(Unavailable.Reason.NO_RESOURCE_AUTHORITY));
    }

    @ParameterizedTest
    @EnumSource(ResourceAuthority.class)
    void staleOrForgedChoiceNeverBecomesTheFullRightsDefault(ResourceAuthority authority) {
        var expected = authority == ResourceAuthority.NONE
                ? Unavailable.Reason.NO_RESOURCE_AUTHORITY : Unavailable.Reason.SELECTED_POLICY_NOT_AUTHORIZED;
        assertThat(presets.mapLegacy(request("ALL_AT_ONCE", authority, QuizTimerState.DISABLED,
                List.of(FIRST), SECOND.policyVersionId()))).isEqualTo(new Unavailable(expected));
    }

    @ParameterizedTest
    @EnumSource(value = ResourceAuthority.class, names = {"OWNER", "FULL_CONTENT_EDITOR", "TAKE_ONLY"})
    void assignmentAlwaysDefersToItsBoundPolicyEvenForFullEditors(ResourceAuthority authority) {
        assertThat(presets.mapLegacy(new LegacyPracticeRequest(1, "TIMED", LaunchContext.ASSIGNMENT, true,
                authority, QuizTimerState.DISABLED, List.of(FIRST), FIRST.policyVersionId())))
                .isEqualTo(new LegacyOnly(LegacyOnly.Reason.ASSIGNMENT_REQUIRES_BOUND_POLICY));
        var unknown = new AuthorizedPresetPolicy(FIRST.policyVersionId(), new PresetReference("FREE_PRACTICE", 2));
        for (var mode : List.of("ONE_BY_ONE", "TIMED")) {
            assertThat(presets.mapLegacy(new LegacyPracticeRequest(1, mode, LaunchContext.ASSIGNMENT, true,
                    authority, QuizTimerState.ENABLED, List.of(unknown), unknown.policyVersionId())))
                    .isEqualTo(new LegacyOnly(LegacyOnly.Reason.ASSIGNMENT_REQUIRES_BOUND_POLICY));
        }
    }

    @Test
    void disabledLegacyTimerHasZeroOneAndMultipleAuthorizedUntimedOutcomes() {
        assertThat(presets.mapLegacy(request("TIMED", ResourceAuthority.TAKE_ONLY, QuizTimerState.DISABLED, List.of(), null)))
                .isEqualTo(new Unavailable(Unavailable.Reason.NO_AUTHORIZED_POLICY));
        assertThat(presets.mapLegacy(request("TIMED", ResourceAuthority.TAKE_ONLY, QuizTimerState.DISABLED, List.of(FIRST), null)))
                .isEqualTo(new Mapped(V1, FIRST.policyVersionId()));
        assertThat(presets.mapLegacy(request("TIMED", ResourceAuthority.TAKE_ONLY, QuizTimerState.DISABLED,
                List.of(FIRST, SECOND), null))).isEqualTo(new ChoiceRequired(List.of(FIRST, SECOND)));
        assertThat(presets.mapLegacy(request("TIMED", ResourceAuthority.TAKE_ONLY, QuizTimerState.DISABLED,
                List.of(FIRST, SECOND), FIRST.policyVersionId()))).isEqualTo(new Mapped(V1, FIRST.policyVersionId()));
    }

    @ParameterizedTest
    @EnumSource(value = ResourceAuthority.class, names = {"OWNER", "FULL_CONTENT_EDITOR", "TAKE_ONLY"})
    void fullRightsCannotDisableRequiredTimingOrSilentlyChangeDelivery(ResourceAuthority authority) {
        assertThat(presets.mapLegacy(request("TIMED", authority, QuizTimerState.ENABLED, List.of(FIRST), null)))
                .isEqualTo(new LegacyOnly(LegacyOnly.Reason.TIMING_NOT_IMPLEMENTED));
        assertThat(presets.mapLegacy(request("ONE_BY_ONE", authority, QuizTimerState.DISABLED, List.of(FIRST), null)))
                .isEqualTo(new LegacyOnly(LegacyOnly.Reason.ONE_BY_ONE_NOT_IMPLEMENTED));
        assertThat(presets.mapLegacy(request("TIMED", authority, QuizTimerState.UNKNOWN, List.of(FIRST), null)))
                .isEqualTo(new Unavailable(Unavailable.Reason.UNKNOWN_TIMER_CONFIGURATION));
        assertThat(presets.mapLegacy(request("TIMED", authority, QuizTimerState.DISABLED, List.of(FIRST), null)))
                .isInstanceOf(Mapped.class);
    }

    @ParameterizedTest
    @EnumSource(QuizTimerState.class)
    void quizTimerDoesNotMakeAllAtOncePracticeTimed(QuizTimerState timer) {
        assertThat(presets.mapLegacy(request("ALL_AT_ONCE", ResourceAuthority.TAKE_ONLY, timer, List.of(FIRST), null)))
                .isEqualTo(new Mapped(V1, FIRST.policyVersionId()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "all_at_once", "ALL_AT_ONCE ", "ADAPTIVE", "java.lang.Runtime"})
    void unknownModesAreExplicitlyUnsupported(String mode) {
        assertThat(presets.mapLegacy(request(mode, ResourceAuthority.OWNER, QuizTimerState.DISABLED, List.of(), null)))
                .isEqualTo(new Unsupported(Unsupported.Reason.UNKNOWN_LEGACY_MODE));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownMappingVersionsNeverRunCurrentRules(int version) {
        assertThat(presets.mapLegacy(new LegacyPracticeRequest(version, "ALL_AT_ONCE", LaunchContext.ORDINARY,
                true, ResourceAuthority.OWNER, QuizTimerState.DISABLED, List.of(), null)))
                .isEqualTo(new Unsupported(Unsupported.Reason.UNKNOWN_MAPPING_VERSION));
    }

    static Stream<Arguments> ordinaryModes() {
        return Stream.of(
                Arguments.of("ALL_AT_ONCE", QuizTimerState.DISABLED, new Mapped(V1, FIRST.policyVersionId())),
                Arguments.of("ONE_BY_ONE", QuizTimerState.DISABLED,
                        new LegacyOnly(LegacyOnly.Reason.ONE_BY_ONE_NOT_IMPLEMENTED)),
                Arguments.of("TIMED", QuizTimerState.DISABLED, new Mapped(V1, FIRST.policyVersionId())),
                Arguments.of("TIMED", QuizTimerState.ENABLED, new LegacyOnly(LegacyOnly.Reason.TIMING_NOT_IMPLEMENTED)),
                Arguments.of("TIMED", QuizTimerState.UNKNOWN, new Unavailable(Unavailable.Reason.UNKNOWN_TIMER_CONFIGURATION)));
    }

    @ParameterizedTest(name = "mode={0}, timer={1}")
    @MethodSource("ordinaryModes")
    void unsupportedAuthorizedPresetTakesPrecedenceOverOrdinaryModeLimitations(
            String mode, QuizTimerState timer, LegacyPracticeMapping supportedOutcome) {
        for (var unsupported : List.of(new PresetReference("FREE_PRACTICE", 2), new PresetReference("FORMAL", 1))) {
            var candidate = new AuthorizedPresetPolicy(FIRST.policyVersionId(), unsupported);
            for (var authority : List.of(ResourceAuthority.OWNER, ResourceAuthority.FULL_CONTENT_EDITOR, ResourceAuthority.TAKE_ONLY)) {
                // A supported v1 control reaches the same authorized selection and mode boundary.
                assertThat(presets.mapLegacy(request(mode, authority, timer, List.of(FIRST), FIRST.policyVersionId())))
                        .isEqualTo(supportedOutcome);
                assertThat(presets.mapLegacy(request(mode, authority, timer, List.of(candidate), candidate.policyVersionId())))
                        .isEqualTo(new Unsupported(Unsupported.Reason.UNSUPPORTED_PRESET));
            }
            assertThat(presets.mapLegacy(request(mode, ResourceAuthority.TAKE_ONLY, timer, List.of(candidate), null)))
                    .isEqualTo(new Unsupported(Unsupported.Reason.UNSUPPORTED_PRESET));
        }
    }

    @Test
    void ambiguityIsNotHiddenByDroppingUnsupportedAlternativesOrReorderingCandidates() {
        var future = new AuthorizedPresetPolicy(SECOND.policyVersionId(), new PresetReference("FORMAL", 1));
        for (var candidates : List.of(List.of(FIRST, future), List.of(future, FIRST))) {
            assertThat(presets.mapLegacy(takeOnly(candidates, null))).isEqualTo(new ChoiceRequired(candidates));
            assertThat(presets.mapLegacy(takeOnly(candidates, FIRST.policyVersionId())))
                    .isEqualTo(new Mapped(V1, FIRST.policyVersionId()));
            assertThat(presets.mapLegacy(takeOnly(candidates, future.policyVersionId())))
                    .isEqualTo(new Unsupported(Unsupported.Reason.UNSUPPORTED_PRESET));
        }
    }

    @Test
    void capturedCandidatesAndOutcomeCannotBeChangedByLaterMutableConfiguration() {
        var candidates = new ArrayList<>(List.of(FIRST, SECOND));
        var captured = takeOnly(candidates, null);
        candidates.clear();
        var outcome = (ChoiceRequired) presets.mapLegacy(captured);
        assertThat(outcome.alternatives()).containsExactly(FIRST, SECOND);
        assertThatThrownBy(() -> captured.authorizedPolicies().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> outcome.alternatives().clear()).isInstanceOf(UnsupportedOperationException.class);
        var changed = takeOnly(List.of(SECOND), null);
        assertThat(presets.mapLegacy(changed)).isEqualTo(new Mapped(V1, SECOND.policyVersionId()));
        assertThat(presets.mapLegacy(captured)).isEqualTo(outcome);
        // This is captured input immutability, not proof of live authorization; #833 must revalidate.
    }

    @Test
    void invalidInputsAndDuplicateIdentitiesFailWithoutPickingAListWinner() {
        rejected(() -> presets.mapLegacy(null), INVALID_MAPPING_INPUT);
        rejected(() -> new AuthorizedPresetPolicy(null, V1), INVALID_MAPPING_INPUT);
        rejected(() -> new AuthorizedPresetPolicy(FIRST.policyVersionId(), null), INVALID_MAPPING_INPUT);
        rejected(() -> takeOnly(null, null), INVALID_MAPPING_INPUT);
        rejected(() -> takeOnly(Arrays.asList(FIRST, null), null), INVALID_MAPPING_INPUT);
        rejected(() -> takeOnly(List.of(FIRST, FIRST), null), DUPLICATE_POLICY_REFERENCE);
        rejected(() -> takeOnly(List.of(FIRST, new AuthorizedPresetPolicy(FIRST.policyVersionId(),
                new PresetReference("FREE_PRACTICE", 2))), null), DUPLICATE_POLICY_REFERENCE);
        rejected(() -> request(null, ResourceAuthority.OWNER, QuizTimerState.DISABLED, List.of(), null), INVALID_MAPPING_INPUT);
        rejected(() -> request("ALL_AT_ONCE", null, QuizTimerState.DISABLED, List.of(), null), INVALID_MAPPING_INPUT);
        rejected(() -> request("ALL_AT_ONCE", ResourceAuthority.OWNER, null, List.of(), null), INVALID_MAPPING_INPUT);
        rejected(() -> new LegacyPracticeRequest(1, "ALL_AT_ONCE", null, true,
                ResourceAuthority.OWNER, QuizTimerState.DISABLED, List.of(), null), INVALID_MAPPING_INPUT);
    }

    @Test
    void resultValuesAlsoProtectTheirStructuralInvariants() {
        var mutable = new ArrayList<>(List.of(FIRST, SECOND));
        var choice = new ChoiceRequired(mutable);
        mutable.clear();
        assertThat(choice.alternatives()).containsExactly(FIRST, SECOND);
        rejected(() -> new ChoiceRequired(null), INVALID_MAPPING_INPUT);
        rejected(() -> new ChoiceRequired(List.of(FIRST)), INVALID_MAPPING_INPUT);
        rejected(() -> new ChoiceRequired(Arrays.asList(FIRST, null)), INVALID_MAPPING_INPUT);
        rejected(() -> new Mapped(null, null), INVALID_MAPPING_INPUT);
        rejected(() -> new Unavailable(null), INVALID_MAPPING_INPUT);
        rejected(() -> new LegacyOnly(null), INVALID_MAPPING_INPUT);
        rejected(() -> new Unsupported(null), INVALID_MAPPING_INPUT);
    }

    private static LegacyPracticeRequest takeOnly(List<AuthorizedPresetPolicy> policies, UUID selected) {
        return request("ALL_AT_ONCE", ResourceAuthority.TAKE_ONLY, QuizTimerState.DISABLED, policies, selected);
    }

    private static LegacyPracticeRequest request(String mode, ResourceAuthority authority, QuizTimerState timer,
                                                  List<AuthorizedPresetPolicy> policies, UUID selected) {
        return new LegacyPracticeRequest(1, mode, LaunchContext.ORDINARY, true, authority, timer, policies, selected);
    }
}
