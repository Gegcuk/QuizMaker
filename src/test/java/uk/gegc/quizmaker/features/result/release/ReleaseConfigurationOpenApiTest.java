package uk.gegc.quizmaker.features.result.release;

import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
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
import uk.gegc.quizmaker.features.auth.application.*;
import uk.gegc.quizmaker.features.auth.infra.security.*;
import uk.gegc.quizmaker.features.result.api.*;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException;
import uk.gegc.quizmaker.shared.api.docs.*;
import uk.gegc.quizmaker.shared.config.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static uk.gegc.quizmaker.features.result.release.ReleaseConfigurationTest.*;

@WebMvcTest(controllers = {ReleaseConfigurationController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = "quizmaker.results.release-configuration-enabled=true")
@Import({SecurityConfig.class, ReleaseConfigurationErrorHandler.class, ReleaseConfigurationJsonConfig.class,
        OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class, SpringDocConfiguration.class,
        SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class, ReleaseConfigurationOpenApiTest.DocConfig.class})
class ReleaseConfigurationOpenApiTest {
    @TestConfiguration(proxyBeanMethods = false) @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean ReleaseConfigurationService configurations;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final UUID QUIZ = UUID.fromString("00000000-0000-0000-0000-000000000010"), REVISION = UUID.fromString("00000000-0000-0000-0000-000000000020"), ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    static final String PATH = "/api/v1/quizzes/{quizId}/revisions/{revisionId}/release-configurations";
    static final String RULE = "{\"version\":1,\"triggers\":[\"MANUAL\"]}";
    static final String BODY = "{\"commandId\":\"00000000-0000-0000-0000-000000000001\",\"expectedVersion\":0,\"rule\":" + RULE + "}";
    ReleaseConfigurationService.Publication publication() {
        return new ReleaseConfigurationService.Publication(ID, QUIZ, REVISION, 1,
                uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat.hash(GOLDEN), manual(), Instant.parse("2026-11-18T12:00:00Z"), false);
    }
    @Test @WithMockUser void typedPublicationPreviewAndCapturedReadsCarryNoParticipantResult() throws Exception {
        when(configurations.preview(eq(QUIZ), eq(REVISION), any())).thenAnswer(invocation -> invocation.getArgument(2));
        when(configurations.publish(any())).thenReturn(publication());
        when(configurations.current(QUIZ, REVISION)).thenReturn(publication());
        when(configurations.read(QUIZ, REVISION, ID)).thenReturn(publication());
        mvc.perform(post(PATH + "/preview", QUIZ, REVISION).contentType("application/json").content(RULE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categories[0]").value("TOTAL_SCORE"))
                .andExpect(jsonPath("$.manualAuthority").value("DEDICATED_ASSIGNMENT_AND_ORGANIZATION_PERMISSION"));
        var response = mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(ID.toString())).andExpect(jsonPath("$.publicationVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("publishedBy", "actorId", "earned", "passed", "response", "objectKey", "email");
        verify(configurations).publish(argThat(c -> c.quizId().equals(QUIZ) && c.revisionId().equals(REVISION)
                && c.expectedVersion() == 0 && c.rule().equals(manual())));
        mvc.perform(get(PATH, QUIZ, REVISION)).andExpect(status().isOk());
        mvc.perform(get(PATH + "/{id}", QUIZ, REVISION, ID)).andExpect(status().isOk());
        mvc.perform(post(PATH + "/preview", QUIZ, REVISION).contentType("application/json")
                .content("{\"version\":1,\"triggers\":[\"MANUAL\"],\"categories\":null,\"schedule\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.categories[1]").value("PASS_FAIL"));
    }
    @Test @WithMockUser void malformedUnknownDuplicateAndCoercedRequestsNeverReachWriter() throws Exception {
        for (String invalid : List.of("{}", "null", "[]", BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":null"),
                BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"), BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
                BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"), BODY.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0"),
                BODY.replace("00000000-0000-0000-0000-000000000001", "1-2-3-4-5"), BODY.replace("\"version\":1", "\"version\":null"),
                BODY.replace("\"MANUAL\"", "\"UNKNOWN\""), BODY.replace("\"version\":1", "\"private\":\"secret\",\"version\":1"), BODY + "{}")) {
            mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(configurations);
    }
    @ParameterizedTest @CsvSource({"INVALID_INPUT,400", "INVALID_SCHEDULE,422", "UNSUPPORTED_VERSION,422", "UNAUTHENTICATED,401", "NOT_FOUND,404", "STALE,409", "CONFLICT,409", "CORRUPT,500", "UNAVAILABLE,503", "UNKNOWN_OUTCOME,503"})
    @WithMockUser void allExpectedFailuresHaveSafeProblemDetails(String reason, int status) throws Exception {
        when(configurations.publish(any())).thenThrow(new ReleaseConfigurationException(ReleaseConfigurationException.Reason.valueOf(reason)));
        mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(BODY))
                .andExpect(status().is(status)).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.code").value("RELEASE_CONFIGURATION_" + reason));
    }
    @Test void anonymousCannotUseAuthorOperations() throws Exception {
        mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH, QUIZ, REVISION)).andExpect(status().isUnauthorized());
        verifyNoInteractions(configurations);
    }
    @Test @WithMockUser void ordinalNumbersCannotChooseReleaseTriggersOrProtectedCategories() throws Exception {
        when(configurations.publish(any())).thenAnswer(invocation -> {
            ReleaseConfigurationService.Command command = invocation.getArgument(0);
            var format = new uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat();
            return new ReleaseConfigurationService.Publication(ID, command.quizId(), command.revisionId(), 1,
                    uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat.hash(format.encode(command.rule())),
                    command.rule(), Instant.parse("2026-11-18T12:00:00Z"), false);
        });
        mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(BODY)).andExpect(status().isOk());
        clearInvocations(configurations);
        for (String invalid : List.of(BODY.replace("\"MANUAL\"", "0"), BODY.replace("\"MANUAL\"", "\"0\""),
                BODY.replace("\"triggers\":[\"MANUAL\"]", "\"triggers\":[\"MANUAL\"],\"categories\":[4]")))
            mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
        verifyNoInteractions(configurations);
    }
    @Test @WithMockUser void invalidRuleVersionAndScheduleHaveNoPublicationEffect() throws Exception {
        mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(BODY.replace("\"version\":1", "\"version\":2"))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(BODY.replace("MANUAL", "SCHEDULED"))).andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(configurations);
    }
    @Test @WithMockUser void fullAndGroupedSpecsHaveNamedContractsValidExamplesAndOneGroup() throws Exception {
        when(configurations.publish(any())).thenReturn(publication());
        var actual = json.readTree(mvc.perform(post(PATH, QUIZ, REVISION).contentType("application/json").content(BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (String url : List.of("/v3/api-docs", "/v3/api-docs/quizzes")) {
            var spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var post = spec.path("paths").path(PATH).path("post");
            assertThat(post.path("operationId").asText()).isEqualTo("publishReleaseConfiguration");
            assertThat(post.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(post.at("/requestBody/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/PublishReleaseConfigurationRequest");
            assertThat(post.at("/responses/200/content/application~1json/schema/$ref").asText()).isEqualTo("#/components/schemas/ReleaseConfigurationPublication");
            assertThat(matches(spec, post.at("/responses/200/content/application~1json/schema"), actual, 0)).isTrue();
            for (String path : List.of(PATH, PATH + "/preview", PATH + "/{configurationId}")) {
                var methods = spec.path("paths").path(path).fields();
                while (methods.hasNext()) {
                    var op = methods.next().getValue();
                    for (String code : List.of("400", "401", "404", "409", "422", "500", "503"))
                        assertThat(op.path("responses").path(code).at("/content/application~1problem+json/schema/$ref").asText()).isEqualTo("#/components/schemas/ProblemDetail");
                    if (op.has("requestBody")) {
                        var media = op.at("/requestBody/content/application~1json");
                        var example = media.path("example");
                        if (example.isMissingNode()) example = media.path("examples").elements().next().path("value");
                        assertThat(matches(spec, media.path("schema"), example, 0)).isTrue();
                    }
                }
            }
        }
        var matcher = new org.springframework.util.AntPathMatcher();
        assertThat(groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(pattern -> matcher.match(pattern, PATH))).map(org.springdoc.core.models.GroupedOpenApi::getGroup)).containsExactly("quizzes");
        var summary = mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(summary).contains("/v3/api-docs/quizzes");
    }
    private static boolean matches(JsonNode spec, JsonNode schema, JsonNode value, int depth) {
        if (depth > 20 || schema.isMissingNode() || value.isMissingNode()) return false;
        if (schema.has("$ref")) return matches(spec, spec.at(schema.path("$ref").asText().substring(1)), value, depth + 1);
        if (value.isNull()) {
            if (schema.path("nullable").asBoolean() || "null".equals(schema.path("type").asText())) return true;
            for (var type : schema.path("type")) if ("null".equals(type.asText())) return true;
            return false;
        }
        for (var part : schema.path("allOf")) if (!matches(spec, part, value, depth + 1)) return false;
        if (schema.has("type")) {
            var types = new ArrayList<String>();
            if (schema.path("type").isArray()) schema.path("type").forEach(t -> types.add(t.asText()));
            else types.add(schema.path("type").asText());
            if (types.stream().noneMatch(t -> switch (t) {
                case "object" -> value.isObject(); case "array" -> value.isArray(); case "string" -> value.isTextual();
                case "integer" -> value.isIntegralNumber(); case "boolean" -> value.isBoolean(); case "null" -> value.isNull(); default -> false;
            })) return false;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (var candidate : schema.path("enum")) if (candidate.equals(value)) found = true;
            if (!found) return false;
        }
        for (var required : schema.path("required")) if (!value.has(required.asText())) return false;
        var fields = schema.path("properties").fields();
        while (fields.hasNext()) { var field = fields.next(); if (value.has(field.getKey()) && !matches(spec, field.getValue(), value.path(field.getKey()), depth + 1)) return false; }
        if (value.isArray() && schema.has("items")) for (var item : value) if (!matches(spec, schema.path("items"), item, depth + 1)) return false;
        return true;
    }
}
