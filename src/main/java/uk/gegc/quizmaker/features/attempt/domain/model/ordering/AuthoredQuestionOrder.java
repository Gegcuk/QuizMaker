package uk.gegc.quizmaker.features.attempt.domain.model.ordering;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.QuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.QuestionOrderingPolicy;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.QuestionOrderingException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.QuestionOrderingException.require;

/**
 * Pure, dark AUTHORED/v1 ordering over trusted immutable revision metadata. Selected IDs are
 * supplied by the selection owner; this boundary neither selects items nor proves authorization.
 * No persistence, content resolution, option ordering or attempt activation occurs here.
 */
public final class AuthoredQuestionOrder {
    public static final int VERSION = 1;

    private AuthoredQuestionOrder() {}

    /** Item occurrence identity, not the mutable question ID or the question-revision ID. */
    public record Item(UUID id, Integer authoredPosition) {
        public Item { require(id != null && authoredPosition != null && authoredPosition >= 0, INVALID_ITEM); }
    }

    /**
     * Complete trusted revision metadata and an explicit already-selected set. Collection order
     * has no meaning. Positions may have gaps; duplicates never acquire a UUID tie-break fallback.
     * This metadata input is internal, not a participant request or a revision existence proof.
     */
    public record Input(int version, UUID quizRevisionId, int revisionSchemaVersion,
                        QuestionOrderingPolicy policy, List<Item> revisionItems, List<UUID> selectedItemIds) {
        public Input {
            validateEvidence(version, quizRevisionId, revisionSchemaVersion, policy);
            require(revisionItems != null && selectedItemIds != null, INVALID_INPUT);
            var ids = new HashSet<UUID>();
            var positions = new HashSet<Integer>();
            for (Item item : revisionItems) {
                require(item != null, INVALID_ITEM);
                require(ids.add(item.id()), DUPLICATE_ITEM);
                require(positions.add(item.authoredPosition()), DUPLICATE_POSITION);
            }
            var selected = new HashSet<UUID>();
            for (UUID id : selectedItemIds) {
                require(id != null, INVALID_ITEM);
                require(selected.add(id), DUPLICATE_ITEM);
                require(ids.contains(id), UNKNOWN_ITEM);
            }
            revisionItems = List.copyOf(revisionItems);
            selectedItemIds = List.copyOf(selectedItemIds);
        }

        /**
         * Projects #597's real immutable revision without copying question/answer content. The
         * caller resolves and authorizes the revision first. #515's content binding must match.
         */
        public static Input fromRevision(ResolvedAttemptPolicy policy, QuizRevision revision,
                                         List<UUID> selectedItemIds) {
            require(policy != null && revision != null, INVALID_INPUT);
            require(policy.content().quizRevisionId().equals(revision.id())
                    && policy.content().revisionSchemaVersion() == revision.schemaVersion(), REVISION_MISMATCH);
            return new Input(VERSION, revision.id(), revision.schemaVersion(), policy.questionOrdering(),
                    revision.items().stream().map(item -> new Item(item.id(), item.authoredPosition())).toList(),
                    selectedItemIds);
        }
    }

    /** Reproducibility metadata only; neither a receipt nor a substitute for persisted form order. */
    public record Evidence(int version, UUID quizRevisionId, int revisionSchemaVersion, QuestionOrderingPolicy policy) {
        public Evidence { validateEvidence(version, quizRevisionId, revisionSchemaVersion, policy); }
    }

    /** Stable source item IDs in display order, with no question payload or protected answer data. */
    public record Result(Evidence evidence, List<UUID> itemIds) {
        public Result {
            require(evidence != null && itemIds != null, INVALID_INPUT);
            var ids = new HashSet<UUID>();
            for (UUID id : itemIds) {
                require(id != null, INVALID_ITEM);
                require(ids.add(id), DUPLICATE_ITEM);
            }
            itemIds = List.copyOf(itemIds);
        }
    }

    public static Result order(Input input) {
        require(input != null, INVALID_INPUT);
        var items = new HashMap<UUID, Item>();
        input.revisionItems().forEach(item -> items.put(item.id(), item));
        List<UUID> ordered = input.selectedItemIds().stream().map(items::get)
                .sorted(Comparator.comparingInt(Item::authoredPosition)).map(Item::id).toList();
        return new Result(new Evidence(input.version(), input.quizRevisionId(), input.revisionSchemaVersion(),
                input.policy()), ordered);
    }

    private static void validateEvidence(int version, UUID revisionId, int revisionVersion, QuestionOrderingPolicy policy) {
        require(version == VERSION, UNSUPPORTED_VERSION);
        require(revisionVersion == 1, UNSUPPORTED_REVISION_VERSION);
        require(revisionId != null && policy != null, INVALID_INPUT);
        require(policy.mode() == QuestionOrder.AUTHORED && policy.algorithmVersion() == 1, UNSUPPORTED_VERSION);
    }
}
