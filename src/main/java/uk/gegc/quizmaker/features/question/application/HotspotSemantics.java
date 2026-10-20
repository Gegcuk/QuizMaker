package uk.gegc.quizmaker.features.question.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Optional author descriptions; AI output must supply a complete textual alternative. */
public final class HotspotSemantics {
    public static final int IMAGE_DESCRIPTION_LIMIT = 2_000;
    public static final int LABEL_LIMIT = 120;
    public static final int REGION_DESCRIPTION_LIMIT = 1_000;

    private HotspotSemantics() { }

    public static void validate(JsonNode content, boolean required) {
        if (content == null || !content.isObject()) {
            throw new QuestionContractException("INVALID_SHAPE", "content");
        }
        validateText(content, "imageDescription", "content", IMAGE_DESCRIPTION_LIMIT, required);
        Set<String> labels = new HashSet<>();
        JsonNode regions = content.path("regions");
        if (!regions.isArray()) throw new QuestionContractException("INVALID_SHAPE", "content.regions");
        for (int i = 0; i < regions.size(); i++) {
            JsonNode region = regions.get(i);
            String path = "content.regions[" + i + "]";
            if (!region.isObject()) throw new QuestionContractException("INVALID_SHAPE", path);
            validateText(region, "label", path, LABEL_LIMIT, required);
            validateText(region, "description", path, REGION_DESCRIPTION_LIMIT, required);
            if (required && !labels.add(region.path("label").textValue().strip().toLowerCase(Locale.ROOT))) {
                throw new QuestionContractException("DUPLICATE_LABEL", path + ".label");
            }
        }
    }

    private static void validateText(JsonNode parent, String name, String path, int maximum, boolean required) {
        JsonNode value = parent.get(name);
        if (value == null || value.isNull() || value.isTextual() && value.textValue().isBlank()) {
            if (required) throw new QuestionContractException("MISSING_FIELD", path + "." + name);
            return;
        }
        if (!value.isTextual()) throw new QuestionContractException("INVALID_SHAPE", path + "." + name);
        String text = value.textValue();
        if (text.codePointCount(0, text.length()) > maximum) {
            throw new QuestionContractException("INVALID_LENGTH", path + "." + name);
        }
    }

    /** Copy only descriptive text, never author-only correctness or arbitrary extensions. */
    public static void copyText(ObjectNode target, JsonNode source, String field) {
        JsonNode value = source.get(field);
        if (value != null && value.isTextual() && !value.textValue().isBlank()) {
            target.put(field, value.textValue());
        }
    }
}
