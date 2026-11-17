package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Stable statement identities; correctness is never participant content. */
public final class ComplianceResponseContractV1 implements VersionedResponseContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.COMPLIANCE_RESPONSE; }

    @Override public void validate(QuestionRevision question, JsonNode response) {
        ComplianceContentV1.read(question); ComplianceContentV1.response(response);
    }
    @Override public OptionLayout optionLayout(QuestionRevision question) {
        ComplianceContentV1.read(question);
        return new OptionLayout(1, true, question.options().stream()
                .map(option -> new OptionLayout.Block(List.of(option.id()), OptionLayout.Pin.NONE)).toList());
    }
    @Override public ParticipantContent participantContent(QuestionRevision question) {
        return presentation(question, false);
    }
    @Override public ParticipantContent participantContentWithSeparateAttachment(QuestionRevision question) {
        return presentation(question, true);
    }
    private ParticipantContent presentation(QuestionRevision question, boolean separateAttachment) {
        var content = ComplianceContentV1.read(question);
        if (!separateAttachment && (question.attachmentAssetId() != null || question.attachmentUrl() != null))
            throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        var statements = new ArrayList<ParticipantContent.ComplianceStatement>();
        var options = question.options().iterator();
        for (var item : content.get("statements")) {
            ParticipantContent.ComplianceImage image = null;
            if (item.hasNonNull("media")) {
                var media = item.get("media");
                if (!media.path("width").isIntegralNumber() || !media.path("height").isIntegralNumber()
                        || !media.path("mimeType").isTextual())
                    throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
                image = new ParticipantContent.ComplianceImage(UUID.fromString(media.get("assetId").textValue()),
                        media.get("width").intValue(), media.get("height").intValue(), media.get("mimeType").textValue());
            }
            statements.add(new ParticipantContent.ComplianceStatement(options.next().id(),
                    item.hasNonNull("text") ? item.get("text").textValue() : null, image));
        }
        return new ParticipantContent.Compliance(statements);
    }
}
