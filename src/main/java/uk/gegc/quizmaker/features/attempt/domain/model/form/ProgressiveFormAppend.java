package uk.gegc.quizmaker.features.attempt.domain.model.form;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.ProgressiveFormAppendException.require;

/**
 * Strategy-neutral append/v1 metadata. These values neither establish access nor resolve a
 * strategy, create storage, certify a commit or authorize delivery. No question/source payload
 * or answer enters this boundary. #571/#574 own the real atomic decision/evidence/form writers.
 */
public final class ProgressiveFormAppend {
    public static final int VERSION = 1;

    private ProgressiveFormAppend() { }

    public enum FormKind { FIXED, PROGRESSIVE }
    public enum DecisionKind { ADAPTIVE, BRANCHING }
    public enum Outcome { APPEND, REPLAY }

    /** Exact immutable revision format, independently of this append contract's version. */
    public record Reference(UUID formId, UUID quizRevisionId, int revisionSchemaVersion) {
        public Reference {
            require(formId != null && quizRevisionId != null, INVALID_INPUT);
            revisionVersion(revisionSchemaVersion);
        }
        @Override public String toString() { return "AppendReference[revisionSchemaVersion=" + revisionSchemaVersion + "]"; }
    }

    /**
     * Complete trusted revision-item membership, not mutable question IDs or an access grant.
     * The caller projects already resolved immutable metadata; no content read/hash is needed.
     * Membership bounds the form without introducing a new numerical product quota.
     */
    public record RevisionScope(int version, UUID quizRevisionId, int revisionSchemaVersion, Set<UUID> sourceItemIds) {
        public RevisionScope {
            ProgressiveFormAppend.version(version);
            revisionVersion(revisionSchemaVersion);
            require(quizRevisionId != null && sourceItemIds != null, INVALID_INPUT);
            var snapshot = new HashSet<>(sourceItemIds);
            require(!snapshot.contains(null), INVALID_INPUT);
            sourceItemIds = Set.copyOf(snapshot);
        }
        @Override public String toString() { return "AppendRevisionScope[itemCount=" + sourceItemIds.size() + "]"; }
    }

    /** Position is progressive delivery order, not the source revision's authored position. */
    public record Item(UUID id, UUID sourceItemId, int position) {
        public Item { require(id != null && sourceItemId != null && position >= 0, INVALID_ITEM); }
        @Override public String toString() { return "AppendItem[position=" + position + "]"; }
    }

    /**
     * Trusted snapshot from the adapter. Progressive positions start at zero and have no gaps;
     * every nonempty append advances formVersion once. Fixed-form positions retain their existing
     * semantics, including gaps. An empty progressive form starts at version zero.
     */
    public record State(int version, FormKind kind, Reference reference, long formVersion, List<Item> items) {
        public State {
            ProgressiveFormAppend.version(version);
            require(kind != null && reference != null && formVersion >= 0, INVALID_STATE);
            items = immutableItems(items, INVALID_STATE);
            if (kind == FormKind.PROGRESSIVE) {
                require(items.isEmpty() ? formVersion == 0 : formVersion > 0 && formVersion <= items.size(), INVALID_STATE);
                for (int i = 0; i < items.size(); i++) require(items.get(i).position() == i, INVALID_ORDER);
            }
        }
        @Override public String toString() {
            return "AppendState[kind=" + kind + ",formVersion=" + formVersion + ",itemCount=" + items.size() + "]";
        }
    }

    /**
     * Opaque immutable decision/evidence references. The strategy integration resolves and persists
     * their actual versioned contents; a UUID or kind alone never proves eligibility or authority.
     * Decision identity is unique within a form, across both decision kinds.
     */
    public record Decision(int version, DecisionKind kind, UUID id, UUID evidenceId) {
        public Decision {
            ProgressiveFormAppend.version(version);
            require(kind != null && id != null && evidenceId != null, INVALID_INPUT);
        }
        @Override public String toString() { return "AppendDecision[kind=" + kind + "]"; }
    }

    /**
     * Preserve the entire original request on retry, including expectedFormVersion. Item order
     * is explicit, never sorted into validity. A stop/terminal decision is not an empty append;
     * it belongs to the consuming strategy's lifecycle contract.
     */
    public record Request(int version, Reference reference, long expectedFormVersion, Decision decision, List<Item> items) {
        public Request {
            ProgressiveFormAppend.version(version);
            require(reference != null && decision != null && expectedFormVersion >= 0
                    && expectedFormVersion < Long.MAX_VALUE, INVALID_INPUT);
            items = immutableItems(items, INVALID_INPUT);
            require(!items.isEmpty(), INVALID_INPUT);
            for (int i = 1; i < items.size(); i++)
                require((long) items.get(i - 1).position() + 1 == items.get(i).position(), INVALID_ORDER);
        }
        @Override public String toString() {
            return "AppendRequest[expectedFormVersion=" + expectedFormVersion + ",itemCount=" + items.size() + "]";
        }
    }

    /** Original acknowledgement metadata; constructing this value is not proof of durable commit. */
    public record Receipt(int version, Request request, long resultingFormVersion) {
        public Receipt {
            ProgressiveFormAppend.version(version);
            require(request != null && resultingFormVersion == request.expectedFormVersion() + 1, INVALID_RECEIPT);
            int firstPosition = request.items().get(0).position();
            require(request.expectedFormVersion() == 0 ? firstPosition == 0
                    : firstPosition >= request.expectedFormVersion(), INVALID_RECEIPT);
        }
        @Override public String toString() { return "AppendReceipt[resultingFormVersion=" + resultingFormVersion + "]"; }
    }

    /**
     * Pure proposal, not a storage result or participant projection. APPEND needs atomic persistence;
     * REPLAY retains the original receipt and requires no append. Neither outcome permits delivery.
     */
    public record Plan(Outcome outcome, Receipt receipt) {
        public Plan { require(outcome != null && receipt != null, INVALID_INPUT); }
        public List<Item> itemsToAppend() { return outcome == Outcome.APPEND ? receipt.request().items() : List.of(); }
        @Override public String toString() { return "AppendPlan[outcome=" + outcome + "]"; }
    }

    private static List<Item> immutableItems(List<Item> items, ProgressiveFormAppendException.Reason missing) {
        require(items != null, missing);
        var snapshot = new ArrayList<>(items);
        var ids = new HashSet<UUID>();
        var sources = new HashSet<UUID>();
        var positions = new HashSet<Integer>();
        for (var item : snapshot) {
            require(item != null, INVALID_ITEM);
            require(ids.add(item.id()) && sources.add(item.sourceItemId()) && positions.add(item.position()), DUPLICATE_ITEM);
        }
        return List.copyOf(snapshot);
    }

    private static void version(int value) { require(value == VERSION, UNSUPPORTED_VERSION); }

    private static void revisionVersion(int value) {
        require(value >= 1 && value <= 3, UNSUPPORTED_REVISION_VERSION);
    }
}
