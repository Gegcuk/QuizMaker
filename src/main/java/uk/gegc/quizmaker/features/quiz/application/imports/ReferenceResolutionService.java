package uk.gegc.quizmaker.features.quiz.application.imports;

import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ReferenceResolutionService {
    /**
     * Starts a fresh per-thread import cache, reading only the supplied names in bounded batches.
     * Missing names remain eligible for normal resolution and auto-create rules. The returned
     * snapshot also seeds dry-run resolution. Callers must clear caches in a finally block;
     * if preloading fails, clear them before falling back to per-item resolution.
     */
    ImportReferenceSnapshot preloadReferences(Collection<String> categoryNames, Collection<String> tagNames);

    Category resolveCategory(String categoryName, boolean autoCreate, String username);

    Set<Tag> resolveTags(List<String> tagNames, boolean autoCreate, String username);

    default void clearCaches() {
        // no-op by default
    }
}
