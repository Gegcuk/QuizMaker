package uk.gegc.quizmaker.features.attempt.form;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppend.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendPlanner.plan;
import static uk.gegc.quizmaker.features.attempt.form.ProgressiveFormAppendFixtures.*;
import static uk.gegc.quizmaker.features.attempt.form.ProgressiveFormAppendTest.rejected;

class ProgressiveFormAppendPlannerTest {
    @ParameterizedTest
    @EnumSource(DecisionKind.class)
    void firstAndLaterAppendsPreserveDecisionOrderWithoutChangingThePrefix(DecisionKind kind) {
        // Given: source order need not equal the selected progressive presentation order.
        var first = new Request(1, REFERENCE, 0, new Decision(1, kind, id("first"), id("evidence-first")),
                List.of(item(3, 0), item(1, 1)));
        var empty = state(0);

        var proposal = plan(empty, REVISION, first, Optional.empty());

        assertThat(proposal.outcome()).isEqualTo(Outcome.APPEND);
        assertThat(proposal.itemsToAppend()).containsExactly(item(3, 0), item(1, 1));
        assertThat(proposal.receipt()).isEqualTo(new Receipt(1, first, 1));
        assertThat(empty.items()).isEmpty();
        assertThat(empty.formVersion()).isZero();

        // A supplied metadata observation is not a simulated database commit.
        var observed = state(1, item(3, 0), item(1, 1));
        var next = request("second", 1, item(0, 2));
        var continuation = plan(observed, REVISION, next, Optional.empty());
        assertThat(continuation.itemsToAppend()).containsExactly(item(0, 2));
        assertThat(continuation.receipt().resultingFormVersion()).isEqualTo(2);
        assertThat(observed.items()).containsExactly(item(3, 0), item(1, 1));
        assertThat(first.items()).containsExactly(item(3, 0), item(1, 1));
    }

    @Test
    void exactReplayReturnsTheOriginalAcknowledgementBeforeCheckingTheNowStaleVersion() {
        var original = request("first", 0, item(2, 0), item(0, 1));
        var receipt = plan(state(0), REVISION, original, Optional.empty()).receipt();
        var beforeNextAppend = state(1, item(2, 0), item(0, 1));
        var later = state(2, item(2, 0), item(0, 1), item(4, 2));

        for (var observation : List.of(beforeNextAppend, later)) {
            var replay = plan(observation, REVISION, original, Optional.of(receipt));
            assertThat(replay.outcome()).isEqualTo(Outcome.REPLAY);
            assertThat(replay.receipt()).isSameAs(receipt);
            assertThat(replay.itemsToAppend()).isEmpty();
            assertThat(replay.receipt().resultingFormVersion()).isEqualTo(1);
        }
        // Deterministic behavior after rebuilding every metadata object, not object identity.
        var rebuilt = new Receipt(1, request("first", 0, item(2, 0), item(0, 1)), 1);
        assertThat(plan(later, REVISION, rebuilt.request(), Optional.of(rebuilt)).receipt()).isEqualTo(receipt);
    }

    @Test
    void changedDecisionReuseConflictsIncludingKindEvidenceExpectedVersionAndItemIdentity() {
        var original = request("first", 0, item(0, 0));
        var receipt = new Receipt(1, original, 1);
        var current = state(1, item(0, 0));
        var changedKind = new Decision(1, DecisionKind.BRANCHING, original.decision().id(), original.decision().evidenceId());
        var changedEvidence = new Decision(1, DecisionKind.ADAPTIVE, original.decision().id(), id("other-evidence"));
        var changed = List.of(
                new Request(1, REFERENCE, 0, changedKind, original.items()),
                new Request(1, REFERENCE, 0, changedEvidence, original.items()),
                request("first", 1, item(1, 1)),
                request("first", 0, item(1, 0)),
                new Request(1, REFERENCE, 0, original.decision(), List.of(new Item(id("new-id"), id("source-0"), 0))),
                request("first", 0, item(0, 0), item(1, 1)));
        for (var request : changed) rejected(() -> plan(current, REVISION, request, Optional.of(receipt)), DECISION_CONFLICT);
        assertThat(current).isEqualTo(state(1, item(0, 0)));
        assertThat(receipt.request()).isEqualTo(original);
    }

    @Test
    void aRereadAfterOneVersionWinsRejectsTheOtherPlanWithoutClaimingDatabaseConcurrency() {
        var first = request("a", 0, item(0, 0));
        var competing = request("b", 0, item(1, 0));
        assertThat(plan(state(0), REVISION, first, Optional.empty()).outcome()).isEqualTo(Outcome.APPEND);
        assertThat(plan(state(0), REVISION, competing, Optional.empty()).outcome()).isEqualTo(Outcome.APPEND);

        // Both proposals were valid. Only the future adapter's atomic version guard can select
        // one durable winner; this fixture proves validation on a subsequent authoritative reread.
        var observedWinner = state(1, item(0, 0));
        rejected(() -> plan(observedWinner, REVISION, competing, Optional.empty()), STALE_VERSION);
        assertThat(observedWinner.items()).containsExactly(item(0, 0));
        var retryWithNewDecision = request("new-b", 1, item(1, 1));
        assertThat(plan(observedWinner, REVISION, retryWithNewDecision, Optional.empty())
                .receipt().resultingFormVersion()).isEqualTo(2);
    }

    @Test
    void wrongFormRevisionAndRevisionFormatDenyEvenWhenAReceiptIsSupplied() {
        var current = state(1, item(0, 0));
        var original = request("d", 0, item(0, 0));
        var receipt = Optional.of(new Receipt(1, original, 1));
        for (var reference : List.of(
                new Reference(id("foreign-form"), REFERENCE.quizRevisionId(), 1),
                new Reference(REFERENCE.formId(), id("other-revision"), 1),
                new Reference(REFERENCE.formId(), REFERENCE.quizRevisionId(), 2))) {
            var wrong = new Request(1, reference, 0, original.decision(), original.items());
            rejected(() -> plan(current, REVISION, wrong, receipt), REFERENCE_MISMATCH);
        }
        rejected(() -> plan(current, new RevisionScope(1, id("other-revision"), 1, REVISION.sourceItemIds()), original, receipt),
                REFERENCE_MISMATCH);
        rejected(() -> plan(current, scope(2), original, receipt), REFERENCE_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void pureMetadataValidationDoesNotLoadContentOrActivateStructuredDelivery(int schema) {
        var reference = new Reference(REFERENCE.formId(), REFERENCE.quizRevisionId(), schema);
        var current = new State(1, FormKind.PROGRESSIVE, reference, 0, List.of());
        var request = new Request(1, reference, 0, decision("d"), List.of(item(0, 0)));
        assertThat(plan(current, scope(schema), request, Optional.empty()).receipt().request().reference()).isEqualTo(reference);
    }

    @Test
    void fixedFormsCannotAcquireAppendSemanticsFromARequestOrReceipt() {
        var fixed = new State(1, FormKind.FIXED, REFERENCE, 0, List.of(item(0, 0), item(1, 2)));
        var request = request("d", 0, item(0, 0));
        rejected(() -> plan(fixed, REVISION, request, Optional.empty()), FIXED_FORM);
        rejected(() -> plan(fixed, REVISION, request, Optional.of(new Receipt(1, request, 1))), FIXED_FORM);
        assertThat(fixed.items()).containsExactly(item(0, 0), item(1, 2));
        assertThat(fixed.formVersion()).isZero();
    }

    @Test
    void missingTrustedInputsDoNotBecomeEmptySourceHistoryOrPermission() {
        var request = request("d", 0, item(0, 0));
        rejected(() -> plan(null, REVISION, request, Optional.empty()), INVALID_INPUT);
        rejected(() -> plan(state(0), null, request, Optional.empty()), INVALID_INPUT);
        rejected(() -> plan(state(0), REVISION, null, Optional.empty()), INVALID_INPUT);
        rejected(() -> plan(state(0), REVISION, request, null), INVALID_INPUT);
        rejected(() -> plan(state(0), new RevisionScope(1, REFERENCE.quizRevisionId(), 1, Set.of()), request, Optional.empty()),
                UNKNOWN_SOURCE_ITEM);
    }

    @Test
    void newItemsMustBelongToTheExactRevisionAndBeNewInBothIdentityDimensions() {
        var current = state(1, item(0, 0));
        rejected(() -> plan(current, REVISION, request("d", 1, new Item(id("new"), id("foreign-source"), 1)), Optional.empty()),
                UNKNOWN_SOURCE_ITEM);
        rejected(() -> plan(current, REVISION, request("d", 1, new Item(id("item-0"), id("source-1"), 1)), Optional.empty()),
                DUPLICATE_ITEM);
        rejected(() -> plan(current, REVISION, request("d", 1, new Item(id("new-item"), id("source-0"), 1)), Optional.empty()),
                DUPLICATE_ITEM);
        rejected(() -> plan(current, REVISION, request("d", 1, item(1, 0)), Optional.empty()), INVALID_ORDER);
        rejected(() -> plan(current, REVISION, request("d", 1, item(1, 2)), Optional.empty()), INVALID_ORDER);
        assertThat(current.items()).containsExactly(item(0, 0));
        assertThat(plan(current, REVISION, request("d", 1, item(1, 1)), Optional.empty()).outcome()).isEqualTo(Outcome.APPEND);
    }

    @Test
    void anInvalidExistingPrefixIsNotRepairedOrIgnoredOnReplay() {
        var invalid = state(1, new Item(id("item"), id("foreign-source"), 0));
        var request = request("d", 0, item(0, 0));
        rejected(() -> plan(invalid, REVISION, request, Optional.of(new Receipt(1, request, 1))), UNKNOWN_SOURCE_ITEM);
        rejected(() -> plan(invalid, REVISION, request("next", 1, item(1, 1)), Optional.empty()), UNKNOWN_SOURCE_ITEM);
    }

    @Test
    void aReceiptMustMatchTheLookupKeyAndTheRetainedAppendAtItsOriginalPositions() {
        var request = request("d", 0, item(0, 0));
        var receipt = new Receipt(1, request, 1);
        var current = state(1, item(0, 0));
        var otherForm = new Reference(id("other-form"), REFERENCE.quizRevisionId(), 1);
        var wrongForm = new Receipt(1, new Request(1, otherForm, 0, request.decision(), request.items()), 1);
        rejected(() -> plan(current, REVISION, request, Optional.of(wrongForm)), INVALID_RECEIPT);
        rejected(() -> plan(current, REVISION, request, Optional.of(new Receipt(1, request("other-decision", 0, item(0, 0)), 1))),
                INVALID_RECEIPT);
        rejected(() -> plan(state(0), REVISION, request, Optional.of(receipt)), INVALID_RECEIPT);
        rejected(() -> plan(state(1, item(1, 0)), REVISION, request, Optional.of(receipt)), INVALID_RECEIPT);
        rejected(() -> plan(state(2, item(1, 0), item(2, 1)), REVISION, request, Optional.of(receipt)), INVALID_RECEIPT);

        var longRequest = request("batch", 0, item(0, 0), item(1, 1), item(2, 2));
        rejected(() -> plan(state(2, item(0, 0), item(1, 1)), REVISION, longRequest,
                Optional.of(new Receipt(1, longRequest, 1))), INVALID_RECEIPT);
        // A latest receipt cannot omit an unexplained suffix at the same form version.
        rejected(() -> plan(state(1, item(0, 0), item(1, 1)), REVISION, request, Optional.of(receipt)), INVALID_RECEIPT);
        assertThat(plan(current, REVISION, request, Optional.of(receipt)).outcome()).isEqualTo(Outcome.REPLAY);
    }

    @Test
    void aReplayCannotExplainMoreLaterAppendsThanTheRemainingSuffixContains() {
        var request = request("batch", 0, item(0, 0), item(1, 1));
        var receipt = Optional.of(new Receipt(1, request, 1));
        // Two subsequent nonempty appends need at least two following items.
        rejected(() -> plan(state(2, item(0, 0), item(1, 1)), REVISION, request, receipt), INVALID_RECEIPT);
        rejected(() -> plan(state(3, item(0, 0), item(1, 1), item(2, 2)), REVISION, request, receipt), INVALID_RECEIPT);
        var consistent = state(3, item(0, 0), item(1, 1), item(2, 2), item(3, 3));
        assertThat(plan(consistent, REVISION, request, receipt).outcome()).isEqualTo(Outcome.REPLAY);
    }

    @Test
    void freshCommandsCannotUseOlderOrFutureFormVersions() {
        var current = state(1, item(0, 0));
        rejected(() -> plan(current, REVISION, request("old", 0, item(1, 1)), Optional.empty()), STALE_VERSION);
        rejected(() -> plan(current, REVISION, request("future", 2, item(1, 1)), Optional.empty()), STALE_VERSION);
        assertThat(plan(current, REVISION, request("current", 1, item(1, 1)), Optional.empty()).outcome()).isEqualTo(Outcome.APPEND);
    }

    @Test
    void seededPermutationsAndDifferentBatchSizesRetainEveryPrefixAndExactReplay() {
        // A deterministic contract property, not a storage/restart/concurrency fixture.
        var random = new Random(661);
        var scope = new RevisionScope(1, REFERENCE.quizRevisionId(), 1,
                IntStream.range(0, 64).mapToObj(i -> id("source-" + i)).collect(Collectors.toSet()));
        for (int run = 0; run < 16; run++) {
            var order = new ArrayList<>(IntStream.range(0, 64).boxed().toList());
            Collections.shuffle(order, random);
            var prefix = new ArrayList<Item>();
            var requests = new ArrayList<Request>();
            var receipts = new ArrayList<Receipt>();
            long version = 0;
            while (prefix.size() < order.size()) {
                int begin = prefix.size();
                int size = Math.min(1 + random.nextInt(7), order.size() - begin);
                var items = IntStream.range(begin, begin + size).mapToObj(i -> item(order.get(i), i)).toList();
                var request = new Request(1, REFERENCE, version, decision("run-" + run + "-" + version), items);
                var observed = new State(1, FormKind.PROGRESSIVE, REFERENCE, version, prefix);
                var planned = plan(observed, scope, request, Optional.empty());
                assertThat(planned.itemsToAppend()).isEqualTo(items);
                assertThat(observed.items()).hasSize(begin);
                requests.add(request);
                receipts.add(planned.receipt());
                prefix.addAll(planned.itemsToAppend());
                version++;
            }
            var finalObservation = new State(1, FormKind.PROGRESSIVE, REFERENCE, version, prefix);
            assertThat(finalObservation.items()).extracting(Item::sourceItemId)
                    .containsExactlyElementsOf(order.stream().map(i -> id("source-" + i)).toList());
            assertThat(finalObservation.items()).extracting(Item::position)
                    .containsExactlyElementsOf(IntStream.range(0, 64).boxed().toList());
            for (int i = 0; i < requests.size(); i++) {
                var replay = plan(finalObservation, scope, requests.get(i), Optional.of(receipts.get(i)));
                assertThat(replay.outcome()).isEqualTo(Outcome.REPLAY);
                assertThat(replay.receipt()).isEqualTo(receipts.get(i));
            }
        }
    }
}
