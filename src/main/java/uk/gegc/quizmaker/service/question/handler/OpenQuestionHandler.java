package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.exception.ValidationException;

@Component
public class OpenQuestionHandler extends QuestionHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void validateContent(CreateQuestionRequest req) {
        JsonNode root;
        try {
            root = mapper.readTree(req.getContent());
        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON for OPEN question");
        }
        JsonNode answer = root.get("answer");
        if (answer == null || answer.asText().isBlank()) {
            throw new ValidationException("OPEN question must have a non-empty 'answer' field");
        }
    }
}
