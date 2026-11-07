package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import static uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException.Reason.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AcceptedSubmissionResponseFinalizer implements SubmissionResponseFinalizer {
    private final AcceptedResponseRepository responses;
    public AcceptedSubmissionResponseFinalizer(AcceptedResponseRepository responses) { this.responses = responses; }
    @Override public CompletionFacts prepare(AttemptReadFactsService.Facts facts, boolean confirmed) {
        var accepted = responses.references(facts.access().attemptId());
        var byItem = new HashMap<UUID, AcceptedResponseRepository.Reference>();
        var sources = facts.revision().items().stream().collect(Collectors.toMap(i -> i.id(), i -> i.question()));
        for (var answer : accepted) require(byItem.put(answer.formItemId(), answer) == null, CORRUPT);
        var items = new ArrayList<CompletionFacts.Item>();
        for (var item : facts.form().items()) {
            var answer = byItem.remove(item.id());
            var question = sources.get(item.sourceItemId());
            require(question != null && facts.contracts().containsKey(question.id()), CORRUPT);
            if (answer != null) require(answer.formId().equals(facts.form().id())
                    && answer.questionRevisionId().equals(question.id())
                    && answer.contract().equals(facts.contracts().get(question.id()).response().version()), CORRUPT);
            items.add(new CompletionFacts.Item(item.id(), answer == null ? CompletionFacts.ResponseState.EMPTY
                    : CompletionFacts.ResponseState.ACCEPTED, CompletionFacts.Scope.APPLICABLE));
        }
        require(byItem.isEmpty(), CORRUPT);
        return new CompletionFacts(1, CompletionFacts.FinalizationMode.LOCK_ON_ACCEPTANCE,
                facts.policy().submission().requireAllAnswers(), confirmed, items);
    }
    @Override public void finalizeResponses(AttemptReadFactsService.Facts facts, CompletionFacts.Plan plan) {
        require(plan.finalizeItems().isEmpty(), UNSUPPORTED);
    }
}
