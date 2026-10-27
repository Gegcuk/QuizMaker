package uk.gegc.quizmaker.features.attempt.ordering;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder.*;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.QuestionOrderingException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.PolicyValidationException;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.QuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.QuestionOrderingPolicy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.QuestionOrderingException.Reason.*;

class AuthoredQuestionOrderTest {
    private static final UUID REVISION = new UUID(0, 900);
    private static final UUID FIRST = new UUID(0, 300);
    private static final UUID SECOND = new UUID(0, 200);
    private static final UUID LAST = new UUID(0, 100);
    private static final QuestionOrderingPolicy POLICY = new QuestionOrderingPolicy(QuestionOrder.AUTHORED, 1);
    private static final List<Item> ITEMS = List.of(new Item(SECOND, 3), new Item(LAST, 7), new Item(FIRST, 1));
    private static final List<UUID> SELECTED = List.of(LAST, FIRST, SECOND);

    @Test
    void authoredOrder_whenPositionsHaveGaps_returnsExactGoldenIdsAndVersionedEvidence() {
        var input = input(ITEMS, SELECTED);
        var expected = new Result(new Evidence(1, REVISION, 1, POLICY), List.of(FIRST, SECOND, LAST));

        assertThat(AuthoredQuestionOrder.order(input)).isEqualTo(expected);
        assertThat(AuthoredQuestionOrder.order(input)).isEqualTo(expected);
        assertThat(input.revisionItems()).isEqualTo(ITEMS);
        assertThat(input.selectedItemIds()).isEqualTo(SELECTED);
    }

    @Test
    void inputPermutations_whenMetadataAndSelectionIterateDifferently_preserveOneGoldenOrder() {
        for (var items : permutations(ITEMS)) {
            for (var selected : permutations(SELECTED)) {
                assertThat(AuthoredQuestionOrder.order(input(items, selected)))
                        .isEqualTo(new Result(new Evidence(1, REVISION, 1, POLICY), List.of(FIRST, SECOND, LAST)));
            }
        }
    }

    @Test
    void largerPermutations_whenPositionsSpanIntegerRange_preserveAllExactIdentitiesWithoutOverflow() {
        var metadata = new ArrayList<Item>();
        var selected = new ArrayList<UUID>();
        for (int i = 0; i < 128; i++) {
            UUID id = new UUID(0, 128 - i);
            metadata.add(new Item(id, i == 127 ? Integer.MAX_VALUE : i * 11));
            selected.add(id);
        }
        var expected = List.copyOf(selected);
        var random = new Random(520);
        for (int repeat = 0; repeat < 50; repeat++) {
            Collections.shuffle(metadata, random);
            Collections.shuffle(selected, random);
            assertThat(AuthoredQuestionOrder.order(input(metadata, selected)).itemIds()).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void selectedSubset_whenSelectionOrderDiffers_ordersOnlySuppliedMembersWithoutSelectingOthers() {
        assertThat(AuthoredQuestionOrder.order(input(ITEMS, List.of(LAST, FIRST))).itemIds()).containsExactly(FIRST, LAST);
        assertThat(AuthoredQuestionOrder.order(input(ITEMS, List.of(SECOND))).itemIds()).containsExactly(SECOND);
    }

    @Test
    void emptySelection_whenRevisionIsEmptyOrNonempty_returnsEmptyOrderWithoutDecidingStartEligibility() {
        assertThat(AuthoredQuestionOrder.order(input(List.of(), List.of())).itemIds()).isEmpty();
        assertThat(AuthoredQuestionOrder.order(input(ITEMS, List.of())).itemIds()).isEmpty();
    }

    @Test
    void defensiveCopies_whenCallerChangesOriginalLists_inputAndResultRemainUnchanged() {
        var items = new ArrayList<>(ITEMS);
        var selected = new ArrayList<>(SELECTED);
        var input = input(items, selected);
        items.clear();
        selected.clear();
        var result = AuthoredQuestionOrder.order(input);

        assertThat(result.itemIds()).containsExactly(FIRST, SECOND, LAST);
        assertThatThrownBy(() -> input.revisionItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> input.selectedItemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.itemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        var resultIds = new ArrayList<>(result.itemIds());
        var copied = new Result(result.evidence(), resultIds);
        resultIds.clear();
        assertThat(copied).isEqualTo(result);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownOrderingVersion_whenInputOrEvidenceIsCreated_rejectsWithoutFallback(int version) {
        rejected(() -> new Input(version, REVISION, 1, POLICY, ITEMS, SELECTED), UNSUPPORTED_VERSION);
        rejected(() -> new Evidence(version, REVISION, 1, POLICY), UNSUPPORTED_VERSION);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownRevisionVersion_whenInputOrEvidenceIsCreated_rejectsWithoutLegacyReconstruction(int version) {
        rejected(() -> new Input(1, REVISION, version, POLICY, ITEMS, SELECTED), UNSUPPORTED_REVISION_VERSION);
        rejected(() -> new Evidence(1, REVISION, version, POLICY), UNSUPPORTED_REVISION_VERSION);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownAlgorithmVersion_whenExistingPolicyBoundaryIsUsed_rejectsBeforeOrdering(int version) {
        assertThatThrownBy(() -> new QuestionOrderingPolicy(QuestionOrder.AUTHORED, version))
                .isInstanceOfSatisfying(PolicyValidationException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(PolicyValidationException.Reason.UNSUPPORTED_STRATEGY_VERSION));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {Integer.MIN_VALUE, -1})
    void missingOrNegativePosition_whenItemIsCreated_rejectsExplicitly(Integer position) {
        rejected(() -> new Item(FIRST, position), INVALID_ITEM);
    }

    @Test
    void duplicatePositions_whenIdsDiffer_rejectsInsteadOfBreakingTiesByUuid() {
        rejected(() -> input(List.of(new Item(FIRST, 1), new Item(SECOND, 1)), List.of(FIRST, SECOND)), DUPLICATE_POSITION);
    }

    @Test
    void duplicateItemIdentity_whenPositionsDiffer_rejectsAmbiguousSourceMetadata() {
        rejected(() -> input(List.of(new Item(FIRST, 1), new Item(FIRST, 3)), List.of(FIRST)), DUPLICATE_ITEM);
    }

    @Test
    void duplicateSelectedIdentity_whenSourceIsValid_rejectsRatherThanSilentlyDeduplicating() {
        rejected(() -> input(ITEMS, List.of(FIRST, FIRST)), DUPLICATE_ITEM);
    }

    @Test
    void foreignOrUnknownItem_whenNotInRevision_rejectsWithoutInventingAPosition() {
        rejected(() -> input(ITEMS, List.of(new UUID(0, 999))), UNKNOWN_ITEM);
        rejected(() -> input(List.of(), List.of(FIRST)), UNKNOWN_ITEM);
    }

    @Test
    void invalidUnselectedMetadata_whenSelectionItselfIsValid_stillRejectsAmbiguousRevision() {
        rejected(() -> input(List.of(new Item(FIRST, 1), new Item(SECOND, 3), new Item(LAST, 3)), List.of(FIRST)),
                DUPLICATE_POSITION);
    }

    @Test
    void missingInputOrMetadata_whenBoundaryIsCalled_returnsBoundedTypedFailures() {
        rejected(() -> AuthoredQuestionOrder.order(null), INVALID_INPUT);
        rejected(() -> new Input(1, null, 1, POLICY, ITEMS, SELECTED), INVALID_INPUT);
        rejected(() -> new Input(1, REVISION, 1, null, ITEMS, SELECTED), INVALID_INPUT);
        rejected(() -> input(null, SELECTED), INVALID_INPUT);
        rejected(() -> input(ITEMS, null), INVALID_INPUT);
        rejected(() -> input(Arrays.asList((Item) null), List.of()), INVALID_ITEM);
        rejected(() -> input(ITEMS, Arrays.asList((UUID) null)), INVALID_ITEM);
        rejected(() -> new Item(null, 0), INVALID_ITEM);
    }

    @Test
    void invalidResult_whenConstructedDirectly_cannotContainNullsOrDuplicateIdentities() {
        var evidence = new Evidence(1, REVISION, 1, POLICY);
        rejected(() -> new Evidence(1, null, 1, POLICY), INVALID_INPUT);
        rejected(() -> new Evidence(1, REVISION, 1, null), INVALID_INPUT);
        rejected(() -> new Result(null, SELECTED), INVALID_INPUT);
        rejected(() -> new Result(evidence, null), INVALID_INPUT);
        rejected(() -> new Result(evidence, List.of(FIRST, FIRST)), DUPLICATE_ITEM);
        rejected(() -> new Result(evidence, Arrays.asList((UUID) null)), INVALID_ITEM);
    }

    @Test
    void rejectedCall_whenFollowedByValidCall_doesNotMutateInputOrRetainPartialOrder() {
        var valid = input(ITEMS, SELECTED);
        var expected = AuthoredQuestionOrder.order(valid);
        rejected(() -> input(ITEMS, List.of(FIRST, new UUID(0, 999))), UNKNOWN_ITEM);
        assertThat(AuthoredQuestionOrder.order(valid)).isEqualTo(expected);
        assertThat(valid.revisionItems()).containsExactlyElementsOf(ITEMS);
        assertThat(valid.selectedItemIds()).containsExactlyElementsOf(SELECTED);
    }

    private static Input input(List<Item> items, List<UUID> selected) {
        return new Input(1, REVISION, 1, POLICY, items, selected);
    }

    private static <T> List<List<T>> permutations(List<T> values) {
        if (values.isEmpty()) return List.of(List.of());
        var result = new ArrayList<List<T>>();
        for (int index = 0; index < values.size(); index++) {
            var remainder = new ArrayList<>(values);
            T first = remainder.remove(index);
            for (var tail : permutations(remainder)) {
                var permutation = new ArrayList<T>();
                permutation.add(first);
                permutation.addAll(tail);
                result.add(List.copyOf(permutation));
            }
        }
        return result;
    }

    private static void rejected(Runnable action, QuestionOrderingException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(QuestionOrderingException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(reason))
                .hasMessage("Question ordering rejected: " + reason).hasNoCause();
    }
}
