package uk.gegc.quizmaker.features.quiz.infra.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Assembler for mapping Quiz entities to export DTOs.
 * Handles conversion of entities and relationships to stable export format.
 */
@Component
@RequiredArgsConstructor
public class QuizExportAssembler {

    private final ObjectMapper objectMapper;

    /**
     * Convert a single Quiz entity to export DTO
     */
    public QuizExportDto toExportDto(Quiz quiz) {
        List<QuestionExportDto> questions = quiz.getQuestions() != null 
                ? quiz.getQuestions().stream()
                    .sorted(java.util.Comparator
                            .comparing(Question::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                            .thenComparing(Question::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                    .map(this::toQuestionExportDto)
                    .collect(Collectors.toList())
                : new ArrayList<>();

        List<String> tagNames = quiz.getTags() != null
                ? quiz.getTags().stream()
                    .map(Tag::getName)
                    .collect(Collectors.toList())
                : new ArrayList<>();

        return new QuizExportDto(
                quiz.getId(),
                quiz.getTitle(),
                quiz.getDescription(),
                quiz.getVisibility(),
                quiz.getDifficulty(),
                quiz.getEstimatedTime(),
                tagNames,
                quiz.getCategory() != null ? quiz.getCategory().getName() : null,
                quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                questions,
                quiz.getCreatedAt(),
                quiz.getUpdatedAt()
        );
    }

    /**
     * Convert a list of Quiz entities to export DTOs
     */
    public List<QuizExportDto> toExportDtos(List<Quiz> quizzes) {
        if (quizzes == null) {
            return new ArrayList<>();
        }
        return quizzes.stream()
                .map(this::toExportDto)
                .collect(Collectors.toList());
    }

    /**
     * Convert a Question entity to export DTO
     */
    private QuestionExportDto toQuestionExportDto(Question question) {
        JsonNode content = parseContent(question.getContent());
        
        return new QuestionExportDto(
                question.getId(),
                question.getType(),
                question.getDifficulty(),
                question.getQuestionText(),
                content,
                question.getHint(),
                question.getExplanation(),
                question.getAttachmentUrl()
        );
    }

    /**
     * Parse question content JSON string to JsonNode
     */
    private JsonNode parseContent(String contentString) {
        try {
            return objectMapper.readTree(contentString);
        } catch (Exception e) {
            // Return empty object node if parsing fails
            return objectMapper.createObjectNode();
        }
    }
}

