package uk.gegc.quizmaker.features.quiz.infra.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;

import java.util.ArrayList;
import java.util.Collections;
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
        
        // Shuffle ORDERING, MATCHING, MCQ, and COMPLIANCE question content to prevent obvious answers
        if (question.getType() == QuestionType.ORDERING 
                || question.getType() == QuestionType.MATCHING
                || question.getType() == QuestionType.MCQ_SINGLE
                || question.getType() == QuestionType.MCQ_MULTI
                || question.getType() == QuestionType.COMPLIANCE) {
            content = shuffleQuestionContent(content, question.getType());
        }
        
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

    /**
     * Shuffle question content for ORDERING, MATCHING, MCQ, and COMPLIANCE questions to prevent obvious answers
     */
    private JsonNode shuffleQuestionContent(JsonNode content, QuestionType type) {
        if (content == null || !content.isObject()) {
            return content;
        }

        ObjectNode shuffledContent = content.deepCopy();

        if (type == QuestionType.MCQ_SINGLE || type == QuestionType.MCQ_MULTI) {
            // Shuffle MCQ options
            if (content.has("options")) {
                ArrayNode options = (ArrayNode) content.get("options");
                List<JsonNode> optionsList = new ArrayList<>();
                options.forEach(optionsList::add);
                Collections.shuffle(optionsList);
                
                ArrayNode shuffledOptions = objectMapper.createArrayNode();
                optionsList.forEach(shuffledOptions::add);
                shuffledContent.set("options", shuffledOptions);
            }
        } else if (type == QuestionType.COMPLIANCE) {
            // Shuffle COMPLIANCE statements
            if (content.has("statements")) {
                ArrayNode statements = (ArrayNode) content.get("statements");
                List<JsonNode> statementsList = new ArrayList<>();
                statements.forEach(statementsList::add);
                Collections.shuffle(statementsList);
                
                ArrayNode shuffledStatements = objectMapper.createArrayNode();
                statementsList.forEach(shuffledStatements::add);
                shuffledContent.set("statements", shuffledStatements);
            }
        } else if (type == QuestionType.ORDERING && content.has("items")) {
            // Preserve the original correct order before shuffling
            ArrayNode items = (ArrayNode) content.get("items");
            ArrayNode correctOrder = objectMapper.createArrayNode();
            for (JsonNode item : items) {
                if (item.has("id")) {
                    correctOrder.add(item.get("id").asInt());
                }
            }
            shuffledContent.set("correctOrder", correctOrder);
            
            // Shuffle ORDERING items for display
            List<JsonNode> itemsList = new ArrayList<>();
            items.forEach(itemsList::add);
            Collections.shuffle(itemsList);
            
            ArrayNode shuffledItems = objectMapper.createArrayNode();
            itemsList.forEach(shuffledItems::add);
            shuffledContent.set("items", shuffledItems);
        } else if (type == QuestionType.MATCHING && content.has("right")) {
            // Shuffle MATCHING right column items
            ArrayNode rightItems = (ArrayNode) content.get("right");
            List<JsonNode> rightItemsList = new ArrayList<>();
            rightItems.forEach(rightItemsList::add);
            Collections.shuffle(rightItemsList);
            
            ArrayNode shuffledRightItems = objectMapper.createArrayNode();
            rightItemsList.forEach(shuffledRightItems::add);
            shuffledContent.set("right", shuffledRightItems);
        }

        return shuffledContent;
    }
}

