package uk.gegc.quizmaker.features.learning.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.learning.application.LearningPlanService;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.auth.application.*;
import uk.gegc.quizmaker.features.auth.infra.security.*;
import uk.gegc.quizmaker.shared.config.*;
import uk.gegc.quizmaker.shared.api.docs.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={LearningSetController.class,ApiDiscoveryController.class})
@TestPropertySource(properties={"quizmaker.learning.sessions-enabled=true","quizmaker.learning.sets-enabled=true"})
@Import({SecurityConfig.class,LearningSessionErrorHandler.class,LearningSetJsonConfig.class,OpenApiConfig.class,OpenApiGroupConfig.class,
        ApiDocumentationService.class,SpringDocConfiguration.class,SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,LearningSetOpenApiTest.Docs.class})
class LearningSetOpenApiTest {
    @TestConfiguration(proxyBeanMethods=false) @EnableConfigurationProperties(SpringDocConfigProperties.class) static class Docs { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean uk.gegc.quizmaker.features.learning.application.LearningSetService sets;
    @MockitoBean AuthSessionService sessions;
    @MockitoBean AuthSessionMetricsService metrics;
    @MockitoBean(name="corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;
    @MockitoBean uk.gegc.quizmaker.shared.util.TrustedProxyUtil proxy;
    @MockitoBean CustomOAuth2UserService oauthUsers;
    @MockitoBean OAuth2AuthenticationSuccessHandler oauthSuccess;
    @MockitoBean OAuth2AuthenticationFailureHandler oauthFailure;
    @MockitoBean OAuth2LoginAuthorizationRequestResolver oauthResolver;
    @MockitoBean OAuth2AuthorizationRequestContextRepository oauthRequests;
    static final String ROOT="/api/v1/learning";
    static final UUID COMMAND=UUID.fromString("11111111-1111-4111-8111-111111111111"),ID=UUID.randomUUID();
    static final String CREATE="{\"commandId\":\"11111111-1111-4111-8111-111111111111\",\"name\":\"Anatomy\"}";
    @Test void authenticationIsRequiredBeforeEveryNewBoundary() throws Exception {
        mvc.perform(post(ROOT+"/sets").contentType("application/json").content(CREATE)).andExpect(status().isUnauthorized());
        for(String path:List.of("/sets","/sets/"+ID,"/sets/"+ID+"/members","/sets/"+ID+"/items","/quiz-items"))
            mvc.perform(get(ROOT+path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(sets);
    }
    @Test @WithMockUser void invalidUnknownCoercedAndTrailingBodiesNeverReachACommand() throws Exception {
        for(String bad:List.of(CREATE+" {}",CREATE+" {",CREATE.replace("\"name\"","\"unknown\""),CREATE.replace("\"Anatomy\"","12"),
                CREATE.replace("\"name\":\"Anatomy\"","\"name\":\"A\",\"name\":\"B\"")," ".repeat(32769),"{}"))
            mvc.perform(post(ROOT+"/sets").contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        String change="{\"commandId\":\""+COMMAND+"\",\"expectedVersion\":1,\"member\":{\"kind\":\"SET\",\"childSetId\":\""+ID+"\"},\"present\":true}";
        for(String bad:List.of(change.replace(",\"present\":true",""),change.replace("\"present\":true","\"present\":\"true\""),
                change.replace("\"expectedVersion\":1","\"expectedVersion\":1.5"),change.replace("\"SET\"","0"),
                change.replace("\"childSetId\"","\"ownerId\""),change.replace("\"expectedVersion\":1","\"expectedVersion\":null")))
            mvc.perform(post(ROOT+"/sets/"+ID+"/members").contentType("application/json").content(bad)).andExpect(status().isBadRequest());
        verifyNoInteractions(sets);
    }
    @Test @WithMockUser void namedResponsesNoStoreAndSanitizedFailuresUseExistingProblemContract() throws Exception {
        var view=new uk.gegc.quizmaker.features.learning.domain.model.LearningSet.View(ID,"Private",1,false,Instant.EPOCH,Instant.EPOCH);
        when(sets.create(any())).thenReturn(new uk.gegc.quizmaker.features.learning.domain.model.LearningSet.Acknowledgement(COMMAND,view,1));
        mvc.perform(post(ROOT+"/sets").contentType("application/json").content(CREATE)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.set.setId").value(ID.toString()));
        for(var entry:Map.of(LearningSessionException.Reason.UNAVAILABLE,404,LearningSessionException.Reason.CONFLICT,409,
                LearningSessionException.Reason.UNSUPPORTED_VERSION,422,LearningSessionException.Reason.PERSISTENCE_UNAVAILABLE,503).entrySet()) {
            doThrow(new LearningSessionException(entry.getKey())).when(sets).read(ID);
            mvc.perform(get(ROOT+"/sets/"+ID)).andExpect(status().is(entry.getValue())).andExpect(jsonPath("$.status").value(entry.getValue()));
        }
        when(sets.create(any())).thenThrow(new uk.gegc.quizmaker.shared.exception.RateLimitExceededException("Learning command rate exceeded",2));
        mvc.perform(post(ROOT+"/sets").contentType("application/json").content(CREATE)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","2"));
    }
    @Test @WithMockUser void openApiPublishesTypedPathsErrorsExamplesNullsAndOneDiscoveryGroup() throws Exception {
        var doc=json.readTree(mvc.perform(get("/v3/api-docs/learning")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var paths=doc.path("paths");
        for(String path:List.of("/sets","/sets/{setId}","/sets/{setId}/members","/sets/{setId}/deletion","/sets/{setId}/items","/quiz-items")) {
            assertThat(paths.has(ROOT+path)).as(path).isTrue();
            assertThat(groups.stream().filter(g->g.getPathsToMatch().stream().anyMatch(pattern->new org.springframework.util.AntPathMatcher().match(pattern,ROOT+path)))).hasSize(1);
            paths.get(ROOT+path).elements().forEachRemaining(op->assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText()).as(path).isNotBlank());
        }
        var operation=paths.get(ROOT+"/sets").get("post");
        assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText()).endsWith("CreateLearningSetRequest");
        for(String code:List.of("400","401","404","409","422","429","500","503"))
            assertThat(operation.at("/responses/"+code+"/content").toString()).contains("ProblemDetail");
        var schemas=doc.at("/components/schemas");
        assertThat(schemas.at("/LearningSelectionPage/properties/items/items/$ref").asText()).endsWith("LearningSelectionItem");
        assertThat(schemas.at("/LearningPublicationSource/required").toString()).contains("version","quizId","quizRevisionId","revisionSchemaVersion");
        for (var entry : Map.of("version", List.of(1), "revisionSchemaVersion", List.of(1, 2, 3)).entrySet()) {
            var property = schemas.at("/LearningPublicationSource/properties/" + entry.getKey());
            assertThat(property.path("type").asText()).isEqualTo("integer");
            var allowed = property.path("enum");
            assertThat(allowed).hasSize(entry.getValue().size());
            for (int i = 0; i < allowed.size(); i++) {
                assertThat(allowed.get(i).isIntegralNumber()).isTrue();
                assertThat(allowed.get(i).intValue()).isEqualTo(entry.getValue().get(i));
            }
        }
        for(String name:List.of("LearningSetPage","LearningSetMemberPage","LearningSelectionPage"))
            assertThat(schemas.at("/"+name+"/properties/next/anyOf").toString()).contains("null");
        assertThat(paths.get(ROOT+"/sets/{setId}/items").get("get").path("description").asText()).contains("#667");
        String example=operation.at("/requestBody/content/application~1json/examples").elements().next().get("value").toString();
        if(json.readTree(example).isTextual()) example=json.readTree(example).asText();
        var request=json.readValue(example,LearningSetController.CreateRequest.class);
        assertThat(request.name()).isEqualTo("Anatomy");
        var view=new uk.gegc.quizmaker.features.learning.domain.model.LearningSet.View(ID,"Anatomy",1,false,Instant.EPOCH,Instant.EPOCH);
        when(sets.create(any())).thenReturn(new uk.gegc.quizmaker.features.learning.domain.model.LearningSet.Acknowledgement(request.commandId(),view,1));
        mvc.perform(post(ROOT+"/sets").contentType("application/json").content(example)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("learning")));
    }
    @Test void publicSetRoutesRequireBothExplicitCapabilityFlags() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(uk.gegc.quizmaker.features.learning.application.LearningSetService.class,()->mock(uk.gegc.quizmaker.features.learning.application.LearningSetService.class))
                .withUserConfiguration(LearningSetController.class);
        runner.run(c->assertThat(c).doesNotHaveBean(LearningSetController.class));
        runner.withPropertyValues("quizmaker.learning.sessions-enabled=true").run(c->assertThat(c).doesNotHaveBean(LearningSetController.class));
        runner.withPropertyValues("quizmaker.learning.sessions-enabled=true","quizmaker.learning.sets-enabled=true").run(c->assertThat(c).hasSingleBean(LearningSetController.class));
    }
}
