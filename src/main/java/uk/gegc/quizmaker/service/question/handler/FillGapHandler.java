package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.exception.ValidationException;

@Component
public class FillGapHandler extends QuestionHandler{

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void validateContent(CreateQuestionRequest request) throws ValidationException {
        JsonNode root;
        try {
            root = objectMapper.readTree(request.getContent());
        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON for FILL_GAP");
        }
        JsonNode text = root.get("text");
        JsonNode gaps = root.get("gaps");
        if(text == null || text.asText().isBlank()){
            throw new ValidationException("FILL_GAP requires non-empty 'text' field");
        }
        if(gaps == null || !gaps.isArray() || gaps.isEmpty()) {
            throw new ValidationException("FILL_GAP must have at least one gap defined");
        }
        for(JsonNode gap : gaps){
            if(!gap.has("id") || !gap.has("answer") || gap.get("answer").asText().isBlank()){
                throw new ValidationException("Each gap must have an 'id' and non-empty 'answer'");
            }
            if(!gap.get("id").canConvertToInt()){
                throw new ValidationException("Gap 'id' must be an Integer");
            }
        }
    }
}
