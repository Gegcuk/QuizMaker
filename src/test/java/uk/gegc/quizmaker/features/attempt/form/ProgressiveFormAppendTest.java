package uk.gegc.quizmaker.features.attempt.form;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppend.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendPlanner.plan;
import static uk.gegc.quizmaker.features.attempt.form.ProgressiveFormAppendFixtures.*;

class ProgressiveFormAppendTest {
    @ParameterizedTest
    @EnumSource(DecisionKind.class)
    void metadataUsesTheSameImmutableContractForEitherDecisionKind(DecisionKind kind) {
        var selected = new Decision(1, kind, id("decision"), id("evidence"));
        var mutable = new ArrayList<>(List.of(item(2, 0), item(0, 1)));
        var request = new Request(1, REFERENCE, 0, selected, mutable);
        var state = new State(1, FormKind.PROGRESSIVE, REFERENCE, 1, mutable);
        var sources = new HashSet<>(REVISION.sourceItemIds());
        var revision = new RevisionScope(1, REFERENCE.quizRevisionId(), 1, sources);
        mutable.clear();
        sources.clear();

        assertThat(request.items()).containsExactly(item(2, 0), item(0, 1));
        assertThat(state.items()).isEqualTo(request.items());
        assertThat(revision.sourceItemIds()).isEqualTo(REVISION.sourceItemIds());
        assertThatThrownBy(() -> request.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> state.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> revision.sourceItemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentMutationCannotReplaceTheValidatedItems(boolean requestInput) throws Exception {
        // Capture through the real COW iterator or array snapshot, then change the live list
        // before the constructor continues. Both observations retain ordinary COW semantics.
        var captured = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        var firstObservation = new AtomicBoolean(true);
        var a = item(0, 0);
        var b = item(1, 1);
        var duplicate = new Item(a.id(), a.sourceItemId(), 1);
        var live = new CopyOnWriteArrayList<Item>(List.of(a, b)) {
            @Override public Iterator<Item> iterator() {
                var snapshot = super.iterator();
                awaitMutation();
                return snapshot;
            }
            @Override public Object[] toArray() {
                var snapshot = super.toArray();
                awaitMutation();
                return snapshot;
            }
            private void awaitMutation() {
                if (firstObservation.getAndSet(false)) {
                    captured.countDown();
                    await(changed);
                }
            }
        };
        var worker = Executors.newSingleThreadExecutor();
        try {
            var mutation = worker.submit(() -> {
                try {
                    await(captured);
                    live.set(1, duplicate);
                } finally {
                    changed.countDown();
                }
            });
            List<Item> retained;
            Plan next;
            if (requestInput) {
                var request = new Request(1, REFERENCE, 0, decision("captured-request"), live);
                retained = request.items();
                next = plan(state(0), REVISION, request, Optional.empty());
            } else {
                var current = new State(1, FormKind.PROGRESSIVE, REFERENCE, 2, live);
                retained = current.items();
                next = plan(current, REVISION, request("next", 2, item(2, 2)), Optional.empty());
            }
            mutation.get(10, TimeUnit.SECONDS);

            assertThat(captured.getCount()).isZero();
            assertThat(live.get(1)).isEqualTo(duplicate);
            assertThat(retained).containsExactly(a, b);
            assertThat(retained).extracting(Item::id).doesNotHaveDuplicates();
            assertThat(retained).extracting(Item::sourceItemId).doesNotHaveDuplicates();
            assertThat(next.outcome()).isEqualTo(Outcome.APPEND);
        } finally {
            captured.countDown();
            changed.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void revisionMembershipValidatesItsOwnSnapshotDuringConcurrentNullInsertion(boolean changeBeforeSnapshot) throws Exception {
        var captured = new CountDownLatch(1);
        var changed = new CountDownLatch(1);
        var firstObservation = new AtomicBoolean(true);
        var initial = Set.of(item(0, 0).sourceItemId(), item(1, 1).sourceItemId());
        var live = new CopyOnWriteArraySet<UUID>(initial) {
            @Override public Iterator<UUID> iterator() { return observe(super::iterator); }
            @Override public Spliterator<UUID> spliterator() { return observe(super::spliterator); }

            private <T> T observe(Supplier<T> capture) {
                if (!firstObservation.getAndSet(false)) return capture.get();
                if (changeBeforeSnapshot) awaitMutation();
                T snapshot = capture.get();
                if (!changeBeforeSnapshot) awaitMutation();
                return snapshot;
            }

            private void awaitMutation() {
                captured.countDown();
                await(changed);
            }
        };
        var worker = Executors.newSingleThreadExecutor();
        try {
            var mutation = worker.submit(() -> {
                try {
                    await(captured);
                    live.add(null);
                } finally {
                    changed.countDown();
                }
            });

            if (changeBeforeSnapshot) {
                rejected(() -> new RevisionScope(1, REFERENCE.quizRevisionId(), 1, live), INVALID_INPUT);
            } else {
                var scope = new RevisionScope(1, REFERENCE.quizRevisionId(), 1, live);
                assertThat(scope.sourceItemIds()).containsExactlyInAnyOrderElementsOf(initial).doesNotContainNull();
                assertThatThrownBy(() -> scope.sourceItemIds().clear()).isInstanceOf(UnsupportedOperationException.class);
                var current = state(0);
                assertThat(plan(current, scope, request("scope-capture", 0, item(0, 0)), Optional.empty()).outcome())
                        .isEqualTo(Outcome.APPEND);
                assertThat(current.items()).isEmpty();
            }
            mutation.get(10, TimeUnit.SECONDS);
            assertThat(captured.getCount()).isZero();
            assertThat(live).containsNull();
        } finally {
            captured.countDown();
            changed.countDown();
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void resourceCloseFailureCannotAttachPrivateDiagnosticsToAPlannerRejection() throws Exception {
        var marker = "SYNTHETIC-CLOSE-MARKER";
        var closed = new AtomicBoolean();
        var request = request("private-decision", 0, item(0, 0));
        ProgressiveFormAppendException rejection = null;
        try (AutoCloseable resource = () -> {
            closed.set(true);
            throw new Exception(marker);
        }) {
            plan(null, REVISION, request, Optional.empty());
        } catch (ProgressiveFormAppendException failure) {
            rejection = failure;
        }

        assertThat(closed).isTrue();
        assertThat(rejection).isNotNull().hasMessage("Progressive form append rejected: INVALID_INPUT").hasNoCause();
        assertThat(rejection.reason()).isEqualTo(INVALID_INPUT);
        assertThat(rejection.getSuppressed()).isEmpty();
        assertThat(rejection.getStackTrace()).isNotEmpty();
        var trace = new StringWriter();
        rejection.printStackTrace(new PrintWriter(trace));
        assertThat(trace.toString()).contains("INVALID_INPUT").doesNotContain(marker);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("Controlled snapshot/mutation checkpoint reached").isTrue();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Snapshot/mutation wait interrupted", failure);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 2, Integer.MAX_VALUE})
    void unknownAppendVersionsFailInEveryEnvelope(int version) {
        var request = request("decision", 0, item(0, 0));
        for (Runnable action : List.<Runnable>of(
                () -> new RevisionScope(version, REFERENCE.quizRevisionId(), 1, Set.of()),
                () -> new State(version, FormKind.PROGRESSIVE, REFERENCE, 0, List.of()),
                () -> new Decision(version, DecisionKind.ADAPTIVE, id("d"), id("e")),
                () -> new Request(version, REFERENCE, 0, decision("d"), request.items()),
                () -> new Receipt(version, request, 1))) {
            rejected(action, UNSUPPORTED_VERSION);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 5, Integer.MAX_VALUE})
    void unknownRevisionFormatsAreNotReinterpreted(int version) {
        rejected(() -> new Reference(id("form"), id("revision"), version), UNSUPPORTED_REVISION_VERSION);
        rejected(() -> scope(version), UNSUPPORTED_REVISION_VERSION);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void deliveredRevisionFormatsAreReferencesWithoutActivatingTheirRuntime(int version) {
        var reference = new Reference(id("form"), id("revision"), version);
        assertThat(reference.revisionSchemaVersion()).isEqualTo(version);
        assertThat(scope(version).revisionSchemaVersion()).isEqualTo(version);
    }

    @Test
    void absentIdentityOrEvidenceNeverEstablishesAnAppend() {
        for (Runnable action : List.<Runnable>of(
                () -> new Reference(null, id("revision"), 1),
                () -> new Reference(id("form"), null, 1),
                () -> new RevisionScope(1, null, 1, Set.of()),
                () -> new RevisionScope(1, id("revision"), 1, null),
                () -> new RevisionScope(1, id("revision"), 1, new HashSet<>(Arrays.asList(id("source"), null))),
                () -> new Decision(1, null, id("decision"), id("evidence")),
                () -> new Decision(1, DecisionKind.ADAPTIVE, null, id("evidence")),
                () -> new Decision(1, DecisionKind.ADAPTIVE, id("decision"), null),
                () -> new Request(1, null, 0, decision("d"), List.of(item(0, 0))),
                () -> new Request(1, REFERENCE, 0, null, List.of(item(0, 0))),
                () -> new Request(1, REFERENCE, 0, decision("d"), null),
                () -> new Request(1, REFERENCE, 0, decision("d"), List.of()),
                () -> new Request(1, REFERENCE, -1, decision("d"), List.of(item(0, 0))),
                () -> new Request(1, REFERENCE, Long.MAX_VALUE, decision("d"), List.of(item(0, 0))))) {
            rejected(action, INVALID_INPUT);
        }
        for (Runnable action : List.<Runnable>of(
                () -> new Item(null, id("source"), 0),
                () -> new Item(id("item"), null, 0),
                () -> new Item(id("item"), id("source"), -1),
                () -> new Request(1, REFERENCE, 0, decision("d"), Arrays.asList((Item) null)),
                () -> new State(1, FormKind.PROGRESSIVE, REFERENCE, 1, Arrays.asList((Item) null)))) {
            rejected(action, INVALID_ITEM);
        }
    }

    @Test
    void stateVersionsDescribeNonemptyAppendsRatherThanUnrelatedLifecycleUpdates() {
        assertThat(state(0).items()).isEmpty();
        assertThat(state(1, item(0, 0), item(1, 1)).formVersion()).isEqualTo(1);
        for (Runnable action : List.<Runnable>of(
                () -> new State(1, null, REFERENCE, 0, List.of()),
                () -> new State(1, FormKind.PROGRESSIVE, null, 0, List.of()),
                () -> new State(1, FormKind.PROGRESSIVE, REFERENCE, 0, null),
                () -> state(-1), () -> state(1), () -> state(0, item(0, 0)),
                () -> state(2, item(0, 0)), () -> state(Long.MAX_VALUE, item(0, 0)))) {
            rejected(action, INVALID_STATE);
        }
    }

    @ParameterizedTest
    @MethodSource("duplicateItems")
    void identitiesSourcesAndPositionsAreIndependentUniquenessConstraints(List<Item> items) {
        rejected(() -> new Request(1, REFERENCE, 0, decision("d"), items), DUPLICATE_ITEM);
        rejected(() -> new State(1, FormKind.PROGRESSIVE, REFERENCE, 1, items), DUPLICATE_ITEM);
    }

    static Stream<List<Item>> duplicateItems() {
        var first = item(0, 0);
        return Stream.of(
                List.of(first, new Item(first.id(), id("source-1"), 1)),
                List.of(first, new Item(id("other-item"), first.sourceItemId(), 1)),
                List.of(first, item(1, 0)));
    }

    @Test
    void progressiveOrderIsExplicitAndNeverRepairedBySorting() {
        rejected(() -> state(1, item(0, 1)), INVALID_ORDER);
        rejected(() -> state(1, item(0, 0), item(1, 2)), INVALID_ORDER);
        rejected(() -> state(1, item(1, 1), item(0, 0)), INVALID_ORDER);
        rejected(() -> request("d", 0, item(0, 0), item(1, 2)), INVALID_ORDER);
        rejected(() -> request("d", 0, item(1, 1), item(0, 0)), INVALID_ORDER);
        rejected(() -> request("d", 0, item(0, Integer.MAX_VALUE), item(1, 0)), INVALID_ORDER);
        var fixed = new State(1, FormKind.FIXED, REFERENCE, 0, List.of(item(0, 0), item(1, 2)));
        assertThat(fixed.items()).extracting(Item::position).containsExactly(0, 2);
    }

    @Test
    void receiptsRetainTheOriginalRequestAndOneVersionAdvance() {
        var request = request("d", 0, item(0, 0), item(1, 1));
        var receipt = new Receipt(1, request, 1);
        assertThat(receipt.request()).isSameAs(request);
        assertThat(receipt.resultingFormVersion()).isEqualTo(1);
        rejected(() -> new Receipt(1, null, 1), INVALID_RECEIPT);
        rejected(() -> new Receipt(1, request, 0), INVALID_RECEIPT);
        rejected(() -> new Receipt(1, request, 2), INVALID_RECEIPT);
        rejected(() -> new Receipt(1, request("d", 0, item(0, 1)), 1), INVALID_RECEIPT);
        rejected(() -> new Receipt(1, request("d", 2, item(0, 1)), 3), INVALID_RECEIPT);
    }

    @Test
    void plansSeparateAppendWorkFromReplayWithoutCertifyingCommit() {
        var receipt = new Receipt(1, request("d", 0, item(0, 0)), 1);
        var append = new Plan(Outcome.APPEND, receipt);
        var replay = new Plan(Outcome.REPLAY, receipt);
        assertThat(append.itemsToAppend()).containsExactly(item(0, 0));
        assertThat(replay.itemsToAppend()).isEmpty();
        assertThat(replay.receipt()).isSameAs(append.receipt());
        rejected(() -> new Plan(null, receipt), INVALID_INPUT);
        rejected(() -> new Plan(Outcome.APPEND, null), INVALID_INPUT);
    }

    @Test
    void diagnosticsExposeOnlyBoundedCategoriesAndCounts() {
        var request = request("private-decision", 0, item(0, 0));
        var receipt = new Receipt(1, request, 1);
        var diagnostics = List.of(REFERENCE.toString(), REVISION.toString(), item(0, 0).toString(),
                state(1, item(0, 0)).toString(), request.decision().toString(), request.toString(),
                receipt.toString(), new Plan(Outcome.APPEND, receipt).toString());
        assertThat(diagnostics).allSatisfy(text -> assertThat(text).doesNotContain(
                REFERENCE.formId().toString(), REFERENCE.quizRevisionId().toString(), item(0, 0).id().toString(),
                item(0, 0).sourceItemId().toString(), request.decision().id().toString(), request.decision().evidenceId().toString()));
        for (var reason : ProgressiveFormAppendException.Reason.values()) {
            var failure = new ProgressiveFormAppendException(reason);
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure).hasMessage("Progressive form append rejected: " + reason).hasNoCause();
        }
    }

    static void rejected(Runnable action, ProgressiveFormAppendException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ProgressiveFormAppendException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason))
                .hasMessage("Progressive form append rejected: " + reason).hasNoCause();
    }
}
