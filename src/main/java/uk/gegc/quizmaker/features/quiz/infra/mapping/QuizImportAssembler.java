package uk.gegc.quizmaker.features.quiz.infra.mapping;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.util.HashSet;
import java.util.Set;

@Component
public class QuizImportAssembler {

    public Quiz toEntity(QuizImportDto dto,
                         User creator,
                         Category category,
                         Set<Tag> tags,
                         UpsertStrategy strategy) {
        if (dto == null) {
            throw new ValidationException("Quiz payload is required");
        }
        if (creator == null) {
            throw new ValidationException("Creator is required");
        }
        if (category == null) {
            throw new ValidationException("Category is required");
        }

        Quiz quiz = new Quiz();
        if (strategy == UpsertStrategy.UPSERT_BY_ID && dto.id() != null) {
            quiz.setId(dto.id());
        }

        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTitle(dto.title());
        quiz.setDescription(dto.description());
        quiz.setVisibility(dto.visibility() != null ? dto.visibility() : Visibility.PRIVATE);
        quiz.setDifficulty(dto.difficulty() != null ? dto.difficulty() : Difficulty.MEDIUM);

        if (dto.estimatedTime() == null) {
            throw new ValidationException("Estimated time is required");
        }
        quiz.setEstimatedTime(dto.estimatedTime());

        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setTimerDuration(null);
        quiz.setTags(tags != null ? new HashSet<>(tags) : new HashSet<>());

        return quiz;
    }
}
