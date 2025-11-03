package uk.gegc.quizmaker.features.question.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JSON Schema and example for a question type")
public record QuestionSchemaResponse(
    @Schema(description = "JSON Schema defining the content structure for this question type")
    JsonNode schema,
    
    @Schema(description = "Example content for this question type")
    JsonNode example,
    
    @Schema(description = "Human-readable description of this question type")
    String description
) {
}

