package uk.gegc.quizmaker.features.learning.api;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
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
        for(String path:List.of("/sets","/sets/{setId}/members","/sets/{setId}/items","/quiz-items")) {
            var parameters=paths.get(ROOT+path).get("get").path("parameters");
            var after=new ArrayList<JsonNode>(); parameters.forEach(p->{if(p.path("name").asText().equals("after")) after.add(p);});
            assertThat(after).as("One optional scalar after query parameter on %s",path).hasSize(1);
            assertThat(after.get(0).path("in").asText()).isEqualTo("query");
            assertThat(after.get(0).path("required").asBoolean()).isFalse();
            assertThat(after.get(0).at("/schema/type").asText()).isEqualTo("string");
            if(path.equals("/sets")) assertThat(after.get(0).at("/schema/format").asText()).isEqualTo("uuid");
        }
        String example=operation.at("/requestBody/content/application~1json/examples").elements().next().get("value").toString();
        if(json.readTree(example).isTextual()) example=json.readTree(example).asText();
        var request=json.readValue(example,LearningSetController.CreateRequest.class);
        assertThat(request.name()).isEqualTo("Anatomy");
        var view=new uk.gegc.quizmaker.features.learning.domain.model.LearningSet.View(ID,"Anatomy",1,false,Instant.EPOCH,Instant.EPOCH);
        when(sets.create(any())).thenReturn(new uk.gegc.quizmaker.features.learning.domain.model.LearningSet.Acknowledgement(request.commandId(),view,1));
        mvc.perform(post(ROOT+"/sets").contentType("application/json").content(example)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/api-summary")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("learning")));
    }
    @ParameterizedTest @ValueSource(strings={"DEBUG","TRACE"}) @WithMockUser
    void mvcDiagnosticsHideCommandsQuestionsAndReturnedCursorsOnNextPage(String level) throws Exception {
        var name="PRIVATE_SET_"+UUID.randomUUID();
        // An existing version-five set can already contain both a quiz and an overlapping question.
        // Read it first; the later create command targets a distinct set, then mutate/delete this one.
        var view=new LearningSet.View(ID,name,5,false,Instant.EPOCH,Instant.EPOCH);
        var source=new LearningSession.Source(1,UUID.randomUUID(),UUID.randomUUID(),1);
        var question=UUID.randomUUID(); var expected=new LearningSchedule.Expected(UUID.randomUUID(),1,0);
        var member=new LearningSet.Member(LearningSet.Kind.QUESTION,null,source,question);
        var item=new LearningSet.Item(source,question,expected,Strategy.FSRS,Instant.EPOCH);
        var scope=new LearningSet.Scope(ID,null);
        var format=new uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat();
        var itemCursor=Base64.getUrlEncoder().withoutPadding().encodeToString(format.encode(new LearningSet.Cursor(
                1,scope,LearningSet.Window.DUE,42,Instant.EPOCH,source.quizId(),source.quizRevisionId(),question,null))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var memberCursor=Base64.getUrlEncoder().withoutPadding().encodeToString(format.encode(new LearningSet.Cursor(
                1,scope,null,42,null,null,null,null,member.key())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var quizScope=new LearningSet.Scope(null,source);
        var quizCursor=Base64.getUrlEncoder().withoutPadding().encodeToString(format.encode(new LearningSet.Cursor(
                1,quizScope,LearningSet.Window.DUE,42,Instant.EPOCH,source.quizId(),source.quizRevisionId(),question,null))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var create=new LearningSetController.CreateRequest(UUID.randomUUID(),name);
        var change=new LearningSetController.ChangeRequest(UUID.randomUUID(),5,member,true);
        var delete=new LearningSetController.DeleteRequest(UUID.randomUUID(),6);
        var created=new LearningSet.Acknowledgement(create.commandId(),new LearningSet.View(UUID.randomUUID(),name,1,false,Instant.EPOCH,Instant.EPOCH),43);
        var changed=new LearningSet.Acknowledgement(change.commandId(),new LearningSet.View(ID,name,6,false,Instant.EPOCH,Instant.EPOCH),44);
        var deleted=new LearningSet.Acknowledgement(delete.commandId(),new LearningSet.View(ID,name,7,true,Instant.EPOCH,Instant.EPOCH),45);
        when(sets.create(any())).thenReturn(created); when(sets.change(eq(ID),any())).thenReturn(changed);
        when(sets.delete(eq(ID),any())).thenReturn(deleted); when(sets.read(ID)).thenReturn(view);
        when(sets.list(1,null)).thenReturn(new LearningSet.SetPage(List.of(view),ID));
        when(sets.members(ID,1,null)).thenReturn(new LearningSet.MemberPage(List.of(member),memberCursor));
        var page=new LearningSet.ItemPage(LearningSet.Window.DUE,Instant.EPOCH,List.of(item),itemCursor);
        var quizPage=new LearningSet.ItemPage(LearningSet.Window.DUE,Instant.EPOCH,List.of(item),quizCursor);
        when(sets.items(eq(scope),eq(LearningSet.Window.DUE),eq(1),isNull())).thenReturn(page);
        when(sets.items(eq(quizScope),eq(LearningSet.Window.DUE),eq(1),isNull())).thenReturn(quizPage);
        var lastQuestion=new UUID(-1L,-1L);
        var lastMember=new LearningSet.Member(LearningSet.Kind.QUESTION,null,source,lastQuestion);
        var lastItem=new LearningSet.Item(source,lastQuestion,new LearningSchedule.Expected(UUID.randomUUID(),1,0),Strategy.FSRS,Instant.EPOCH);
        var lastView=new LearningSet.View(new UUID(-1L,-1L),"PRIVATE_SECOND_SET",1,false,Instant.EPOCH,Instant.EPOCH);
        var lastSets=new LearningSet.SetPage(List.of(lastView),null);
        var lastMembers=new LearningSet.MemberPage(List.of(lastMember),null);
        var lastItems=new LearningSet.ItemPage(LearningSet.Window.DUE,Instant.EPOCH,List.of(lastItem),null);
        when(sets.list(1,ID)).thenReturn(lastSets);
        when(sets.members(ID,1,memberCursor)).thenReturn(lastMembers);
        when(sets.items(scope,LearningSet.Window.DUE,1,itemCursor)).thenReturn(lastItems);
        when(sets.items(quizScope,LearningSet.Window.DUE,1,quizCursor)).thenReturn(lastItems);

        var loggers=List.of((Logger)LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation"),
                (Logger)LoggerFactory.getLogger("org.springframework.web.method"),
                (Logger)LoggerFactory.getLogger(mvc.getDispatcherServlet().getClass()));
        var levels=loggers.stream().map(Logger::getLevel).toList();
        var additivity=loggers.stream().map(Logger::isAdditive).toList();
        var thread=Thread.currentThread();
        var captured=new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if(Thread.currentThread()==thread) { event.prepareForDeferredProcessing(); super.append(event); }
            }
        };
        captured.setContext(loggers.get(0).getLoggerContext()); captured.start();
        loggers.forEach(logger->{logger.addAppender(captured);logger.setAdditive(false);logger.setLevel(Level.toLevel(level));});
        try {
            var acknowledgements=new ArrayList<JsonNode>();
            var commands=List.of(create,change,delete);
            var paths=List.of(ROOT+"/sets",ROOT+"/sets/"+ID+"/members",ROOT+"/sets/"+ID+"/deletion");
            var actualView=json.readTree(mvc.perform(get(ROOT+"/sets/"+ID)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(actualView).isEqualTo(wire(view));
            var actualSets=json.readTree(mvc.perform(get(ROOT+"/sets").queryParam("limit","1")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(actualSets.path("next").asText()).isEqualTo(ID.toString());
            var actualMembers=json.readTree(mvc.perform(get(ROOT+"/sets/"+ID+"/members").queryParam("limit","1"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(actualMembers).isEqualTo(wire(new LearningSet.MemberPage(List.of(member),memberCursor)));
            var actualItems=json.readTree(mvc.perform(get(ROOT+"/sets/"+ID+"/items").queryParam("window","DUE").queryParam("limit","1"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var actualQuizItems=json.readTree(mvc.perform(get(ROOT+"/quiz-items").queryParam("quizId",source.quizId().toString())
                    .queryParam("quizRevisionId",source.quizRevisionId().toString()).queryParam("revisionSchemaVersion","1").queryParam("window","DUE").queryParam("limit","1"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(actualItems).isEqualTo(wire(page)); assertThat(actualQuizItems).isEqualTo(wire(quizPage));
            assertThat(actualQuizItems.path("items")).isEqualTo(actualItems.path("items"));

            var nextSets=json.readTree(mvc.perform(get(ROOT+"/sets").queryParam("limit","1").queryParam("after",actualSets.path("next").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var nextMembers=json.readTree(mvc.perform(get(ROOT+"/sets/"+ID+"/members").queryParam("limit","1").queryParam("after",actualMembers.path("next").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var nextItems=json.readTree(mvc.perform(get(ROOT+"/sets/"+ID+"/items").queryParam("window","DUE").queryParam("limit","1")
                    .queryParam("after",actualItems.path("next").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            var nextQuizItems=json.readTree(mvc.perform(get(ROOT+"/quiz-items").queryParam("quizId",source.quizId().toString())
                    .queryParam("quizRevisionId",source.quizRevisionId().toString()).queryParam("revisionSchemaVersion","1")
                    .queryParam("window","DUE").queryParam("limit","1").queryParam("after",actualQuizItems.path("next").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            assertThat(nextSets).isEqualTo(wire(lastSets)); assertThat(nextMembers).isEqualTo(wire(lastMembers));
            assertThat(nextItems).isEqualTo(wire(lastItems)); assertThat(nextQuizItems).isEqualTo(wire(lastItems));
            verify(sets).list(1,UUID.fromString(actualSets.path("next").asText()));
            verify(sets).members(ID,1,actualMembers.path("next").asText());
            verify(sets).items(scope,LearningSet.Window.DUE,1,actualItems.path("next").asText());
            verify(sets).items(quizScope,LearningSet.Window.DUE,1,actualQuizItems.path("next").asText());

            for(int i=0;i<commands.size();i++) acknowledgements.add(json.readTree(mvc.perform(post(paths.get(i))
                    .contentType("application/json").content(json.writeValueAsString(commands.get(i))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()));
            assertThat(acknowledgements).containsExactly(wire(created),wire(changed),wire(deleted));

            var bodyEvents=captured.list.stream().filter(event->event.getLoggerName().endsWith(".HttpEntityMethodProcessor")
                    || event.getLoggerName().endsWith(".RequestResponseBodyMethodProcessor")).toList();
            assertThat(bodyEvents.stream().filter(event->event.getFormattedMessage().startsWith("Read ")).count())
                    .as("MVC actually logged all three command body reads").isEqualTo(3);
            assertThat(bodyEvents.stream().filter(event->event.getFormattedMessage().startsWith("Writing [")).count())
                    .as("MVC actually logged all twelve response body writes").isEqualTo(12);
            var requestEvents=captured.list.stream().filter(event->event.getLoggerName().equals(loggers.get(2).getName())
                    && event.getFormattedMessage().startsWith("GET ")).toList();
            assertThat(requestEvents).as("DispatcherServlet actually logged all nine GET requests").hasSize(9);
            assertThat(requestEvents.stream().map(ILoggingEvent::getFormattedMessage).filter(message->message.contains("?")))
                    .as("Every real query string was logged in redacted form").hasSize(8)
                    .allSatisfy(message->assertThat(message).contains("?[redacted]"));
            if(level.equals("TRACE")) assertThat(captured.list.stream().filter(event->event.getFormattedMessage().startsWith("Arguments: ")).count())
                    .as("MVC actually logged all twelve resolved argument lists").isEqualTo(12);
            var forbidden=new ArrayList<String>(List.of(actualView.path("name").asText(),actualSets.path("next").asText(),
                    actualMembers.path("next").asText(),actualItems.path("next").asText(),
                    actualQuizItems.path("next").asText(),
                    actualItems.at("/items/0/questionRevisionId").asText(),actualItems.at("/items/0/expected/cycleId").asText(),
                    actualItems.at("/items/0/source/quizId").asText(),actualItems.at("/items/0/source/quizRevisionId").asText()));
            forbidden.add(nextSets.at("/items/0/name").asText());
            forbidden.add(nextItems.at("/items/0/questionRevisionId").asText());
            forbidden.add(nextItems.at("/items/0/expected/cycleId").asText());
            acknowledgements.forEach(ack->{forbidden.add(ack.path("commandId").asText());forbidden.add(ack.at("/set/setId").asText());});
            assertThat(forbidden).allSatisfy(value->assertThat(value).isNotBlank());
            var diagnostics=bodyEvents.stream().map(event->event.getFormattedMessage()+
                    (event.getThrowableProxy()==null?"":ThrowableProxyUtil.asString(event.getThrowableProxy()))).toList();
            assertThat(diagnostics).allSatisfy(message->assertThat(message).doesNotContain(forbidden.toArray(String[]::new)));
            // The set ID is also an HTTP path argument; the private payload values are not.
            var privateValues=forbidden.stream().filter(value->!value.equals(ID.toString())).toArray(String[]::new);
            assertThat(captured.list).allSatisfy(event->assertThat(event.getFormattedMessage()+
                    (event.getThrowableProxy()==null?"":ThrowableProxyUtil.asString(event.getThrowableProxy())))
                    .doesNotContain(privateValues));
        } finally {
            for(int i=0;i<loggers.size();i++) {var logger=loggers.get(i);logger.detachAppender(captured);logger.setLevel(levels.get(i));logger.setAdditive(additivity.get(i));}
            captured.stop();
        }
    }
    private JsonNode wire(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
        return json.readTree(json.writeValueAsString(value));
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
