package uk.gegc.quizmaker.features.attempt.domain.model.form;

import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException.Reason.INVALID_MATERIAL;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException.require;

/** Participant-safe output only. Protected revision/owner/contract/source references stay internal. */
public record ParticipantForm(UUID id, int schemaVersion, List<Item> items) {
    public ParticipantForm {
        require(id != null && schemaVersion == 1 && items != null, INVALID_MATERIAL);
        var ids = new HashSet<UUID>();
        int previous = -1;
        for (var item : items) {
            require(item != null && ids.add(item.id()) && item.position() > previous, INVALID_MATERIAL);
            previous = item.position();
        }
        items = List.copyOf(items);
    }

    public record Item(UUID id, int position, QuestionType type, String questionText, ParticipantContent content) {
        public Item {
            require(id != null && position >= 0 && type != null && questionText != null && content != null, INVALID_MATERIAL);
        }

        @Override public String toString() { return "ParticipantForm.Item[type=" + type + ", position=" + position + "]"; }
    }

    @Override public String toString() { return "ParticipantForm[schemaVersion=" + schemaVersion + ", itemCount=" + items.size() + "]"; }
}
