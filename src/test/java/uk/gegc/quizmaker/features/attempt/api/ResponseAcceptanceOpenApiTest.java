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

@WebMvcTest(controllers = {ResponseAcceptanceController.class, ResponseBatchController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = {"quizmaker.attempts.response-acceptance-enabled=true", "quizmaker.attempts.response-batch-enabled=true"})
@Import({SecurityConfig.class, ResponseBatchErrorHandler.class, ResponseBatchJsonConfig.class, ResponseAcceptanceErrorHandler.class, ResponseAcceptanceJsonConfig.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        ResponseAcceptanceOpenApiTest.DocConfig.class})
class ResponseAcceptanceOpenApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService responses;
    @MockitoBean uk.gegc.quizmaker.features.attempt.application.ResponseBatchAcceptanceService batches;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final String PATH = "/api/v1/attempts/{attemptId}/responses";

    private static final String BODY = """
            {"formItemId":"00000000-0000-0000-0000-000000000002","expectedStateVersion":1,"response":{"type":"TRUE_FALSE","answer":true}}
            """;

    private static final String BATCH_PATH = "/api/v1/attempts/{attemptId}/responses/batch";
    private static final String BATCH_BODY = """
            {"expectedStateVersion":1,"responses":[{"formItemId":"00000000-0000-0000-0000-000000000002","response":{"type":"TRUE_FALSE","answer":true}}]}
            """;

    @Test @WithMockUser void batchHasTypedAcknowledgementAndUsesOnlyTheNewCommand() throws Exception {
        var attempt=UUID.randomUUID(); var item=UUID.fromString("00000000-0000-0000-0000-000000000002"); var accepted=UUID.randomUUID();
        when(batches.acceptBatch(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.BatchResult(
                attempt,List.of(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.AcceptedItem(item,accepted)),2,false));
        String body=mvc.perform(post(BATCH_PATH,attempt).header("Idempotency-Key","batch-key").contentType("application/json").content(BATCH_BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.responses[0].acceptedResponseId").value(accepted.toString())).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("correct","score","explanation","batch-key"); verifyNoInteractions(responses);
        var capture=org.mockito.ArgumentCaptor.forClass(uk.gegc.quizmaker.features.attempt.application.ResponseBatchAcceptanceService.BatchCommand.class);
        verify(batches).acceptBatch(capture.capture()); assertThat(capture.getValue().responses()).hasSize(1);
        assertThat(capture.getValue().expectedStateVersion()).isEqualTo(1);
    }

    @Test void unauthenticatedBatchNeverInvokesTheCommand() throws Exception {
        mvc.perform(post(BATCH_PATH,UUID.randomUUID()).header("Idempotency-Key","batch-key").contentType("application/json").content(BATCH_BODY))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(batches);
    }

    @ParameterizedTest @WithMockUser
    @org.junit.jupiter.params.provider.ValueSource(strings={"{", "{\"expectedStateVersion\":1,\"expectedStateVersion\":1,\"responses\":[]}",
            "{\"expectedStateVersion\":1,\"responses\":[],\"includeCorrectness\":true}",
            "{\"expectedStateVersion\":\"1\",\"responses\":[]}"})
    void malformedBatchIsSanitizedBeforeTheService(String body) throws Exception {
        mvc.perform(post(BATCH_PATH,UUID.randomUUID()).header("Idempotency-Key","batch-key").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(content().contentType("application/problem+json"));
        verifyNoInteractions(batches);
    }

    @ParameterizedTest @WithMockUser
    @org.junit.jupiter.params.provider.ValueSource(strings={"TRUE_FALSE","MCQ_MULTI","FILL_GAP"})
    void semanticBatchResponseValidationUses422RatherThanMalformedRequest(String type) throws Exception {
        var body=json.readTree(BATCH_BODY); var response=json.createObjectNode().put("type",type);
        switch (type) {
            case "TRUE_FALSE" -> response.putNull("answer");
            case "FILL_GAP" -> response.putArray("answers").addObject().put("gapId",0).putNull("answer");
            case "MCQ_MULTI" -> {
                var ids=response.putArray("selectedOptionIds");
                for (int i=0;i<101;i++) ids.add(new UUID(0,i+1).toString());
            }
            default -> throw new AssertionError(type);
        }
        ((com.fasterxml.jackson.databind.node.ObjectNode)body.path("responses").get(0)).set("response",response);
        when(batches.acceptBatch(any())).thenThrow(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.Reason.INVALID_ITEMS,
                List.of(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.ItemError(0,
                        uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.Code.INVALID_RESPONSE))));
        mvc.perform(post(BATCH_PATH,UUID.randomUUID()).header("Idempotency-Key","invalid-response").contentType("application/json").content(body.toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].index").value(0))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RESPONSE"));
        verify(batches).acceptBatch(any());
    }

    @Test @WithMockUser void batchBodyAndItemLimitsReturn413BeforeInvocation() throws Exception {
        var body=json.readTree(BATCH_BODY); var array=(com.fasterxml.jackson.databind.node.ArrayNode)body.path("responses");
        var first=array.get(0).deepCopy(); for(int i=1;i<=500;i++) array.add(first);
        mvc.perform(post(BATCH_PATH,UUID.randomUUID()).header("Idempotency-Key","key").contentType("application/json").content(body.toString()))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(post(BATCH_PATH,UUID.randomUUID()).header("Idempotency-Key","key").contentType("application/json")
                .content(" ".repeat(5*1024*1024)+BATCH_BODY)).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(batches);
    }

    @Test @WithMockUser void exactBatchLimitsHaveSuccessfulControls() throws Exception {
        var attempt=UUID.randomUUID(); var single=json.readTree(BATCH_BODY);
        var many=single.deepCopy(); var responses=(com.fasterxml.jackson.databind.node.ArrayNode)many.path("responses"); responses.removeAll();
        var accepted=new java.util.ArrayList<uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.AcceptedItem>();
        for(int i=0;i<500;i++) {
            var item=UUID.randomUUID(); responses.addObject().put("formItemId",item.toString()).putObject("response").put("type","TRUE_FALSE").put("answer",true);
            accepted.add(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.AcceptedItem(item,UUID.randomUUID()));
        }
        when(batches.acceptBatch(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.BatchResult(attempt,accepted,501,false));
        mvc.perform(post(BATCH_PATH,attempt).header("Idempotency-Key","maximum").contentType("application/json").content(many.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responses.length()").value(500));
        var item=UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(batches.acceptBatch(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.BatchResult(attempt,
                List.of(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatch.AcceptedItem(item,UUID.randomUUID())),2,false));
        String exact=" ".repeat(5*1024*1024-BATCH_BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)+BATCH_BODY;
        mvc.perform(post(BATCH_PATH,attempt).header("Idempotency-Key","exact-bytes").contentType("application/json").content(exact))
                .andExpect(status().isOk());
    }

    @Test @WithMockUser void batchErrorEnvelopeContainsOnlyIndexesAndBoundedCodes() throws Exception {
        when(batches.acceptBatch(any())).thenThrow(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.Reason.INVALID_ITEMS,
                List.of(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.ItemError(0,
                        uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.Code.INVALID_RESPONSE))));
        mvc.perform(post(BATCH_PATH,UUID.randomUUID()).header("Idempotency-Key","key").contentType("application/json").content(BATCH_BODY))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors[0].index").value(0))
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RESPONSE"));
    }

    @Test @WithMockUser void batchAppearsInExactlyOneGroupAndApiSummary() throws Exception {
        String path="/api/v1/attempts/{attemptId}/responses/batch";
        var full=json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var grouped=json.readTree(mvc.perform(get("/v3/api-docs/attempts")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(full.path("paths").has(path)).isTrue(); assertThat(grouped.path("paths").has(path)).isTrue();
        assertThat(groups.stream().filter(g -> java.util.Arrays.stream(g.getPathsToMatch().toArray(new String[0]))
                .anyMatch(pattern -> new org.springframework.util.AntPathMatcher().match(pattern,"/api/v1/attempts/id/responses/batch"))).count()).isEqualTo(1);
        assertThat(grouped.path("paths").path(path).path("post").path("responses").has("413")).isTrue();
        assertThat(grouped.path("components").path("schemas").has("AcceptResponseBatchRequest")).isTrue();
        assertThat(grouped.path("components").path("schemas").has("ResponseBatchAcknowledgement")).isTrue();
        assertThat(grouped.path("components").path("schemas").has("ResponseBatchProblem")).isTrue();
        var operation=grouped.path("paths").path(path).path("post");
        var requestExample=operation.path("requestBody").path("content").path("application/json").path("example");
        var responseExample=operation.path("responses").path("200").path("content").path("application/json").path("example");
        assertThat(json.treeToValue(requestExample, uk.gegc.quizmaker.features.attempt.api.dto.AcceptResponseBatchRequest.class).responses()).hasSize(1);
        assertThat(json.treeToValue(responseExample, uk.gegc.quizmaker.features.attempt.api.dto.ResponseBatchAcknowledgement.class).responses()).hasSize(1);

        String summary=mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(summary).contains("attempts");
    }

    @Test @WithMockUser void typedCommandReturnsOnlyBoundedAcknowledgementAndPassesStableInput() throws Exception {
        var attempt = UUID.randomUUID(); var answer = UUID.randomUUID();
        when(responses.accept(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService.Result(attempt, answer, 2, false));
        String result = mvc.perform(post(PATH, attempt).header("Idempotency-Key", "exact-key")
                        .contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.acceptedResponseId").value(answer.toString())).andExpect(jsonPath("$.stateVersion").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(result).size()).isEqualTo(4);
        assertThat(result).doesNotContain("correct", "explanation", "score", "response", "exact-key");
        var captured = org.mockito.ArgumentCaptor.forClass(uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService.Command.class);
        verify(responses).accept(captured.capture());
        assertThat(captured.getValue().attemptId()).isEqualTo(attempt);
        assertThat(captured.getValue().expectedStateVersion()).isEqualTo(1);
        assertThat(captured.getValue().type()).isEqualTo(QuestionType.TRUE_FALSE);
        assertThat(captured.getValue().response().path("answer").booleanValue()).isTrue();
        assertThat(captured.getValue().toString()).doesNotContain("exact-key", attempt.toString());
    }

    @ParameterizedTest @WithMockUser
    @CsvSource({"UNAUTHENTICATED,401", "NOT_FOUND,404", "STALE,409", "CONFLICT,409", "INACTIVE,409", "IN_PROGRESS,409",
            "EXPIRED,409", "INVALID_INPUT,422", "UNSUPPORTED,422", "CORRUPT,500", "RETRYABLE,503", "UNKNOWN_OUTCOME,503", "UNAVAILABLE,503"})
    void errorCodesAreSanitizedAndDocumented(String reason, int status) throws Exception {
        when(responses.accept(any())).thenThrow(new uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException(
                uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.valueOf(reason)));
        String result = mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "private-key")
                        .contentType("application/json").content(BODY))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RESPONSE_" + reason)).andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("private-key", "Exception", "stackTrace", "Response acceptance rejected");
    }

    @Test @WithMockUser void missingKeyBadUuidMalformedUnknownAndOversizedPayloadsNeverCallService() throws Exception {
        mvc.perform(post(PATH, UUID.randomUUID()).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        for (String invalid : List.of("{}", BODY.replace("TRUE_FALSE", "OPEN"), BODY.replace("true", "null"),
                BODY.replace("\"expectedStateVersion\":1,", ""), BODY.replace("\"answer\":true", "\"answer\":{}"),
                BODY.replace("true", "\"true\""), BODY.replace("true", "1"), BODY.replace(":1,", ":1.5,"),
                BODY.replace("true", "true,\"answer\":false"), BODY.replace("true", "true,\"ownerId\":\"private\""),
                BODY.replace("\"type\":\"TRUE_FALSE\",\"answer\":true", "\"type\":\"FILL_GAP\",\"answers\":[{\"gapId\":1,\"answer\":123}]"),
                BODY.replace("\"type\":\"TRUE_FALSE\",\"answer\":true", "\"type\":\"FILL_GAP\",\"answers\":[{\"gapId\":1,\"answer\":true}]"),
                BODY.replace("00000000-0000-0000-0000-000000000002", "1-1-1-1-1"))) {
            mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "key").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        mvc.perform(post(PATH, "private-invalid-id").header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Response command could not be accepted"));
        mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "has whitespace").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());
        var oversized = json.readTree(BODY); ((com.fasterxml.jackson.databind.node.ObjectNode) oversized).set("response",
                json.createObjectNode().put("type", "MCQ_MULTI").set("selectedOptionIds", json.createArrayNode()));
        for (int i = 0; i < 101; i++) ((com.fasterxml.jackson.databind.node.ArrayNode) oversized.at("/response/selectedOptionIds")).add(UUID.randomUUID().toString());
        mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "key").contentType("application/json").content(oversized.toString()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(responses);
    }

    @Test @WithMockUser
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void malformedAnswerNeverAppearsInFrameworkDiagnostics(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        String marker = "response-raw-private-marker";
        var result = mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "key")
                .contentType("application/json").content(BODY.replace("true", "\"" + marker + "\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESPONSE_REQUEST"))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
        verifyNoInteractions(responses);
    }

    @Test void unauthenticatedHttpRequestNeverReachesApplication() throws Exception {
        mvc.perform(post(PATH, UUID.randomUUID()).header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(responses);
    }

    @Test @WithMockUser void schemaUnionExamplesErrorsSecurityAndApiSummaryAreConsistent() throws Exception {
        for (String url : List.of("/v3/api-docs", "/v3/api-docs/attempts")) {
            var spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var operation = spec.path("paths").path(PATH).path("post");
            var schemas = spec.at("/components/schemas");
            assertThat(operation.path("operationId").asText()).isEqualTo("acceptAttemptResponse");
            assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/AcceptResponseRequest");
            assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/AcceptedResponseAcknowledgement");
            for (String name : List.of("AcceptResponseRequest", "AcceptedResponseAcknowledgement", "ConfirmedResponse", "ConfirmedSingleChoice",
                    "ConfirmedMultipleChoice", "ConfirmedTrueFalse", "ConfirmedOrdering", "ConfirmedGapAnswers", "ConfirmedGapAnswer"))
                assertThat(schemas.has(name)).as(name).isTrue();
            assertThat(schemas.at("/AcceptResponseRequest/properties/response/oneOf")).hasSize(5);
            assertThat(schemas.path("ConfirmedResponse").has("oneOf")).isFalse();
            assertThat(schemas.at("/ConfirmedResponse/discriminator/mapping")).hasSize(5);
            for (String code : List.of("400", "401", "404", "409", "422", "500", "503"))
                assertThat(operation.path("responses").path(code).findValuesAsText("$ref")).contains("#/components/schemas/ProblemDetail");
            for (var variant : List.of(
                    "{\"type\":\"MCQ_SINGLE\",\"selectedOptionId\":\"00000000-0000-0000-0000-000000000004\"}",
                    "{\"type\":\"MCQ_MULTI\",\"selectedOptionIds\":[]}", "{\"type\":\"TRUE_FALSE\",\"answer\":false}",
                    "{\"type\":\"ORDERING\",\"orderedItemIds\":[]}", "{\"type\":\"FILL_GAP\",\"answers\":[{\"gapId\":1,\"answer\":\"Paris\"}]}")) {
                var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(BODY); body.set("response", json.readTree(variant));
                assertThat(matchesFormSchema(spec, schemas.path("AcceptResponseRequest"), body, 0)).as(variant).isTrue();
                assertThat(json.treeToValue(body, uk.gegc.quizmaker.features.attempt.api.dto.AcceptResponseRequest.class).response()).isNotNull();
                body.putObject("response").put("type", "UNKNOWN");
                assertThat(matchesFormSchema(spec, schemas.path("AcceptResponseRequest"), body, 0)).isFalse();
            }
            var requestExample = operation.at("/requestBody/content/application~1json/example");
            if (requestExample.isMissingNode()) requestExample = operation.at("/requestBody/content/application~1json/examples").elements().next().path("value");
            assertThat(matchesFormSchema(spec, schemas.path("AcceptResponseRequest"), requestExample, 0)).isTrue();
            var resultExample = operation.at("/responses/200/content/application~1json/example");
            if (resultExample.isMissingNode()) resultExample = operation.at("/responses/200/content/application~1json/examples").elements().next().path("value");
            assertThat(matchesFormSchema(spec, schemas.path("AcceptedResponseAcknowledgement"), resultExample, 0)).isTrue();
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
