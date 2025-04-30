package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

@Component
public class McqMultiHandler extends QuestionHandler{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root;
        try {
            root = objectMapper.readTree(request.getContent());
        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON for MCQ_MULTI");
        }

        JsonNode options = root.get("options");
        if(options == null || options.size() < 2 || !options.isArray()){
            throw new ValidationException("MCQ_MULTI must have at least 2 options");
        }
        boolean hasCorrect = false;
        for(JsonNode option : options) {
            if(!option.has("text") || option.get("text").asText().isBlank()){
                throw new ValidationException("Each option needs a non-empty 'text'");
            }
            if(option.path("correct").asBoolean(false)) {
                hasCorrect = true;
            }
        }
        if(!hasCorrect)
            throw new ValidationException("MCQ_MULTI must have at least one correct answer");
    }
}
