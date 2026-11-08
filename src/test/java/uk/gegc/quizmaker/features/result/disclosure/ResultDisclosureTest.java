package uk.gegc.quizmaker.features.result.disclosure;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.*;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.result.disclosure.DisclosureFixtures.*;

class ResultDisclosureTest {
    @Test void releasedPracticeUsesTheCapturedPolicyAndRetainedOriginalReview() {
        var decisions = ResultDisclosure.evaluate(new Fixture().build());
        assertThat(decisions.categories()).containsOnlyKeys(Category.values());
        assertThat(decisions.requested(ALL)).containsExactlyInAnyOrderElementsOf(PRACTICE);
        PRACTICE.forEach(category -> assertThat(decisions.category(category)).isEqualTo(new Decision(Outcome.ALLOW, Reason.ALLOWED)));
        // The immutable result is from 2000. No obsolete 12/24-month practice age cutoff is inferred.
        assertThat(decisions.allows(Category.REVIEW)).isTrue();
        for (var category : Set.of(Category.PASS_FAIL, Category.FEEDBACK))
            assertThat(decisions.category(category)).isEqualTo(new Decision(Outcome.DENY, Reason.POLICY_DENIED));
    }

    @Test void formalDefaultHasOnlyTotalScoreAndPassFailWithoutChoosingAReleaseTrigger() {
        var fixture = new Fixture().formal();
        assertThat(ResultDisclosure.evaluate(fixture.build()).requested(ALL)).containsExactlyInAnyOrderElementsOf(FORMAL);
        fixture.pendingRelease();
        var pending = ResultDisclosure.evaluate(fixture.build());
        FORMAL.forEach(category -> assertThat(pending.category(category)).isEqualTo(new Decision(Outcome.PENDING, Reason.RELEASE_PENDING)));
        assertThat(pending.requested(ALL)).isEmpty();
        assertThat(pending.category(Category.CORRECT_ANSWER).reason()).isEqualTo(Reason.POLICY_DENIED);
    }

    @Test void scorePermissionIsIndependentOfCorrectnessAnswersExplanationAndReview() {
        var fixture = new Fixture();
        fixture.policy = new Policy(1, BINDING, Family.FORMAL_ASSESSMENT, Set.of(Category.TOTAL_SCORE));
        var decisions = ResultDisclosure.evaluate(fixture.build());
        assertThat(decisions.requested(ALL)).containsExactly(Category.TOTAL_SCORE);
        fixture.policy = new Policy(1, BINDING, Family.FORMAL_ASSESSMENT, Set.of(Category.REVIEW));
        decisions = ResultDisclosure.evaluate(fixture.build());
        assertThat(decisions.requested(ALL)).containsExactly(Category.REVIEW);
        assertThat(decisions.allows(Category.CORRECT_ANSWER)).isFalse();
    }

    @Test void everyExplicitSavedCategoryCombinationIsIndependentAndPendingNeverGrantsData() {
        var fixture = new Fixture();
        for (int mask = 0; mask < 1 << Category.values().length; mask++) {
            Set<Category> permitted = subset(mask);
            fixture.policy = new Policy(1, BINDING, Family.FORMAL_ASSESSMENT, permitted);
            fixture.release = new ReleaseFacts(1, BINDING, RESULT.id(), ReleaseState.RELEASED);
            var released = ResultDisclosure.evaluate(fixture.build());
            assertThat(released.requested(ALL)).as("saved category mask %s", mask).containsExactlyInAnyOrderElementsOf(permitted);
            fixture.pendingRelease();
            var pending = ResultDisclosure.evaluate(fixture.build());
            assertThat(pending.requested(ALL)).isEmpty();
            for (var category : Category.values()) assertThat(pending.category(category).outcome())
                    .isEqualTo(permitted.contains(category) ? Outcome.PENDING : Outcome.DENY);
        }
    }

    @Test void pendingResultAndPendingReleaseHaveDistinctBoundedReasons() {
        var fixture = new Fixture().pendingResult();
        assertThat(ResultDisclosure.evaluate(fixture.build()).category(Category.TOTAL_SCORE))
                .isEqualTo(new Decision(Outcome.PENDING, Reason.RESULT_PENDING));
        fixture = new Fixture().pendingRelease();
        assertThat(ResultDisclosure.evaluate(fixture.build()).category(Category.TOTAL_SCORE))
                .isEqualTo(new Decision(Outcome.PENDING, Reason.RELEASE_PENDING));
    }

    @Test void revocationDeniesEveryPreviouslyPermittedCategory() {
        var fixture = new Fixture();
        fixture.release = new ReleaseFacts(1, BINDING, RESULT.id(), ReleaseState.REVOKED);
        var denied = ResultDisclosure.evaluate(fixture.build());
        assertThat(denied.requested(ALL)).isEmpty();
        PRACTICE.forEach(category -> assertThat(denied.category(category).reason()).isEqualTo(Reason.RELEASE_REVOKED));
        assertThat(ResultDisclosure.evaluate(new Fixture().build()).requested(ALL)).containsExactlyInAnyOrderElementsOf(PRACTICE);
    }

    @ParameterizedTest @EnumSource(value = ReviewAccess.class, names = {"WITHHELD", "REVOKED"})
    void currentReviewRestrictionDoesNotHideAnIndependentlyReleasedTotal(ReviewAccess access) {
        var fixture = new Fixture(); fixture.review = access;
        var decisions = ResultDisclosure.evaluate(fixture.build());
        assertThat(decisions.requested(ALL)).containsExactly(Category.TOTAL_SCORE);
        assertThat(decisions.category(Category.REVIEW).reason()).isEqualTo(Reason.REVIEW_UNAVAILABLE);
        assertThat(decisions.category(Category.CORRECT_ANSWER).reason()).isEqualTo(Reason.REVIEW_UNAVAILABLE);
    }

    @Test void absentAuthoredExplanationDoesNotSuppressOtherReleasedCategories() {
        var fixture = new Fixture();
        fixture.available = EnumSet.complementOf(EnumSet.of(Category.EXPLANATION));
        var decisions = ResultDisclosure.evaluate(fixture.build());
        assertThat(decisions.category(Category.EXPLANATION)).isEqualTo(new Decision(Outcome.DENY, Reason.CONTENT_UNAVAILABLE));
        assertThat(decisions.requested(ALL)).containsExactlyInAnyOrder(Category.TOTAL_SCORE, Category.QUESTION_SCORE,
                Category.CORRECTNESS, Category.CORRECT_ANSWER, Category.SUBMITTED_ANSWERS, Category.REVIEW);
    }

    @ParameterizedTest @MethodSource("activeStates")
    void terminalReviewDoesNotBecomeAnActiveAnswerOracle(AttemptLifecycle.State state) {
        var fixture = new Fixture().pendingResult(); fixture.attempt = new AttemptFacts(BINDING, state);
        assertAllDenied(fixture.build(), Reason.ATTEMPT_NOT_SUBMITTED);
        fixture = new Fixture(); fixture.attempt = new AttemptFacts(BINDING, state);
        assertAllDenied(fixture.build(), Reason.INCONSISTENT_FACTS);
    }
    static Stream<AttemptLifecycle.State> activeStates() {
        return Stream.of(new AttemptLifecycle.State(1, AttemptLifecycle.Execution.CREATED, AttemptLifecycle.AnswerCollection.NOT_STARTED),
                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.NOT_STARTED),
                new AttemptLifecycle.State(1, AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.IN_PROGRESS));
    }

    @Test void missingOrDeniedIdentityNeverRevealsProtectedContextValidity() {
        assertAllDenied(null, Reason.ACCESS_DENIED);
        var fixture = new Fixture(); fixture.audience = null; assertAllDenied(fixture.build(), Reason.ACCESS_DENIED);
        var audiences = Arrays.asList(new Audience(null, RESULT.attemptId(), USER, USER),
                new Audience(Access.DENIED, RESULT.attemptId(), USER, USER),
                new Audience(Access.MISSING, RESULT.attemptId(), USER, USER),
                new Audience(Access.VERIFIED, null, USER, USER), new Audience(Access.VERIFIED, RESULT.attemptId(), null, USER),
                new Audience(Access.VERIFIED, RESULT.attemptId(), USER, null),
                new Audience(Access.VERIFIED, RESULT.attemptId(), UUID.randomUUID(), USER));
        for (var audience : audiences) {
            fixture = new Fixture(); fixture.audience = audience; assertAllDenied(fixture.build(), Reason.ACCESS_DENIED);
            fixture.version = 99; fixture.policy = null; fixture.result = null;
            assertAllDenied(fixture.build(), Reason.ACCESS_DENIED);
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 2, Integer.MAX_VALUE})
    void everyUnknownContractVersionFailsClosed(int version) {
        var fixture = new Fixture(); fixture.version = version; assertAllDenied(fixture.build(), Reason.UNSUPPORTED_VERSION);
        fixture = new Fixture(); fixture.policy = new Policy(version, BINDING, Family.PERSONAL_PRACTICE, PRACTICE);
        assertAllDenied(fixture.build(), Reason.UNSUPPORTED_VERSION);
        fixture = new Fixture(); fixture.result = new ResultFacts(version, BINDING, RESULT.id(), ResultState.FINAL);
        assertAllDenied(fixture.build(), Reason.UNSUPPORTED_VERSION);
        fixture = new Fixture(); fixture.release = new ReleaseFacts(version, BINDING, RESULT.id(), ReleaseState.RELEASED);
        assertAllDenied(fixture.build(), Reason.UNSUPPORTED_VERSION);
    }

    @Test void incompleteStatesAndContextNeverUsePermissiveDefaults() {
        var incomplete = new ArrayList<Context>();
        var f = new Fixture(); f.policy = null; incomplete.add(f.build());
        f = new Fixture(); f.attempt = null; incomplete.add(f.build());
        f = new Fixture(); f.result = null; incomplete.add(f.build());
        f = new Fixture(); f.release = null; incomplete.add(f.build());
        f = new Fixture(); f.review = null; incomplete.add(f.build());
        f = new Fixture(); f.available = null; incomplete.add(f.build());
        f = new Fixture(); f.policy = new Policy(1, BINDING, null, PRACTICE); incomplete.add(f.build());
        f = new Fixture(); f.policy = new Policy(1, BINDING, Family.PERSONAL_PRACTICE, null); incomplete.add(f.build());
        f = new Fixture(); f.attempt = new AttemptFacts(BINDING, null); incomplete.add(f.build());
        f = new Fixture(); f.result = new ResultFacts(1, BINDING, RESULT.id(), null); incomplete.add(f.build());
        f = new Fixture(); f.release = new ReleaseFacts(1, BINDING, RESULT.id(), null); incomplete.add(f.build());
        incomplete.forEach(context -> assertAllDenied(context, Reason.INVALID_INPUT));
    }

    @Test void everyIdentityAndPolicyHashBindingMustMatchTheAuthorizedAttempt() {
        var variants = Arrays.asList(null,
                new Binding(UUID.randomUUID(), BINDING.formId(), BINDING.policySnapshotId(), BINDING.quizRevisionId(), BINDING.policyHash()),
                new Binding(BINDING.attemptId(), UUID.randomUUID(), BINDING.policySnapshotId(), BINDING.quizRevisionId(), BINDING.policyHash()),
                new Binding(BINDING.attemptId(), BINDING.formId(), UUID.randomUUID(), BINDING.quizRevisionId(), BINDING.policyHash()),
                new Binding(BINDING.attemptId(), BINDING.formId(), BINDING.policySnapshotId(), UUID.randomUUID(), BINDING.policyHash()),
                new Binding(BINDING.attemptId(), BINDING.formId(), BINDING.policySnapshotId(), BINDING.quizRevisionId(), "b".repeat(64)));
        for (var binding : variants) {
            var f = new Fixture(); f.attempt = new AttemptFacts(binding, f.attempt.state()); assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
            f = new Fixture(); f.result = new ResultFacts(1, binding, RESULT.id(), ResultState.FINAL); assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
            f = new Fixture(); f.release = new ReleaseFacts(1, binding, RESULT.id(), ReleaseState.RELEASED); assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
        }
        var f = new Fixture(); f.audience = new Audience(Access.VERIFIED, UUID.randomUUID(), USER, USER);
        assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
    }

    @Test void malformedBindingsAndResultReleaseCombinationsAreRejected() {
        var malformed = Arrays.asList(new Binding(null, BINDING.formId(), BINDING.policySnapshotId(), BINDING.quizRevisionId(), BINDING.policyHash()),
                new Binding(BINDING.attemptId(), null, BINDING.policySnapshotId(), BINDING.quizRevisionId(), BINDING.policyHash()),
                new Binding(BINDING.attemptId(), BINDING.formId(), null, BINDING.quizRevisionId(), BINDING.policyHash()),
                new Binding(BINDING.attemptId(), BINDING.formId(), BINDING.policySnapshotId(), null, BINDING.policyHash()),
                new Binding(BINDING.attemptId(), BINDING.formId(), BINDING.policySnapshotId(), BINDING.quizRevisionId(), null),
                new Binding(BINDING.attemptId(), BINDING.formId(), BINDING.policySnapshotId(), BINDING.quizRevisionId(), "x".repeat(64)),
                new Binding(BINDING.attemptId(), BINDING.formId(), BINDING.policySnapshotId(), BINDING.quizRevisionId(), "a".repeat(65)));
        for (var binding : malformed) {
            var f = new Fixture(); f.policy = new Policy(1, binding, Family.PERSONAL_PRACTICE, PRACTICE);
            assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
        }
        var f = new Fixture(); f.policy = new Policy(1, null, Family.PERSONAL_PRACTICE, PRACTICE);
        assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
        f = new Fixture(); f.result = new ResultFacts(1, BINDING, null, ResultState.FINAL); assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
        f = new Fixture(); f.release = new ReleaseFacts(1, BINDING, UUID.randomUUID(), ReleaseState.RELEASED); assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
        for (var state : ReleaseState.values()) {
            f = new Fixture().pendingResult(); f.release = new ReleaseFacts(1, BINDING, RESULT.id(), state);
            assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
        }
        f = new Fixture().pendingResult(); f.result = new ResultFacts(1, BINDING, RESULT.id(), ResultState.PENDING);
        assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
        f = new Fixture().pendingResult(); f.release = new ReleaseFacts(1, BINDING, null, ReleaseState.RELEASED);
        assertAllDenied(f.build(), Reason.INCONSISTENT_FACTS);
    }

    @Test void clientIncludeFlagsCanOnlyNarrowCurrentServerDecisions() {
        var practice = ResultDisclosure.evaluate(new Fixture().build());
        var formal = ResultDisclosure.evaluate(new Fixture().formal().build());
        var pending = ResultDisclosure.evaluate(new Fixture().pendingRelease().build());
        var f = new Fixture(); f.audience = new Audience(Access.DENIED, RESULT.attemptId(), USER, USER);
        var denied = ResultDisclosure.evaluate(f.build());
        for (int mask = 0; mask < 1 << Category.values().length; mask++) {
            var requested = subset(mask);
            var expectedPractice = EnumSet.noneOf(Category.class); expectedPractice.addAll(requested); expectedPractice.retainAll(PRACTICE);
            var expectedFormal = EnumSet.noneOf(Category.class); expectedFormal.addAll(requested); expectedFormal.retainAll(FORMAL);
            assertThat(practice.requested(requested)).isEqualTo(expectedPractice);
            assertThat(formal.requested(requested)).isEqualTo(expectedFormal);
            assertThat(pending.requested(requested)).isEmpty(); assertThat(denied.requested(requested)).isEmpty();
        }
        assertThat(practice.requested(null)).isEmpty(); assertThat(practice.requested(Collections.singleton(null))).isEmpty();
        assertThat(practice.category(null)).isEqualTo(new Decision(Outcome.DENY, Reason.INVALID_INPUT));
        assertThat(practice.allows(null)).isFalse();
    }

    @Test void unknownCategoryNamesDoNotBecomeAliasesOrDefaults() {
        for (var category : Category.values()) assertThat(ResultDisclosure.parseCategory(1, category.name())).contains(category);
        for (String name : Arrays.asList(null, "", "total_score", "TOTAL_SCORE ", "RUBRIC", "GRADER_COMMENTS",
                "COHORT_STATISTICS", "SECRET_PRIVATE_PAYLOAD", "x".repeat(10000)))
            assertThat(ResultDisclosure.parseCategory(1, name)).isEmpty();
        assertThat(ResultDisclosure.parseCategory(2, "TOTAL_SCORE")).isEmpty();
    }

    @Test void categoriesAndDecisionsAreDefensivelyCopiedAndDiagnosticsContainNoPrivateReferences() {
        var categories = EnumSet.of(Category.TOTAL_SCORE);
        var f = new Fixture(); f.policy = new Policy(1, BINDING, Family.FORMAL_ASSESSMENT, categories); f.available = categories;
        var context = f.build(); categories.clear();
        var decisions = ResultDisclosure.evaluate(context);
        assertThat(decisions.requested(ALL)).containsExactly(Category.TOTAL_SCORE);
        assertThatThrownBy(() -> context.policy().permitted().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> context.available().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decisions.categories().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decisions.requested(ALL).clear()).isInstanceOf(UnsupportedOperationException.class);
        var diagnostic = List.of(BINDING, context, context.audience(), context.policy(), context.attempt(), context.result(), context.release(), decisions).toString();
        assertThat(diagnostic).doesNotContain(USER.toString(), RESULT.id().toString(), RESULT.attemptId().toString(), BINDING.policyHash());
        assertThat(decisions.categories().keySet()).containsExactly(Category.values());
        assertThatThrownBy(() -> new Policy(1, BINDING, Family.FORMAL_ASSESSMENT, Collections.singleton(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid disclosure categories");
        assertThatThrownBy(() -> new Decision(Outcome.ALLOW, Reason.POLICY_DENIED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Decision(Outcome.DENY, Reason.RESULT_PENDING)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void practiceAdapterRejectsMissingOrForeignCapturedPolicy() {
        assertThatThrownBy(() -> Policy.practice(BINDING, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Policy.practice(null, savedPractice())).isInstanceOf(IllegalArgumentException.class);
        var foreign = new Binding(BINDING.attemptId(), BINDING.formId(), BINDING.policySnapshotId(), UUID.randomUUID(), BINDING.policyHash());
        assertThatThrownBy(() -> Policy.practice(foreign, savedPractice())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid practice disclosure policy");
    }

    private static Set<Category> subset(int mask) {
        var values = EnumSet.noneOf(Category.class);
        for (var category : Category.values()) if ((mask & (1 << category.ordinal())) != 0) values.add(category);
        return values;
    }
    private static void assertAllDenied(Context context, Reason reason) {
        var decisions = ResultDisclosure.evaluate(context);
        assertThat(decisions.categories()).hasSize(Category.values().length);
        assertThat(decisions.requested(ALL)).isEmpty();
        assertThat(decisions.categories().values()).allSatisfy(value -> assertThat(value).isEqualTo(new Decision(Outcome.DENY, reason)));
    }
}
