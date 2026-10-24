package uk.gegc.quizmaker.features.attempt.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.policy.PolicyFixtures.*;
import static uk.gegc.quizmaker.features.attempt.policy.ResolvedAttemptPolicyTest.rejected;

class PolicyValidationTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "authored", " AUTHORED", "AUTHORED ", "AUTHORED\n", "SEEDED", "1",
            "java.lang.Runtime", "private-answer-secret"})
    @DisplayName("symbolic values: when a name is unknown then the error never echoes it")
    void unknownNamesHaveTypedContentFreeFailures(String value) {
        rejected(() -> ResolvedAttemptPolicy.parseValue(AnswerOrder.class, value), UNKNOWN_VALUE);
    }

    @Test
    @DisplayName("symbolic values: when input is oversized then the failure retains no input or nested exception")
    void oversizedNamesHaveTheSameBoundedFailure() {
        String oversized = "private-input-".repeat(100_000);
        rejected(() -> ResolvedAttemptPolicy.parseValue(AnswerOrder.class, oversized), UNKNOWN_VALUE);
        assertThat(ResolvedAttemptPolicy.parseValue(AnswerOrder.class, "AUTHORED")).isEqualTo(AnswerOrder.AUTHORED);
    }

    @Test
    @DisplayName("vocabulary: when deferred modes are requested then there is no implicit activation")
    void deferredModesAndInvalidParserArgumentsFailClosed() {
        rejected(() -> ResolvedAttemptPolicy.parseValue(SelectionMode.class, "RANDOM_COUNT"), UNKNOWN_VALUE);
        rejected(() -> ResolvedAttemptPolicy.parseValue(DeliveryMode.class, "ONE_AT_A_TIME"), UNKNOWN_VALUE);
        rejected(() -> ResolvedAttemptPolicy.parseValue(TimingMode.class, "TOTAL_ATTEMPT"), UNKNOWN_VALUE);
        rejected(() -> ResolvedAttemptPolicy.parseValue(ParticipantMode.class, "GUEST"), UNKNOWN_VALUE);
        rejected(() -> ResolvedAttemptPolicy.parseValue(GradingMode.class, "PARTIAL_CREDIT"), UNKNOWN_VALUE);
        rejected(() -> ResolvedAttemptPolicy.parseValue(AnswerOrder.class, null), INVALID_VALUE);
        rejected(() -> ResolvedAttemptPolicy.<AnswerOrder>parseValue(null, "AUTHORED"), INVALID_VALUE);
        rejected(() -> ResolvedAttemptPolicy.parseValue(QuestionType.class, "MCQ_SINGLE"), INVALID_VALUE);
        rejected(() -> fixture().disclosure.releaseOf(null), INVALID_VALUE);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    @DisplayName("versions: when content or strategy version is unknown then no latest-version fallback occurs")
    void versionReferencesAreExact(int version) {
        var content = fixture().content;
        rejected(() -> new ContentPolicy(content.quizRevisionId(), version, content.formMode(), content.sections(), content.cases()),
                UNSUPPORTED_CONTENT_VERSION);
        rejected(() -> new SelectionPolicy(SelectionMode.ALL, version), UNSUPPORTED_STRATEGY_VERSION);
        rejected(() -> new QuestionOrderingPolicy(QuestionOrder.AUTHORED, version), UNSUPPORTED_STRATEGY_VERSION);
        rejected(() -> new AnswerOrderingPolicy(AnswerOrder.AUTHORED, version), UNSUPPORTED_STRATEGY_VERSION);
        rejected(() -> new AnswerOrderingPolicy(AnswerOrder.SHUFFLED_PER_ATTEMPT, version), UNSUPPORTED_STRATEGY_VERSION);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 10001, Integer.MAX_VALUE})
    @DisplayName("score units: when threshold exceeds the basis-point range then it is rejected without clamping")
    void thresholdDoesNotOverflowClampOrUsePercentUnits(int value) {
        rejected(() -> result(true, value), INVALID_VALUE);
    }

    @Test
    @DisplayName("answer locking: when feedback follows acceptance then editing accepted answers is rejected")
    void answerOracleCombinationIsRejected() {
        var input = fixture();
        var valid = input.build();
        input.answerChange = new AnswerChangePolicy(AnswerChangeMode.EDIT_UNTIL_FINALIZATION);
        rejected(input::build, INVALID_COMBINATION);
        input.answerChange = valid.answerChange();
        assertThat(input.build()).isEqualTo(valid);
    }

    @Test
    @DisplayName("answer changes: when feedback is withheld then deferred editing still cannot be enabled")
    void disablingFeedbackDoesNotActivateEditableAnswers() {
        var input = fixture();
        input.answerChange = new AnswerChangePolicy(AnswerChangeMode.EDIT_UNTIL_FINALIZATION);
        input.disclosure = new DisclosurePolicy(input.disclosure.rules().stream()
                .map(rule -> new DisclosureRule(rule.category(), ReleaseMoment.NEVER)).toList());
        rejected(input::build, UNSUPPORTED_CAPABILITY);
    }

    @Test
    @DisplayName("submission: when autosave lacks drafts or a deferred capability is requested then construction fails")
    void rejectsContradictionsAndDeferredSubmissionCapabilities() {
        var input = fixture();
        input.submission = submission(false, true, false, true);
        rejected(input::build, INVALID_COMBINATION);
        input.submission = submission(true, false, false, true);
        rejected(input::build, UNSUPPORTED_CAPABILITY);
        input.submission = submission(true, true, false, true);
        rejected(input::build, UNSUPPORTED_CAPABILITY);
        input.submission = submission(false, false, true, true);
        rejected(input::build, UNSUPPORTED_COMBINATION);
        input.submission = submission(false, false, false, false);
        rejected(input::build, UNSUPPORTED_COMBINATION);
        input.submission = submission(false, false, false, true);
        assertThat(input.build()).isEqualTo(fixture().build());
    }

    static Stream<Arguments> differentReleaseRules() {
        var valid = fixture().disclosure;
        return Arrays.stream(DisclosureCategory.values()).flatMap(category -> Arrays.stream(ReleaseMoment.values())
                .filter(moment -> moment != valid.releaseOf(category)).map(moment -> Arguments.of(category, moment)));
    }

    @ParameterizedTest(name = "category={0}, rejectedMoment={1}")
    @MethodSource("differentReleaseRules")
    @DisplayName("disclosure: when an initial rule is changed then no other policy is silently accepted")
    void unsupportedReleaseCombinationsFailClosed(DisclosureCategory category, ReleaseMoment moment) {
        var input = fixture();
        input.disclosure = new DisclosurePolicy(input.disclosure.rules().stream()
                .map(rule -> rule.category() == category ? new DisclosureRule(category, moment) : rule).toList());
        rejected(input::build, UNSUPPORTED_COMBINATION);
    }

    @Test
    @DisplayName("disclosure completeness: when categories are missing duplicated null or oversized then input is rejected")
    void rejectsMalformedDisclosureCollections() {
        var rules = fixture().disclosure.rules();
        rejected(() -> new DisclosurePolicy(rules.subList(0, rules.size() - 1)), INVALID_VALUE);
        var duplicate = new ArrayList<>(rules);
        duplicate.set(1, duplicate.get(0));
        rejected(() -> new DisclosurePolicy(duplicate), INVALID_VALUE);
        var oversized = new ArrayList<>(rules);
        oversized.add(rules.get(0));
        rejected(() -> new DisclosurePolicy(oversized), INVALID_VALUE);
        var withNull = new ArrayList<>(rules);
        withNull.set(0, null);
        rejected(() -> new DisclosurePolicy(withNull), INVALID_VALUE);
    }

    @Test
    @DisplayName("review: when content is malformed or partial then the full-review contract is preserved")
    void rejectsMalformedAndUnsupportedReviewWithoutInventingRetention() {
        var input = fixture();
        rejected(() -> review(List.of(ReviewCategory.CORRECTNESS, ReviewCategory.CORRECTNESS)), INVALID_VALUE);
        rejected(() -> review(Arrays.asList(ReviewCategory.CORRECTNESS, null)), INVALID_VALUE);
        rejected(() -> review(CollectionsForTest.oversizedReview()), INVALID_VALUE);
        input.review = review(List.of());
        rejected(input::build, UNSUPPORTED_COMBINATION);
        input.review = review(List.of(ReviewCategory.SUBMITTED_ANSWERS));
        rejected(input::build, UNSUPPORTED_COMBINATION);
        input.review = fixture().review;
        input.results = result(false, 7000);
        rejected(input::build, UNSUPPORTED_COMBINATION);
    }

    static Stream<Arguments> requiredReferenceComponents() throws ReflectiveOperationException {
        var policy = fixture().build();
        var records = new ArrayList<Object>();
        records.add(policy);
        for (RecordComponent component : ResolvedAttemptPolicy.class.getRecordComponents()) {
            if (!component.getType().isPrimitive()) records.add(component.getAccessor().invoke(policy));
        }
        records.add(policy.disclosure().rules().get(0));
        var arguments = new ArrayList<Arguments>();
        for (Object record : records) {
            var components = record.getClass().getRecordComponents();
            for (int index = 0; index < components.length; index++) {
                if (!components[index].getType().isPrimitive()) {
                    arguments.add(Arguments.of(record, index, record.getClass().getSimpleName() + "." + components[index].getName()));
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "required={2}")
    @MethodSource("requiredReferenceComponents")
    @DisplayName("required fields: when any reference is null then the boundary returns a stable safe error")
    void allRequiredComponentsRejectNull(Object record, int nullIndex, String description) throws Exception {
        var components = record.getClass().getRecordComponents();
        var types = Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        var values = new Object[components.length];
        for (int index = 0; index < components.length; index++) values[index] = components[index].getAccessor().invoke(record);
        values[nullIndex] = null;
        assertThatThrownBy(() -> record.getClass().getDeclaredConstructor(types).newInstance(values))
                .as(description).isInstanceOf(InvocationTargetException.class)
                .satisfies(failure -> assertThat(failure.getCause())
                        .isInstanceOfSatisfying(PolicyValidationException.class,
                                invalid -> assertThat(invalid.reason()).isEqualTo(INVALID_VALUE))
                        .hasMessage("Attempt policy rejected: INVALID_VALUE").hasNoCause());
    }

    private static ReviewPolicy review(List<ReviewCategory> categories) {
        return new ReviewPolicy(ReviewAvailability.AFTER_COMPLETION, categories, ReviewWindow.WHILE_ACCOUNT_AND_RESULT_RETAINED);
    }

    private static final class CollectionsForTest {
        static List<ReviewCategory> oversizedReview() {
            return List.of(ReviewCategory.SUBMITTED_ANSWERS, ReviewCategory.CORRECTNESS,
                    ReviewCategory.CORRECT_ANSWERS, ReviewCategory.EXPLANATIONS, ReviewCategory.SUBMITTED_ANSWERS);
        }
    }
}
