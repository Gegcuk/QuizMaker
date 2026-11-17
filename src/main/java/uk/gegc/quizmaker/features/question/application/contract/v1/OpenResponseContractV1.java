package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** OPEN has no selectable options and never projects the saved answer key into the form. */
public final class OpenResponseContractV1 implements VersionedResponseContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.OPEN_RESPONSE; }

    @Override public void validate(QuestionRevision question, JsonNode response) {
        OpenContentV1.read(question);
        OpenContentV1.response(response);
    }

    @Override public OptionLayout optionLayout(QuestionRevision question) {
        OpenContentV1.read(question);
        return new OptionLayout(1, true, List.of());
    }

    @Override public ParticipantContent participantContent(QuestionRevision question) {
        return participantContent(question, false);
    }

    @Override public ParticipantContent participantContentWithSeparateAttachment(QuestionRevision question) {
        return participantContent(question, true);
    }

    private ParticipantContent participantContent(QuestionRevision question, boolean separateAttachment) {
        JsonNode content = OpenContentV1.read(question);
        if (!separateAttachment && (question.attachmentAssetId() != null || question.attachmentUrl() != null)
                || containsMedia(content))
            throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        return new ParticipantContent.Open();
    }

    private static boolean containsMedia(JsonNode value) {
        if (value.isObject() && (value.hasNonNull("media") || value.hasNonNull("imageUrl"))) return true;
        if (value.isContainerNode()) for (var child : value) if (containsMedia(child)) return true;
        return false;
    }
}
