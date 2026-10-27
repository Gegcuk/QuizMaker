package uk.gegc.quizmaker.features.attempt.selection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gegc.quizmaker.features.attempt.application.QuestionSelectionService;
import uk.gegc.quizmaker.features.attempt.application.impl.QuestionSelectionServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.selection.QuestionSelectionFixtures.*;

class QuestionSelectionServiceTest {
    private final QuestionSelectionService service = new QuestionSelectionServiceImpl();

    @Test
    void all_whenMetadataIsUnordered_returnsEveryEligibleOccurrenceInAuthoredOrderWithExactEvidence() {
        var result = service.select(input(Configuration.all()));

        assertThat(result).isEqualTo(new Result(1, new Evidence(1, 1, REVISION, 1,
                new Configuration(1, Strategy.ALL, 1, List.of())), ORDERED));
        assertThat(result.itemIds()).doesNotContain(FOREIGN);
    }

    @Test
    void manualSet_whenRequestOrderDiffers_returnsOnlyTheExactSubsetInRevisionOrder() {
        var result = service.select(input(Configuration.manualSet(List.of(LAST, FIRST))));

        assertThat(result.itemIds()).containsExactly(FIRST, LAST);
        assertThat(result.evidence()).isEqualTo(new Evidence(1, 1, REVISION, 1,
                new Configuration(1, Strategy.MANUAL_SET, 1, List.of(FIRST, LAST))));
        assertThat(result.toString()).doesNotContain(MIDDLE.toString(), FOREIGN.toString());
        assertThat(service.select(input(Configuration.manualSet(List.of(MIDDLE)))).itemIds()).containsExactly(MIDDLE);
    }

    @Test
    void equivalentInputPermutations_whenSelected_reproduceOneGoldenResultAndConfiguration() {
        var expected = service.select(input(Configuration.manualSet(List.of(FIRST, LAST))));
        for (var metadata : permutations(ITEMS)) {
            for (var ids : permutations(List.of(LAST, FIRST))) {
                var request = new Input(1, REVISION, 1, Configuration.manualSet(ids), metadata, List.of());
                assertThat(service.select(request)).isEqualTo(expected);
            }
            assertThat(service.select(new Input(1, REVISION, 1, Configuration.all(), metadata, List.of())).itemIds())
                    .containsExactlyElementsOf(ORDERED);
        }
    }

    @Test
    void largerPermutationFixtures_whenMetadataChangesIterationOrder_preserveExactAllAndManualSets() {
        var metadata = new ArrayList<Item>();
        var allIds = new ArrayList<UUID>();
        var manualIds = new ArrayList<UUID>();
        for (int index = 0; index < 257; index++) {
            var id = new UUID(0, 1000 - index);
            metadata.add(new Item(id, index == 256 ? Integer.MAX_VALUE : index * 3));
            allIds.add(id);
            if (index % 3 == 0) manualIds.add(id);
        }
        var expectedManual = List.copyOf(manualIds);
        var random = new Random(544);
        for (int repeat = 0; repeat < 32; repeat++) {
            Collections.shuffle(metadata, random);
            Collections.shuffle(manualIds, random);
            assertThat(service.select(new Input(1, REVISION, 1, Configuration.all(), metadata, List.of())).itemIds())
                    .containsExactlyElementsOf(allIds);
            assertThat(service.select(new Input(1, REVISION, 1, Configuration.manualSet(manualIds), metadata, List.of())).itemIds())
                    .containsExactlyElementsOf(expectedManual);
        }
    }

    @ParameterizedTest
    @EnumSource(Strategy.class)
    void emptyEligibleRevision_whenEmptySelectionRequested_returnsVersionedEmptyResult(Strategy strategy) {
        var configuration = new Configuration(1, strategy, 1, List.of());
        var result = service.select(new Input(1, REVISION, 1, configuration, List.of(), List.of()));

        assertThat(result).isEqualTo(new Result(1, new Evidence(1, 1, REVISION, 1, configuration), List.of()));
    }

    @Test
    void emptyManualSet_whenEligibleItemsAndAtomicGroupsExist_selectsNothingWithoutInferringStartEligibility() {
        var result = service.select(input(Configuration.manualSet(List.of()), List.of(group(FIRST, MIDDLE))));

        assertThat(result.itemIds()).isEmpty();
        assertThat(result.evidence().configuration().strategy()).isEqualTo(Strategy.MANUAL_SET);
    }

    @Test
    void eligibleSubset_whenAllSelected_doesNotAddMissingBankMembers() {
        var request = new Input(1, REVISION, 1, Configuration.all(), List.of(ITEMS.get(1)), List.of());

        assertThat(service.select(request).itemIds()).containsExactly(FIRST);
        assertThat(service.select(request).toString()).doesNotContain(MIDDLE.toString(), LAST.toString());
    }

    @Test
    void atomicGroup_whenSelectingAllOrNone_acceptsBothThroughTheSameSelectionBoundary() {
        var groups = List.of(group(FIRST, MIDDLE));

        assertThat(service.select(input(Configuration.all(), groups)).itemIds()).containsExactlyElementsOf(ORDERED);
        assertThat(service.select(input(Configuration.manualSet(List.of(MIDDLE, FIRST)), groups)).itemIds())
                .containsExactly(FIRST, MIDDLE);
        assertThat(service.select(input(Configuration.manualSet(List.of(LAST)), groups)).itemIds()).containsExactly(LAST);
    }

    @Test
    void partialAtomicGroup_whenRequested_rejectsWithoutExpandingOrSilentlyDroppingTheSelection() {
        var groups = List.of(group(FIRST, MIDDLE));
        var partial = input(Configuration.manualSet(List.of(LAST, FIRST)), groups);

        assertThatThrownBy(() -> service.select(partial))
                .isInstanceOfSatisfying(QuestionSelectionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(CONSTRAINT_VIOLATION))
                .hasMessage("Question selection rejected: CONSTRAINT_VIOLATION");
        assertThat(partial.configuration().manualItemIds()).containsExactly(LAST, FIRST);
        assertThat(service.select(input(Configuration.manualSet(List.of(FIRST, MIDDLE)), groups)).itemIds())
                .containsExactly(FIRST, MIDDLE);
    }

    @Test
    void overlappingSuppliedGroups_whenSelected_eachMustBeWholeWithoutInventingCaseAuthoringRules() {
        var groups = List.of(group(FIRST, MIDDLE), group(MIDDLE, LAST));

        assertThat(service.select(input(Configuration.all(), groups)).itemIds()).containsExactlyElementsOf(ORDERED);
        assertThat(service.select(input(Configuration.manualSet(List.of()), groups)).itemIds()).isEmpty();
        assertThatThrownBy(() -> service.select(input(Configuration.manualSet(List.of(FIRST, MIDDLE)), groups)))
                .isInstanceOf(QuestionSelectionException.class)
                .hasMessage("Question selection rejected: CONSTRAINT_VIOLATION");
    }

    @Test
    void equivalentGroupAndMemberPermutations_whenSelected_preserveResultAndEvidence() {
        var expected = service.select(input(Configuration.all(), List.of(group(FIRST, MIDDLE), group(LAST))));

        assertThat(service.select(input(Configuration.all(), List.of(group(LAST), group(MIDDLE, FIRST)))))
                .isEqualTo(expected);
    }

    @Test
    void mutableCallerLists_whenChangedAfterConstruction_cannotAlterCapturedInputOrReturnedResult() {
        var manual = new ArrayList<>(List.of(LAST, FIRST, MIDDLE));
        var metadata = new ArrayList<>(ITEMS);
        var members = new ArrayList<>(List.of(MIDDLE, FIRST));
        var group = new AtomicGroup(1, members);
        var groups = new ArrayList<>(List.of(group));
        var configuration = Configuration.manualSet(manual);
        var request = new Input(1, REVISION, 1, configuration, metadata, groups);
        manual.clear(); metadata.clear(); members.clear(); groups.clear();

        var result = service.select(request);
        assertThat(result.itemIds()).containsExactlyElementsOf(ORDERED);
        assertThat(request.eligibleItems()).hasSize(3);
        assertThat(request.atomicGroups()).containsExactly(group);
        assertThat(group.itemIds()).containsExactly(MIDDLE, FIRST);
        assertThatThrownBy(() -> request.eligibleItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> request.atomicGroups().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> configuration.manualItemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> group.itemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.itemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        var returned = new ArrayList<>(result.itemIds());
        var copied = new Result(1, result.evidence(), returned);
        returned.clear();
        assertThat(copied).isEqualTo(result);
    }

    @Test
    void rejectedCall_whenFollowedByValidCalls_doesNotRetainOrPartiallyModifySelectionState() {
        var valid = input(Configuration.all());
        var expected = service.select(valid);
        assertThatThrownBy(() -> service.select(input(Configuration.manualSet(List.of(FIRST)), List.of(group(FIRST, MIDDLE)))))
                .isInstanceOf(QuestionSelectionException.class);
        assertThatThrownBy(() -> input(Configuration.manualSet(List.of(FOREIGN))))
                .isInstanceOf(QuestionSelectionException.class);

        assertThat(new QuestionSelectionServiceImpl().select(valid)).isEqualTo(expected);
        assertThat(service.select(valid)).isEqualTo(expected);
        assertThat(valid.eligibleItems()).extracting(Item::id).containsExactlyElementsOf(ORDERED);
    }

    @Test
    void missingInput_whenServiceCalled_returnsBoundedTypedFailure() {
        assertThatThrownBy(() -> service.select(null)).isInstanceOfSatisfying(QuestionSelectionException.class,
                failure -> assertThat(failure.reason()).isEqualTo(INVALID_INPUT))
                .hasMessage("Question selection rejected: INVALID_INPUT");
    }

    @Test
    void requestDiagnostic_whenRendered_omitsRevisionIdentityAndEligibleBankMembership() {
        assertThat(input(Configuration.manualSet(List.of(FIRST)), List.of(group(MIDDLE, LAST))).toString())
                .isEqualTo("QuestionSelection.Input[version=1, eligibleItemCount=3, atomicGroupCount=1]")
                .doesNotContain(REVISION.toString(), FIRST.toString(), MIDDLE.toString(), LAST.toString());
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
}
