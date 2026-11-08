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

@WebMvcTest(controllers = {NavigationController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = "quizmaker.attempts.navigation-commands-enabled=true")
@Import({SecurityConfig.class, NavigationErrorHandler.class, ResponseAcceptanceJsonConfig.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        NavigationOpenApiTest.DocConfig.class})
class NavigationOpenApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean uk.gegc.quizmaker.features.attempt.application.NavigationService navigation;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final String PATH = "/api/v1/attempts/{attemptId}/navigation";
    static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID ITEM = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final String BODY = """
            {"formItemId":"00000000-0000-0000-0000-000000000002","expectedStateVersion":1,"expectedNavigationVersion":1}
            """;

    @Test @WithMockUser void sequentialAcknowledgementContainsOnlyOriginalPositionMetadata() throws Exception {
        when(navigation.navigate(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.application.NavigationService.Result(
                ATTEMPT, ITEM, 1, new uk.gegc.quizmaker.features.attempt.domain.model.navigation.FlatNavigation.Cursor(UUID.randomUUID(), ITEM, 2), true));
        var body = mvc.perform(post(PATH, ATTEMPT).header("Idempotency-Key", "exact-key").contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.navigation.currentFormItemId").value(ITEM.toString()))
                .andExpect(jsonPath("$.navigation.version").value(2)).andExpect(jsonPath("$.stateVersion").value(1))
                .andExpect(jsonPath("$.replayed").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("correct", "score", "answer", "explanation", "exact-key", "formId");
        var captured = org.mockito.ArgumentCaptor.forClass(uk.gegc.quizmaker.features.attempt.application.NavigationService.Command.class);
        verify(navigation).navigate(captured.capture()); var c = captured.getValue();
        assertThat(c.attemptId()).isEqualTo(ATTEMPT); assertThat(c.formItemId()).isEqualTo(ITEM);
        assertThat(c.expectedStateVersion()).isEqualTo(1); assertThat(c.expectedNavigationVersion()).isEqualTo(1);
        assertThat(c.toString()).doesNotContain(ATTEMPT.toString(), "exact-key");
    }

    @Test @WithMockUser void freeDoesNotExposeAPosition() throws Exception {
        when(navigation.navigate(any())).thenReturn(new uk.gegc.quizmaker.features.attempt.application.NavigationService.Result(ATTEMPT, ITEM, 1, null, false));
        mvc.perform(post(PATH, ATTEMPT).header("Idempotency-Key", "free").contentType("application/json")
                        .content(BODY.replace(",\"expectedNavigationVersion\":1", "")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.navigation").doesNotExist());
        verify(navigation).navigate(argThat(c -> c.expectedNavigationVersion() == null));
    }

    @ParameterizedTest @CsvSource({"UNAUTHENTICATED,401", "NOT_FOUND,404", "INVALID_INPUT,400", "UNSUPPORTED,422", "STALE,409",
            "CONFLICT,409", "INACTIVE,409", "ANSWER_REQUIRED,409", "TRANSITION_DENIED,409", "IN_PROGRESS,409", "EXPIRED,409",
            "CORRUPT,500", "RETRYABLE,503", "UNKNOWN_OUTCOME,503", "UNAVAILABLE,503"})
    @WithMockUser void failuresUseTypedContentFreeProblemDetail(String reason, int code) throws Exception {
        when(navigation.navigate(any())).thenThrow(new uk.gegc.quizmaker.features.attempt.domain.model.navigation.NavigationException(
                uk.gegc.quizmaker.features.attempt.domain.model.navigation.NavigationException.Reason.valueOf(reason)));
        mvc.perform(post(PATH, ATTEMPT).header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().is(code)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("NAVIGATION_" + reason));
    }

    @Test @WithMockUser void malformedCoercedUnknownAndMissingInputNeverReachesApplication() throws Exception {
        for (String invalid : List.of("{}", "null", "[]", BODY.replace("1,", "1.5,"), BODY.replace("1,", "\"1\","),
                BODY.replace(ITEM.toString(), "1-2-3-4-5"), BODY.replace("\"expectedStateVersion\":1", "\"expectedStateVersion\":null"),
                BODY.replace("\"expectedNavigationVersion\":1", "\"expectedNavigationVersion\":0"),
                BODY.replace("\"expectedNavigationVersion\":1", "\"expectedNavigationVersion\":true"),
                BODY.replace("}", ",\"policy\":\"FREE\"}"), BODY.replace("}", ",\"expectedStateVersion\":1}"))) {
            mvc.perform(post(PATH, ATTEMPT).header("Idempotency-Key", "key").contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH, ATTEMPT).contentType("application/json").content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH, ATTEMPT).header("Idempotency-Key", "bad key").contentType("application/json").content(BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(navigation);
    }

    @Test void anonymousIsRejectedBeforeApplication() throws Exception {
        mvc.perform(post(PATH, ATTEMPT).header("Idempotency-Key", "key").contentType("application/json").content(BODY))
                .andExpect(status().isUnauthorized()); verifyNoInteractions(navigation);
    }

    @Test @WithMockUser void fullAndGroupedOpenApiDiscoverNamedSchemasAndValidateExamples() throws Exception {
        for (String url : List.of("/v3/api-docs", "/v3/api-docs/attempts")) {
            var spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var schemas = spec.at("/components/schemas"); var op = spec.path("paths").path(PATH).path("post");
            assertThat(op.path("operationId").asText()).isEqualTo("navigateAttempt");
            assertThat(op.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(op.at("/requestBody/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/NavigateAttemptRequest");
            assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/NavigateAttemptAcknowledgement");
            for (String code : List.of("400", "401", "404", "409", "422", "500", "503"))
                assertThat(op.path("responses").path(code).findValuesAsText("$ref")).contains("#/components/schemas/ProblemDetail");
            for (String node : List.of("/requestBody/content/application~1json", "/responses/200/content/application~1json")) {
                var media = op.at(node); var example = media.path("example");
                if (example.isMissingNode()) example = media.path("examples").elements().next().path("value");
                assertThat(matchesFormSchema(spec, media.path("schema"), example, 0)).isTrue();
            }
            assertThat(op.path("parameters").findValuesAsText("name")).contains("attemptId", "Idempotency-Key");
            assertThat(op.path("description").asText()).contains("ATTEMPT_READ", "Online", "SEQUENTIAL", "FREE");
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
