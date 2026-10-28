package uk.gegc.quizmaker.features.question.domain.model.contract;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Trusted metadata supplied by an exact response adapter, never a participant request.
 * Blocks partition the authored options into contiguous units. Members keep their relative
 * order; only whole unpinned blocks may move. Semantic-order/disabled-shuffle questions use
 * shuffleAllowed=false. FIRST/LAST anchor the complete block, including singleton options.
 * An adapter must not manufacture constraints or infer this descriptor from a mutable question.
 */
public record OptionLayout(int version, boolean shuffleAllowed, List<Block> blocks) {
    public OptionLayout {
        require(version == 1 && blocks != null);
        var ids = new HashSet<UUID>();
        for (var block : blocks) {
            require(block != null);
            for (var id : block.optionIds()) require(ids.add(id));
        }
        blocks = List.copyOf(blocks);
    }

    public enum Pin { NONE, FIRST, LAST }

    public record Block(List<UUID> optionIds, Pin pin) {
        public Block {
            require(optionIds != null && !optionIds.isEmpty() && pin != null);
            require(optionIds.stream().allMatch(java.util.Objects::nonNull));
            optionIds = List.copyOf(optionIds);
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
    }
}
