package uk.gegc.quizmaker.features.attempt.compatibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptPresetServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.*;

class LegacyCompatibilityTest {
    @ParameterizedTest
    @EnumSource(Family.class)
    void historicalRowsNeverBecomeTargetSnapshotsOrWorkingAdapters(Family family) {
        Finding finding = classify(family, new Observation(UUID.randomUUID(), false, false, false, false, false, false));
        assertThat(finding.classification()).isIn(Classification.LEGACY_ONLY, Classification.UNKNOWN);
        assertThat(finding.ownerIssue()).isPositive();
    }

    @Test
    void overlappingDiscrepanciesHaveOneStableOwnerWithoutHidingKnownDefectsAsSupported() {
        UUID id = UUID.randomUUID();
        assertThat(classify(Family.ANSWER, new Observation(id, true, true, true, true, true, true)))
                .isEqualTo(Finding.BROKEN_REFERENCE);
        assertThat(classify(Family.ANSWER, new Observation(id, false, true, true, true, true, true)))
                .isEqualTo(Finding.UNKNOWN_VALUE);
        assertThat(classify(Family.ANSWER, new Observation(id, false, false, true, true, true, true)))
                .isEqualTo(Finding.AMBIGUOUS_IDENTITY);
        assertThat(classify(Family.ANSWER, new Observation(id, false, false, false, true, true, true)))
                .isEqualTo(Finding.DUPLICATE_ANSWER);
        assertThat(classify(Family.ANSWER, new Observation(id, false, false, false, true, false, true)))
                .isEqualTo(Finding.INCONSISTENT_STATE);
        assertThat(classify(Family.QUIZ_CONFIGURATION, new Observation(id, false, false, false, false, false, true)))
                .isEqualTo(Finding.UNEXPECTED_REPETITION);
        assertThat(new Observation(id, false, false, false, false, false, false).toString()).doesNotContain(id.toString());
    }

    @ParameterizedTest
    @CsvSource({"ALL_AT_ONCE,DISABLED,MAPPED_REQUEST", "TIMED,DISABLED,MAPPED_REQUEST",
            "TIMED,ENABLED,LATER_CAPABILITY", "ONE_BY_ONE,DISABLED,LATER_CAPABILITY",
            "TIMED,UNKNOWN,UNKNOWN_VALUE", "future,DISABLED,UNSUPPORTED_MAPPING"})
    void actualVersionedPresetMappingPreservesDisabledTimerAndCapabilityBoundaries(
            String mode, QuizTimerState timer, Finding expected) {
        var request = new LegacyPracticeRequest(1, mode, LaunchContext.ORDINARY, true,
                ResourceAuthority.OWNER, timer, List.of(), null);
        assertThat(classifyMapping(new AttemptPresetServiceImpl().mapLegacy(request))).isEqualTo(expected);
    }

    @Test
    void actualMappingDeniesMissingAuthorityAndSeparatesAmbiguousChoice() {
        var presets = new AttemptPresetServiceImpl();
        var request = new LegacyPracticeRequest(1, "ALL_AT_ONCE", LaunchContext.ORDINARY, false,
                ResourceAuthority.OWNER, QuizTimerState.DISABLED, List.of(), null);
        assertThat(classifyMapping(presets.mapLegacy(request))).isEqualTo(Finding.ACCESS_UNAVAILABLE);
        var options = List.of(new AuthorizedPresetPolicy(UUID.randomUUID(), BuiltInPracticePreset.REFERENCE),
                new AuthorizedPresetPolicy(UUID.randomUUID(), BuiltInPracticePreset.REFERENCE));
        request = new LegacyPracticeRequest(1, "ALL_AT_ONCE", LaunchContext.ORDINARY, true,
                ResourceAuthority.TAKE_ONLY, QuizTimerState.DISABLED, options, null);
        assertThat(classifyMapping(presets.mapLegacy(request))).isEqualTo(Finding.CHOICE_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(Finding.class)
    void committedAndUncertainTargetOutcomesNeverBecomeAbsent(Finding compatibility) {
        assertThat(routingInput(StartEvidence.COMMITTED_TARGET, compatibility)).isEqualTo(RoutingInput.TARGET_OWNED);
        assertThat(routingInput(StartEvidence.UNKNOWN_OR_IN_FLIGHT, compatibility)).isEqualTo(RoutingInput.RECONCILE);
        assertThat(routingInput(StartEvidence.PROVEN_ABSENT, compatibility)).isEqualTo(
                compatibility == Finding.MAPPED_REQUEST ? RoutingInput.COMPATIBLE_CANDIDATE : RoutingInput.NOT_COMPATIBLE);
    }

    @Test
    void invalidInputsAndUnreconciledReportsCannotImplyReadiness() {
        assertThatThrownBy(() -> new Limits(0, 10, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(1001, 10, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(10, 0, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(10, 1_000_001, 30)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(10, 10, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Limits(10, 10, 301)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FamilyCount(Family.ATTEMPT, 2, true, Map.of(Finding.LEGACY_HISTORY, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FamilyCount(Family.ATTEMPT, -1, true, Map.of(Finding.LEGACY_HISTORY, -1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Report(1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> classifyMapping(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> routingInput(null, Finding.MAPPED_REQUEST)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void repetitionClaimRequiresCompleteCleanConfigurationAndBothEmptyTables() {
        var counts = new ArrayList<FamilyCount>();
        for (Family family : Family.values()) counts.add(new FamilyCount(family, 0, true, Map.of()));
        assertThat(new Report(1, counts).emptyRepetitionConfirmed()).isTrue();
        counts.set(4, new FamilyCount(Family.QUIZ_CONFIGURATION, 1, false, Map.of(Finding.MUTABLE_CONFIGURATION, 1L)));
        assertThat(new Report(1, counts).emptyRepetitionConfirmed()).isFalse();
        counts.set(4, new FamilyCount(Family.QUIZ_CONFIGURATION, 1, true, Map.of(Finding.UNEXPECTED_REPETITION, 1L)));
        assertThat(new Report(1, counts).emptyRepetitionConfirmed()).isFalse();
        counts.set(4, new FamilyCount(Family.QUIZ_CONFIGURATION, 0, true, Map.of()));
        counts.set(5, new FamilyCount(Family.REPETITION_ENTRY, 1, true, Map.of(Finding.UNEXPECTED_REPETITION, 1L)));
        assertThat(new Report(1, counts).emptyRepetitionConfirmed()).isFalse();
    }
}
