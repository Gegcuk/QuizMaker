package uk.gegc.quizmaker.features.attempt.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.evaluate;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.lifecycle.AttemptLifecycleTest.*;

class LifecycleCompositionTest {
    private static final State COLLECTING = state(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void eightEditableResponsesProposalAndEmptyItemRespectSavedRequireAll(boolean requireAll) {
        // LIFECYCLE-COMPOSITION: approved ten-item vector; no fake persistence or policy activation.
        var items = new ArrayList<>(items(8, ResponseState.EDITABLE));
        items.add(item(9, ResponseState.PENDING_CONFIRMATION, Scope.APPLICABLE));
        items.add(item(10, ResponseState.EMPTY, Scope.APPLICABLE));
        var facts = new CompletionFacts(1, FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION, requireAll, true, items);
        var actual = evaluate(COLLECTING, Event.SUBMIT, Context.submit(facts));
        if (requireAll) {
            assertThat(actual).isEqualTo(new Denied(INCOMPLETE_REQUIRED_SCOPE));
        } else {
            var allowed = (Advance) actual;
            assertThat(allowed.state()).isEqualTo(state(Execution.SUBMITTED, AnswerCollection.COMPLETE));
            assertThat(allowed.plan().finalizeItems()).containsExactlyElementsOf(items.subList(0, 8).stream()
                    .map(Item::formItemId).toList());
            assertThat(allowed.plan().unansweredItems()).containsExactly(id(9), id(10));
            assertThat(evaluate(allowed.state(), Event.SUBMIT, Context.identicalReplay()))
                    .isEqualTo(new Replay(allowed.state()));
        }
        assertThat(facts.items()).containsExactlyElementsOf(items);
        assertThat(facts.items().get(0).response()).isEqualTo(ResponseState.EDITABLE);
        assertThat(facts.items().get(8).response()).isEqualTo(ResponseState.PENDING_CONFIRMATION);
        assertThat(COLLECTING).isEqualTo(state(Execution.IN_PROGRESS, AnswerCollection.IN_PROGRESS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void immediateAcceptanceNeverReacceptsExistingAnswers(boolean requireAll) {
        var facts = new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, requireAll, true,
                items(10, ResponseState.ACCEPTED));
        var result = (Advance) evaluate(COLLECTING, Event.SUBMIT, Context.submit(facts));
        assertThat(result.plan()).isEqualTo(Plan.none());
        assertThat(result.state().terminal()).isTrue();
        assertThat(facts.items()).allMatch(item -> item.response() == ResponseState.ACCEPTED);
    }

    @ParameterizedTest
    @EnumSource(value = ResponseState.class, names = {"EMPTY", "DRAFT", "PENDING_CONFIRMATION"})
    void draftAndProposedPayloadAreNeverAutomaticallyAccepted(ResponseState unanswered) {
        for (var mode : FinalizationMode.values()) {
            var items = List.of(item(1, ResponseState.ACCEPTED, Scope.APPLICABLE), item(2, unanswered, Scope.APPLICABLE));
            var permissive = new CompletionFacts(1, mode, false, true, items);
            var result = (Advance) evaluate(COLLECTING, Event.SUBMIT, Context.submit(permissive));
            assertThat(result.plan().finalizeItems()).isEmpty();
            assertThat(result.plan().unansweredItems()).containsExactly(id(2));
            var required = new CompletionFacts(1, mode, true, true, items);
            assertThat(evaluate(COLLECTING, Event.SUBMIT, Context.submit(required)))
                    .isEqualTo(new Denied(INCOMPLETE_REQUIRED_SCOPE));
        }
    }

    @Test
    void pendingAndMissingResponsesInClosedScopeDoNotBlockOrInventAnswers() {
        var items = List.of(item(1, ResponseState.EMPTY, Scope.PERMANENTLY_CLOSED),
                item(2, ResponseState.PENDING_CONFIRMATION, Scope.PERMANENTLY_CLOSED),
                item(3, ResponseState.EDITABLE, Scope.APPLICABLE));
        var facts = new CompletionFacts(1, FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION, true, true, items);
        var result = (Advance) evaluate(COLLECTING, Event.SUBMIT, Context.submit(facts));
        assertThat(result.plan().finalizeItems()).containsExactly(id(3));
        assertThat(result.plan().unansweredItems()).containsExactly(id(1), id(2));
        // The same unanswered fact in an applicable scope still blocks voluntary completion.
        var open = List.of(item(1, ResponseState.EMPTY, Scope.APPLICABLE), items.get(1), items.get(2));
        assertThat(evaluate(COLLECTING, Event.SUBMIT, Context.submit(new CompletionFacts(1,
                FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION, true, true, open))))
                .isEqualTo(new Denied(INCOMPLETE_REQUIRED_SCOPE));
    }

    @ParameterizedTest
    @EnumSource(FinalizationMode.class)
    void voluntaryCompletionAlwaysRequiresConfirmation(FinalizationMode mode) {
        for (boolean requireAll : List.of(false, true)) {
            var unconfirmed = new CompletionFacts(1, mode, requireAll, false, items(2, ResponseState.ACCEPTED));
            assertThat(evaluate(COLLECTING, Event.SUBMIT, Context.submit(unconfirmed)))
                    .isEqualTo(new Denied(CONFIRMATION_REQUIRED));
            var confirmed = new CompletionFacts(1, mode, requireAll, true, unconfirmed.items());
            assertThat(evaluate(COLLECTING, Event.SUBMIT, Context.submit(confirmed))).isInstanceOf(Advance.class);
        }
    }

    @Test
    void unansweredOnlyFormCanCompleteWithoutEverClaimingResponseAcceptance() {
        var initial = state(Execution.IN_PROGRESS, AnswerCollection.NOT_STARTED);
        var facts = new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true, items(2, ResponseState.EMPTY));
        var result = (Advance) evaluate(initial, Event.SUBMIT, Context.submit(facts));
        assertThat(result.plan().finalizeItems()).isEmpty();
        assertThat(result.plan().unansweredItems()).containsExactly(id(1), id(2));
        assertThat(result.state().answers()).isEqualTo(AnswerCollection.COMPLETE);
    }

    @Test
    void mixedAcceptedAndEditableWorkPlansOnlyEligibleNewAcceptanceInFormOrder() {
        var items = List.of(item(3, ResponseState.EDITABLE, Scope.APPLICABLE),
                item(1, ResponseState.ACCEPTED, Scope.APPLICABLE), item(2, ResponseState.EDITABLE, Scope.APPLICABLE));
        var result = (Advance) evaluate(COLLECTING, Event.SUBMIT, Context.submit(new CompletionFacts(1,
                FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION, true, true, items)));
        assertThat(result.plan().finalizeItems()).containsExactly(id(3), id(2));
        assertThat(result.plan().unansweredItems()).isEmpty();
    }

    @Test
    void invalidCompletionFactsFailBeforeAnyPlanCanEscape() {
        var valid = items(1, ResponseState.ACCEPTED);
        rejected(() -> new CompletionFacts(1, null, false, true, valid), INVALID_INPUT);
        rejected(() -> new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true, null), INVALID_INPUT);
        rejected(() -> new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true, List.of()), INVALID_INPUT);
        rejected(() -> new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true,
                java.util.Arrays.asList((Item) null)), CONTRADICTORY_FACTS);
        rejected(() -> new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true,
                List.of(valid.get(0), valid.get(0))), CONTRADICTORY_FACTS);
        rejected(() -> new CompletionFacts(1, FinalizationMode.LOCK_ON_ACCEPTANCE, false, true,
                items(1, ResponseState.EDITABLE)), CONTRADICTORY_FACTS);
        rejected(() -> new CompletionFacts(1, FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION, false, true,
                List.of(item(1, ResponseState.EDITABLE, Scope.PERMANENTLY_CLOSED))), CONTRADICTORY_FACTS);
        rejected(() -> new Item(null, ResponseState.EMPTY, Scope.APPLICABLE), INVALID_INPUT);
        rejected(() -> new Item(ITEM, null, Scope.APPLICABLE), INVALID_INPUT);
        rejected(() -> new Item(ITEM, ResponseState.EMPTY, null), INVALID_INPUT);
        rejected(() -> new Plan(null, List.of()), INVALID_INPUT);
        rejected(() -> new Plan(List.of(), null), INVALID_INPUT);
        rejected(() -> new Plan(List.of(ITEM, ITEM), List.of()), CONTRADICTORY_FACTS);
        rejected(() -> new Plan(List.of(ITEM), List.of(ITEM)), CONTRADICTORY_FACTS);
        rejected(() -> new Plan(List.of(), java.util.Arrays.asList((UUID) null)), CONTRADICTORY_FACTS);
    }

    @Test
    void factsAndPlansAreDefensivelyCopiedAndCannotBeMutatedByCallers() {
        var mutable = new ArrayList<>(items(2, ResponseState.EDITABLE));
        var facts = new CompletionFacts(1, FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION, false, true, mutable);
        mutable.clear();
        var result = (Advance) evaluate(COLLECTING, Event.SUBMIT, Context.submit(facts));
        assertThat(result.plan().finalizeItems()).containsExactly(id(1), id(2));
        assertThatThrownBy(() -> facts.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.plan().finalizeItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.plan().unansweredItems().add(ITEM)).isInstanceOf(UnsupportedOperationException.class);
        var ids = new ArrayList<>(List.of(ITEM));
        var unanswered = new ArrayList<>(List.of(id(2)));
        var plan = new Plan(ids, unanswered);
        ids.clear();
        unanswered.clear();
        assertThat(plan.finalizeItems()).containsExactly(ITEM);
        assertThat(plan.unansweredItems()).containsExactly(id(2));
    }

    private static List<Item> items(int count, ResponseState response) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> item(i, response, Scope.APPLICABLE)).toList();
    }

    private static Item item(int number, ResponseState response, Scope scope) { return new Item(id(number), response, scope); }
    private static UUID id(int number) { return new UUID(0, number); }
}
