package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptFormResponse;
import uk.gegc.quizmaker.features.attempt.api.dto.ParticipantAttemptView;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;

/** Participant-safe projection from the saved form. Callers decide which content can be disclosed. */
final class ParticipantViewItems {
    private ParticipantViewItems() { }

    static ParticipantForm project(AttemptReadFactsService.Facts saved) {
        var sources = saved.revision().items().stream().collect(Collectors.toMap(i -> i.id(), i -> i.question()));
        // Validate the complete immutable form even when its content must be withheld.
        var allMediaIds = saved.form().items().stream().filter(i -> {
            var q = sources.get(i.sourceItemId()); return q != null && q.attachmentAssetId() != null;
        }).map(FixedForm.Item::id).collect(Collectors.toSet());
        return ParticipantFormProjector.projectForView(saved.form(), saved.revision(), saved.order(), saved.contracts(), allMediaIds);
    }

    static List<ParticipantAttemptView.Item> visible(UUID attemptId, AttemptReadFactsService.Facts saved,
            ParticipantForm projected, ParticipantViewMediaReader media) {
        var visible = projected.items().stream().filter(item -> saved.access().lifecycle().state().terminal() || saved.visible(item.id()))
                .map(ParticipantForm.Item::id).collect(Collectors.toSet());
        var images = ParticipantFormMedia.read(media, attemptId, saved.form(), saved.revision(), projected, visible, saved.access().lifecycle().state().terminal());
        var attachments = images.entrySet().stream().filter(e -> e.getKey().optionId() == null)
                .collect(Collectors.toMap(e -> e.getKey().formItemId(), Map.Entry::getValue));
        var permitted = new ParticipantForm(projected.id(), projected.schemaVersion(),
                projected.items().stream().filter(item -> visible.contains(item.id())).toList());
        return AttemptFormResponse.items(permitted, images).stream().map(item -> {
            var image = attachments.get(item.id());
            return new ParticipantAttemptView.Item(item.id(), item.position(), item.type(), item.questionText(), item.content(),
                    image == null ? null : new ParticipantAttemptView.Image(image.availability(), image.url()));
        }).toList();
    }
}
