package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

/** Two explicit columns; only captured right-column identities may be shuffled. */
public final class MatchingResponseContractV1 implements VersionedResponseContract {
    @Override public ContractVersion version() { return SupportedQuestionContracts.MATCHING_RESPONSE; }

    @Override public void validate(QuestionRevision question, JsonNode response) {
        MatchingContentV1.read(question); MatchingContentV1.response(response);
    }

    @Override public OptionLayout optionLayout(QuestionRevision question) {
        var content = MatchingContentV1.read(question);
        var blocks = new ArrayList<OptionLayout.Block>();
        blocks.add(new OptionLayout.Block(question.options().subList(0, content.get("left").size()).stream()
                .map(QuestionRevision.Option::id).toList(), OptionLayout.Pin.FIRST));
        question.options().subList(content.get("left").size(), question.options().size()).forEach(option ->
                blocks.add(new OptionLayout.Block(List.of(option.id()), OptionLayout.Pin.NONE)));
        return new OptionLayout(1, true, blocks);
    }

    @Override public ParticipantContent participantContent(QuestionRevision question) {
        return presentation(question, false);
    }
    @Override public ParticipantContent participantContentWithSeparateAttachment(QuestionRevision question) {
        return presentation(question, true);
    }
    private ParticipantContent presentation(QuestionRevision question, boolean separateAttachment) {
        var content = MatchingContentV1.read(question);
        if (!separateAttachment && (question.attachmentAssetId() != null || question.attachmentUrl() != null))
            throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        var options = question.options().iterator();
        return new ParticipantContent.Matching(column(content.get("left"), options), column(content.get("right"), options));
    }
    private static List<ParticipantContent.MatchingOption> column(JsonNode items, Iterator<QuestionRevision.Option> options) {
        var result = new ArrayList<ParticipantContent.MatchingOption>();
        for (var item : items) {
            ParticipantContent.MatchingImage image = null;
            if (item.hasNonNull("media")) {
                var media = item.get("media");
                // Only publication-resolved, retained images can be presented. No mutable URL/asset lookup.
                if (!media.path("cdnUrl").isTextual() || !media.path("width").isIntegralNumber()
                        || !media.path("height").isIntegralNumber() || !media.path("mimeType").isTextual())
                    throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
                image = new ParticipantContent.MatchingImage(media.get("cdnUrl").textValue(), media.get("width").intValue(),
                        media.get("height").intValue(), media.get("mimeType").textValue());
            }
            result.add(new ParticipantContent.MatchingOption(options.next().id(), item.hasNonNull("text") ? item.get("text").textValue() : null, image));
        }
        return List.copyOf(result);
    }
}
