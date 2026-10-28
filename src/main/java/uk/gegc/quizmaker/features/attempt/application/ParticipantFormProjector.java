package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.FormOptionOrder;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException.require;

/** Pure projection; never serializes QuestionRevision or copies arbitrary JSON into participant data. */
public final class ParticipantFormProjector {
    private ParticipantFormProjector() { }

    public static ParticipantForm project(FixedForm form, QuizRevision revision, FormOptionOrder order,
                                         Map<UUID, ResolvedQuestionContracts> contracts) {
        require(form != null && revision != null && order != null && contracts != null, INVALID_INPUT);
        require(form.quizRevisionId().equals(revision.id()) && form.id().equals(order.formId()), INPUT_MISMATCH);
        require(form.items().size() == order.items().size(), INPUT_MISMATCH);
        var sources = new HashMap<UUID, QuizRevision.Item>();
        revision.items().forEach(item -> sources.put(item.id(), item));
        var orders = new HashMap<UUID, FormOptionOrder.Item>();
        order.items().forEach(item -> orders.put(item.formItemId(), item));
        var result = new ArrayList<ParticipantForm.Item>();
        for (var item : form.items()) {
            var source = sources.get(item.sourceItemId());
            var options = orders.get(item.id());
            require(source != null && options != null && item.sourceItemId().equals(options.sourceItemId()), INPUT_MISMATCH);
            var question = source.question();
            var pair = contracts.get(question.id());
            require(pair != null && pair.binding().equals(options.binding()), INPUT_MISMATCH);
            // Media retention/generation authority belongs to the real publication/media integrations.
            require(question.attachmentAssetId() == null && question.attachmentUrl() == null, UNSUPPORTED_MEDIA);
            ParticipantContent content;
            try {
                content = pair.response().participantContent(question);
                require(content != null, INVALID_MATERIAL);
                content = orderedContent(question.type(), content, options.optionIds());
            } catch (ContractResolutionException | FormMaterializationException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                // Adapter diagnostics can contain raw source/answers. Do not preserve them as causes.
                throw new FormMaterializationException(INVALID_MATERIAL);
            }
            result.add(new ParticipantForm.Item(item.id(), item.position(), question.type(), question.questionText(), content));
        }
        return new ParticipantForm(form.id(), form.schemaVersion(), result);
    }

    private static ParticipantContent orderedContent(QuestionType type, ParticipantContent content, List<UUID> order) {
        return switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> {
                require(content instanceof ParticipantContent.Choices, INVALID_MATERIAL);
                yield new ParticipantContent.Choices(reorder(((ParticipantContent.Choices) content).options(), order));
            }
            case TRUE_FALSE -> {
                require(content instanceof ParticipantContent.TrueFalse && order.isEmpty(), INVALID_MATERIAL);
                yield content;
            }
            case FILL_GAP -> {
                require(content instanceof ParticipantContent.FillGap, INVALID_MATERIAL);
                var gap = (ParticipantContent.FillGap) content;
                yield new ParticipantContent.FillGap(gap.text(), gap.gaps(), reorder(gap.options(), order));
            }
            case ORDERING -> {
                require(content instanceof ParticipantContent.Ordering, INVALID_MATERIAL);
                yield new ParticipantContent.Ordering(reorder(((ParticipantContent.Ordering) content).items(), order));
            }
            default -> throw new FormMaterializationException(UNSUPPORTED_FAMILY);
        };
    }

    private static List<ParticipantContent.Option> reorder(List<ParticipantContent.Option> options, List<UUID> order) {
        var byId = new HashMap<UUID, ParticipantContent.Option>();
        options.forEach(option -> byId.put(option.id(), option));
        require(byId.size() == order.size() && byId.keySet().containsAll(order), INVALID_MATERIAL);
        return order.stream().map(byId::get).toList();
    }
}
