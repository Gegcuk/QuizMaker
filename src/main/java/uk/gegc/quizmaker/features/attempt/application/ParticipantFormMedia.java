package uk.gegc.quizmaker.features.attempt.application;

import java.util.*;
import java.util.stream.Collectors;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.*;

/** One current-authority batch for every visible image occurrence, with no storage or URL fallback. */
public final class ParticipantFormMedia {
    private ParticipantFormMedia() { }

    public static Map<ParticipantViewMediaReader.Reference, ParticipantViewMediaReader.Image> read(
            ParticipantViewMediaReader reader, UUID attemptId, FixedForm form, QuizRevision revision,
            ParticipantForm projected, Set<UUID> visible, boolean terminal) {
        var sources = revision.items().stream().collect(Collectors.toMap(i -> i.id(), i -> i.question()));
        var content = projected.items().stream().collect(Collectors.toMap(ParticipantForm.Item::id, ParticipantForm.Item::content));
        var references = new ArrayList<ParticipantViewMediaReader.Reference>();
        for (var item : form.items()) {
            if (!visible.contains(item.id())) continue;
            var question = sources.get(item.sourceItemId());
            if (question == null || !content.containsKey(item.id())) throw new AttemptDeliveryException(CORRUPT);
            if (question.attachmentAssetId() != null || question.attachmentUrl() != null) {
                if (question.attachmentAssetId() == null) throw new AttemptDeliveryException(CORRUPT);
                references.add(new ParticipantViewMediaReader.Reference(item.id(), question.id(), question.attachmentAssetId()));
            }
            if (content.get(item.id()) instanceof ParticipantContent.Matching matching) {
                for (var column : List.of(matching.left(), matching.right())) for (var option : column)
                    if (option.image() != null) references.add(new ParticipantViewMediaReader.Reference(
                            item.id(), question.id(), option.image().assetId(), option.id()));
            }
            if (content.get(item.id()) instanceof ParticipantContent.Compliance compliance) {
                for (var statement : compliance.statements()) if (statement.image() != null)
                    references.add(new ParticipantViewMediaReader.Reference(item.id(), question.id(),
                            statement.image().assetId(), statement.id()));
            }
        }
        var expected = new HashSet<>(references);
        if (expected.size() != references.size()) throw new AttemptDeliveryException(CORRUPT);
        var images = reader.read(attemptId, revision.id(), List.copyOf(references));
        if (images == null || !images.keySet().equals(expected) || images.values().stream().anyMatch(Objects::isNull))
            throw new AttemptDeliveryException(CORRUPT);
        if (!terminal && images.values().stream().anyMatch(i -> i.availability() != ParticipantViewMediaReader.Availability.AVAILABLE))
            throw new AttemptDeliveryException(UNAVAILABLE);
        return Map.copyOf(images);
    }
}
