package uk.gegc.quizmaker.features.attempt.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.policy.PolicyFixtures.*;

class ResolvedAttemptPolicyTest {
    @Test
    @DisplayName("policy v1: when approved practice is represented then all dimensions have explicit semantics")
    void approvedPracticeVectorHasExplicitIndependentDimensions() {
        // Given / When: a server-prepared value, without a preset name or AttemptMode.
        var policy = fixture().build();

        // Then: the vocabulary describes the outcome, not an engine/mode-name switch.
        assertThat(policy.schemaVersion()).isEqualTo(1);
        assertThat(policy.content().quizRevisionId().toString()).endsWith("000000000042");
        assertThat(policy.content().revisionSchemaVersion()).isEqualTo(1);
        assertThat(policy.content().formMode()).isEqualTo(FormMode.PRECOMPUTED);
        assertThat(policy.content().sections()).isEqualTo(SectionMode.IMPLICIT_FLAT);
        assertThat(policy.content().cases()).isEqualTo(CaseMode.NOT_CONFIGURED);
        assertThat(policy.questionSelection()).isEqualTo(new SelectionPolicy(SelectionMode.ALL, 1));
        assertThat(policy.questionOrdering()).isEqualTo(new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1));
        assertThat(policy.answerOrdering()).isEqualTo(new AnswerOrderingPolicy(AnswerOrder.AUTHORED, 1));
        assertThat(policy.delivery().mode()).isEqualTo(DeliveryMode.ALL_AT_ONCE);
        assertThat(policy.navigation().mode()).isEqualTo(NavigationMode.FREE);
        assertThat(policy.submission().draftsEnabled()).isFalse();
        assertThat(policy.submission().autosaveEnabled()).isFalse();
        assertThat(policy.submission().requireAllAnswers()).isFalse();
        assertThat(policy.submission().completionConfirmationRequired()).isTrue();
        assertThat(policy.submission().mode()).isEqualTo(SubmissionMode.SINGLE_AND_ATOMIC_BATCH);
        assertThat(policy.submission().retries()).isEqualTo(RetrySemantics.IDENTICAL_REPLAY_CHANGED_CONFLICT);
        assertThat(policy.submission().finalSubmitPayload()).isEqualTo(FinalSubmitPayload.NONE);
        assertThat(policy.submission().unanswered()).isEqualTo(UnansweredPolicy.NO_CREDIT_WITHOUT_ACCEPTANCE);
        assertThat(policy.answerChange().mode()).isEqualTo(AnswerChangeMode.LOCK_ON_ACCEPTANCE);
        assertThat(policy.hints().mode()).isEqualTo(HintMode.ON_REQUEST);
        assertThat(policy.hints().source()).isEqualTo(HintSource.AUTHORED);
        assertThat(policy.timing().mode()).isEqualTo(TimingMode.NONE);
        assertThat(policy.pause().mode()).isEqualTo(PauseMode.DISABLED);
        assertThat(policy.attempts().mode()).isEqualTo(AttemptLimitMode.UNLIMITED);
        assertThat(policy.access().participant()).isEqualTo(ParticipantMode.AUTHENTICATED);
        assertThat(policy.access().validation()).isEqualTo(AccessValidation.SERVER_REVALIDATED_AT_START_AND_READ);
        assertThat(policy.grading().mode()).isEqualTo(GradingMode.BINARY);
        assertThat(policy.grading().units()).isEqualTo(ScoreUnits.EXACT_POINTS_WITH_INTEGER_BASIS_POINTS);
        assertThat(policy.review().availability()).isEqualTo(ReviewAvailability.AFTER_COMPLETION);
        assertThat(policy.review().window()).isEqualTo(ReviewWindow.WHILE_ACCOUNT_AND_RESULT_RETAINED);
        assertThat(policy.review().content()).containsExactly(ReviewCategory.SUBMITTED_ANSWERS,
                ReviewCategory.CORRECTNESS, ReviewCategory.CORRECT_ANSWERS, ReviewCategory.EXPLANATIONS);
        assertThat(policy.results().runningProgressEnabled()).isTrue();
        assertThat(policy.results().passThresholdBasisPoints()).isEqualTo(7000);
        assertThat(ResolvedAttemptPolicy.DEFAULT_PASS_THRESHOLD_BASIS_POINTS).isEqualTo(7000);
        assertThat(ResolvedAttemptPolicy.BASIS_POINTS_SCALE).isEqualTo(10000);
        assertThat(policy.results().selection()).isEqualTo(ResultSelectionMode.NOT_CONFIGURED);
        assertThat(policy.results().leaderboard()).isEqualTo(LeaderboardMode.DISABLED);
        assertThat(policy.results().certificates()).isEqualTo(CertificateMode.DISABLED);
        assertThat(policy.accessibility().accommodations()).isEqualTo(AccommodationMode.NOT_CONFIGURED);
        assertThat(policy.integrity().formEvidence()).isEqualTo(FormEvidence.EXACT_PERSISTED_IDENTITIES_AND_ORDER);
        assertThat(policy.integrity().engineBinding()).isEqualTo(EngineBinding.IMMUTABLE);
        assertThat(policy.integrity().versions()).isEqualTo(VersionHandling.REJECT_UNKNOWN);
    }

    @Test
    @DisplayName("disclosure: when practice is represented then protected feedback follows immutable acceptance")
    void goldenDisclosureVectorDoesNotAuthorizeEarlyAnswers() {
        var disclosure = fixture().build().disclosure();
        assertThat(disclosure.releaseOf(DisclosureCategory.CORRECTNESS)).isEqualTo(ReleaseMoment.AFTER_ACCEPTANCE);
        assertThat(disclosure.releaseOf(DisclosureCategory.QUESTION_SCORE)).isEqualTo(ReleaseMoment.AFTER_ACCEPTANCE);
        assertThat(disclosure.releaseOf(DisclosureCategory.CORRECT_ANSWER)).isEqualTo(ReleaseMoment.AFTER_ACCEPTANCE);
        assertThat(disclosure.releaseOf(DisclosureCategory.EXPLANATION)).isEqualTo(ReleaseMoment.AFTER_ACCEPTANCE);
        assertThat(disclosure.releaseOf(DisclosureCategory.TOTAL_SCORE)).isEqualTo(ReleaseMoment.AFTER_COMPLETION);
        assertThat(disclosure.releaseOf(DisclosureCategory.RUBRIC)).isEqualTo(ReleaseMoment.NEVER);
        assertThat(disclosure.releaseOf(DisclosureCategory.GRADER_COMMENTS)).isEqualTo(ReleaseMoment.NEVER);
        assertThat(disclosure.releaseOf(DisclosureCategory.COHORT_STATISTICS)).isEqualTo(ReleaseMoment.NEVER);
    }

    static Stream<Arguments> approvedChoices() {
        return Stream.of(HintMode.DISABLED, HintMode.ON_REQUEST).flatMap(hint ->
                Stream.of(AnswerOrder.AUTHORED, AnswerOrder.SHUFFLED_PER_ATTEMPT).flatMap(order ->
                        Stream.of(0, 7000, 10000).map(threshold -> Arguments.of(hint, order, threshold))));
    }

    @ParameterizedTest(name = "hint={0}, optionOrder={1}, thresholdBasisPoints={2}")
    @MethodSource("approvedChoices")
    @DisplayName("policy values: when approved choices and numeric boundaries are supplied then they are retained")
    void retainsApprovedChoicesWithoutImplementingTheirRuntime(HintMode hint, AnswerOrder order, int threshold) {
        var input = fixture();
        input.hints = new HintPolicy(hint, HintSource.AUTHORED);
        input.answerOrdering = new AnswerOrderingPolicy(order, 1);
        input.results = result(true, threshold);
        var policy = input.build();
        assertThat(policy.hints().mode()).isEqualTo(hint);
        assertThat(policy.answerOrdering().mode()).isEqualTo(order);
        assertThat(policy.results().passThresholdBasisPoints()).isEqualTo(threshold);
        // This is value construction, not proof of shuffle, allowance or grading execution.
        assertThat(policy.content()).isEqualTo(fixture().content);
    }

    @Test
    @DisplayName("normalization: when collection order varies then the same immutable policy value results")
    void inputPermutationsHaveIdenticalNormalizedValues() {
        var original = fixture().build();
        for (int seed = 0; seed < 100; seed++) {
            var rules = new ArrayList<>(original.disclosure().rules());
            var content = new ArrayList<>(original.review().content());
            Collections.shuffle(rules, new Random(seed));
            Collections.shuffle(content, new Random(seed + 100));
            var input = fixture();
            input.disclosure = new DisclosurePolicy(rules);
            input.review = new ReviewPolicy(ReviewAvailability.AFTER_COMPLETION, content,
                    ReviewWindow.WHILE_ACCOUNT_AND_RESULT_RETAINED);
            var normalized = input.build();
            assertThat(normalized).isEqualTo(original).hasSameHashCodeAs(original);
            assertThat(normalized.disclosure().rules()).containsExactlyElementsOf(original.disclosure().rules());
            assertThat(normalized.review().content()).containsExactlyElementsOf(original.review().content());
        }
    }

    @Test
    @DisplayName("immutability: when the caller changes collections then the accepted value remains unchanged")
    void defensivelyCopiesEveryCollection() {
        var original = fixture().build();
        var rules = new ArrayList<>(original.disclosure().rules());
        var content = new ArrayList<>(original.review().content());
        var input = fixture();
        input.disclosure = new DisclosurePolicy(rules);
        input.review = new ReviewPolicy(ReviewAvailability.AFTER_COMPLETION, content,
                ReviewWindow.WHILE_ACCOUNT_AND_RESULT_RETAINED);
        var captured = input.build();
        rules.clear();
        content.clear();
        assertThat(captured).isEqualTo(original);
        assertThatThrownBy(() -> captured.disclosure().rules().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> captured.review().content().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @EnumSource(AnswerOrder.class)
    @DisplayName("symbolic values: when an exact supported name is parsed then its meaning is stable")
    void exactSymbolicNamesRoundTrip(AnswerOrder value) {
        assertThat(ResolvedAttemptPolicy.parseValue(AnswerOrder.class, value.name())).isSameAs(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    @DisplayName("schema: when a version is unknown then construction fails without choosing a fallback")
    void unknownSchemaFailsClosed(int version) {
        var input = fixture();
        input.schemaVersion = version;
        rejected(input::build, UNSUPPORTED_SCHEMA_VERSION);
    }

    static void rejected(Runnable action, PolicyValidationException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(PolicyValidationException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessage("Attempt policy rejected: " + reason).hasNoCause();
    }
}
