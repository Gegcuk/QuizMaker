package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionContentRequest;
import uk.gegc.quizmaker.exception.ValidationException;

import java.io.IOException;

@Component
public class TrueFalseHandler extends QuestionHandler{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        try{
            JsonNode root = objectMapper.readTree(request.getContent());
            if(!root.has("answer") || !root.get("answer").isBoolean()){
                throw new ValidationException("TRUE_FALSE requires as 'answer' boolean field");
            }
        } catch (IOException e){
            throw new ValidationException("Invalid JSON for TRUE_FALSE");
        }
    }
}
