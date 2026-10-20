package uk.gegc.quizmaker.features.question.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.attempt.api.AttemptController;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.application.QuestionSchemaService;
import uk.gegc.quizmaker.features.question.application.QuestionService;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.shared.api.docs.ApiDiscoveryController;
import uk.gegc.quizmaker.shared.api.docs.ApiDocumentationService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(controllers = {QuestionController.class, AttemptController.class, ApiDiscoveryController.class})
@Import({OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        QuestionContractsOpenApiTest.SpringDocTestConfig.class})
class QuestionContractsOpenApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class SpringDocTestConfig { }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean QuestionService questions;
    @MockitoBean QuestionSchemaService questionSchemas;
    @MockitoBean AttemptService attempts;

    @Test
    @WithMockUser
    void groupAndFullDocumentsExposeReachableAllFamilySchemasExamplesAndBoundedErrors() throws Exception {
        for (String url : new String[]{"/v3/api-docs/questions", "/v3/api-docs/attempts", "/v3/api-docs"}) {
            JsonNode spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            JsonNode schemas = spec.path("components").path("schemas");
            var hotspot = schemas.path("HotspotAuthorContentV1");
            assertThat(hotspot.path("required").toString()).doesNotContain("imageDescription");
            assertThat(hotspot.at("/properties/imageDescription/maxLength").asInt()).isEqualTo(2_000);
            assertThat(hotspot.at("/properties/regions/items/properties/label/maxLength").asInt()).isEqualTo(120);
            assertThat(hotspot.at("/properties/regions/items/required").toString()).doesNotContain("label", "description");
            var hotspotSafe = schemas.path("HotspotSafeContentV1");
            assertThat(hotspotSafe.at("/properties/regions/items/properties").has("description")).isTrue();
            assertThat(hotspotSafe.at("/properties/regions/items/properties").has("correct")).isFalse();
            for (QuestionType type : QuestionType.values()) {
                for (var view : QuestionContracts.View.values()) {
                    JsonNode schema = schemas.path(QuestionContracts.name(type, view));
                    assertThat(schema.isMissingNode()).as("%s %s %s", url, type, view).isFalse();
                    assertThat(schema.path("example")).isEqualTo(QuestionContracts.example(type, view));
                    assertThat(schema.path("x-contract-version").asInt()).isEqualTo(1);
                    if (view == QuestionContracts.View.SAFE) assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
                }
            }
            assertThat(schemas.path("OpenCorrectAnswerV1").at("/properties/answer/type").toString()).contains("string", "null");
            String dto = url.endsWith("questions") ? "CreateQuestionRequest" : "AnswerSubmissionRequest";
            String property = dto.startsWith("Create") ? "content" : "response";
            assertThat(schemas.path(dto).path("properties").path(property).path("anyOf")).hasSize(9);
            assertThat(schemas.path(dto).path("properties").path("contractVersion").path("enum").get(0).asInt()).isEqualTo(1);
            if (spec.path("paths").has("/api/v1/attempts/{attemptId}/answers")) {
                var operation = spec.path("paths").path("/api/v1/attempts/{attemptId}/answers").path("post");
                assertThat(operation.at("/responses/400/content/application~1problem+json/schema/$ref").asText()).endsWith("/ProblemDetail");
                assertThat(operation.at("/responses/409/content/application~1problem+json/schema/$ref").asText()).endsWith("/ProblemDetail");
                assertThat(schemas.path("AnswerReviewDto").path("anyOf")).hasSize(9);
                var participant = schemas.path("McqSingleQuestionForAttemptDtoV1");
                assertThat(participant.path("required").toString()).contains("id", "type", "safeContent", "questionText", "difficulty");
                assertThat(participant.path("example").path("id").asText()).isNotBlank();
            }
        }
        var summary = json.readTree(mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.path("groups").findValuesAsText("specUrl")).contains("/v3/api-docs/questions", "/v3/api-docs/attempts");
    }

    @Test
    @WithMockUser
    void unsupportedVersionAndUnknownFamilyReturnSafeProblemsBeforeCallingTheService() throws Exception {
        for (String body : new String[]{
                "{\"type\":\"PRIVATE-CANARY\",\"difficulty\":\"EASY\",\"questionText\":\"Example question\",\"content\":{}}",
                "{\"type\":\"TRUE_FALSE\",\"difficulty\":\"EASY\",\"questionText\":\"Example question\",\"content\":{\"answer\":true},\"contractVersion\":2}"}) {
            var response = mvc.perform(post("/api/v1/questions").with(csrf()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("PRIVATE-CANARY", "java.lang", "parseError");
            assertThat(response.length()).isLessThan(1_000);
        }
        org.mockito.Mockito.verifyNoInteractions(questions);
    }
    @Test
    @WithMockUser
    void unknownSchemaFamilyHasABoundedProblemWithoutEchoingThePathValue() throws Exception {
        var response = mvc.perform(get("/api/v1/questions/schemas/PRIVATE-CANARY"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("PRIVATE-CANARY", "parseError", "java.lang");
        assertThat(response.length()).isLessThan(1_000);
    }

    @Test
    @WithMockUser
    void beanValidationFailureCannotEchoAnUnboundedClientValue() throws Exception {
        var body = json.createObjectNode().put("type", "TRUE_FALSE").put("difficulty", "EASY")
                .put("questionText", "PRIVATE-CANARY".repeat(2_000));
        body.putObject("content").put("answer", true);
        var response = mvc.perform(post("/api/v1/questions").with(csrf())
                        .contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("PRIVATE-CANARY");
        assertThat(response.length()).isLessThan(1_000);
        org.mockito.Mockito.verifyNoInteractions(questions);
    }

    @Test
    @WithMockUser
    void batchValidationErrorsKeepBoundedFieldDiagnostics() throws Exception {
        var body = json.createObjectNode();
        var answers = body.putArray("answers");
        for (int i = 0; i < 40; i++) answers.addObject();
        var response = mvc.perform(post("/api/v1/attempts/{id}/answers/batch", java.util.UUID.randomUUID())
                        .with(csrf()).contentType("application/json").content(json.writeValueAsBytes(body)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        var problem = json.readTree(response);
        assertThat(problem.path("fieldErrors")).hasSize(16);
        assertThat(problem.path("errorsTruncated").booleanValue()).isTrue();
        assertThat(problem.path("fieldErrors").get(0).path("field").asText()).startsWith("answers[");
        assertThat(response.length()).isLessThan(6_000);
        org.mockito.Mockito.verifyNoInteractions(attempts);
    }

}
