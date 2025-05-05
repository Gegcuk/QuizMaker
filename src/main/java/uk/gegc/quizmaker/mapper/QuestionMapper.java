package uk.gegc.quizmaker.mapper;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionDto;
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;
import uk.gegc.quizmaker.model.question.Question;
import uk.gegc.quizmaker.model.quiz.Quiz;
import uk.gegc.quizmaker.model.quiz.Tag;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class QuestionMapper {

    public static QuestionDto toDto(Question question) {
        QuestionDto dto = new QuestionDto();
        dto.setId(question.getId());
        dto.setType(question.getType());
        dto.setDifficulty(question.getDifficulty());
        dto.setQuestionText(question.getQuestionText());
        dto.setContent(question.getContent());
        dto.setHint(question.getHint());
        dto.setExplanation(question.getExplanation());
        dto.setAttachmentUrl(question.getAttachmentUrl());
        dto.setCreatedAt(question.getCreatedAt());
        dto.setUpdatedAt(question.getUpdatedAt());
        dto.setQuizIds(
                question.getQuizId()
                        .stream()
                        .map(Quiz::getId)
                        .collect(Collectors.toList())
        );
        dto.setTagIds(
                question.getTags()
                        .stream()
                        .map(Tag::getId)
                        .collect(Collectors.toList())
        );
        return dto;
    }

    public static Question toEntity(CreateQuestionRequest req, List<Quiz> quizzes, List<Tag> tags) {
        Question question = new Question();
        question.setType(req.getType());
        question.setDifficulty(req.getDifficulty());
        question.setQuestionText(req.getQuestionText());
        question.setContent(req.getContent().toString());
        question.setHint(req.getHint());
        question.setExplanation(req.getExplanation());
        question.setAttachmentUrl(req.getAttachmentUrl());
        question.setQuizId(quizzes);
        question.setTags(tags);
        return question;
    }

    public static void updateEntity(Question question, UpdateQuestionRequest req,
                                    List<Quiz> quizzes, List<Tag> tags) {
        if (req.getType() != null) {
            question.setType(req.getType());
        }
        if (req.getDifficulty() != null) {
            question.setDifficulty(req.getDifficulty());
        }
        if (req.getQuestionText() != null) {
            question.setQuestionText(req.getQuestionText());
        }
        if (req.getContent() != null) {
            question.setContent(req.getContent().toString());
        }
        if (req.getHint() != null) {
            question.setHint(req.getHint());
        }
        if (req.getExplanation() != null) {
            question.setExplanation(req.getExplanation());
        }
        if (req.getAttachmentUrl() != null) {
            question.setAttachmentUrl(req.getAttachmentUrl());
        }
        if (quizzes != null) {
            question.setQuizId(quizzes);
        }
        if (tags != null) {
            question.setTags(tags);
        }
    }
}
