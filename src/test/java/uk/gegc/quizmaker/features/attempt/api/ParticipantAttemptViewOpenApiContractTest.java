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
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.application.ParticipantViewDisclosureReader;
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

@WebMvcTest(controllers = {AttemptController.class, ParticipantAttemptViewController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = "quizmaker.attempts.participant-view-enabled=true")
@Import({SecurityConfig.class, ParticipantAttemptViewErrorHandler.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        ParticipantAttemptViewOpenApiContractTest.DocConfig.class})
class ParticipantAttemptViewOpenApiContractTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean uk.gegc.quizmaker.features.attempt.application.ParticipantAttemptViewService views;
    @MockitoBean uk.gegc.quizmaker.features.attempt.application.AttemptService legacy;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final String PATH = "/api/v2/attempts/{attemptId}";


    @Test @WithMockUser void typedViewIgnoresCallerDisclosureAndPreservesAllContentVariants() throws Exception {
        var view = fixture(); when(views.read(view.attemptId())).thenReturn(view);
        String plain = mvc.perform(get(PATH, view.attemptId())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        String flags = mvc.perform(get(PATH, view.attemptId()).param("includeCorrectAnswer", "true")
                        .param("includeCorrectness", "true").param("includeExplanation", "true").param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(flags).isEqualTo(plain);
        assertThat(json.readValue(plain, ParticipantAttemptView.class)).isEqualTo(view);
        assertThat(plain).doesNotContain("correctAnswer", "explanation", "score", "sourceKey", "policySnapshot", "userId", "submittedAt", "image");
        verifyNoInteractions(legacy);
    }

    @Test @WithMockUser void timedViewHasNamedSafeSchemaAndSchemaValidExampleInFullAndGroupedSpecs() throws Exception {
        var base=fixture(); var deadline=base.startedAt().plusSeconds(1800);
        var timer=new ParticipantAttemptTiming(1,base.startedAt(),deadline,0,base.startedAt(),false);
        var timed=new ParticipantAttemptView(base.representation(),base.schemaVersion(),base.attemptId(),base.quizId(),base.quizRevisionId(),base.formId(),
                base.stateVersion(),base.policyVersion(),base.execution(),base.answerCollection(),base.startedAt(),base.submittedAt(),ParticipantAttemptView.Timing.TIMED,
                base.contentVisibility(),base.items(),base.allowedActions(),base.result(),base.navigation(),timer);
        when(views.read(base.attemptId())).thenReturn(timed);
        String body=mvc.perform(get(PATH,base.attemptId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readValue(body,ParticipantAttemptView.class)).isEqualTo(timed);
        assertThat(json.readTree(body).path("timingDetails").path("effectiveDeadline").asText()).isEqualTo(deadline.toString());
        for(String path:List.of("/v3/api-docs","/v3/api-docs/attempts-v2")) {
            var spec=json.readTree(mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var schemas=spec.path("components").path("schemas");
            assertThat(schemas.path("ParticipantAttemptView").at("/properties/timingDetails/$ref").asText()).isEqualTo("#/components/schemas/ParticipantAttemptTiming");
            assertThat(schemas.path("ParticipantAttemptTiming").path("properties").fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("version","startedAt","effectiveDeadline","graceSeconds","serverTime","expired");
            assertThat(matchesFormSchema(spec,schemas.path("ParticipantAttemptView"),json.readTree(body),0)).isTrue();
        }
    }

    @Test @WithMockUser void legacyIsExplicitlyTheUnchangedLegacyShapeWithoutTargetFields() throws Exception {
        var old = new AttemptDetailsDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), java.time.Instant.parse("2026-11-06T12:00:00Z"),
                null, uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus.IN_PROGRESS,
                uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode.ALL_AT_ONCE, List.of());
        when(legacy.getAttemptDetail("user", old.attemptId())).thenReturn(old);
        String body = mvc.perform(get("/api/v1/attempts/{attemptId}", old.attemptId())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body)).isEqualTo(json.valueToTree(old));
        assertThat(body).doesNotContain("representation", "stateVersion", "allowedActions", "quizRevisionId");
        verifyNoInteractions(views);
    }

    @Test @WithMockUser void v1ReviewAndAnswerKeyNeverDelegateToTheEnabledTargetReader() throws Exception {
        var id = UUID.randomUUID();
        var review = new AttemptReviewDto(id, UUID.randomUUID(), UUID.randomUUID(),
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, 1.0, 1, 1, List.of());
        when(legacy.getAttemptReview("user", id, false, false, false)).thenReturn(review);
        when(legacy.getAttemptAnswerKey("user", id)).thenReturn(review);
        for (String suffix : List.of("/review", "/answer-key")) {
            var body = mvc.perform(get("/api/v1/attempts/{id}" + suffix, id)
                            .param("includeUserAnswers", "false").param("includeCorrectAnswers", "false")
                            .param("includeQuestionContext", "false"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(body)).isEqualTo(json.valueToTree(review));
        }
        verify(legacy).getAttemptReview("user", id, false, false, false);
        verify(legacy).getAttemptAnswerKey("user", id);
        verifyNoInteractions(views);
    }

    @Test @WithMockUser void versionedSpecsExposeExactlyOneConcreteEnvelopePerRoute() throws Exception {
        var full = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var v1 = json.readTree(mvc.perform(get("/v3/api-docs/attempts")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var v2 = json.readTree(mvc.perform(get("/v3/api-docs/attempts-v2")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (String suffix : List.of("", "/review", "/answer-key")) {
            String legacyPath = "/api/v1/attempts/{attemptId}" + suffix;
            String targetPath = PATH + suffix;
            String legacySchema = suffix.isEmpty() ? "AttemptDetailsDto" : "AttemptReviewDto";
            assertThat(v1.path("paths").has(targetPath)).isFalse();
            assertThat(v2.path("paths").has(legacyPath)).isFalse();
            for (var spec : List.of(full, v1)) {
                assertThat(spec.path("paths").path(legacyPath).path("get").path("tags"))
                        .isEqualTo(json.valueToTree(List.of("Attempts")));
                var response = spec.path("paths").path(legacyPath).path("get")
                        .at("/responses/200/content/application~1json");
                assertThat(response.at("/schema/$ref").asText()).isEqualTo("#/components/schemas/" + legacySchema);
                for (var example : response.path("examples"))
                    assertThat(matchesFormSchema(spec, response.path("schema"), example.path("value"), 0)).isTrue();
            }
            for (var spec : List.of(full, v2)) {
                assertThat(spec.path("paths").path(targetPath).path("get").path("tags"))
                        .isEqualTo(json.valueToTree(List.of("Policy-driven Attempts")));
                assertThat(spec.path("paths").path(targetPath).path("get")
                        .at("/responses/200/content/application~1json/schema/$ref").asText())
                        .isEqualTo("#/components/schemas/ParticipantAttemptView");
            }
        }
        var ids = new java.util.ArrayList<String>();
        for (var path : full.path("paths")) for (var operation : path)
            if (operation.has("operationId")) ids.add(operation.path("operationId").asText());
        assertThat(ids).doesNotHaveDuplicates();
    }

    @ParameterizedTest @WithMockUser
    @CsvSource({"UNAUTHENTICATED,401", "NOT_FOUND,404", "BUSY,409", "UNSUPPORTED,422", "CORRUPT,500", "UNAVAILABLE,503"})
    void failuresAreBoundedProblemDetailsWithoutPartialContent(String reason, int code) throws Exception {
        when(views.read(any())).thenThrow(new AttemptDeliveryException(AttemptDeliveryException.Reason.valueOf(reason)));
        String body = mvc.perform(get(PATH, UUID.randomUUID())).andExpect(status().is(code))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(code)).andExpect(jsonPath("$.items").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("stackTrace", "Exception", "Attempt delivery rejected:"); verifyNoInteractions(legacy);
    }

    @Test void unauthenticatedRequestDoesNotReachEitherReader() throws Exception {
        mvc.perform(get(PATH, UUID.randomUUID()).accept("application/json")).andExpect(status().isUnauthorized());
        verifyNoInteractions(views, legacy);
    }
    @Test @WithMockUser void invalidIdDoesNotReachEitherReader() throws Exception {
        mvc.perform(get(PATH, "invalid")).andExpect(status().isBadRequest()); verifyNoInteractions(views, legacy);
    }

    @Test @WithMockUser void schemasExamplesSecurityErrorsAndDiscoveryMatchTheActualResponses() throws Exception {
        for (String url : List.of("/v3/api-docs", "/v3/api-docs/attempts-v2")) {
            JsonNode spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var op = spec.path("paths").path(PATH).path("get"); var schemas = spec.path("components").path("schemas");
            assertThat(op.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/ParticipantAttemptView");
            assertThat(schemas.path("ParticipantAttemptView").has("oneOf")).isFalse();
            for (String code : List.of("400", "401", "403", "404", "409", "422", "500", "503"))
                assertThat(op.path("responses").path(code).findValuesAsText("$ref")).contains("#/components/schemas/ProblemDetail");
            for (String name : List.of("ParticipantAttemptView", "ParticipantAttemptItem", "ParticipantAttemptImage", "AttemptFormContent",
                    "AttemptFormChoices", "AttemptFormTrueFalse", "AttemptFormFillGap", "AttemptFormOrdering")) assertThat(schemas.has(name)).as(name).isTrue();
            var examples = op.at("/responses/200/content/application~1json/examples"); assertThat(examples).hasSize(3);
            for (var example : examples) assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), example.path("value"), 0)).as("Example %s", example).isTrue();
            var value = json.valueToTree(fixture());
            assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), value, 0)).isTrue();
            var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) value.deepCopy(); invalid.put("schemaVersion", 2);
            assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), invalid, 0)).isFalse();
            invalid.put("schemaVersion", 1); invalid.remove("stateVersion");
            assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), invalid, 0)).isFalse();
            assertThat(schemas.path("ParticipantAttemptView").path("properties").toString()).doesNotContain("correctAnswer", "score", "explanation", "subjectId");
        }
        var matcher = new org.springframework.util.AntPathMatcher();
        assertThat(groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(p -> matcher.match(p, PATH)))
                .map(org.springdoc.core.models.GroupedOpenApi::getGroup)).containsExactly("attempts-v2");
        var summary = json.readTree(mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.path("groups").findValuesAsText("specUrl")).contains("/v3/api-docs/attempts-v2");
    }

    @Test @WithMockUser void releasedSchemasReviewRoutesAndExamplesHaveUnambiguousTypedContracts() throws Exception {
        var matcher = new org.springframework.util.AntPathMatcher();
        for (String specPath : List.of("/v3/api-docs", "/v3/api-docs/attempts-v2")) {
            var spec = json.readTree(mvc.perform(get(specPath)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var schemas = spec.path("components").path("schemas");
            for (String name : List.of("ParticipantResultView", "ReleasedPracticeScore", "ReleasedPracticeItem", "ReleasedPracticeAnswer",
                    "ReleasedPracticeOptions", "ReleasedPracticeOrderedItems", "ReleasedPracticeBoolean", "ReleasedPracticeGaps", "ReleasedPracticeGap"))
                assertThat(schemas.has(name)).as(name).isTrue();
            assertThat(schemas.path("ParticipantResultView").path("properties").path("items").path("items").path("$ref").asText())
                    .isEqualTo("#/components/schemas/ReleasedPracticeItem");
            var value = json.valueToTree(releasedFixture());
            assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), value, 0)).isTrue();
            var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) value.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.at("/result/items/0/correctAnswer")).put("kind", "UNKNOWN");
            assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), invalid, 0)).isFalse();
            var legacy = json.valueToTree(new AttemptReviewDto(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    java.time.Instant.EPOCH, java.time.Instant.EPOCH, 1.0, 1, 1, List.of()));
            assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), legacy, 0)).isFalse();
            for (String suffix : List.of("/review", "/answer-key")) {
                String path = PATH + suffix;
                var op = spec.path("paths").path(path).path("get");
                assertThat(op.path("security").get(0).has("bearerAuth")).isTrue();
                assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/ParticipantAttemptView");
                for (String code : List.of("401", "403", "404", "409", "422", "500", "503"))
                    assertThat(op.path("responses").path(code).findValuesAsText("$ref")).contains("#/components/schemas/ProblemDetail");
                for (var example : op.at("/responses/200/content/application~1json/examples"))
                    assertThat(matchesFormSchema(spec, schemas.path("ParticipantAttemptView"), example.path("value"), 0)).as(path).isTrue();
                assertThat(groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(p -> matcher.match(p, path)))
                        .map(org.springdoc.core.models.GroupedOpenApi::getGroup)).containsExactly("attempts-v2");
            }
        }
    }

    static ParticipantAttemptView releasedFixture() {
        var base = fixture();
        var answers = base.items().stream().map(item -> {
            uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.Answer answer = switch (item.type()) {
                case MCQ_SINGLE, MCQ_MULTI -> new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.Options(
                        List.of(((AttemptFormResponse.Choices) item.content()).options().get(0).id()));
                case ORDERING -> new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.OrderedItems(
                        ((AttemptFormResponse.Ordering) item.content()).items().stream().map(AttemptFormResponse.Option::id).toList());
                case TRUE_FALSE -> new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.BooleanAnswer(true);
                case FILL_GAP -> new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.Gaps(List.of(
                        new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.Gap(1, "Paris")));
                default -> throw new AssertionError(item.type());
            };
            return new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.Item(item.id(), 1, true,
                    uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.ResponseState.ACCEPTED, answer, answer, "Explanation");
        }).toList();
        return new ParticipantAttemptView(base.representation(), 1, base.attemptId(), base.quizId(), base.quizRevisionId(), base.formId(),
                7, 1, AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE, base.startedAt(), base.startedAt(),
                base.timing(), base.contentVisibility(), base.items(), base.allowedActions(),
                new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView(1, base.startedAt(),
                        new uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView.Score(5, 5, 10000), true, answers));
    }

    static ParticipantAttemptView fixture() {
        var form = AttemptFormOpenApiContractTest.fixture();
        return new ParticipantAttemptView(ParticipantAttemptView.Representation.POLICY_DRIVEN, 1, form.attemptId(), UUID.randomUUID(),
                UUID.randomUUID(), form.form().id(), 1, 1, AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.NOT_STARTED,
                java.time.Instant.parse("2026-11-06T12:00:00Z"), null, ParticipantAttemptView.Timing.UNTIMED,
                ParticipantViewDisclosureReader.ContentVisibility.VISIBLE, AttemptFormResponse.items(form.form()).stream().map(i ->
                    new ParticipantAttemptView.Item(i.id(), i.position(), i.type(), i.questionText(), i.content(), null)).toList(), form.allowedActions());
    }

    /** The response's schema keywords, including exclusive unions and a recursive-schema guard. */
    static boolean matchesFormSchema(JsonNode spec, JsonNode schema, JsonNode value, int depth) {
        if (depth > 20 || schema.isMissingNode()) return false;
        if (value.isNull()) return schema.path("nullable").asBoolean()
                || schema.path("type").isArray() && java.util.stream.StreamSupport.stream(schema.path("type").spliterator(), false).anyMatch(t -> t.asText().equals("null"));
        if (schema.has("$ref")) return matchesFormSchema(spec, spec.at(schema.path("$ref").asText().substring(1)), value, depth + 1);
        for (var part : schema.path("allOf")) if (!matchesFormSchema(spec, part, value, depth + 1)) return false;
        if (schema.has("oneOf")) {
            int matches = 0;
            for (var part : schema.path("oneOf")) if (matchesFormSchema(spec, part, value, depth + 1)) matches++;
            if (matches != 1) return false;
        }
        String type = schema.path("type").isArray() ? schema.path("type").get(0).asText() : schema.path("type").asText();
        if (schema.has("type") && !switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
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


}
