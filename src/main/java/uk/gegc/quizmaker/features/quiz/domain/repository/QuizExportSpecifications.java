package uk.gegc.quizmaker.features.quiz.domain.repository;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportFilter;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Specifications for building quiz export queries.
 * Supports filtering by multiple criteria.
 */
public final class QuizExportSpecifications {

    private QuizExportSpecifications() {
    }

    /**
     * Build specification from export filter
     */
    public static Specification<Quiz> build(QuizExportFilter filter) {
        return (root, query, cb) -> {
            // Prevent duplicates when joining
            if (query != null) {
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();

            // Always exclude deleted quizzes
            predicates.add(cb.equal(root.get("isDeleted"), false));

            if (filter == null) {
                return cb.and(predicates.toArray(new Predicate[0]));
            }

            // Filter by specific quiz IDs
            if (filter.quizIds() != null && !filter.quizIds().isEmpty()) {
                predicates.add(root.get("id").in(filter.quizIds()));
            }

            // Filter by category IDs
            if (filter.categoryIds() != null && !filter.categoryIds().isEmpty()) {
                var categoryJoin = root.join("category", JoinType.INNER);
                predicates.add(categoryJoin.get("id").in(filter.categoryIds()));
            }

            // Filter by tags (any matching tag qualifies)
            if (filter.tags() != null && !filter.tags().isEmpty()) {
                var tagJoin = root.join("tags", JoinType.INNER);
                List<String> lowered = filter.tags().stream()
                        .filter(Objects::nonNull)
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .toList();
                predicates.add(cb.lower(tagJoin.get("name")).in(lowered));
            }

            // Filter by author ID
            if (filter.authorId() != null) {
                predicates.add(cb.equal(root.get("creator").get("id"), filter.authorId()));
            }

            // Filter by difficulty
            if (filter.difficulty() != null) {
                predicates.add(cb.equal(root.get("difficulty"), filter.difficulty()));
            }

            // Search in title or description
            if (filter.search() != null && !filter.search().isBlank()) {
                String like = "%" + filter.search().toLowerCase(Locale.ROOT) + "%";
                var titleLike = cb.like(cb.lower(root.get("title")), like);
                var descLike = cb.like(cb.lower(root.get("description")), like);
                predicates.add(cb.or(titleLike, descLike));
            }

            // Apply scope-based filtering
            if (filter.scope() != null) {
                switch (filter.scope().toLowerCase()) {
                    case "public" -> {
                        // Public scope: only PUBLIC visibility and PUBLISHED status
                        predicates.add(cb.equal(root.get("visibility"), Visibility.PUBLIC));
                        predicates.add(cb.equal(root.get("status"), QuizStatus.PUBLISHED));
                    }
                    case "me" -> {
                        // Me scope: user's own quizzes (filtered by authorId in filter)
                        // No additional predicates needed beyond authorId
                    }
                    case "all" -> {
                        // All scope: no additional restrictions (requires QUIZ_MODERATE permission)
                        // No additional predicates
                    }
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}

