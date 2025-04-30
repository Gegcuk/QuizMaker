package uk.gegc.quizmaker.service.question.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.exception.ValidationException;

@Component
public class ComplianceHandler extends QuestionHandler{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void validateContent(CreateQuestionRequest request) throws ValidationException {
        JsonNode root;
        try {
            root = objectMapper.readTree(request.getContent());
        } catch (JsonProcessingException e) {
            throw new ValidationException("Invalid JSON for COMPLIANCE question");
        }
        JsonNode statements = root.get("statements");
        if(statements == null || !statements.isArray() || statements.isEmpty()){
            throw new ValidationException("COMPLIANCE must have at least one statement");
        }
        boolean hasCompliant = false;
        for(JsonNode statement : statements){
            if(!statement.has("text") || statement.get("text").asText().isBlank()){
                throw new ValidationException("Each statement must have a non-empty 'text");
            }
            if(statement.path("compliant").asBoolean(false)){
                hasCompliant = true;
            }
        }
        if(!hasCompliant){
            throw new ValidationException("At least one statement must be marked compliant");
        }
    }
}
