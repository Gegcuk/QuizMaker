package uk.gegc.quizmaker.features.attempt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.attempt.api.dto.AttemptFormResponse;
import uk.gegc.quizmaker.features.attempt.application.AttemptDeliveryService;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import uk.gegc.quizmaker.shared.api.docs.ApiDiscoveryController;
import uk.gegc.quizmaker.shared.api.docs.ApiDocumentationService;
import uk.gegc.quizmaker.shared.config.OpenApiConfig;
import uk.gegc.quizmaker.shared.config.OpenApiGroupConfig;
import uk.gegc.quizmaker.shared.config.SecurityConfig;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.infra.security.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {AttemptFormController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = "quizmaker.attempts.flat-delivery-enabled=true")
@Import({SecurityConfig.class, AttemptDeliveryErrorHandler.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        AttemptFormOpenApiContractTest.DocConfig.class})
class AttemptFormOpenApiContractTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean AttemptDeliveryService delivery;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final String PATH = "/api/v1/attempts/{attemptId}/form";

    @Test @WithMockUser
    void allContentVariantsHaveTypedStableSafeJsonAndIgnoreIncludeFlags() throws Exception {
        var view = fixture(); when(delivery.read(view.attemptId())).thenReturn(view);
        String plain = mvc.perform(get(PATH, view.attemptId())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        String flags = mvc.perform(get(PATH, view.attemptId()).param("includeCorrectAnswers", "true")
                        .param("includeExplanation", "true").param("ownerId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(flags).isEqualTo(plain);
        assertThat(json.readValue(plain, AttemptFormResponse.class)).isEqualTo(AttemptFormResponse.from(view));
        assertThat(plain).doesNotContain("correct", "explanation", "ownerId", "scoring", "sourceKey", "binding");
        assertThat(json.readTree(plain).path("items").findValuesAsText("kind"))
                .containsExactly("CHOICES", "CHOICES", "TRUE_FALSE", "FILL_GAP", "ORDERING");
    }

    @ParameterizedTest @WithMockUser
    @CsvSource({"UNAUTHENTICATED,401", "NOT_FOUND,404", "INACTIVE,409", "BUSY,409", "UNSUPPORTED,422", "CORRUPT,500", "UNAVAILABLE,503"})
    void failuresHaveSafeProblemBodiesAndNoCache(String reason, int code) throws Exception {
        when(delivery.read(any())).thenThrow(new AttemptDeliveryException(AttemptDeliveryException.Reason.valueOf(reason)));
        String response = mvc.perform(get(PATH, UUID.randomUUID())).andExpect(status().is(code))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(code)).andExpect(jsonPath("$.items").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Exception", "stackTrace", "Attempt delivery rejected:");
    }

    @Test @WithMockUser void invalidUuidHasNoServiceCallOrEchoedPayload() throws Exception {
        mvc.perform(get(PATH, "not-a-uuid")).andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.detail").value("Invalid attempt identifier"));
        verifyNoInteractions(delivery);
    }

    @Test void unauthenticatedRequestNeverReachesTheService() throws Exception {
        mvc.perform(get(PATH, UUID.randomUUID()).accept("application/json")).andExpect(status().isUnauthorized());
        verifyNoInteractions(delivery);
    }

    @Test @WithMockUser
    void namedSchemasExampleErrorsAndSecurityBelongToExactlyOneDiscoverableGroup() throws Exception {
        for (String url : List.of("/v3/api-docs", "/v3/api-docs/attempts")) {
            JsonNode spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var operation = spec.path("paths").path(PATH).path("get");
            assertThat(operation.path("operationId").asText()).isEqualTo("readAttemptForm");
            assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
            var schemas = spec.path("components").path("schemas");
            assertThat(spec.at("/components/securitySchemes").has("bearerAuth")).isTrue();
            assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
                    .isEqualTo("#/components/schemas/AttemptFormResponse");
            for (String code : List.of("400", "401", "404", "409", "422", "500", "503"))
                assertThat(operation.path("responses").path(code).findValuesAsText("$ref")).contains("#/components/schemas/ProblemDetail");
            for (String name : List.of("AttemptFormResponse", "AttemptFormItem", "AttemptFormContent", "AttemptFormChoices",
                    "AttemptFormTrueFalse", "AttemptFormFillGap", "AttemptFormOrdering", "AttemptFormOption", "AttemptFormGap"))
                assertThat(schemas.has(name)).as(name).isTrue();
            assertThat(schemas.path("AttemptFormItem").at("/properties/content/oneOf")).hasSize(4);
            assertThat(schemas.path("AttemptFormContent").has("oneOf")).isFalse();
            assertThat(schemas.path("AttemptFormContent").at("/discriminator/mapping").size()).isEqualTo(4);
            assertThat(schemas.path("AttemptFormResponse").path("required").toString()).contains("formId", "stateVersion", "items", "allowedActions");
            var example = operation.at("/responses/200/content").elements().next().path("example");
            if (example.isMissingNode()) example = operation.at("/responses/200/content").elements().next().path("examples").elements().next().path("value");
            var parsed = json.treeToValue(example, AttemptFormResponse.class);
            assertThat(matchesFormSchema(spec, schemas.path("AttemptFormResponse"), example, 0)).isTrue();
            assertThat(matchesFormSchema(spec, schemas.path("AttemptFormResponse"),
                    json.valueToTree(AttemptFormResponse.from(fixture())), 0)).isTrue();
            var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) example.deepCopy();
            invalid.put("schemaVersion", 2);
            assertThat(matchesFormSchema(spec, schemas.path("AttemptFormResponse"), invalid, 0)).isFalse();
            invalid.put("schemaVersion", 1);
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/items/0/content")).put("kind", "UNKNOWN");
            assertThat(matchesFormSchema(spec, schemas.path("AttemptFormResponse"), invalid, 0)).isFalse();
            assertThat(parsed.schemaVersion()).isEqualTo(1);
            assertThat(parsed.items().get(0).content()).isInstanceOf(AttemptFormResponse.TrueFalse.class);
            assertThat(schemas.path("AttemptFormItem").path("properties").toString()).doesNotContain("correctAnswer", "explanation", "ownerId");
        }
        var matcher = new org.springframework.util.AntPathMatcher();
        assertThat(groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(p -> matcher.match(p, PATH)))
                .map(org.springdoc.core.models.GroupedOpenApi::getGroup)).containsExactly("attempts");
        var summary = json.readTree(mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.path("groups").findValuesAsText("specUrl")).contains("/v3/api-docs/attempts");
    }

    /** The response's schema keywords, including exclusive unions and a recursive-schema guard. */
    private static boolean matchesFormSchema(JsonNode spec, JsonNode schema, JsonNode value, int depth) {
        if (depth > 20 || schema.isMissingNode() || value.isNull()) return false;
        if (schema.has("$ref")) return matchesFormSchema(spec, spec.at(schema.path("$ref").asText().substring(1)), value, depth + 1);
        for (var part : schema.path("allOf")) if (!matchesFormSchema(spec, part, value, depth + 1)) return false;
        if (schema.has("oneOf")) {
            int matches = 0;
            for (var part : schema.path("oneOf")) if (matchesFormSchema(spec, part, value, depth + 1)) matches++;
            if (matches != 1) return false;
        }
        if (schema.has("type") && !switch (schema.path("type").asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            default -> throw new AssertionError("Add validation for the new response schema type");
        }) return false;
        if (schema.has("enum")) {
            boolean found = false;
            for (var allowed : schema.path("enum")) if (allowed.equals(value)) found = true;
            if (!found) return false;
        }
        if (schema.has("minimum") && value.decimalValue().compareTo(schema.path("minimum").decimalValue()) < 0) return false;
        if (schema.has("maximum") && value.decimalValue().compareTo(schema.path("maximum").decimalValue()) > 0) return false;
        if ("uuid".equals(schema.path("format").asText())) {
            try { if (!UUID.fromString(value.asText()).toString().equals(value.asText())) return false; }
            catch (IllegalArgumentException invalid) { return false; }
        }
        for (var required : schema.path("required")) if (!value.has(required.asText())) return false;
        var fields = schema.path("properties").fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (value.has(field.getKey()) && !matchesFormSchema(spec, field.getValue(), value.path(field.getKey()), depth + 1)) return false;
        }
        if (schema.has("items")) for (var item : value)
            if (!matchesFormSchema(spec, schema.path("items"), item, depth + 1)) return false;
        return true;
    }

    static AttemptDeliveryService.View fixture() {
        List<ParticipantContent> values = List.of(new ParticipantContent.Choices(options("A", "B")),
                new ParticipantContent.Choices(options("C", "D")), new ParticipantContent.TrueFalse(),
                new ParticipantContent.FillGap("Visit {1}", List.of(new ParticipantContent.Gap(1)), options("Paris", "Rome")),
                new ParticipantContent.Ordering(options("First", "Second")));
        var types = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI, QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
        var items = java.util.stream.IntStream.range(0, 5).mapToObj(i ->
                new ParticipantForm.Item(UUID.randomUUID(), i, types.get(i), "Question " + i, values.get(i))).toList();
        return new AttemptDeliveryService.View(UUID.randomUUID(), 1, new ParticipantForm(UUID.randomUUID(), 1, items),
                List.of(AttemptCommands.Type.VIEW_ATTEMPT));
    }

    private static List<ParticipantContent.Option> options(String... texts) {
        return java.util.Arrays.stream(texts).map(text -> new ParticipantContent.Option(UUID.randomUUID(), text)).toList();
    }
}
