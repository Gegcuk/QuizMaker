package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.util.*;

/** The response payload retains the legacy v1 fields and captured source keys, never display indexes. */
public final class FamilyResponseContractV1 implements VersionedResponseContract {
    private final QuestionType type;
    private final ContractVersion version;

    public FamilyResponseContractV1(QuestionType type) {
        this.type = type;
        this.version = BaselineQuestionContracts.response(type);
    }

    @Override public ContractVersion version() { return version; }

    @Override public void validate(QuestionRevision question, JsonNode response) {
        FamilyContentV1.read(type, question);
        FamilyShapeV1.validate(type, FamilyShapeV1.View.RESPONSE, response);
    }

    @Override public OptionLayout optionLayout(QuestionRevision question) {
        FamilyContentV1.read(type, question);
        // These v1 source schemas define independent options; policy still selects AUTHORED by default.
        return new OptionLayout(1, true, question.options().stream()
                .map(option -> new OptionLayout.Block(List.of(option.id()), OptionLayout.Pin.NONE)).toList());
    }

    @Override public ParticipantContent participantContent(QuestionRevision question) {
        JsonNode content = FamilyContentV1.read(type, question);
        if (question.attachmentAssetId() != null || question.attachmentUrl() != null || containsMedia(content))
            throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        var options = new ArrayList<ParticipantContent.Option>();
        for (int i = 0; i < question.options().size(); i++) {
            String text = switch (type) {
                case MCQ_SINGLE, MCQ_MULTI -> content.path("options").get(i).path("text").asText();
                case ORDERING -> content.path("items").get(i).path("text").asText();
                case FILL_GAP -> content.path("options").get(i).textValue();
                default -> throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
            };
            options.add(new ParticipantContent.Option(question.options().get(i).id(), text));
        }
        return switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> new ParticipantContent.Choices(options);
            case TRUE_FALSE -> new ParticipantContent.TrueFalse();
            case ORDERING -> new ParticipantContent.Ordering(options);
            case FILL_GAP -> new ParticipantContent.FillGap(content.path("text").textValue(),
                    FamilyContentV1.values(content.path("gaps")).stream().map(n -> new ParticipantContent.Gap(n.path("id").intValue())).toList(), options);
            default -> throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        };
    }

    private static boolean containsMedia(JsonNode node) {
        if (node.isObject() && (node.hasNonNull("media") || node.hasNonNull("imageUrl"))) return true;
        if (node.isContainerNode()) for (var child : node) if (containsMedia(child)) return true;
        return false;
    }
}
