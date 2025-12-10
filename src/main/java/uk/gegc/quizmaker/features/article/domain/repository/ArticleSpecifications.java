package uk.gegc.quizmaker.features.article.domain.repository;

import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import uk.gegc.quizmaker.features.article.api.dto.ArticleSearchCriteria;
import uk.gegc.quizmaker.features.article.domain.model.Article;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ArticleSpecifications {

    private ArticleSpecifications() {
    }

    public static Specification<Article> build(ArticleSearchCriteria criteria) {
        return (root, query, cb) -> {
            if (query != null) {
                query.distinct(true);
            }

            List<Predicate> predicates = new ArrayList<>();

            if (criteria == null) {
                return cb.and(predicates.toArray(new Predicate[0]));
            }

            if (criteria.status() != null) {
                predicates.add(cb.equal(root.get("status"), criteria.status()));
            }

            if (criteria.contentGroup() != null && !criteria.contentGroup().isBlank()) {
                predicates.add(cb.equal(cb.lower(root.get("contentGroup")),
                        criteria.contentGroup().toLowerCase(Locale.ROOT)));
            }

            if (criteria.tags() != null && !criteria.tags().isEmpty()) {
                var tagJoin = root.join("tags", JoinType.INNER);
                List<String> loweredTags = criteria.tags().stream()
                        .filter(Objects::nonNull)
                        .map(tag -> tag.toLowerCase(Locale.ROOT))
                        .toList();
                predicates.add(cb.lower(tagJoin.get("name")).in(loweredTags));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
