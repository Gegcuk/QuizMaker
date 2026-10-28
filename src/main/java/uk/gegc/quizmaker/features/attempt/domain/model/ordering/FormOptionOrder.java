package uk.gegc.quizmaker.features.attempt.domain.model.ordering;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.require;

/** Exact immutable identity/order only: no source keys, correctness or question payload. */
public record FormOptionOrder(UUID formId, int schemaVersion, AnswerOrderingPolicy policy, List<Item> items) {
    public FormOptionOrder {
        require(schemaVersion == 1, UNSUPPORTED_VERSION);
        require(formId != null && policy != null && items != null, INVALID_ORDER);
        var ids = new HashSet<UUID>();
        for (var item : items) require(item != null && ids.add(item.formItemId()), INVALID_ORDER);
        items = items.stream().sorted(Comparator.comparing(item -> item.formItemId().toString())).toList();
    }

    public record Item(UUID formItemId, UUID sourceItemId, QuestionContractBinding binding, List<UUID> optionIds) {
        public Item {
            require(formItemId != null && sourceItemId != null && binding != null && optionIds != null, INVALID_ORDER);
            var ids = new HashSet<UUID>();
            for (var id : optionIds) require(id != null && ids.add(id), INVALID_ORDER);
            optionIds = List.copyOf(optionIds);
        }
    }
}
