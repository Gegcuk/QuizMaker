package uk.gegc.quizmaker.features.learning.application.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.ParticipantFormProjector;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import java.util.List;
import java.util.UUID;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.require;

/** Reads only selected immutable revisions, after the owning service has checked current source access. */
@Service
@ConditionalOnProperty(name = "quizmaker.learning.sessions-enabled", havingValue = "true")
public class PublishedLearningItemMaterial implements LearningItemMaterial, LearningFormContent {
    private final LearningPublicationItems publications;
    private final QuestionContractRegistry contracts;

    public PublishedLearningItemMaterial(LearningPublicationItems publications, QuestionContractRegistry contracts) {
        this.publications = publications;
        this.contracts = contracts;
    }

    @Override public List<LearningForm.Item> describe(UUID learner, LearningSession.Source source, List<UUID> questions) {
        var selected = publications.selected(learner, source, questions);
        return questions.stream().map(id -> {
            var row = selected.get(id);
            var content = content(row);
            return new LearningForm.Item(row.sourceItemId(), id, row.question().schemaVersion(),
                    row.binding().response(), LearningFormContent.optionIds(content));
        }).toList();
    }

    @Override public List<PresentedItem> read(LearningSession session, List<LearningForm.Item> items) {
        var selected = publications.selected(session.learnerId(), session.source(),
                items.stream().map(LearningForm.Item::questionRevisionId).toList());
        return items.stream().map(item -> {
            var row = selected.get(item.questionRevisionId());
            var safe = content(row);
            require(row.sourceItemId().equals(item.sourceItemId()) && item.response().equals(row.binding().response())
                    && item.questionSchemaVersion() == row.question().schemaVersion(), SOURCE_UNAVAILABLE);
            return new PresentedItem(item, row.question().questionText(),
                    ParticipantFormProjector.orderedContent(row.question().type(), safe, item.optionIds()));
        }).toList();
    }

    private ParticipantContent content(LearningPublicationItems.Item row) {
        require(row != null && row.question() != null, SOURCE_UNAVAILABLE);
        var question = row.question();
        // These routes have no current-authority image reader. Do not drop required material or expose stored URLs.
        require(question.attachmentUrl() == null && question.attachmentAssetId() == null, SOURCE_UNAVAILABLE);
        var content = contracts.resolve(row.binding()).response().participantContent(question);
        require(content != null && !(content instanceof ParticipantContent.Hotspot), SOURCE_UNAVAILABLE);
        if (content instanceof ParticipantContent.Matching matching) {
            require(java.util.stream.Stream.concat(matching.left().stream(), matching.right().stream())
                    .noneMatch(option -> option.image() != null), SOURCE_UNAVAILABLE);
        }
        if (content instanceof ParticipantContent.Compliance compliance) {
            require(compliance.statements().stream().noneMatch(statement -> statement.image() != null), SOURCE_UNAVAILABLE);
        }
        return content;
    }
}
