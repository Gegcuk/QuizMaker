package uk.gegc.quizmaker.features.attempt.domain.model.ordering;

import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy;
import uk.gegc.quizmaker.features.question.domain.model.contract.OptionLayout;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.require;

/** Pure v1 calculation. Resume reads persisted order; this is neither grading nor a random-selection strategy. */
public final class AnswerOptionOrder {
    private AnswerOptionOrder() { }

    public static List<UUID> order(UUID formId, UUID itemId, AnswerOrderingPolicy policy,
                                   QuestionRevision question, OptionLayout layout) {
        require(formId != null && itemId != null && policy != null && question != null && layout != null, INVALID_ORDER);
        require(policy.algorithmVersion() == 1 && layout.version() == 1, UNSUPPORTED_VERSION);
        var authored = question.options().stream().map(QuestionRevision.Option::id).toList();
        // Both strategies validate the complete descriptor, including unshuffled/protected questions.
        require(layout.blocks().stream().flatMap(block -> block.optionIds().stream()).toList().equals(authored), INVALID_ORDER);
        var movable = new ArrayList<OptionLayout.Block>();
        OptionLayout.Block first = null, last = null;
        for (int index = 0; index < layout.blocks().size(); index++) {
            var block = layout.blocks().get(index);
            switch (block.pin()) {
                case FIRST -> { require(first == null && index == 0, INVALID_ORDER); first = block; }
                case LAST -> { require(last == null && index == layout.blocks().size() - 1, INVALID_ORDER); last = block; }
                case NONE -> movable.add(block);
            }
        }
        if (policy.mode() == AnswerOrder.AUTHORED || !layout.shuffleAllowed()) return authored;
        // Server-owned form/item identity and a fixed format seed Fisher-Yates v1. No content is hashed.
        Collections.shuffle(movable, new Random(seed(formId, itemId)));
        var result = new ArrayList<UUID>(authored.size());
        if (first != null) result.addAll(first.optionIds());
        movable.forEach(block -> result.addAll(block.optionIds()));
        if (last != null) result.addAll(last.optionIds());
        return List.copyOf(result);
    }

    private static long seed(UUID form, UUID item) {
        var metadata = ByteBuffer.allocate(36).putInt(1).putLong(form.getMostSignificantBits())
                .putLong(form.getLeastSignificantBits()).putLong(item.getMostSignificantBits()).putLong(item.getLeastSignificantBits());
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(metadata.array())).getLong(); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
