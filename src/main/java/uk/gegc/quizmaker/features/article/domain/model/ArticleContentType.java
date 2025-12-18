package uk.gegc.quizmaker.features.article.domain.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

public enum ArticleContentType {
    BLOG("blog"),
    MARKETING("marketing"),
    RESEARCH("research"),
    INSTRUCTIONS("instructions"),
    GUIDE("guide"),
    CASE_STUDY("case_study"),
    PRODUCT_UPDATE("product_update"),
    ANNOUNCEMENT("announcement"),
    ROADMAP("roadmap");

    private final String value;

    ArticleContentType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ArticleContentType fromValue(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String normalized = normalize(rawValue);
        return Arrays.stream(values())
                .filter(type -> normalize(type.value).equals(normalized) || normalize(type.name()).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown article content type: " + rawValue));
    }

    private static String normalize(String input) {
        return input.trim()
                .toLowerCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
    }
}
