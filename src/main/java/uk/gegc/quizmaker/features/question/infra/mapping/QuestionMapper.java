package uk.gegc.quizmaker.features.question.infra.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionDto;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class QuestionMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static QuestionDto toDto(Question question) {
        QuestionDto dto = new QuestionDto();
        dto.setId(question.getId());
        dto.setType(question.getType());
        dto.setDifficulty(question.getDifficulty());
        dto.setQuestionText(question.getQuestionText());

        String raw = question.getContent();
        if (raw != null && !raw.isBlank()) {
            try {
                dto.setContent(MAPPER.readTree(raw));
            } catch (JsonProcessingException e) {
                throw new RuntimeException(
                        "Failed to parse JSON content for question " + question.getId(), e
                );
            }
        } else {
            dto.setContent(null);
        }

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

    public static void updateEntity(Question question,
                                    UpdateQuestionRequest req,
                                    List<Quiz> quizzes,
                                    List<Tag> tags) {
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
            question.getQuizId().clear();
            question.getQuizId().addAll(quizzes);
        }
        if (tags != null) {
            question.getTags().clear();
            question.getTags().addAll(tags);
        }
    }
}