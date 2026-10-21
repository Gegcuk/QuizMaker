package uk.gegc.quizmaker.features.quiz.application.imports;

import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.Map;

/** Existing references indexed by trimmed, lower-case names for one import. */
public record ImportReferenceSnapshot(Map<String, Category> categories, Map<String, Tag> tags) {
    public ImportReferenceSnapshot {
        categories = Map.copyOf(categories);
        tags = Map.copyOf(tags);
    }

    public static ImportReferenceSnapshot empty() {
        return new ImportReferenceSnapshot(Map.of(), Map.of());
    }
}
