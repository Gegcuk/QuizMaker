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

@WebMvcTest(controllers={LearningPlanController.class,ApiDiscoveryController.class})
@TestPropertySource(properties={"quizmaker.learning.sessions-enabled=true","quizmaker.learning.custom-plans-enabled=true"})
@Import({SecurityConfig.class,LearningSessionErrorHandler.class,LearningPlanJsonConfig.class,OpenApiConfig.class,OpenApiGroupConfig.class,
        ApiDocumentationService.class,SpringDocConfiguration.class,SpringDocWebMvcConfiguration.class,
        MultipleOpenApiSupportConfiguration.class,LearningPlanOpenApiTest.Docs.class})
class LearningPlanOpenApiTest {
    @TestConfiguration(proxyBeanMethods=false) @EnableConfigurationProperties(SpringDocConfigProperties.class) static class Docs { }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired List<org.springdoc.core.models.GroupedOpenApi> groups;
    @MockitoBean LearningPlanService plans;
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
    static final String SAVE="""
        {"commandId":"11111111-1111-4111-8111-111111111111","expectedVersion":0,
        "definition":{"version":1,"name":"Private anatomy","intervals":[{"value":1,"unit":"DAYS"},{"value":3,"unit":"DAYS"}],
        "mode":"FINITE","coefficient":2,"timeZone":"Europe/London","localTime":"09:00"}}
        """;
    @Test void authenticationIsRequiredBeforePlanCommands() throws Exception {
        mvc.perform(post(ROOT+"/plans").contentType("application/json").content(SAVE)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT+"/plans/"+ID)).andExpect(status().isUnauthorized()); verifyNoInteractions(plans);
    }
    @Test @WithMockUser void malformedAmbiguousAndTrailingInputNeverDispatchesACommand() throws Exception {
        for(String bad:List.of(SAVE+" {}",SAVE+" {",SAVE.replace("\"expectedVersion\":0","\"expectedVersion\":0.5"),
                SAVE.replace("\"expectedVersion\":0","\"expectedVersion\":\"0\""),
                SAVE.replace("\"expectedVersion\":0","\"expectedVersion\":0,\"expectedVersion\":1"),
                SAVE.replace("\"DAYS\"","0"),SAVE.replace("\"DAYS\"","\"0\""),SAVE.replace("\"value\":1","\"value\":0"),
                SAVE.replace("\"expectedVersion\":0,",""),SAVE.replace("\"mode\":\"FINITE\"","\"mode\":\"FINITE\",\"ownerId\":\""+ID+"\""),
                " ".repeat(32769))) {
            mvc.perform(post(ROOT+"/plans").contentType("application/json").content(bad))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Private anatomy"))));
        }
        verifyNoInteractions(plans);
    }
    @Test @WithMockUser void validBodyReturnsNamedPrivateAcknowledgementAndWhitespaceIsAllowed() throws Exception {
        var definition=json.readValue(json.readTree(SAVE).get("definition").toString(),Definition.class);
        var view=new View(ID,1,1,definition,false,Instant.parse("2026-11-22T09:00:00Z"),Instant.parse("2026-11-22T09:00:00Z"));
        when(plans.create(any())).thenReturn(new Acknowledgement(COMMAND,view,null,null));
        mvc.perform(post(ROOT+"/plans").contentType("application/json").content(SAVE+" \n\t"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.commandId").value(COMMAND.toString())).andExpect(jsonPath("$.plan.planVersion").value(1))
                .andExpect(jsonPath("$.plan.definition.intervals[1].value").value(3));
        verify(plans).create(argThat(c->c.commandId().equals(COMMAND) && c.expectedVersion()==0 && c.definition().equals(definition)));
    }
    @Test @WithMockUser void mvcDebugAndTraceDoNotExposePrivateConfigurationOrAcknowledgementIds() throws Exception {
        var definition=json.readValue(json.readTree(SAVE).get("definition").toString(),Definition.class);
        when(plans.create(any())).thenReturn(new Acknowledgement(COMMAND,new View(ID,1,1,definition,false,Instant.EPOCH,Instant.EPOCH),null,null));
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger("org.springframework.web");
        var before=logger.getLevel();
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); appender.start();
        logger.addAppender(appender); logger.setLevel(ch.qos.logback.classic.Level.TRACE);
        try {
            mvc.perform(post(ROOT+"/plans").contentType("application/json").content(SAVE)).andExpect(status().isOk());
            mvc.perform(post(ROOT+"/plans").contentType("application/json").content(SAVE+" {}")).andExpect(status().isBadRequest());
            String logs=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(logs).contains("LearningPlan").doesNotContain("Private anatomy","Europe/London",COMMAND.toString(),ID.toString());
        } finally { logger.setLevel(before); logger.detachAppender(appender); appender.stop(); }
    }
    @Test @WithMockUser void domainFailuresUseControlledProblemsAndRetryAfter() throws Exception {
        for(var entry:Map.of(LearningSessionException.Reason.UNAVAILABLE,404,LearningSessionException.Reason.CONFLICT,409,
                LearningSessionException.Reason.UNSUPPORTED_VERSION,422,LearningSessionException.Reason.PERSISTENCE_UNAVAILABLE,503).entrySet()) {
            doThrow(new LearningSessionException(entry.getKey())).when(plans).read(ID);
            mvc.perform(get(ROOT+"/plans/"+ID)).andExpect(status().is(entry.getValue())).andExpect(jsonPath("$.status").value(entry.getValue()));
        }
        when(plans.create(any())).thenThrow(new uk.gegc.quizmaker.shared.exception.RateLimitExceededException("Learning command rate exceeded",2));
        mvc.perform(post(ROOT+"/plans").contentType("application/json").content(SAVE)).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After","2"));
    }
    @Test @WithMockUser void openApiHasTypedEnvelopesErrorsExamplesOneGroupAndDiscovery() throws Exception {
        var doc=json.readTree(mvc.perform(get("/v3/api-docs/learning")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var paths=doc.get("paths");
        for(String path:List.of("/plans","/plans/{planId}","/plans/{planId}/versions","/plans/{planId}/pause","/plan-bindings","/plan-cycles","/plan-cycles/{questionRevisionId}")) {
            assertThat(paths.has(ROOT+path)).as(path).isTrue();
            assertThat(groups.stream().filter(g->g.getPathsToMatch().stream().anyMatch(pattern->new org.springframework.util.AntPathMatcher().match(pattern,ROOT+path)))).hasSize(1);
            paths.get(ROOT+path).elements().forEachRemaining(op->assertThat(op.at("/responses/200/content/application~1json/schema/$ref").asText()).as(path).isNotBlank());
        }
        var operation=paths.get(ROOT+"/plans").get("post");
        assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText()).contains("SaveLearningPlanRequest");
        String example=operation.at("/requestBody/content/application~1json/examples").elements().next().get("value").toString();
        if(json.readTree(example).isTextual()) example=json.readTree(example).asText();
        var definition=json.readValue(json.readTree(example).get("definition").toString(),Definition.class);
        when(plans.create(any())).thenReturn(new Acknowledgement(COMMAND,new View(ID,1,1,definition,false,Instant.EPOCH,Instant.EPOCH),null,null));
        mvc.perform(post(ROOT+"/plans").contentType("application/json").content(example)).andExpect(status().isOk());
        for(String status:List.of("400","401","404","409","422","429","500","503"))
            assertThat(operation.at("/responses/"+status+"/content").toString()).contains("ProblemDetail");
        assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText()).isNotBlank();
        var schemas=doc.at("/components/schemas");
        assertThat(schemas.at("/LearningPlanPage/properties/items/items/$ref").asText()).endsWith("LearningPlanView");
        assertThat(schemas.at("/LearningPlanDefinition/properties/intervals/minItems").asInt()).isEqualTo(1);
        assertThat(schemas.at("/LearningPlanDefinition/properties/intervals/maxItems").asInt()).isEqualTo(100);
        assertThat(schemas.at("/LearningPlanDefinition/properties/coefficient/minimum").decimalValue()).isEqualByComparingTo("1");
        mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("learning")));
    }
    @Test void planRoutesRequireBothExplicitCapabilityFlags() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(LearningPlanService.class,()->mock(LearningPlanService.class)).withUserConfiguration(LearningPlanController.class);
        runner.run(c->assertThat(c).doesNotHaveBean(LearningPlanController.class));
        runner.withPropertyValues("quizmaker.learning.sessions-enabled=true").run(c->assertThat(c).doesNotHaveBean(LearningPlanController.class));
        runner.withPropertyValues("quizmaker.learning.sessions-enabled=true","quizmaker.learning.custom-plans-enabled=true")
                .run(c->assertThat(c).hasSingleBean(LearningPlanController.class));
    }
}
