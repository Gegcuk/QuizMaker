package uk.gegc.quizmaker.mapper;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.QuizStatus;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.model.user.User;

import java.util.Set;
import java.util.stream.Collectors;


@Component
public class QuizMapper {

    public Quiz toEntity(CreateQuizRequest req, User creator, Category category, Set<Tag> tags) {
        Quiz quiz = new Quiz();
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTitle(req.title());
        quiz.setDescription(req.description());
        quiz.setVisibility(req.visibility());
        quiz.setDifficulty(req.difficulty());
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(req.estimatedTime());
        quiz.setIsRepetitionEnabled(req.isRepetitionEnabled());
        quiz.setIsTimerEnabled(req.timerEnabled());
        quiz.setTimerDuration(req.timerDuration());
        quiz.setTags(tags);
        return quiz;
    }

    public void updateEntity(Quiz quiz, UpdateQuizRequest req, Category category, Set<Tag> tags) {
        if (category != null) {
            quiz.setCategory(category);
        }
        if (req.title() != null) {
            quiz.setTitle(req.title());
        }
        if (req.description() != null) {
            quiz.setDescription(req.description());
        }
        if (req.visibility() != null) {
            quiz.setVisibility(req.visibility());
        }
        if (req.difficulty() != null) {
            quiz.setDifficulty(req.difficulty());
        }
        if (req.estimatedTime() != null) {
            quiz.setEstimatedTime(req.estimatedTime());
        }
        if (req.isRepetitionEnabled() != null) {
            quiz.setIsRepetitionEnabled(req.isRepetitionEnabled());
        }
        if (req.timerEnabled() != null) {
            quiz.setIsTimerEnabled(req.timerEnabled());
        }
        if (req.timerDuration() != null) {
            quiz.setTimerDuration(req.timerDuration());
        }
        if (tags != null) {
            quiz.setTags(tags);
        }
    }

    public QuizDto toDto(Quiz quiz) {
        return new QuizDto(
                quiz.getId(),
                quiz.getCreator().getId(),
                quiz.getCategory().getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getVisibility(),
                quiz.getDifficulty(),
                quiz.getStatus(),
                quiz.getEstimatedTime(),
                quiz.getIsRepetitionEnabled(),
                quiz.getIsTimerEnabled(),
                quiz.getTimerDuration(),
                quiz.getTags().stream().map(Tag::getId).collect(Collectors.toList()),
                quiz.getCreatedAt(),
                quiz.getUpdatedAt()
        );
    }
}
