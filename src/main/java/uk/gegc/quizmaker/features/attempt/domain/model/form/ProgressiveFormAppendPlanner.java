package uk.gegc.quizmaker.features.attempt.domain.model.form;

import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppend.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException.require;

/** Pure validation over trusted metadata; no persistence, authorization, strategy or delivery. */
public final class ProgressiveFormAppendPlanner {
    private ProgressiveFormAppendPlanner() { }

    /**
     * accepted is the exact form/decision receipt lookup, not only the latest receipt. The adapter
     * must supply a coherent current state/receipt observation under its serialization boundary.
     * Work is linear in supplied membership/prefix/append metadata, not constant with form size.
     */
    public static Plan plan(State current, RevisionScope revision, Request request, Optional<Receipt> accepted) {
        require(current != null && revision != null && request != null && accepted != null, INVALID_INPUT);
        require(current.reference().equals(request.reference())
                && current.reference().quizRevisionId().equals(revision.quizRevisionId())
                && current.reference().revisionSchemaVersion() == revision.revisionSchemaVersion(), REFERENCE_MISMATCH);
        require(current.kind() == FormKind.PROGRESSIVE, FIXED_FORM);

        var ids = new HashSet<UUID>();
        var sources = new HashSet<UUID>();
        for (var item : current.items()) {
            require(revision.sourceItemIds().contains(item.sourceItemId()), UNKNOWN_SOURCE_ITEM);
            ids.add(item.id());
            sources.add(item.sourceItemId());
        }

        if (accepted.isPresent()) {
            var receipt = accepted.get();
            require(receipt.request().reference().equals(current.reference())
                    && receipt.request().decision().id().equals(request.decision().id()), INVALID_RECEIPT);
            require(receipt.request().equals(request), DECISION_CONFLICT);
            require(receipt.resultingFormVersion() <= current.formVersion(), INVALID_RECEIPT);
            var appended = receipt.request().items();
            long followingAppends = current.formVersion() - receipt.resultingFormVersion();
            long followingItems = current.items().size() - ((long) appended.get(appended.size() - 1).position() + 1);
            require(followingItems >= followingAppends && (followingAppends > 0 || followingItems == 0), INVALID_RECEIPT);
            for (var item : receipt.request().items()) {
                require(item.position() < current.items().size()
                        && current.items().get(item.position()).equals(item), INVALID_RECEIPT);
            }
            return new Plan(Outcome.REPLAY, receipt);
        }

        require(current.formVersion() == request.expectedFormVersion(), STALE_VERSION);
        require((long) current.items().size() + request.items().size() <= Integer.MAX_VALUE, INVALID_ORDER);
        for (int i = 0; i < request.items().size(); i++) {
            var item = request.items().get(i);
            require(item.position() == current.items().size() + i, INVALID_ORDER);
            require(revision.sourceItemIds().contains(item.sourceItemId()), UNKNOWN_SOURCE_ITEM);
            require(ids.add(item.id()) && sources.add(item.sourceItemId()), DUPLICATE_ITEM);
        }
        return new Plan(Outcome.APPEND, new Receipt(VERSION, request, current.formVersion() + 1));
    }
}
