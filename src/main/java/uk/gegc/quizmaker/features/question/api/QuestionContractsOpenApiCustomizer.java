package uk.gegc.quizmaker.features.question.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.application.QuestionContracts.View;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds the same v1 definitions used at runtime to every document containing these DTOs. */
public final class QuestionContractsOpenApiCustomizer implements GlobalOpenApiCustomizer {
    @Override
    public void customise(OpenAPI api) {
        if (api.getComponents() == null || api.getComponents().getSchemas() == null) return;
        Map<String, Schema> schemas = api.getComponents().getSchemas();
        if (List.of("QuestionDto", "CreateQuestionRequest", "QuestionForAttemptDto", "AnswerSubmissionRequest", "AnswerReviewDto")
                .stream().noneMatch(schemas::containsKey)) return;

        for (QuestionType type : QuestionType.values()) {
            for (View view : View.values()) {
                Schema<?> schema = convert(QuestionContracts.schema(type, view));
                schema.setExample(QuestionContracts.example(type, view));
                schema.addExtension("x-question-type", type.name());
                schema.addExtension("x-contract-version", 1);
                schemas.put(QuestionContracts.name(type, view), schema);
            }
        }
        bind(schemas, "CreateQuestionRequest", "content", View.AUTHOR, false);
        bind(schemas, "UpdateQuestionRequest", "content", View.AUTHOR, false);
        bind(schemas, "QuestionDto", "content", View.AUTHOR, true);
        bind(schemas, "QuestionForAttemptDto", "safeContent", View.SAFE, true);
        bind(schemas, "AnswerSubmissionRequest", "response", View.RESPONSE, false);
        bind(schemas, "AnswerSubmissionDto", "correctAnswer", View.CORRECT, true);
        bind(schemas, "AnswerReviewDto", "questionSafeContent", View.SAFE, true);
        bind(schemas, "AnswerReviewDto", "userResponse", View.RESPONSE, true);
        bind(schemas, "AnswerReviewDto", "correctAnswer", View.CORRECT, true);

        for (String dto : List.of("CreateQuestionRequest", "UpdateQuestionRequest", "AnswerSubmissionRequest",
                "QuestionDto", "QuestionForAttemptDto", "AnswerSubmissionDto", "AnswerReviewDto")) {
            Schema<?> schema = schemas.get(dto);
            if (schema == null) continue;
            Schema<?> version = new IntegerSchema()._enum(List.of(1))._default(1)
                    .description("Contract version 1. Legacy requests may omit it; unknown or null versions return 400 before mutation.");
            if (!dto.endsWith("Request")) version.setReadOnly(true);
            schema.addProperty("contractVersion", version);
        }
        familyEnvelopes(schemas, "CreateQuestionRequest", Map.of("content", View.AUTHOR), false);
        familyEnvelopes(schemas, "UpdateQuestionRequest", Map.of("content", View.AUTHOR), false);
        familyEnvelopes(schemas, "QuestionDto", Map.of("content", View.AUTHOR), true);
        familyEnvelopes(schemas, "QuestionForAttemptDto", Map.of("safeContent", View.SAFE), true);
        familyEnvelopes(schemas, "AnswerReviewDto", Map.of("questionSafeContent", View.SAFE,
                "userResponse", View.RESPONSE, "correctAnswer", View.CORRECT), true);

        // Participant type selects the family. TRUE_FALSE and OPEN both have empty safe content;
        // using oneOf here would incorrectly reject those valid payloads as ambiguous.
        if (api.getPaths() != null) api.getPaths().forEach((path, item) -> {
            if (!(path.startsWith("/api/v1/questions") || path.startsWith("/api/v1/attempts")
                    || path.contains("/shared/"))) return;
            item.readOperationsMap().forEach((method, operation) -> {
                if (operation.getResponses() == null) return;
                if (method != io.swagger.v3.oas.models.PathItem.HttpMethod.GET) {
                    operation.getResponses().addApiResponse("400", problem("Invalid question data or unsupported contract version"));
                    if (path.endsWith("/answers") || path.endsWith("/answers/batch") || path.endsWith("/complete")) {
                        operation.getResponses().addApiResponse("409", problem("Referenced question data is unavailable; no partial write"));
                    }
                }
            });
        });
    }

    private static void bind(Map<String, Schema> schemas, String dto, String property, View view, boolean nullable) {
        Schema<?> envelope = schemas.get(dto);
        if (envelope == null || envelope.getProperties() == null || !envelope.getProperties().containsKey(property)) return;
        List<Schema> alternatives = new ArrayList<>();
        for (QuestionType type : QuestionType.values()) alternatives.add(new Schema<>().$ref("#/components/schemas/" + QuestionContracts.name(type, view)));
        if (nullable) alternatives.add(new Schema<>().types(Set.of("null")));
        Schema<?> union = new ComposedSchema().anyOf(alternatives).description(
                "V1 family is selected by the question type (or resolved questionId for submission). "
                        + "NOT_INCLUDED means omitted by existing inclusion rules, never false/incorrect. "
                        + "UNAVAILABLE means invalid stored data; see contentProblem. HOTSPOT correct answer uses regionId; participant response uses selectedRegionId. "
                        + "Web/iOS: absent contractVersion means v1; tolerate additive availability fields, never infer an answer or score from omission.");
        envelope.addProperty(property, union);
    }

    private static void familyEnvelopes(Map<String, Schema> schemas, String dto, Map<String, View> payloads, boolean nullable) {
        Schema<?> base = schemas.get(dto);
        if (base == null || base.getProperties() == null) return;
        List<Schema> alternatives = new ArrayList<>();
        Map<String, String> discriminator = new LinkedHashMap<>();
        for (QuestionType type : QuestionType.values()) {
            Schema<Object> family = new Schema<>();
            family.setType("object");
            family.setProperties(new LinkedHashMap<>(base.getProperties()));
            family.addProperty("type", new StringSchema()._enum(List.of(type.name())));
            List<String> required = new ArrayList<>(base.getRequired() == null ? List.of() : base.getRequired());
            if (!required.contains("type")) required.add("type");
            if (dto.equals("QuestionDto") || dto.equals("QuestionForAttemptDto")) {
                for (String field : List.of("id", "questionText", "difficulty", dto.equals("QuestionDto") ? "content" : "safeContent")) {
                    if (!required.contains(field)) required.add(field);
                }
            } else if (dto.equals("AnswerReviewDto") && !required.contains("questionId")) required.add("questionId");
            family.setRequired(required);
            payloads.forEach((property, view) -> {
                Schema<?> reference = new Schema<>().$ref("#/components/schemas/" + QuestionContracts.name(type, view));
                if (nullable) reference = new ComposedSchema().anyOf(List.of(reference, new Schema<>().types(Set.of("null"))));
                family.addProperty(property, reference);
            });
            if (dto.equals("AnswerReviewDto")) family.setExample(QuestionContracts.reviewExample(type));
            else {
                var example = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
                example.put("type", type.name()); example.put("contractVersion", 1);
                example.put("questionText", "Example question"); example.put("difficulty", "MEDIUM");
                if (base.getProperties().containsKey("id")) example.put("id", "00000000-0000-0000-0000-000000000001");
                payloads.forEach((property, view) -> example.set(property, QuestionContracts.example(type, view)));
                family.setExample(example);
            }
            String name = QuestionContracts.name(type, View.AUTHOR).replace("AuthorContentV1", dto + "V1");
            schemas.put(name, family);
            discriminator.put(type.name(), "#/components/schemas/" + name);
            alternatives.add(new Schema<>().$ref("#/components/schemas/" + name));
        }
        base.setAnyOf(alternatives);
        base.setDiscriminator(new io.swagger.v3.oas.models.media.Discriminator().propertyName("type").mapping(discriminator));
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new io.swagger.v3.oas.models.media.Content()
                .addMediaType("application/problem+json", new io.swagger.v3.oas.models.media.MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ProblemDetail"))));
    }

    private static Schema<?> convert(JsonNode node) {
        Schema<Object> schema = new Schema<>();
        if (node.has("type")) {
            if (node.get("type").isArray()) {
                Set<String> types = new java.util.LinkedHashSet<>();
                node.get("type").forEach(type -> types.add(type.asText()));
                schema.setTypes(types);
            } else schema.setType(node.path("type").asText());
        }
        if (node.has("description")) schema.setDescription(node.get("description").asText());
        if (node.has("format")) schema.setFormat(node.get("format").asText());
        if (node.has("minimum")) schema.setMinimum(node.get("minimum").decimalValue());
        if (node.has("maximum")) schema.setMaximum(node.get("maximum").decimalValue());
        if (node.has("minLength")) schema.setMinLength(node.get("minLength").asInt());
        if (node.has("maxLength")) schema.setMaxLength(node.get("maxLength").asInt());
        if (node.has("minItems")) schema.setMinItems(node.get("minItems").asInt());
        if (node.has("maxItems")) schema.setMaxItems(node.get("maxItems").asInt());
        if (node.has("additionalProperties")) schema.setAdditionalProperties(node.get("additionalProperties").asBoolean());
        if (node.has("items")) schema.setItems(convert(node.get("items")));
        if (node.has("properties")) {
            Map<String, Schema> properties = new LinkedHashMap<>();
            node.get("properties").fields().forEachRemaining(field -> properties.put(field.getKey(), convert(field.getValue())));
            schema.setProperties(properties);
        }
        if (node.has("required")) {
            List<String> required = new ArrayList<>();
            node.get("required").forEach(field -> required.add(field.asText()));
            if (!required.isEmpty()) schema.setRequired(required);
        }
        if (node.has("anyOf")) {
            List<Schema> alternatives = new ArrayList<>();
            node.get("anyOf").forEach(branch -> alternatives.add(convert(branch)));
            schema.setAnyOf(alternatives);
        }
        return schema;
    }
}
