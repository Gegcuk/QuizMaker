package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Internal region projection only; public image/description delivery remains a separate capability. */
public final class HotspotResponseContractV1 implements VersionedResponseContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.HOTSPOT_RESPONSE; }

    @Override public void validate(QuestionRevision question, JsonNode response) {
        HotspotContentV1.read(question);
        HotspotContentV1.response(response);
    }
    @Override public OptionLayout optionLayout(QuestionRevision question) {
        HotspotContentV1.read(question);
        return new OptionLayout(1, true, question.options().stream()
                .map(option -> new OptionLayout.Block(List.of(option.id()), OptionLayout.Pin.NONE)).toList());
    }
    @Override public ParticipantContent participantContent(QuestionRevision question) {
        var content = HotspotContentV1.read(question);
        if (question.attachmentAssetId() == null || question.attachmentUrl() == null
                || !question.attachmentUrl().equals(content.path("imageUrl").textValue()))
            throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        var regions = new ArrayList<ParticipantContent.HotspotRegion>();
        var options = question.options().iterator();
        for (var region : content.path("regions")) regions.add(new ParticipantContent.HotspotRegion(
                options.next().id(), region.path("x").intValue(), region.path("y").intValue(),
                region.path("width").intValue(), region.path("height").intValue(),
                HotspotContentV1.text(region, "label", 120), HotspotContentV1.text(region, "description", 1_000)));
        return new ParticipantContent.Hotspot(question.attachmentAssetId(),
                HotspotContentV1.text(content, "imageDescription", 2_000), regions);
    }
    @Override public ParticipantContent participantContentWithSeparateAttachment(QuestionRevision question) {
        return participantContent(question);
    }
}
