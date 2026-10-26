package uk.gegc.quizmaker.features.attempt.domain.model.lifecycle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleException.require;

/**
 * Trusted immutable facts for a voluntary attempt submission, not a request DTO or response store.
 * Callers supply every form item in persisted form order and its authoritative completion scope.
 * Editable facts describe the approved #834 contract only; constructing them activates no policy.
 * No payload, grade, access decision, clock, section transition or database effect is represented.
 */
public record CompletionFacts(int version, FinalizationMode finalization, boolean requireAllAnswers,
                              boolean completionConfirmed, List<Item> items) {
    public enum FinalizationMode { LOCK_ON_ACCEPTANCE, EDIT_UNTIL_ATTEMPT_SUBMISSION }
    public enum ResponseState { EMPTY, DRAFT, EDITABLE, PENDING_CONFIRMATION, ACCEPTED }
    public enum Scope { APPLICABLE, PERMANENTLY_CLOSED }

    public CompletionFacts {
        require(version == AttemptLifecycle.VERSION, UNSUPPORTED_VERSION);
        require(finalization != null && items != null && !items.isEmpty(), INVALID_INPUT);
        var identities = new HashSet<UUID>();
        for (var item : items) {
            require(item != null && identities.add(item.formItemId()), CONTRADICTORY_FACTS);
            // A closed scope cannot retain an ordinary response still requiring finalization.
            require(item.response() != ResponseState.EDITABLE
                    || finalization == FinalizationMode.EDIT_UNTIL_ATTEMPT_SUBMISSION
                    && item.scope() == Scope.APPLICABLE, CONTRADICTORY_FACTS);
        }
        items = List.copyOf(items);
    }

    public record Item(UUID formItemId, ResponseState response, Scope scope) {
        public Item { require(formItemId != null && response != null && scope != null, INVALID_INPUT); }
    }

    /** These are proposed effects, not proof of acceptance, completion or disclosure. */
    public record Plan(List<UUID> finalizeItems, List<UUID> unansweredItems) {
        public Plan {
            require(finalizeItems != null && unansweredItems != null, INVALID_INPUT);
            var identities = new HashSet<UUID>();
            for (var group : List.of(finalizeItems, unansweredItems)) {
                for (var id : group) require(id != null && identities.add(id), CONTRADICTORY_FACTS);
            }
            finalizeItems = List.copyOf(finalizeItems);
            unansweredItems = List.copyOf(unansweredItems);
        }

        public static Plan none() { return new Plan(List.of(), List.of()); }
    }

    boolean hasResponseActivity() {
        return items.stream().anyMatch(item -> item.response() != ResponseState.EMPTY);
    }

    Plan plan() {
        var finalizeItems = new ArrayList<UUID>();
        var unansweredItems = new ArrayList<UUID>();
        for (var item : items) {
            switch (item.response()) {
                case ACCEPTED -> { /* Preserve already immutable evidence. */ }
                case EDITABLE -> finalizeItems.add(item.formItemId());
                case EMPTY, DRAFT, PENDING_CONFIRMATION -> {
                    require(!requireAllAnswers || item.scope() == Scope.PERMANENTLY_CLOSED,
                            INCOMPLETE_REQUIRED_SCOPE);
                    unansweredItems.add(item.formItemId());
                }
            }
        }
        require(completionConfirmed, CONFIRMATION_REQUIRED);
        return new Plan(finalizeItems, unansweredItems);
    }
}
