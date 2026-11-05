package uk.gegc.quizmaker.features.question.application.contract.v1;

import com.fasterxml.jackson.databind.JsonNode;
import uk.gegc.quizmaker.features.question.application.QuestionContractException;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision;

import java.util.*;
import java.util.stream.StreamSupport;

/** Version-owned rules on immutable values; no handler dispatch or mutable authoring entity. */
final class FamilyContentV1 {
    private FamilyContentV1() { }

    static JsonNode read(QuestionType type, QuestionRevision question) {
        if (question == null || question.schemaVersion() != 1 || question.type() != type)
            throw new ContractResolutionException(ContractResolutionException.Reason.INVALID_BINDING);
        JsonNode content = FamilyShapeV1.read(type, FamilyShapeV1.View.AUTHOR, question.content());
        switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> {
                long correct = values(content.get("options")).stream().filter(n -> n.path("correct").asBoolean()).count();
                require(type == QuestionType.MCQ_SINGLE ? correct == 1 : correct > 0);
                content.get("options").forEach(FamilyContentV1::textOrMedia);
            }
            case ORDERING -> content.get("items").forEach(FamilyContentV1::textOrMedia);
            case TRUE_FALSE -> { /* The boolean shape is complete. */ }
            case FILL_GAP -> fillGap(content);
            default -> throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        }
        // Identity completeness comes from the publication format, never display positions.
        List<String> expected = keys(type, content);
        require(expected.equals(question.options().stream().map(QuestionRevision.Option::sourceKey).toList()));
        return content;
    }

    static List<String> keys(QuestionType type, JsonNode content) {
        return switch (type) {
            case MCQ_SINGLE, MCQ_MULTI -> values(content.path("options")).stream().map(n -> n.path("id").textValue()).toList();
            case ORDERING -> values(content.path("items")).stream().map(n -> n.path("id").asText()).toList();
            case FILL_GAP -> java.util.stream.IntStream.range(0, content.path("options").size()).mapToObj(n -> "options/" + n).toList();
            case TRUE_FALSE -> List.of();
            default -> throw new ContractResolutionException(ContractResolutionException.Reason.UNSUPPORTED_CONTRACT);
        };
    }

    static List<JsonNode> values(JsonNode array) { return StreamSupport.stream(array.spliterator(), false).toList(); }

    /** Owner-approved v1 refinement: stable casing regardless of the host's default locale. */
    static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }

    private static void fillGap(JsonNode content) {
        var gaps = values(content.get("gaps"));
        var ids = new HashSet<Integer>();
        for (var gap : gaps) {
            require(!gap.get("answer").textValue().isBlank());
            ids.add(gap.get("id").intValue());
        }
        for (int i = 1; i <= gaps.size(); i++) require(ids.contains(i));
        var placeholders = new HashSet<Integer>();
        var matcher = java.util.regex.Pattern.compile("\\{(\\d+)\\}").matcher(content.get("text").textValue());
        try { while (matcher.find()) placeholders.add(Integer.parseInt(matcher.group(1))); }
        catch (NumberFormatException invalid) { require(false); }
        require(!content.get("text").textValue().isBlank() && placeholders.equals(ids));
        if (content.has("options")) {
            var answers = new HashSet<String>();
            for (var gap : gaps) require(answers.add(normalize(gap.get("answer").textValue())));
            var options = new HashSet<String>();
            for (var option : content.get("options"))
                require(!option.textValue().isBlank() && options.add(normalize(option.textValue())));
            require(options.containsAll(answers) && options.size() - gaps.size() >= 6 && options.size() <= 10);
        }
    }

    private static void textOrMedia(JsonNode item) {
        require(!item.path("text").asText("").isBlank() || item.hasNonNull("media"));
    }

    static void require(boolean valid) {
        if (!valid) throw new QuestionContractException("INVALID_CONTENT", "content");
    }
}
