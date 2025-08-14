package uk.gegc.quizmaker.repository.quiz;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.model.quiz.Quiz;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class QuizSpecifications {

    private QuizSpecifications() {
    }

    public static Specification<Quiz> build(QuizSearchCriteria criteria) {
        return (root, query, cb) -> {
            // Prevent duplicates when joining tags/categories
            if (query != null) {
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();

            if (criteria == null) {
                return cb.and(predicates.toArray(new Predicate[0]));
            }

            // Category by names (case-insensitive)
            if (criteria.category() != null && !criteria.category().isEmpty()) {
                var categoryJoin = root.join("category", JoinType.INNER);
                List<String> lowered = criteria.category().stream()
                        .filter(Objects::nonNull)
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .toList();
                predicates.add(cb.lower(categoryJoin.get("name")).in(lowered));
            }

            // Tag by names (case-insensitive). Any matching tag qualifies
            if (criteria.tag() != null && !criteria.tag().isEmpty()) {
                var tagJoin = root.join("tags", JoinType.INNER);
                List<String> lowered = criteria.tag().stream()
                        .filter(Objects::nonNull)
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .toList();
                predicates.add(cb.lower(tagJoin.get("name")).in(lowered));
            }

            // Author username exact match (case-insensitive)
            if (criteria.authorName() != null && !criteria.authorName().isBlank()) {
                var authorJoin = root.join("creator", JoinType.INNER);
                predicates.add(cb.equal(cb.lower(authorJoin.get("username")),
                        criteria.authorName().toLowerCase(Locale.ROOT)));
            }

            // Full-text like on title or description (case-insensitive)
            if (criteria.search() != null && !criteria.search().isBlank()) {
                String like = "%" + criteria.search().toLowerCase(Locale.ROOT) + "%";
                var titleLike = cb.like(cb.lower(root.get("title")), like);
                var descLike = cb.like(cb.lower(root.get("description")), like);
                predicates.add(cb.or(titleLike, descLike));
            }

            // Difficulty exact match
            if (criteria.difficulty() != null) {
                predicates.add(cb.equal(root.get("difficulty"), criteria.difficulty()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}


