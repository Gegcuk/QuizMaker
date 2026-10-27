package uk.gegc.quizmaker.features.attempt.selection;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.selection.QuestionSelectionFixtures.*;

class QuestionSelectionContractTest {
    private static final Configuration ALL = Configuration.all();
    private static final Evidence EVIDENCE = new Evidence(1, 1, REVISION, 1, ALL);

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownContractVersions_whenConstructed_failClosedAtEveryVersionedBoundary(int version) {
        rejected(() -> new Configuration(version, Strategy.ALL, 1, List.of()), UNSUPPORTED_VERSION);
        rejected(() -> new Input(version, REVISION, 1, ALL, ITEMS, List.of()), UNSUPPORTED_VERSION);
        rejected(() -> new AtomicGroup(version, List.of(FIRST)), UNSUPPORTED_VERSION);
        rejected(() -> new Evidence(version, 1, REVISION, 1, ALL), UNSUPPORTED_VERSION);
        rejected(() -> new Evidence(1, version, REVISION, 1, ALL), UNSUPPORTED_VERSION);
        rejected(() -> new Result(version, EVIDENCE, ORDERED), UNSUPPORTED_VERSION);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownRevisionVersion_whenInputOrEvidenceConstructed_rejectsInsteadOfAssumingV1(int version) {
        rejected(() -> new Input(1, REVISION, version, ALL, ITEMS, List.of()), UNSUPPORTED_REVISION_VERSION);
        rejected(() -> new Evidence(1, 1, REVISION, version, ALL), UNSUPPORTED_REVISION_VERSION);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unsupportedStrategyVersion_whenConfigured_rejectsBothKnownStrategies(int version) {
        rejected(() -> new Configuration(1, Strategy.ALL, version, List.of()), UNSUPPORTED_STRATEGY_VERSION);
        rejected(() -> new Configuration(1, Strategy.MANUAL_SET, version, List.of(FIRST)), UNSUPPORTED_STRATEGY_VERSION);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "all", "manual_set", " ALL", "ALL ", "RANDOM_COUNT", "MANUAL", "private-token\nprivate-answer"})
    void unknownStrategy_whenParsed_hasNoAliasFallbackOrInputEcho(String value) {
        rejected(() -> Strategy.parse(value), UNKNOWN_STRATEGY);
    }

    @Test
    void knownStrategy_whenParsed_acceptsOnlyTheExactStableIdentifier() {
        assertThat(Strategy.parse("ALL")).isEqualTo(Strategy.ALL);
        assertThat(Strategy.parse("MANUAL_SET")).isEqualTo(Strategy.MANUAL_SET);
        assertThatThrownBy(() -> Strategy.parse("secret".repeat(20_000)))
                .hasMessage("Question selection rejected: UNKNOWN_STRATEGY");
    }

    @Test
    void invalidConfiguration_whenConstructed_rejectsMissingStrategyOrUnusedManualIds() {
        rejected(() -> new Configuration(1, null, 1, List.of()), INVALID_CONFIGURATION);
        rejected(() -> new Configuration(1, Strategy.ALL, 1, List.of(FIRST)), INVALID_CONFIGURATION);
        rejected(() -> Configuration.manualSet(null), INVALID_INPUT);
        rejected(() -> Configuration.manualSet(Arrays.asList(FIRST, null)), INVALID_ITEM);
        rejected(() -> Configuration.manualSet(List.of(FIRST, FIRST)), DUPLICATE_ITEM);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {Integer.MIN_VALUE, -1})
    void missingOrNegativePosition_whenItemConstructed_rejectsRatherThanGuessingOrder(Integer position) {
        rejected(() -> new Item(FIRST, position), INVALID_ITEM);
    }

    @Test
    void missingMetadata_whenInputConstructed_returnsBoundedTypedFailure() {
        rejected(() -> new Item(null, 0), INVALID_ITEM);
        rejected(() -> new Input(1, null, 1, ALL, ITEMS, List.of()), INVALID_INPUT);
        rejected(() -> new Input(1, REVISION, 1, null, ITEMS, List.of()), INVALID_INPUT);
        rejected(() -> new Input(1, REVISION, 1, ALL, null, List.of()), INVALID_INPUT);
        rejected(() -> new Input(1, REVISION, 1, ALL, ITEMS, null), INVALID_INPUT);
        rejected(() -> new Input(1, REVISION, 1, ALL, Arrays.asList((Item) null), List.of()), INVALID_ITEM);
    }

    @Test
    void duplicateOccurrenceOrAuthoredPosition_whenMetadataSupplied_rejectsAmbiguityEvenOutsideManualSubset() {
        rejected(() -> new Input(1, REVISION, 1, ALL,
                List.of(new Item(FIRST, 0), new Item(FIRST, 1)), List.of()), DUPLICATE_ITEM);
        rejected(() -> new Input(1, REVISION, 1, Configuration.manualSet(List.of(FIRST)),
                List.of(new Item(FIRST, 0), new Item(MIDDLE, 1), new Item(LAST, 1)), List.of()), DUPLICATE_POSITION);
    }

    @Test
    void unknownOrIneligibleManualMember_whenComparedWithExactEligibleMetadata_rejectsBeforeSelection() {
        rejected(() -> input(Configuration.manualSet(List.of(FIRST, FOREIGN))), UNKNOWN_ITEM);
        rejected(() -> new Input(1, REVISION, 1, Configuration.manualSet(List.of(MIDDLE)),
                List.of(new Item(FIRST, 1)), List.of()), UNKNOWN_ITEM);
        rejected(() -> new Input(1, REVISION, 1, Configuration.manualSet(List.of(FIRST)), List.of(), List.of()), UNKNOWN_ITEM);
    }

    @Test
    void invalidAtomicMetadata_whenSupplied_rejectsEmptyDuplicateNullOrUnavailableMembers() {
        rejected(() -> new AtomicGroup(1, List.of()), INVALID_CONSTRAINT);
        rejected(() -> new AtomicGroup(1, null), INVALID_INPUT);
        rejected(() -> new AtomicGroup(1, Arrays.asList(FIRST, null)), INVALID_ITEM);
        rejected(() -> group(FIRST, FIRST), DUPLICATE_ITEM);
        rejected(() -> input(ALL, Arrays.asList((AtomicGroup) null)), INVALID_CONSTRAINT);
        rejected(() -> input(ALL, List.of(group(FIRST, FOREIGN))), INVALID_CONSTRAINT);
        rejected(() -> input(ALL, List.of(group(FIRST, MIDDLE), group(MIDDLE, FIRST))), INVALID_CONSTRAINT);
        rejected(() -> new Input(1, REVISION, 1, ALL, List.of(new Item(FIRST, 1)),
                List.of(group(FIRST, MIDDLE))), INVALID_CONSTRAINT);
    }

    @Test
    void equivalentManualConfigurations_whenConstructed_haveIdenticalImmutableCanonicalMetadata() {
        assertThat(Configuration.manualSet(List.of(LAST, FIRST, MIDDLE)))
                .isEqualTo(Configuration.manualSet(List.of(MIDDLE, LAST, FIRST)));
        assertThat(group(FIRST, LAST)).isEqualTo(group(LAST, FIRST));
    }

    @Test
    void malformedEvidenceOrResult_whenConstructed_cannotRepresentUnknownIdentityOrAmbiguousSelection() {
        rejected(() -> new Evidence(1, 1, null, 1, ALL), INVALID_INPUT);
        rejected(() -> new Evidence(1, 1, REVISION, 1, null), INVALID_INPUT);
        rejected(() -> new Result(1, null, ORDERED), INVALID_RESULT);
        rejected(() -> new Result(1, EVIDENCE, null), INVALID_INPUT);
        rejected(() -> new Result(1, EVIDENCE, Arrays.asList(FIRST, null)), INVALID_ITEM);
        rejected(() -> new Result(1, EVIDENCE, List.of(FIRST, FIRST)), DUPLICATE_ITEM);
        var manual = new Evidence(1, 1, REVISION, 1, Configuration.manualSet(List.of(FIRST, LAST)));
        rejected(() -> new Result(1, manual, List.of(FIRST)), INVALID_RESULT);
        rejected(() -> new Result(1, manual, List.of(FIRST, LAST, FOREIGN)), INVALID_RESULT);
        assertThat(new Result(1, manual, List.of(FIRST, LAST)).itemIds()).containsExactly(FIRST, LAST);
    }

    private static void rejected(ThrowingCallable action, Reason reason) {
        assertThatThrownBy(action).isInstanceOfSatisfying(QuestionSelectionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessage("Question selection rejected: " + reason)
                .hasNoCause();
    }
}
