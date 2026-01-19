package uk.gegc.quizmaker.features.quiz.application.imports;

import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.List;
import java.util.Set;

public interface ReferenceResolutionService {
    Category resolveCategory(String categoryName, boolean autoCreate, String username);

    Set<Tag> resolveTags(List<String> tagNames, boolean autoCreate, String username);

    default void clearCaches() {
        // no-op by default
    }
}
