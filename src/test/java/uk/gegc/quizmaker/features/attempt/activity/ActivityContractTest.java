package uk.gegc.quizmaker.features.attempt.activity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.*;
import uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gegc.quizmaker.features.attempt.activity.ActivityFixtures.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContract.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.activity.ActivityContractException.Reason.*;

class ActivityContractTest {
    @ParameterizedTest
    @EnumSource(ResponseRetention.class)
    void explicitSubmissionCompletesRequiredParticipationWithoutRequiringOptionalItems(ResponseRetention retention) {
        var policy = ungraded(retention);
        var started = start(policy, new StartFacts(true));
        assertThat(started.state()).isEqualTo(IncompleteState.IN_PROGRESS);
        assertThat(responseDisposition(policy, started)).isEqualTo(retention == ResponseRetention.RETAIN_IMMUTABLE_RESPONSES
                ? ResponseDisposition.RETAIN_IMMUTABLY : ResponseDisposition.DO_NOT_RETAIN);

        var completed = complete(policy, started, new CompletionEvidence(true, List.of(REQUIRED), TIME));
        assertThat(completed).isEqualTo(new Completed(1, TIME));
        assertThat(completed.kind()).isEqualTo(Kind.UNGRADED);
        assertThat(started.state()).isEqualTo(IncompleteState.IN_PROGRESS); // Pure plan, no mutation.
        rejected(() -> assessedResult(completed), NOT_GRADED);
        rejected(() -> requireGrading(policy), NOT_GRADED);
        rejected(() -> responseDisposition(policy, completed), NOT_IN_PROGRESS);
        rejected(() -> complete(policy, completed, new CompletionEvidence(true, List.of(REQUIRED), TIME)), NOT_IN_PROGRESS);
    }

    @Test
    void noRetentionRequiresAdvanceNoticeButRetainedResponsesDoNotInventAnAdditionalNoticeGate() {
        var facts = new StartFacts(false);
        assertThat(start(ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES), facts).state())
                .isEqualTo(IncompleteState.IN_PROGRESS);
        var noRetention = ungraded(ResponseRetention.COMPLETION_ONLY_NO_RESPONSE_RETENTION);
        rejected(() -> start(noRetention, facts), PRIVACY_NOTICE_REQUIRED);
        assertThat(start(noRetention, new StartFacts(true)).state()).isEqualTo(IncompleteState.IN_PROGRESS);
    }

    @Test
    void viewingExpiryOrPartialParticipationCannotStandInForExplicitFinalSubmit() {
        var policy = ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES);
        var running = start(policy, new StartFacts(false));
        rejected(() -> complete(policy, running, new CompletionEvidence(false, List.of(REQUIRED, OPTIONAL), TIME)), FINAL_SUBMIT_REQUIRED);
        rejected(() -> complete(policy, running, new CompletionEvidence(true, List.of(OPTIONAL), TIME)), PARTICIPATION_REQUIRED);
        rejected(() -> complete(policy, running, new CompletionEvidence(true, List.of(REQUIRED, FOREIGN), TIME)), CONTRADICTORY_STATE);
        assertThat(complete(policy, running, new CompletionEvidence(true, List.of(REQUIRED, OPTIONAL), TIME)))
                .isEqualTo(new Completed(1, TIME));
    }

    @ParameterizedTest
    @EnumSource(value = IncompleteState.class, names = {"NOT_STARTED", "ABANDONED", "EXPIRED"})
    void everyNonRunningStateRemainsIncompleteEvenWithEnoughParticipation(IncompleteState state) {
        var policy = ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES);
        var current = new Incomplete(1, state);
        rejected(() -> complete(policy, current, new CompletionEvidence(true, List.of(REQUIRED), TIME)), NOT_IN_PROGRESS);
        rejected(() -> responseDisposition(policy, current), NOT_IN_PROGRESS);
        rejected(() -> assessedResult(current), NOT_GRADED);
        assertThat(current.state()).isEqualTo(state);
    }

    @Test
    void publishedRequiredFlagsDetermineCompletionForEveryParticipationSubsetAndSubmitChoice() {
        // Exhaust all required/optional and participation subsets; no arbitrary response-count default.
        var ids = List.of(REQUIRED, OPTIONAL, FOREIGN);
        for (int requiredMask = 0; requiredMask < 8; requiredMask++) {
            var requirements = new ArrayList<ItemRequirement>();
            for (int i = 0; i < ids.size(); i++) requirements.add(new ItemRequirement(ids.get(i), (requiredMask & (1 << i)) != 0));
            var policy = new UngradedPolicy(1, ResponseRetention.RETAIN_IMMUTABLE_RESPONSES,
                    new ParticipationRequirements(requirements));
            for (int participationMask = 0; participationMask < 8; participationMask++) {
                var participated = new ArrayList<UUID>();
                for (int i = 0; i < ids.size(); i++) if ((participationMask & (1 << i)) != 0) participated.add(ids.get(i));
                for (boolean submit : List.of(false, true)) {
                    var facts = new CompletionEvidence(submit, participated, TIME);
                    var running = start(policy, new StartFacts(false));
                    if (!submit) rejected(() -> complete(policy, running, facts), FINAL_SUBMIT_REQUIRED);
                    else if ((participationMask & requiredMask) != requiredMask) {
                        var missing = new ArrayList<UUID>();
                        for (int i = 0; i < ids.size(); i++)
                            if (((requiredMask & ~participationMask) & (1 << i)) != 0) missing.add(ids.get(i));
                        assertThatThrownBy(() -> complete(policy, running, facts)).isInstanceOfSatisfying(
                                ActivityContractException.class, failure -> {
                                    assertThat(failure.reason()).isEqualTo(PARTICIPATION_REQUIRED);
                                    assertThat(failure.missingFormItemIds()).containsExactlyElementsOf(missing);
                                });
                    } else assertThat(complete(policy, running, facts)).isEqualTo(new Completed(1, TIME));
                }
            }
        }
        var noAdditionalRequirements = new UngradedPolicy(1, ResponseRetention.RETAIN_IMMUTABLE_RESPONSES,
                new ParticipationRequirements(List.of()));
        assertThat(complete(noAdditionalRequirements, start(noAdditionalRequirements, new StartFacts(false)),
                new CompletionEvidence(true, List.of(), TIME))).isEqualTo(new Completed(1, TIME));
    }

    @Test
    void membershipAndEvidenceAreDefensiveSnapshotsWithoutResponsePayloads() {
        var items = new ArrayList<>(List.of(new ItemRequirement(REQUIRED, true)));
        var requirements = new ParticipationRequirements(items);
        items.clear();
        assertThat(requirements.items()).containsExactly(new ItemRequirement(REQUIRED, true));
        assertThatThrownBy(() -> requirements.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        var ids = new ArrayList<>(List.of(REQUIRED));
        var evidence = new CompletionEvidence(true, ids, TIME);
        ids.clear();
        assertThat(evidence.participatedItems()).containsExactly(REQUIRED);
        assertThatThrownBy(() -> evidence.participatedItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(evidence.toString()).doesNotContain(REQUIRED.toString());
        assertThat(requirements.toString()).doesNotContain(REQUIRED.toString());
        assertThat(requirements.items().get(0).toString()).doesNotContain(REQUIRED.toString());
        rejected(() -> new ParticipationRequirements(List.of(new ItemRequirement(REQUIRED, true), new ItemRequirement(REQUIRED, false))), INVALID_INPUT);
        rejected(() -> new ParticipationRequirements(Arrays.asList((ItemRequirement) null)), INVALID_INPUT);
        rejected(() -> new CompletionEvidence(true, List.of(REQUIRED, REQUIRED), TIME), INVALID_INPUT);
        rejected(() -> new CompletionEvidence(true, Arrays.asList((UUID) null), TIME), INVALID_INPUT);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void zeroAndNonzeroGradedResultsKeepTheirExistingMeaningAndIdentity(int earned) {
        var policy = gradedPolicy();
        var result = gradedResult(earned);
        assertThat(requireGrading(new GradedPolicy(1, policy))).isSameAs(policy);
        assertThat(assessedResult(new GradedOutcome(1, result))).isSameAs(result);
        assertThat(result.score().earned()).isEqualTo(earned);
        assertThat(result.score().possible()).isEqualTo(2);
        assertThat(result.score().passed()).isEqualTo(earned == 2);
        assertThat(new GradedPolicy(1, policy).kind()).isEqualTo(Kind.GRADED);
        assertThat(new GradedOutcome(1, result).kind()).isEqualTo(Kind.GRADED);
    }

    @Test
    void onlyKnownGradedMetadataCanEnterAssessedConsumersAndLegacyIsNotReclassified() {
        assertThat(classify(null, null)).isEqualTo(Classification.LEGACY);
        assertThat(assessmentDisposition(classify(null, null))).isEqualTo(AssessmentDisposition.LEGACY_NOT_COMPARABLE);
        assertThat(assessmentDisposition(classify(1, "GRADED"))).isEqualTo(AssessmentDisposition.GRADED_CANDIDATE);
        assertThat(assessmentDisposition(classify(1, "UNGRADED"))).isEqualTo(AssessmentDisposition.EXCLUDED_UNGRADED);
        rejected(() -> classify(1, null), INVALID_INPUT);
        rejected(() -> classify(null, "GRADED"), INVALID_INPUT);
        rejected(() -> classify(2, "GRADED"), UNSUPPORTED_VERSION);
        rejected(() -> classify(1, "LEGACY"), UNKNOWN_KIND);
        rejected(() -> assessmentDisposition(null), INVALID_INPUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ungraded", "UNGRADED ", "0", "COMPLETION_ONLY", "private-response-"})
    void unknownKindsNeverSelectDefaultsOrEchoTheInput(String kind) {
        rejected(() -> classify(1, kind), UNKNOWN_KIND);
        rejected(() -> parseKind(kind), UNKNOWN_KIND);
    }

    @Test
    void completionTimeUsesCallerEvidenceAndExistingMicrosecondPrecision() {
        var instant = Instant.parse("2026-11-18T12:00:00.123456999Z");
        assertThat(new Completed(1, instant).completedAt()).isEqualTo(TIME);
        rejected(() -> new Completed(1, null), INVALID_INPUT);
    }

    @Test
    void missingInputsAndUnsupportedVersionsHaveBoundedFailures() {
        var policy = ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES);
        var running = start(policy, new StartFacts(false));
        var facts = new CompletionEvidence(true, List.of(REQUIRED), TIME);
        for (var action : List.<Runnable>of(
                () -> start(null, new StartFacts(false)), () -> start(policy, null),
                () -> responseDisposition(null, running), () -> responseDisposition(policy, null),
                () -> complete(null, running, facts), () -> complete(policy, null, facts), () -> complete(policy, running, null),
                () -> new ItemRequirement(null, true), () -> new ParticipationRequirements(null),
                () -> new CompletionEvidence(true, null, TIME), () -> new CompletionEvidence(true, List.of(), null),
                () -> new UngradedPolicy(1, null, policy.participation()), () -> new UngradedPolicy(1, policy.responseRetention(), null),
                () -> new GradedPolicy(1, null), () -> new GradedOutcome(1, null), () -> new Incomplete(1, null),
                () -> requireGrading(null), () -> assessedResult(null), () -> parseKind(null))) rejected(action, INVALID_INPUT);
        for (int version : List.of(-1, 0, 2, Integer.MAX_VALUE)) {
            rejected(() -> new GradedPolicy(version, gradedPolicy()), UNSUPPORTED_VERSION);
            rejected(() -> new UngradedPolicy(version, policy.responseRetention(), policy.participation()), UNSUPPORTED_VERSION);
            rejected(() -> new GradedOutcome(version, gradedResult(0)), UNSUPPORTED_VERSION);
            rejected(() -> new Incomplete(version, IncompleteState.IN_PROGRESS), UNSUPPORTED_VERSION);
            rejected(() -> new Completed(version, TIME), UNSUPPORTED_VERSION);
        }
    }

    @Test
    void vocabularyParsingIsExactClosedAndCannotLeakPrivateInput() {
        for (var owner : ActivityContract.class.getDeclaredClasses()) if (owner.isEnum()) roundTrip(owner.asSubclass(Enum.class));
        rejected(() -> parseValue(Kind.class, null), INVALID_INPUT);
        rejected(() -> parseValue(null, "GRADED"), INVALID_INPUT);
        rejected(() -> parseValue(ResolvedAttemptPolicy.GradingMode.class, "BINARY"), INVALID_INPUT);
        rejected(() -> parseValue(Kind.class, "secret-".repeat(100_000)), UNKNOWN_VALUE);
        @SuppressWarnings({"rawtypes", "unchecked"}) Class<Kind> invalid = (Class) String.class;
        rejected(() -> parseValue(invalid, "GRADED"), INVALID_INPUT);
        assertThat(new GradedPolicy(1, gradedPolicy()).toString()).doesNotContain(id(10).toString());
        assertThat(new GradedOutcome(1, gradedResult(0)).toString()).doesNotContain(id(20).toString());
        assertThat(ungraded(ResponseRetention.RETAIN_IMMUTABLE_RESPONSES).toString()).doesNotContain(REQUIRED.toString());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void roundTrip(Class<? extends Enum> type) {
        for (var value : type.getEnumConstants()) assertThat(parseValue(type, value.name())).isSameAs(value);
        rejected(() -> parseValue(type, "private-response"), UNKNOWN_VALUE);
    }

    static void rejected(Runnable action, ActivityContractException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ActivityContractException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("Activity contract rejected: " + reason);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
        });
    }
}
