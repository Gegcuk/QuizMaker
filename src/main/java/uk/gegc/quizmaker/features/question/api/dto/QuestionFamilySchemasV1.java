package uk.gegc.quizmaker.features.question.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Active manual-authoring and participant JSON Schema definitions; independent of stricter AI prompt rules")
public record QuestionFamilySchemasV1(int version, JsonNode author, JsonNode safe, JsonNode response, JsonNode correct) { }
