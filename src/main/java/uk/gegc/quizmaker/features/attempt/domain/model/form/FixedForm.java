package uk.gegc.quizmaker.features.attempt.domain.model.form;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException.Reason.*;

/** Identity and supplied order only. Selection, ordering algorithms and delivery are separate. */
public record FixedForm(UUID id, int schemaVersion, UUID quizRevisionId, List<Item> items) {
    public FixedForm {
        if (schemaVersion != 1) throw new FixedFormException(UNSUPPORTED_VERSION);
        require(id != null && quizRevisionId != null && items != null);
        var ids = new HashSet<UUID>();
        var sources = new HashSet<UUID>();
        var positions = new HashSet<Integer>();
        for (var item : items) {
            require(item != null && ids.add(item.id()) && sources.add(item.sourceItemId())
                    && positions.add(item.position()));
        }
        items = items.stream().sorted(Comparator.comparingInt(Item::position)).toList();
    }

    public record Item(UUID id, UUID sourceItemId, int position) {
        public Item { require(id != null && sourceItemId != null && position >= 0); }
    }

    private static void require(boolean valid) {
        if (!valid) throw new FixedFormException(INVALID_FORM);
    }
}
