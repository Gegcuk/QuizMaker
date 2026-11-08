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

@WebMvcTest(controllers = {DraftCommandController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = "quizmaker.attempts.draft-commands-enabled=true")
@Import({SecurityConfig.class, DraftCommandErrorHandler.class, ResponseAcceptanceJsonConfig.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        DraftCommandOpenApiTest.DocConfig.class})
class DraftCommandOpenApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean uk.gegc.quizmaker.features.attempt.application.DraftCommandService drafts;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final String PATH = "/api/v1/attempts/{attemptId}/drafts/{formItemId}";
    static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final String BODY = """
            {"expectedStateVersion":1,"expectedDraftVersion":0,"response":{"type":"TRUE_FALSE","answer":true}}
            """;

    @Test @WithMockUser void saveReturnsContentFreeAcknowledgementAndPassesBothVersions() throws Exception {
        when(drafts.execute(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.application.DraftCommandService.Result(
                ATTEMPT, ITEM, 1, 1, uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Status.PRESENT, false));
        var result = mvc.perform(put(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "exact-key").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.draftVersion").value(1)).andExpect(jsonPath("$.stateVersion").value(1))
                .andExpect(jsonPath("$.status").value("PRESENT")).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(result).size()).isEqualTo(6);
        assertThat(result).doesNotContain("answer", "correct", "score", "explanation", "exact-key");
        var captured = org.mockito.ArgumentCaptor.forClass(uk.gegc.quizmaker.features.attempt.application.DraftCommandService.Command.class);
        verify(drafts).execute(captured.capture()); var c = captured.getValue();
        assertThat(c.attemptId()).isEqualTo(ATTEMPT); assertThat(c.formItemId()).isEqualTo(ITEM);
        assertThat(c.operation()).isEqualTo(AttemptCommands.Type.SAVE_DRAFT);
        assertThat(c.expectedStateVersion()).isEqualTo(1); assertThat(c.expectedDraftVersion()).isZero();
        assertThat(c.type()).isEqualTo(QuestionType.TRUE_FALSE); assertThat(c.response().path("answer").booleanValue()).isTrue();
        assertThat(c.toString()).doesNotContain("exact-key", ATTEMPT.toString(), "answer");
    }

    @Test @WithMockUser void deleteUsesBodyFreeVersionParametersAndNeverAcceptsAValue() throws Exception {
        when(drafts.execute(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.application.DraftCommandService.Result(
                ATTEMPT, ITEM, 2, 4, uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Status.DELETED, true));
        mvc.perform(delete(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "delete-key")
                        .param("expectedStateVersion", "2").param("expectedDraftVersion", "3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.draftVersion").value(4)).andExpect(jsonPath("$.replayed").value(true));
        var captured = org.mockito.ArgumentCaptor.forClass(uk.gegc.quizmaker.features.attempt.application.DraftCommandService.Command.class);
        verify(drafts).execute(captured.capture()); var c = captured.getValue();
        assertThat(c.operation()).isEqualTo(AttemptCommands.Type.DELETE_DRAFT);
        assertThat(c.expectedStateVersion()).isEqualTo(2); assertThat(c.expectedDraftVersion()).isEqualTo(3);
        assertThat(c.type()).isNull(); assertThat(c.response()).isNull();
    }

    @ParameterizedTest @WithMockUser
    @CsvSource({"UNAUTHENTICATED,401", "NOT_FOUND,404", "STALE,409", "CONFLICT,409", "INACTIVE,409", "IN_PROGRESS,409", "EXPIRED,409",
            "INVALID_INPUT,422", "UNSUPPORTED,422", "CORRUPT,500", "RETRYABLE,503", "UNKNOWN_OUTCOME,503", "UNAVAILABLE,503"})
    void typedErrorsHaveTheSameSafeContractForBothCommands(String reason, int status) throws Exception {
        when(drafts.execute(any())).thenThrow(new uk.gegc.quizmaker.features.attempt.domain.model.draft.DraftCommandException(
                uk.gegc.quizmaker.features.attempt.domain.model.draft.DraftCommandException.Reason.valueOf(reason)));
        for (var request : List.of(put(PATH, ATTEMPT, ITEM).contentType("application/json").content(BODY),
                delete(PATH, ATTEMPT, ITEM).param("expectedStateVersion", "1").param("expectedDraftVersion", "0"))) {
            var result = mvc.perform(request.header("Idempotency-Key", "private-key"))
                    .andExpect(status().is(status)).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("DRAFT_COMMAND_" + reason)).andReturn().getResponse().getContentAsString();
            assertThat(result).doesNotContain("private-key", "Exception", "stackTrace", "answer");
        }
    }

    @Test @WithMockUser void malformedMissingUnknownCoercedAndOversizedValuesNeverReachService() throws Exception {
        for (String invalid : List.of("{}", BODY.replace("TRUE_FALSE", "OPEN"), BODY.replace("true", "null"),
                BODY.replace("\"expectedDraftVersion\":0,", ""), BODY.replace(":0,", ":-1,"), BODY.replace(":0,", ":0.5,"),
                BODY.replace("true", "\"true\""), BODY.replace("true", "1"), BODY.replace("true", "true,\"answer\":false"),
                BODY.replace("true", "true,\"ownerId\":\"private\""),
                BODY.replace("\"type\":\"TRUE_FALSE\",\"answer\":true", "\"type\":\"FILL_GAP\",\"answers\":[{\"gapId\":1,\"answer\":123}]"))) {
            mvc.perform(put(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "key").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_DRAFT_REQUEST"));
        }
        mvc.perform(put(PATH, ATTEMPT, ITEM).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(put(PATH, "private-invalid-id", ITEM).header("Idempotency-Key", "key").contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(put(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "has whitespace").contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(delete(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "key")).andExpect(status().isBadRequest());
        mvc.perform(delete(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "key").param("expectedStateVersion", "invalid").param("expectedDraftVersion", "0"))
                .andExpect(status().isBadRequest());
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(BODY);
        var ids = json.createArrayNode(); for (int n = 0; n < 101; n++) ids.add(ITEM.toString());
        body.set("response", json.createObjectNode().put("type", "MCQ_MULTI").set("selectedOptionIds", ids));
        mvc.perform(put(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "key").contentType("application/json").content(body.toString()))
                .andExpect(status().isBadRequest()); verifyNoInteractions(drafts);
    }

    @Test @WithMockUser
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void malformedPrivateValueDoesNotReachFrameworkDiagnostics(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String marker = "draft-private-framework-marker";
        var result = mvc.perform(put(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "key").contentType("application/json")
                        .content(BODY.replace("true", "\"" + marker + "\"")))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain(marker); assertThat(output.getAll()).doesNotContain(marker); verifyNoInteractions(drafts);
    }

    @Test void anonymousSaveAndDeleteAreRejectedBeforeApplication() throws Exception {
        mvc.perform(put(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "key").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(delete(PATH, ATTEMPT, ITEM).header("Idempotency-Key", "key").param("expectedStateVersion", "1").param("expectedDraftVersion", "0"))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(drafts);
    }

    @Test @WithMockUser void fullAndGroupedContractsDiscoverBothNamedOperationsWithValidExamples() throws Exception {
        for (String url : List.of("/v3/api-docs", "/v3/api-docs/attempts")) {
            var spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var schemas = spec.at("/components/schemas");
            for (String method : List.of("put", "delete")) {
                var op = spec.path("paths").path(PATH).path(method);
                assertThat(op.path("operationId").asText()).isEqualTo(method.equals("put") ? "saveAttemptDraft" : "deleteAttemptDraft");
                assertThat(op.path("security").get(0).has("bearerAuth")).isTrue();
                assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/DraftCommandAcknowledgement");
                for (String code : List.of("400", "401", "404", "409", "422", "500", "503"))
                    assertThat(op.path("responses").path(code).findValuesAsText("$ref")).contains("#/components/schemas/ProblemDetail");
                var example = op.at("/responses/200/content/application~1json/example");
                if (example.isMissingNode()) example = op.at("/responses/200/content/application~1json/examples").elements().next().path("value");
                assertThat(matchesFormSchema(spec, schemas.path("DraftCommandAcknowledgement"), example, 0)).isTrue();
                assertThat(example.path("status").asText()).isEqualTo(method.equals("put") ? "PRESENT" : "DELETED");
                assertThat(op.path("parameters").findValuesAsText("name")).contains("attemptId", "formItemId", "Idempotency-Key");
            }
            var put = spec.path("paths").path(PATH).path("put");
            assertThat(put.at("/requestBody/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/SaveDraftRequest");
            assertThat(schemas.at("/SaveDraftRequest/properties/response/oneOf")).hasSize(5);
            var example = put.at("/requestBody/content/application~1json/example");
            if (example.isMissingNode()) example = put.at("/requestBody/content/application~1json/examples").elements().next().path("value");
            assertThat(matchesFormSchema(spec, schemas.path("SaveDraftRequest"), example, 0)).isTrue();
            assertThat(spec.path("paths").path(PATH).path("delete").has("requestBody")).isFalse();
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

}
