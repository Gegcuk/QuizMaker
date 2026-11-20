package uk.gegc.quizmaker.features.learning.application.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.ParticipantFormProjector;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
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
        var selected = publications.firstOccurrences(learner, source, questions);
        return questions.stream().map(id -> {
            var row = selected.get(id);
            require(row != null, SOURCE_UNAVAILABLE);
            var content = content(row);
            return new LearningForm.Item(row.sourceItemId(), id, row.question().schemaVersion(),
                    row.binding().response(), LearningFormContent.optionIds(content));
        }).toList();
    }

    @Override public List<PresentedItem> read(LearningSession session, List<LearningForm.Item> items) {
        var selected = publications.savedOccurrences(session.learnerId(), session.source(),
                items.stream().map(LearningForm.Item::sourceItemId).toList());
        return items.stream().map(item -> {
            var row = selected.get(item.sourceItemId());
            require(row != null, CORRUPT);
            var safe = content(row);
            require(row.question().id().equals(item.questionRevisionId()) && item.response().equals(row.binding().response())
                    && item.questionSchemaVersion() == row.question().schemaVersion(), CORRUPT);
            return new PresentedItem(item, row.question().questionText(),
                    ParticipantFormProjector.orderedContent(row.question().type(), safe, item.optionIds()));
        }).toList();
    }

    private ParticipantContent content(LearningPublicationItems.Item row) {
        require(row.question() != null, CORRUPT);
        var question = row.question();
        // These routes have no current-authority image reader. Do not drop required material or expose stored URLs.
        require(question.attachmentUrl() == null && question.attachmentAssetId() == null, SOURCE_UNAVAILABLE);
        ParticipantContent content;
        try {
            content = contracts.resolve(row.binding()).response().participantContent(question);
        } catch (QuestionContractException invalidStoredContent) {
            throw new LearningFormException(CORRUPT);
        } catch (ContractResolutionException failure) {
            throw new LearningFormException(switch (failure.reason()) {
                case UNSUPPORTED_CONTRACT -> UNSUPPORTED_VERSION;
                case PERSISTENCE_UNAVAILABLE -> PERSISTENCE_UNAVAILABLE;
                case REVISION_UNAVAILABLE -> SOURCE_UNAVAILABLE;
                default -> CORRUPT;
            });
        }
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
