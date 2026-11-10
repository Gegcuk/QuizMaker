package uk.gegc.quizmaker.features.attempt.expiry;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.*;
import static org.assertj.core.api.Assertions.*;

class AttemptExpiryContractTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void expiryPreservesPolicyAndAcceptedAnswersWhileIgnoringMissingRequiredItemsAndConfirmation(boolean all) {
        var accepted=UUID.randomUUID(); var draft=UUID.randomUUID(); var proposal=UUID.randomUUID(); var empty=UUID.randomUUID();
        var facts=new CompletionFacts(1,CompletionFacts.FinalizationMode.LOCK_ON_ACCEPTANCE,all,false,List.of(
                new CompletionFacts.Item(accepted,CompletionFacts.ResponseState.ACCEPTED,CompletionFacts.Scope.APPLICABLE),
                new CompletionFacts.Item(draft,CompletionFacts.ResponseState.DRAFT,CompletionFacts.Scope.APPLICABLE),
                new CompletionFacts.Item(proposal,CompletionFacts.ResponseState.PENDING_CONFIRMATION,CompletionFacts.Scope.APPLICABLE),
                new CompletionFacts.Item(empty,CompletionFacts.ResponseState.EMPTY,CompletionFacts.Scope.APPLICABLE)));
        var state=new State(1,Execution.IN_PROGRESS,AnswerCollection.IN_PROGRESS);
        var decision=AttemptLifecycle.expire(state,facts);
        assertThat(decision).isInstanceOfSatisfying(Advance.class,result->{
            assertThat(result.state().terminal()).isTrue();
            assertThat(result.plan().finalizeItems()).isEmpty();
            assertThat(result.plan().unansweredItems()).containsExactly(draft,proposal,empty);
        });
        assertThat(facts.requireAllAnswers()).isEqualTo(all);
        assertThat(AttemptLifecycle.evaluate(state,Event.SUBMIT,Context.submit(facts))).isInstanceOf(Denied.class);
    }
    @Test void futureEditablePlanIsNotProofOfConversionAndDoesNotAcceptAProposal() {
        var ordinary=UUID.randomUUID(); var proposal=UUID.randomUUID();
        var prepared=new CompletionFacts(1,CompletionFacts.FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION,true,false,List.of(
                new CompletionFacts.Item(ordinary,CompletionFacts.ResponseState.EDITABLE,CompletionFacts.Scope.APPLICABLE),
                new CompletionFacts.Item(proposal,CompletionFacts.ResponseState.PENDING_CONFIRMATION,CompletionFacts.Scope.APPLICABLE)));
        var result=(Advance)AttemptLifecycle.expire(new State(1,Execution.IN_PROGRESS,AnswerCollection.IN_PROGRESS),prepared);
        assertThat(result.plan().finalizeItems()).containsExactly(ordinary);
        assertThat(result.plan().unansweredItems()).containsExactly(proposal);
    }
    @Test void missingContradictoryAndTerminalFactsCannotCreateAnExpiryTransition() {
        var running=new State(1,Execution.IN_PROGRESS,AnswerCollection.NOT_STARTED);
        assertThat(AttemptLifecycle.expire(null,null)).isEqualTo(new Denied(LifecycleException.Reason.INVALID_INPUT));
        assertThat(AttemptLifecycle.expire(running,null)).isEqualTo(new Denied(LifecycleException.Reason.MISSING_CONTEXT));
        assertThat(AttemptLifecycle.expire(new State(1,Execution.CREATED,AnswerCollection.NOT_STARTED),null))
                .isEqualTo(new Denied(LifecycleException.Reason.NOT_STARTED));
        assertThat(AttemptLifecycle.expire(new State(1,Execution.SUBMITTED,AnswerCollection.COMPLETE),null))
                .isEqualTo(new Denied(LifecycleException.Reason.TERMINAL_STATE));
        var activity=new CompletionFacts(1,CompletionFacts.FinalizationMode.LOCK_ON_ACCEPTANCE,false,false,
                List.of(new CompletionFacts.Item(UUID.randomUUID(),CompletionFacts.ResponseState.ACCEPTED,CompletionFacts.Scope.APPLICABLE)));
        assertThat(AttemptLifecycle.expire(running,activity)).isEqualTo(new Denied(LifecycleException.Reason.CONTRADICTORY_FACTS));
    }
    @Test void delayedProcessingKeepsEffectiveDeadlineSeparateAndNeverReopensTerminalState() {
        Instant start=Instant.parse("2026-11-10T10:00:00Z"), deadline=start.plusSeconds(1800), processing=deadline.plusSeconds(300);
        var running=LifecycleState.initial(UUID.randomUUID(),start).advance(new State(1,Execution.IN_PROGRESS,AnswerCollection.NOT_STARTED),start);
        var next=new State(1,Execution.SUBMITTED,AnswerCollection.COMPLETE);
        var expired=running.expire(next,deadline,processing);
        assertThat(expired.submittedAt()).isEqualTo(deadline);
        assertThat(expired.updatedAt()).isEqualTo(processing);
        assertThat(expired.rowVersion()).isEqualTo(2);
        assertThatThrownBy(()->expired.expire(next,deadline,processing)).isInstanceOf(LifecycleStateException.class);
        assertThatThrownBy(()->running.expire(next,deadline,deadline.minusSeconds(1))).isInstanceOf(LifecycleStateException.class);
        assertThatThrownBy(()->running.expire(next,start.minusSeconds(1),processing)).isInstanceOf(LifecycleStateException.class);
    }
}
