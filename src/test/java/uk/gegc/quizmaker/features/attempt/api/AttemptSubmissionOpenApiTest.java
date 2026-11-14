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

@WebMvcTest(controllers = {AttemptSubmissionController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = "quizmaker.attempts.submission-enabled=true")
@Import({SecurityConfig.class, AttemptSubmissionErrorHandler.class, AttemptSubmissionJsonConfig.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        AttemptSubmissionOpenApiTest.DocConfig.class})
class AttemptSubmissionOpenApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService responses;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final String PATH = "/api/v2/attempts/{attemptId}/submit";


    private static final String BODY = "{\"expectedStateVersion\":9,\"completionConfirmed\":true}";

    @Test @WithMockUser void commandCarriesNoAnswersAndAcknowledgementIsNotAResult() throws Exception {
        var attempt = UUID.randomUUID(); var submission = UUID.randomUUID();
        when(responses.submit(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService.Result(attempt, submission, 10, false));
        String result = mvc.perform(post(PATH, attempt).header("Idempotency-Key", "exact-key").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.submissionId").value(submission.toString())).andExpect(jsonPath("$.stateVersion").value(10))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(result).size()).isEqualTo(4);
        assertThat(result).doesNotContain("score", "correct", "answer", "exact-key");
        var captured = org.mockito.ArgumentCaptor.forClass(uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService.Command.class);
        verify(responses).submit(captured.capture());
        assertThat(captured.getValue().attemptId()).isEqualTo(attempt);
        assertThat(captured.getValue().expectedStateVersion()).isEqualTo(9);
        assertThat(captured.getValue().completionConfirmed()).isTrue();
    }

    @ParameterizedTest @WithMockUser
    @CsvSource({"UNAUTHENTICATED,401", "NOT_FOUND,404", "INVALID_INPUT,400", "STALE,409", "CONFLICT,409", "INACTIVE,409", "IN_PROGRESS,409",
            "EXPIRED,409", "UNSUPPORTED,422", "INCOMPLETE,422", "CONFIRMATION_REQUIRED,422", "CORRUPT,500", "RETRYABLE,503", "UNKNOWN_OUTCOME,503", "UNAVAILABLE,503"})
    void typedErrorsHaveSafeDocumentedCodesAndOnlyOwnMissingFormItems(String reason, int status) throws Exception {
        var missing = reason.equals("INCOMPLETE") ? List.of(UUID.randomUUID()) : List.<UUID>of();
        when(responses.submit(any())).thenThrow(new uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException(
                uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException.Reason.valueOf(reason), missing));
        var result = mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "private-key").contentType("application/json").content(BODY))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("SUBMISSION_" + reason))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(result).path("missingFormItemIds")).hasSize(missing.size());
        assertThat(result).doesNotContain("private-key", "Exception", "stackTrace", "Attempt submission rejected");
    }

    @Test @WithMockUser
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void malformedOrAuthorityBearingPayloadsAreRejectedBeforeTheService(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        for (String body : List.of("{}", BODY + BODY, BODY.replace("9", "-1"), BODY.replace("9", "1.5"), BODY.replace("9", "\"9\""),
                BODY.replace("true", "\"true\""), BODY.replace("true", "null"), BODY.replace("true", "1"),
                BODY.replace("true", "true,\"answers\":[\"private-answer-marker\"]"),
                BODY.replace("true", "true,\"ownerId\":\"private-owner-marker\""),
                BODY.replace("true", "true,\"requireAllAnswers\":false"), BODY.replace("9", "9,\"expectedStateVersion\":10"))) {
            String result = mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "key").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SUBMISSION_REQUEST"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(result).doesNotContain("private-answer-marker", "private-owner-marker");
        }
        mvc.perform(post(PATH, UUID.randomUUID()).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH, "bad-id").header("Idempotency-Key", "key").contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "has whitespace").contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        assertThat(output.getAll()).doesNotContain("private-answer-marker", "private-owner-marker");
        verifyNoInteractions(responses);
    }

    @Test void unauthenticatedRequestDoesNotReachTheService() throws Exception {
        mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(responses);
    }

    @Test @WithMockUser void namedSchemasExamplesSecuritySingleGroupAndApiSummaryAgree() throws Exception {
        for (String url : List.of("/v3/api-docs", "/v3/api-docs/attempts-v2")) {
            var spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var op = spec.path("paths").path(PATH).path("post"); var schemas = spec.at("/components/schemas");
            assertThat(op.path("tags")).isEqualTo(json.valueToTree(List.of("Policy-driven Attempts")));
            assertThat(op.path("operationId").asText()).isEqualTo("submitAttempt");
            assertThat(op.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(op.at("/requestBody/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/AttemptSubmitRequest");
            assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/AttemptSubmitAcknowledgement");
            for (String name : List.of("AttemptSubmitRequest", "AttemptSubmitAcknowledgement", "AttemptSubmitProblem")) assertThat(schemas.has(name)).isTrue();
            assertThat(schemas.path("AttemptSubmitRequest").path("properties").size()).isEqualTo(2);
            assertThat(schemas.path("AttemptSubmitRequest").path("additionalProperties").booleanValue()).isFalse();
            assertThat(schemas.path("AttemptSubmitRequest").path("required")).hasSize(2);
            assertThat(schemas.path("AttemptSubmitAcknowledgement").path("properties").size()).isEqualTo(4);
            for (String code : List.of("400", "401", "404", "409", "422", "500", "503"))
                assertThat(op.path("responses").path(code).findValuesAsText("$ref")).contains("#/components/schemas/AttemptSubmitProblem");
            var requestExample = op.at("/requestBody/content/application~1json/example");
            if (requestExample.isMissingNode()) requestExample = op.at("/requestBody/content/application~1json/examples").elements().next().path("value");
            var responseExample = op.at("/responses/200/content/application~1json/example");
            if (responseExample.isMissingNode()) responseExample = op.at("/responses/200/content/application~1json/examples").elements().next().path("value");
            assertThat(matchesFormSchema(spec, schemas.path("AttemptSubmitRequest"), requestExample, 0)).isTrue();
            assertThat(matchesFormSchema(spec, schemas.path("AttemptSubmitAcknowledgement"), responseExample, 0)).isTrue();
        }
        var matcher = new org.springframework.util.AntPathMatcher();
        assertThat(groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(p -> matcher.match(p, PATH)))
                .map(org.springdoc.core.models.GroupedOpenApi::getGroup)).containsExactly("attempts-v2");
        var summary = json.readTree(mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.path("groups").findValuesAsText("specUrl")).contains("/v3/api-docs/attempts-v2");
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
            case "boolean" -> value.isBoolean();
            default -> throw new AssertionError("Add validation for the new response schema type");
        }) return false;
        if (schema.has("enum")) {
            boolean found = false;
            for (var allowed : schema.path("enum")) if (allowed.equals(value)) found = true;
            if (!found) return false;
        }
        if (schema.has("maxLength") && value.textValue().codePointCount(0, value.textValue().length()) > schema.path("maxLength").intValue()) return false;
        if (schema.has("maxItems") && value.size() > schema.path("maxItems").intValue()) return false;
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

    @Test @WithMockUser void targetRouteHasNoLegacyV1Alias() throws Exception {
        String retired = PATH.replace("/api/v2/", "/api/v1/");
        for (String specPath : List.of("/v3/api-docs", "/v3/api-docs/attempts")) {
            String spec = mvc.perform(get(specPath)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(spec).doesNotContain("\"" + retired + "\"");
        }
        mvc.perform(get(retired, UUID.randomUUID(), UUID.randomUUID()).accept("application/json"))
                .andExpect(status().isNotFound());
    }

}
