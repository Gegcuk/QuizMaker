package uk.gegc.quizmaker.features.learning.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.learning.application.LearningStudyService;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException;
import uk.gegc.quizmaker.shared.exception.RateLimitExceededException;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;
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

@WebMvcTest(controllers = {LearningSessionController.class, ApiDiscoveryController.class})
@TestPropertySource(properties = {"quizmaker.learning.sessions-enabled=true"})
@Import({SecurityConfig.class, LearningSessionErrorHandler.class, LearningResponseJsonConfig.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        LearningSessionOpenApiTest.DocConfig.class})
class LearningSessionOpenApiTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class DocConfig { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean LearningStudyService study;
    @MockitoBean AuthSessionService authSessions;
    @MockitoBean AuthSessionMetricsService authMetrics;
    @MockitoBean(name = "corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;

    private static final String ROOT = "/api/v1/learning";
    private static final String START = """
            {"commandId":"00000000-0000-4000-8000-000000000001","source":{"kind":"QUIZ_REVISION",
             "quizId":"00000000-0000-4000-8000-000000000002","quizRevisionId":"00000000-0000-4000-8000-000000000003",
             "revisionSchemaVersion":1},"configuration":{"strategy":"fsrs/v1","version":1},"limit":3}
            """;
    private static final String RESPOND = """
            {"commandId":"00000000-0000-4000-8000-000000000001","expectedSessionVersion":1,
             "sourceItemId":"00000000-0000-4000-8000-000000000002","rating":"GOOD",
             "response":{"type":"TRUE_FALSE","answer":true}}
            """;

    @Test void allLearningOperationsRequireAuthentication() throws Exception {
        mvc.perform(get(ROOT + "/sessions/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        for (var path : List.of("/sessions", "/enrollments")) {
            mvc.perform(post(ROOT + path).contentType("application/json").content(START)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(ROOT + "/sessions/" + UUID.randomUUID() + "/responses")
                .contentType("application/json").content(RESPOND)).andExpect(status().isUnauthorized());
        verifyNoInteractions(study);
    }

    @Test @WithMockUser void responseParsingRejectsAmbiguousOrCoercedInputBeforeService() throws Exception {
        String path = ROOT + "/sessions/" + UUID.randomUUID() + "/responses";
        for (String malformed : List.of(RESPOND.replace("true", "\"true\""),
                RESPOND.replace("true", "true,\"answer\":false"),
                RESPOND.replace("\"rating\":\"GOOD\"", "\"rating\":\"CONFIDENT\""),
                RESPOND.replace("\"expectedSessionVersion\":1", "\"expectedSessionVersion\":\"1\""))) {
            mvc.perform(post(path).contentType("application/json").content(malformed))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        verifyNoInteractions(study);
    }

    @Test @WithMockUser void responseRateDenialReturnsRetryAfter() throws Exception {
        when(study.respond(any())).thenThrow(new RateLimitExceededException("Learning command rate exceeded", 2));
        mvc.perform(post(ROOT + "/sessions/" + UUID.randomUUID() + "/responses")
                .contentType("application/json").content(RESPOND))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "2"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test @WithMockUser void unknownStrategyAndInvalidSourceNeverReachTheService() throws Exception {
        mvc.perform(post(ROOT + "/sessions").contentType("application/json").content(START.replace("fsrs/v1","unknown")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.status").value(422));
        mvc.perform(post(ROOT + "/sessions").contentType("application/json").content(START.replace("QUIZ_REVISION","UNKNOWN")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(study);
    }

    @Test @WithMockUser void rateAndUnavailableSourceHaveDistinctSanitizedContracts() throws Exception {
        when(study.start(any())).thenThrow(new RateLimitExceededException("Learning command rate exceeded", 2));
        mvc.perform(post(ROOT + "/sessions").contentType("application/json").content(START))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","2"))
                .andExpect(jsonPath("$.status").value(429));
        when(study.resume(any())).thenThrow(new LearningSessionException(LearningSessionException.Reason.SOURCE_UNAVAILABLE));
        mvc.perform(get(ROOT + "/sessions/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.status").value(404));
    }

    @Test @WithMockUser void lockWaitAndChangedCommandHaveDistinctConflictTypes() throws Exception {
        var lockWait = DatabaseConflictException.from(new java.sql.SQLException("synthetic lock wait", "HY000", 1205)).orElseThrow();
        when(study.start(any())).thenThrow(lockWait, new LearningSessionException(LearningSessionException.Reason.CONFLICT));
        var request = post(ROOT + "/sessions").contentType("application/json").content(START);
        mvc.perform(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorTypes.DATA_CONFLICT.toString()))
                .andExpect(jsonPath("$.detail").value("A concurrent operation is in progress. Retry the original request."));
        mvc.perform(request).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ErrorTypes.IDEMPOTENCY_CONFLICT.toString()));
    }

    @Test @WithMockUser void learningOperationsHaveOneDiscoverableGroupAndTypedExamples() throws Exception {
        var spec = json.readTree(mvc.perform(get("/v3/api-docs/learning")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var summary = json.readTree(mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(summary.path("groups").findValuesAsText("specUrl")).containsOnlyOnce("/v3/api-docs/learning");
        var paths = spec.path("paths");
        assertThat(paths.size()).isEqualTo(4);
        var full = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        var matcher = new org.springframework.util.AntPathMatcher();
        for (String path : List.of(ROOT+"/enrollments",ROOT+"/sessions",ROOT+"/sessions/{sessionId}",ROOT+"/sessions/{sessionId}/responses")) {
            var operation = paths.path(path).path(path.endsWith("}") ? "get" : "post");
            assertThat(full.path("paths").path(path).isObject()).isTrue();
            assertThat(operation.path("security").findValue("bearerAuth")).isNotNull();
            assertThat(groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(p -> matcher.match(p,path)))
                    .map(org.springdoc.core.models.GroupedOpenApi::getGroup)).containsExactly("learning");
            for (String status : List.of("400","401","404","409","422","503")) {
                assertThat(operation.path("responses").path(status).findValuesAsText("$ref")).anyMatch(s -> s.endsWith("/ProblemDetail"));
            }
            String expected = path.endsWith("enrollments") ? "EnrollLearningItemsResponse"
                    : path.endsWith("responses") ? "LearningResponseAcknowledgement" : "LearningSessionView";
            assertThat(operation.path("responses").path("200").findValuesAsText("$ref")).anyMatch(s -> s.endsWith("/"+expected));
            if (!path.endsWith("}")) {
                var body = operation.at("/requestBody/content/application~1json");
                assertThat(body.path("schema").path("$ref").asText()).startsWith("#/components/schemas/");
                JsonNode example = body.path("example");
                if (example.isMissingNode()) example = body.path("examples").findValue("value");
                assertThat(example).isNotNull();
                if (path.endsWith("responses")) {
                    var sample = json.treeToValue(example, LearningSessionApi.RespondRequest.class);
                    assertThat(sample.expectedSessionVersion()).isEqualTo(1L);
                    assertThat(sample.response()).isEqualTo(new uk.gegc.quizmaker.features.attempt.api.dto.AcceptResponseRequest.Truth(true));
                    assertThat(operation.at("/responses/429/headers/Retry-After").isObject()).isTrue();
                    assertThat(body.path("schema").path("$ref").asText()).endsWith("/RespondToLearningItemRequest");
                } else {
                    assertThat(example.path("source").path("kind").asText()).isEqualTo("QUIZ_REVISION");
                }
            }
        }

    }

}
