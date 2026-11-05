package uk.gegc.quizmaker.features.media.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.auth.application.AuthSessionMetricsService;
import uk.gegc.quizmaker.features.auth.infra.security.*;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.cors.CorsConfigurationSource;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.media.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.api.docs.*;
import uk.gegc.quizmaker.shared.config.*;
import uk.gegc.quizmaker.shared.exception.*;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.shared.security.aspect.PermissionAspect;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {MediaController.class, uk.gegc.quizmaker.features.question.api.QuestionController.class, ApiDiscoveryController.class})
@Import({SecurityConfig.class, OpenApiConfig.class, OpenApiGroupConfig.class, ApiDocumentationService.class,
        SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
        AppPermissionEvaluator.class, PermissionAspect.class, MediaRestoreContractTest.Config.class})
class MediaRestoreContractTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @EnableConfigurationProperties(SpringDocConfigProperties.class)
    static class Config { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean MediaAssetService media;
    @MockitoBean uk.gegc.quizmaker.features.question.application.QuestionService questions;
    @MockitoBean uk.gegc.quizmaker.features.question.application.QuestionSchemaService questionSchemas;
    @MockitoBean UserRepository users;
    @MockitoBean RateLimitService rateLimits;
    @MockitoBean AuthSessionService sessions;
    @MockitoBean AuthSessionMetricsService sessionMetrics;
    @MockitoBean(name = "corsConfigurationSource") CorsConfigurationSource cors;
    @MockitoBean TrustedProxyUtil trustedProxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthRequests;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthContext;
    @MockitoBean ClientRegistrationRepository oauthClients;
    final UUID id = UUID.randomUUID();

    @Test void unauthenticatedRestoreCannotCallTheApplication() throws Exception {
        mvc.perform(post("/api/v1/media/{assetId}/restore", id).with(csrf()).accept("application/json")).andExpect(status().isUnauthorized());
        verifyNoInteractions(media);
    }

    @Test @WithMockUser(username = "author")
    void createPermissionDoesNotSubstituteForDeletePermission() throws Exception {
        grant("MEDIA_CREATE");
        mvc.perform(post("/api/v1/media/{assetId}/restore", id).with(csrf())).andExpect(status().isForbidden());
        verifyNoInteractions(media);
    }

    @Test @WithMockUser(username = "author")
    void approvedDeletePermissionCallsRestoreForTheAuthenticatedActor() throws Exception {
        grant("MEDIA_DELETE");
        when(media.restore(id, "author")).thenReturn(response());
        mvc.perform(post("/api/v1/media/{assetId}/restore", id).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assetId").value(id.toString()))
                .andExpect(jsonPath("$.status").value("READY"));
        verify(media).restore(id, "author");
    }

    @Test @WithMockUser(username = "author")
    void ownershipAndStorageFailuresUseProblemDetails() throws Exception {
        grant("MEDIA_DELETE");
        for (var test : List.of(
                new Failure(new ForbiddenException("You cannot modify this media asset"), 403),
                new Failure(new ResourceNotFoundException("Saved image binding is unavailable"), 404),
                new Failure(new SavedImageException(SavedImageException.Reason.CONFLICT), 409),
                new Failure(new SavedImageException(SavedImageException.Reason.UNAVAILABLE), 503))) {
            reset(media);
            when(media.restore(id, "author")).thenThrow(test.exception());
            mvc.perform(post("/api/v1/media/{assetId}/restore", id).with(csrf()))
                    .andExpect(status().is(test.status())).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(test.status()))
                    .andExpect(jsonPath("$.detail").isNotEmpty());
        }
    }

    @Test @WithMockUser
    void restoreHasOneGroupNamedSchemaExamplesAndDiscovery() throws Exception {
        String path = "/api/v1/media/{assetId}/restore";
        for (String url : List.of("/v3/api-docs/media", "/v3/api-docs")) {
            var spec = json.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var operation = spec.path("paths").path(path).path("post");
            assertThat(operation.path("security").isEmpty()).isFalse();
            assertThat(operation.path("description").asText()).contains("MEDIA_DELETE", "current ownership", "idempotent");
            var success = operation.path("responses").path("200").path("content").path("application/json");
            assertThat(success.path("schema").path("$ref").asText()).endsWith("/MediaAssetResponse");
            var example = success.path("examples").path("Restored image").path("value");
            assertThat(json.treeToValue(example, MediaAssetResponse.class).status()).isEqualTo(MediaAssetStatus.READY);
            for (String code : List.of("401", "403", "404", "409", "503"))
                assertThat(operation.path("responses").path(code).toString()).contains("ProblemDetail");
        }
        var matcher = new org.springframework.util.AntPathMatcher();
        assertThat(groups.stream().filter(g -> g.getPathsToMatch().stream().anyMatch(p -> matcher.match(p, path))).toList())
                .extracting(org.springdoc.core.models.GroupedOpenApi::getGroup).containsExactly("media");
        var summary = mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var mediaGroup = json.readTree(summary).path("groups").findValues("specUrl");
        assertThat(mediaGroup).contains(json.getNodeFactory().textNode("/v3/api-docs/media"));
    }

    @Test @WithMockUser(username = "author")
    void questionImageFailuresKeepLegacyValidationAndAddSanitizedStorageErrors() throws Exception {
        grant("QUESTION_CREATE");
        String request = """
                {"type":"TRUE_FALSE","difficulty":"EASY","questionText":"Is this image correct?",
                 "content":{"answer":true},"quizIds":[],"tagIds":[]}
                """;
        for (var test : List.of(new Failure(new ValidationException("Image unavailable"), 400),
                new Failure(new SavedImageException(SavedImageException.Reason.CONFLICT), 409),
                new Failure(new SavedImageException(SavedImageException.Reason.UNAVAILABLE), 503))) {
            reset(questions);
            when(questions.createQuestion(eq("author"), any())).thenThrow(test.exception());
            mvc.perform(post("/api/v1/questions").contentType("application/json").content(request))
                    .andExpect(status().is(test.status())).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        var spec = json.readTree(mvc.perform(get("/v3/api-docs/questions")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (String path : List.of("/api/v1/questions", "/api/v1/questions/{id}")) {
            var responses = spec.path("paths").path(path).path(path.endsWith("{id}") ? "patch" : "post").path("responses");
            for (String status : List.of("400", "409", "503")) assertThat(responses.path(status).toString()).contains("ProblemDetail");
        }
    }

    private void grant(String permission) {
        var actor = new User(); actor.setUsername("author");
        actor.setRoles(Set.of(Role.builder().roleName("test-author").permissions(Set.of(Permission.builder()
                .permissionName(permission).build())).build()));
        when(users.findByUsernameWithRolesAndPermissions("author")).thenReturn(Optional.of(actor));
    }
    private MediaAssetResponse response() {
        return new MediaAssetResponse(id, MediaAssetType.IMAGE, MediaAssetStatus.READY, "retained/image", "https://cdn.example.invalid/image",
                "image/png", 42L, 640, 480, "image.png", null, null, "author", Instant.EPOCH, Instant.EPOCH);
    }
    private record Failure(RuntimeException exception, int status) { }
}
