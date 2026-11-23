package uk.gegc.quizmaker.features.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptDeliveryService;
import uk.gegc.quizmaker.features.attempt.application.AttemptStartService;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService;
import uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.result.application.AutomaticResultService;
import uk.gegc.quizmaker.features.result.application.ImmediateResultReleaseService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.application.QuestionService;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.api.problem.ErrorTypes;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;


import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Real custom/mixed Learning HTTP commands, immutable publication and MySQL (#840).
 *
 * <p>The production application context runs with the Learning routes enabled, real JWT security and
 * error handling, the Flyway schema and MySQL. Every Learning row is created by real HTTP commands from
 * empty storage. The source publication is prepared by the #700 writer. The public activation
 * scenario must exercise the #935 author route and the Learning routes together.</p>
 */
@Tag("db-serial")
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = LearningCustomMySqlIntegrationTest.SchemaCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "quizmaker.learning.sessions-enabled=true",
        "quizmaker.learning.custom-plans-enabled=true",
        "quizmaker.learning.custom-reviews-enabled=true",
        "quizmaker.learning.due.max-batch-size=20",
        // These existing loggers print request URIs and quiz IDs; this suite's diagnostics stay ID-free.
        "logging.level.uk.gegc.quizmaker.features.auth.infra.security.JwtAuthenticationFilter=ERROR",
        "logging.level.uk.gegc.quizmaker.features.result.application.impl.QuizAnalyticsServiceImpl=WARN"
})
class LearningCustomMySqlIntegrationTest {
    private static final String CATALOG = "learning_custom_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PORT = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
    private static final String ADMIN_USER = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String ROOT = "/api/v1/learning";
    private static final long TOKEN = 60_000_000L;
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Learning-owned rows in foreign-key deletion order; used for class-local cleanup only. */
    private static final List<String> LEARNING_TABLES = List.of("learning_custom_change","learning_custom_stage",
            "learning_plan_command","learning_plan_question_override","learning_plan_quiz_default","learning_custom_state",
            "learning_plan_version","learning_plan","learning_enrollment_item","learning_enrollment",
            "learning_prepared_publication","learning_session_response","learning_session_start","learning_cycle_session",
            "learning_review_event","learning_fsrs_state","learning_schedule_receipt","learning_schedule_cycle",
            "learning_schedule_slot","learning_form_option","learning_form_item","learning_form",
            "learning_session_create_receipt","learning_session","learning_command_budget");
    private static final MutableClock CLOCK = new MutableClock();

    private static String serverUrl() {
        if (!PORT.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        return "jdbc:mysql://127.0.0.1:" + PORT + "/";
    }

    @DynamicPropertySource
    static void ownCatalog(DynamicPropertyRegistry properties) {
        // Flyway creates this class's production schema before Hibernate validates it.
        properties.add("spring.datasource.url", () -> serverUrl() + CATALOG + OPTIONS + "&createDatabaseIfNotExist=true");
        properties.add("spring.datasource.username", () -> ADMIN_USER);
        properties.add("spring.datasource.password", () -> ADMIN_PASSWORD);
    }

    public static class SchemaCleanup extends AbstractTestExecutionListener {
        // afterTestClass runs in reverse order: close the context and its pool before dropping the catalog.
        @Override public int getOrder() { return 1000; }

        @Override public void afterTestClass(TestContext ignored) {
            admin().execute("DROP DATABASE IF EXISTS " + CATALOG);
        }
    }

    /** Both the application clock and the admission clock used by D33 accounting follow one test timeline. */
    @TestBean(name = "clock", methodName = "learningClock") Clock applicationClock;
    @TestBean(name = "authAdmissionClock", methodName = "learningClock") Clock admissionClock;

    static Clock learningClock() { return CLOCK; }

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    uk.gegc.quizmaker.features.learning.domain.repository.LearningStudyRepository responseStore;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    uk.gegc.quizmaker.features.learning.domain.repository.LearningPlanRepository planStore;
    @Autowired uk.gegc.quizmaker.features.learning.application.LearningCycleService cycleService;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionRepository questions;
    @Autowired QuestionService authoring;
    @Autowired QuizRevisionPublicationService publications;
    @Autowired AuthSessionService authSessions;
    @Autowired MeterRegistry metrics;
    @Autowired AttemptStartService attemptStarts;
    @Autowired AttemptDeliveryService attemptDelivery;
    @Autowired ResponseAcceptanceService attemptResponses;
    @Autowired AttemptSubmissionService attemptSubmissions;
    @Autowired AutomaticResultService automaticResults;
    @Autowired ImmediateResultReleaseService resultReleases;
    TransactionTemplate tx;
    Instant now;

    @BeforeEach
    void freezeTime() {
        tx = new TransactionTemplate(transactions);
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        CLOCK.set(now);
    }

    @AfterEach
    void removeLearningRows() {
        SecurityContextHolder.clearContext();
        tx.setReadOnly(false);
        tx.executeWithoutResult(status -> {
            sql.update("UPDATE learning_schedule_slot SET active_cycle_id=NULL,cycle_version=0");
            for (String table : LEARNING_TABLES) sql.update("DELETE FROM " + table);
        });
    }

    @Test
    void mixedSessionAdvancesEachCapturedStrategyAndExactReplayDoesNotAdvanceAgain() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Custom item","FSRS item");
        var custom=custom(anna,source,0,definition("FINITE",1,3,7));
        body(perform(enroll(anna,source,UUID.randomUUID(),List.of(source.questions().get(1)))).andReturn(),200);
        var form=read(perform(start(anna,source,UUID.randomUUID(),20,null,null)).andReturn(),200);
        UUID session=UUID.fromString(form.path("sessionId").asText());
        assertThat(form.path("items").size()).isEqualTo(2);
        var edit=json.createObjectNode().put("commandId",UUID.randomUUID().toString()).put("expectedVersion",1);
        edit.set("definition",definition("FINITE",7,14,30));
        send(anna,"/plans/"+custom.path("planId").asText()+"/versions",edit,200);
        for(int i=0;i<2;i++) {
            var item=form.path("items").get(i); UUID command=UUID.randomUUID();
            var first=body(perform(respond(anna,session,command,i+1,item.path("sourceItemId").asText(),"GOOD",true)).andReturn(),200);
            assertSame(body(perform(respond(anna,session,command,i+1,item.path("sourceItemId").asText(),"GOOD",true)).andReturn(),200),first,"exact accepted response");
            body(perform(respond(anna,session,command,i+1,item.path("sourceItemId").asText(),"EASY",true)).andReturn(),409);
        }
        assertThat(count("learning_custom_change")).isOne(); assertThat(count("learning_review_event")).isOne();
        assertThat(count("learning_session_response")).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_cycle_session WHERE released=0",Integer.class)).isZero();
        var cycle=cycle(anna,source,0); assertThat(cycle.path("sequence").asLong()).isOne();
        assertThat(cycle.path("stage").asInt()).isZero(); assertThat(cycle.path("cycleId").asText().equals(custom.path("cycleId").asText())).isTrue();
        var fresh=context.getAutowireCapableBeanFactory().createBean(uk.gegc.quizmaker.features.learning.application.impl.LearningCycleServiceImpl.class);
        var restored=asPrincipal(anna,()->fresh.read(domain(source),source.questions().get(0)));
        assertThat(restored.expected().sequence()).isOne();
        assertThat(restored.dueAt()).isEqualTo(now.atZone(ZoneId.of("Europe/London")).toLocalDate().plusDays(1)
                .atTime(9,0).atZone(ZoneId.of("Europe/London")).toInstant());
        assertThat(count("spaced_repetition_entry")).isZero(); assertThat(count("repetition_review_log")).isZero();
        assertThat(count("attempts")).isZero(); assertThat(count("attempt_automatic_result")).isZero();
    }

    @Test
    void finiteCompletionPracticePauseAndRestartUseRealCommands() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Finite item");
        var initial=custom(anna,source,0,definition("FINITE",1));
        var accepted=review(anna,source,null,"GOOD"); assertThat(accepted.path("nextDueAt").isNull()).isFalse();
        var afterFirst=cycle(anna,source,0);
        review(anna,source,"PRACTICE","AGAIN"); assertSame(cycle(anna,source,0),afterFirst,"practice preserves custom state");
        var finished=review(anna,source,"EARLY","EASY"); assertThat(finished.path("nextDueAt").isNull()).isTrue();
        var finalCycle=cycle(anna,source,0); assertThat(finalCycle.path("completed").asBoolean()).isTrue();
        review(anna,source,"PRACTICE","GOOD"); assertSame(cycle(anna,source,0),finalCycle,"practice preserves completed state");
        assertThat(read(perform(start(anna,source,UUID.randomUUID(),20,null,null)).andReturn(),200).path("completed").asBoolean()).isTrue();
        var restart=command(UUID.randomUUID(),source); restart.remove("configuration");
        restart.put("questionRevisionId",source.questions().get(0).toString()).put("action","RESTART").put("strategy","CUSTOM").put("confirmed",true);
        restart.set("expected",json.createObjectNode().put("cycleId",finalCycle.path("cycleId").asText())
                .put("cycleVersion",finalCycle.path("cycleVersion").asLong()).put("sequence",finalCycle.path("sequence").asLong()));
        restart.put("targetPlanId",initial.path("planId").asText()).put("targetPlanVersion",1);
        var restarted=send(anna,"/plan-cycles",restart,200).path("cycle");
        assertThat(restarted.path("sequence").asLong()).isZero(); assertThat(restarted.path("cycleVersion").asLong()).isEqualTo(2);
        assertThat(count("learning_custom_change")).isEqualTo(2);
        var pause=json.createObjectNode().put("commandId",UUID.randomUUID().toString()).put("expectedVersion",1).put("paused",true);
        send(anna,"/plans/"+initial.path("planId").asText()+"/pause",pause,200);
        assertThat(read(perform(start(anna,source,UUID.randomUUID(),20,null,null)).andReturn(),200).path("items").isEmpty()).isTrue();
        CLOCK.set(now.plusSeconds(31L*86400));
        anna=new Persona(anna.id(),anna.name(),authSessions.issueTokensForUser(users.findById(anna.id()).orElseThrow()).accessToken());
        pause.put("commandId",UUID.randomUUID().toString()).put("expectedVersion",2).put("paused",false);
        send(anna,"/plans/"+initial.path("planId").asText()+"/pause",pause,200);
        assertThat(read(perform(start(anna,source,UUID.randomUUID(),20,null,null)).andReturn(),200).path("items").size()).isOne();
    }

    @Test
    void generatedHistoryIsDurableAndAgainUsesTheActualStageLineage() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Indefinite item");
        custom(anna,source,0,definition("INDEFINITE",14)); review(anna,source,null,"GOOD");
        for(int days:new int[]{28,56,112}) {
            CLOCK.set(CLOCK.instant().plusSeconds(60)); var ack=review(anna,source,"EARLY","GOOD");
            var expected=CLOCK.instant().atZone(ZoneId.of("Europe/London")).toLocalDate().plusDays(days)
                    .atTime(9,0).atZone(ZoneId.of("Europe/London")).toInstant();
            assertThat(Instant.parse(ack.path("nextDueAt").asText())).isEqualTo(expected);
        }
        assertThat(count("learning_custom_stage")).isEqualTo(3);
        CLOCK.set(CLOCK.instant().plusSeconds(60)); review(anna,source,"EARLY","AGAIN");
        assertThat(cycle(anna,source,0).path("stage").asInt()).isOne();
        assertThat(count("learning_custom_stage")).isEqualTo(3); assertThat(count("learning_custom_change")).isEqualTo(5);
    }

    @Test
    void timezonePreviewIsReadOnlyAndStaleConfirmationCannotEraseAReview() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Calendar item");
        custom(anna,source,0,definition("FINITE",1,3,7));
        var previewRequest=json.createObjectNode().put("timeZone","America/New_York"); previewRequest.set("source",sourceRef(source));
        var preview=send(anna,timezone(source,0)+"/preview",previewRequest,200);
        var before=cycle(anna,source,0); long credit=credit(anna);
        send(anna,timezone(source,0)+"/preview",previewRequest,200);
        assertSame(cycle(anna,source,0),before,"unconfirmed preview preserves due"); assertThat(credit(anna)).isEqualTo(credit);
        review(anna,source,null,"GOOD"); var afterReview=cycle(anna,source,0);
        var confirmation=json.createObjectNode().put("commandId",UUID.randomUUID().toString()).put("confirmed",true);
        confirmation.set("preview",preview); send(anna,timezone(source,0)+"/confirm",confirmation,409);
        send(anna,timezone(source,0)+"/confirm",confirmation,409);
        assertSame(cycle(anna,source,0),afterReview,"stale preview preserves committed review");
        preview=send(anna,timezone(source,0)+"/preview",previewRequest,200); confirmation.set("preview",preview);
        var confirmed=send(anna,timezone(source,0)+"/confirm",confirmation,200);
        assertSame(send(anna,timezone(source,0)+"/confirm",confirmation,200),confirmed,"timezone replay");
        assertThat(cycle(anna,source,0).path("sequence").asLong()).isOne();
        assertThat(cycle(anna,source,0).path("dueAt").asText().equals(preview.path("nextDueAt").asText())).isTrue();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_custom_change WHERE kind='GOOD'",Integer.class)).isOne();
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_custom_change WHERE kind='TIMEZONE'",Integer.class)).isOne();
        var current=cycle(anna,source,0);
        var change=command(UUID.randomUUID(),source); change.remove("configuration");
        change.put("questionRevisionId",source.questions().get(0).toString()).put("action","SWITCH").put("strategy","FSRS").put("confirmed",true);
        change.set("expected",json.createObjectNode().put("cycleId",current.path("cycleId").asText())
                .put("cycleVersion",current.path("cycleVersion").asLong()).put("sequence",current.path("sequence").asLong()));
        var replacement=send(anna,"/plan-cycles",change,200).path("cycle");
        assertSame(send(anna,timezone(source,0)+"/confirm",confirmation,200),confirmed,"old accepted timezone receipt survives switching");
        confirmation.put("commandId",UUID.randomUUID().toString());
        send(anna,timezone(source,0)+"/confirm",confirmation,409);
        assertSame(cycle(anna,source,0),replacement,"stale timezone preview leaves replacement unchanged");
    }

    @Test
    void reachedResponseFailureRollsBackScheduleHistoryProgressReceiptAndCredit() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Rollback item");
        custom(anna,source,0,definition("FINITE",1,3));
        var form=read(perform(start(anna,source,UUID.randomUUID(),1,null,null)).andReturn(),200);
        UUID session=UUID.fromString(form.path("sessionId").asText()),command=UUID.randomUUID();
        String item=form.path("items").get(0).path("sourceItemId").asText(); long credit=credit(anna);
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        var responseSpy=(uk.gegc.quizmaker.features.learning.domain.repository.LearningStudyRepository)
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(responseStore);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(count("learning_custom_change")).isOne(); assertThat(count("learning_session_response")).isOne();
            assertThat(sql.queryForObject("SELECT sequence FROM learning_custom_state",Long.class)).isOne();
            reached.set(true); throw new uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException(
                    uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.PERSISTENCE_UNAVAILABLE);
        }).when(responseSpy).recordResponse(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        try { body(perform(respond(anna,session,command,1,item,"GOOD",true)).andReturn(),503); }
        finally { org.mockito.Mockito.doCallRealMethod().when(responseSpy).recordResponse(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any()); }
        assertThat(reached).isTrue(); assertThat(count("learning_custom_change")).isZero(); assertThat(count("learning_session_response")).isZero();
        assertThat(cycle(anna,source,0).path("sequence").asLong()).isZero(); assertThat(credit(anna)).isEqualTo(credit);
        assertThat(read(perform(resume(anna,session)).andReturn(),200).path("reviewedItems").asInt()).isZero();
        body(perform(respond(anna,session,command,1,item,"GOOD",true)).andReturn(),200);
        assertThat(count("learning_custom_change")).isOne();
    }

    @Test
    void competingSessionsAdvanceOnceAndIdenticalConcurrentRetriesReturnTheSameAcknowledgement() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Race item");
        custom(anna,source,0,definition("FINITE",1,3));
        var a=read(perform(start(anna,source,UUID.randomUUID(),1,null,null)).andReturn(),200);
        var b=read(perform(start(anna,source,UUID.randomUUID(),1,null,null)).andReturn(),200);
        UUID sa=UUID.fromString(a.path("sessionId").asText()),sb=UUID.fromString(b.path("sessionId").asText());
        String item=a.path("items").get(0).path("sourceItemId").asText();
        var race=whileLearnerIsLocked(anna,()->respond(anna,sa,UUID.randomUUID(),1,item,"GOOD",true),
                ()->respond(anna,sb,UUID.randomUUID(),1,item,"GOOD",true));
        assertThat(race.stream().map(r->r.getResponse().getStatus()).toList()).containsExactlyInAnyOrder(200,409);
        assertThat(count("learning_custom_change")).isOne();
        var states=List.of(read(perform(resume(anna,sa)).andReturn(),200).path("status").asText(),
                read(perform(resume(anna,sb)).andReturn(),200).path("status").asText());
        assertThat(states).containsExactlyInAnyOrder("COMPLETED","SUPERSEDED");
        var early=read(perform(start(anna,source,UUID.randomUUID(),1,"EARLY",source.questions().get(0))).andReturn(),200);
        UUID session=UUID.fromString(early.path("sessionId").asText()),command=UUID.randomUUID();
        var replay=whileLearnerIsLocked(anna,()->respond(anna,session,command,1,item,"GOOD",true),()->respond(anna,session,command,1,item,"GOOD",true));
        assertSame(body(replay.get(0),200),body(replay.get(1),200),"concurrent exact replay");
        assertThat(count("learning_custom_change")).isEqualTo(2);
    }

    @Test
    void privateSourceAndCurrentPermissionApplyToTheNewRoutesAndReplay() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var bob=persona("bob","QUIZ_UPDATE");
        var source=publishedQuiz(anna,"Private item"); custom(anna,source,0,definition("FINITE",1,3));
        var request=json.createObjectNode().put("timeZone","UTC"); request.set("source",sourceRef(source));
        send(null,timezone(source,0)+"/preview",request,401); send(bob,timezone(source,0)+"/preview",request,404);
        var preview=send(anna,timezone(source,0)+"/preview",request,200);
        var confirm=json.createObjectNode().put("commandId",UUID.randomUUID().toString()).put("confirmed",true); confirm.set("preview",preview);
        send(anna,timezone(source,0)+"/confirm",confirm,200);
        sql.update("DELETE FROM user_roles WHERE user_id=UUID_TO_BIN(?)",anna.id().toString());
        send(anna,timezone(source,0)+"/confirm",confirm,404);
        assertThat(count("learning_custom_change")).isOne();
        request.put("timeZone","bad/zone"); send(bob,timezone(source,0)+"/preview",request,404);
    }

    @Test
    void openApiPublishesNamedTimezoneContractsAndStrictInputWithOneLearningGroup() throws Exception {
        var doc=read(perform(get("/v3/api-docs/learning")).andReturn(),200);
        var schemas=doc.at("/components/schemas");
        for (String field:List.of("plan", "binding", "cycle")) {
            var alternatives=schemas.at("/LearningPlanAcknowledgement/properties/"+field+"/anyOf");
            assertThat(alternatives.isArray()).isTrue();
            assertThat(alternatives.toString()).contains("\"type\":\"null\"", "#/components/schemas/LearningPlan");
        }
        for (String field:List.of("nextItemId", "nextDueAt"))
            assertThat(schemas.at("/LearningResponseAcknowledgement/properties/"+field+"/anyOf").toString())
                    .contains("\"type\":\"null\"", "\"format\"");
        assertThat(schemas.at("/LearningPlanCyclePage/properties/next/anyOf").toString()).contains("\"type\":\"null\"");
        assertThat(schemas.at("/ProblemDetail/properties/instance/format").asText()).isEqualTo("uri-reference");
        String path=ROOT+"/plan-cycles/{questionRevisionId}/timezone";
        for(String suffix:List.of("/preview","/confirm")) {
            var operation=doc.path("paths").path(path+suffix).path("post");
            assertThat(operation.at("/requestBody/content/application~1json/schema/$ref").asText()).contains("LearningTimezoneRequest");
            assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText()).isNotBlank();
            for(String status:List.of("400","401","404","409","415","422","429","500","503"))
                assertThat(operation.at("/responses/"+status+"/content").toString()).contains("ProblemDetail");
            var media=operation.at("/requestBody/content/application~1json");
            var example=media.has("example")?media.path("example"):media.path("examples").elements().next().path("value");
            String text=example.isTextual()?example.asText():example.toString();
            Class<?> type=suffix.equals("/preview")?uk.gegc.quizmaker.features.learning.api.LearningTimezoneController.PreviewRequest.class
                    :uk.gegc.quizmaker.features.learning.api.LearningTimezoneController.ConfirmRequest.class;
            Object parsed=json.readValue(text,type);
            assertThat(context.getBean(jakarta.validation.Validator.class).validate(parsed)).isEmpty();
        }
        assertThat(read(perform(get("/api/v1/api-summary")).andReturn(),200).toString()).contains("learning");
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Validation item"); custom(anna,source,0,definition("FINITE",1));
        var request=json.createObjectNode().put("timeZone","UTC"); request.set("source",sourceRef(source));
        for(String bad:List.of(request+" {}",request.toString().replace("\"UTC\"","1"),request.toString().replace("\"timeZone\"","\"ownerId\"")))
            body(perform(authorized(post(ROOT+timezone(source,0)+"/preview"),anna).contentType("application/json").content(bad)).andReturn(),400);
        send(anna,timezone(source,0)+"/preview",request,200);
        body(perform(authorized(post(ROOT+timezone(source,0)+"/preview"),anna).contentType("text/plain").content(request.toString())).andReturn(),415);
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(uk.gegc.quizmaker.features.learning.application.LearningCycleService.class,
                        ()->org.mockito.Mockito.mock(uk.gegc.quizmaker.features.learning.application.LearningCycleService.class))
                .withUserConfiguration(uk.gegc.quizmaker.features.learning.api.LearningTimezoneController.class);
        runner.withPropertyValues("quizmaker.learning.sessions-enabled=true","quizmaker.learning.custom-plans-enabled=true")
                .run(c->assertThat(c).doesNotHaveBean(uk.gegc.quizmaker.features.learning.api.LearningTimezoneController.class));
        runner.withPropertyValues("quizmaker.learning.sessions-enabled=true","quizmaker.learning.custom-plans-enabled=true","quizmaker.learning.custom-reviews-enabled=true")
                .run(c->assertThat(c).hasSingleBean(uk.gegc.quizmaker.features.learning.api.LearningTimezoneController.class));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(uk.gegc.quizmaker.features.learning.application.impl.LearningCycleServiceImpl.class)
                .withPropertyValues("quizmaker.learning.sessions-enabled=true","quizmaker.learning.custom-plans-enabled=true")
                .run(c->assertThat(c).doesNotHaveBean(uk.gegc.quizmaker.features.learning.application.impl.LearningCycleServiceImpl.class));
    }

    @Test
    void firstCustomEnrollmentRollsBackRealPublicationPreparationAtAReachedCycleWrite() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Preparation A","Preparation B");
        var create=json.createObjectNode().put("commandId",UUID.randomUUID().toString()).put("expectedVersion",0);
        create.set("definition",definition("FINITE",1)); var plan=send(anna,"/plans",create,200).path("plan");
        var binding=command(UUID.randomUUID(),source); binding.remove("configuration"); binding.put("expectedVersion",0).put("planId",plan.path("planId").asText());
        send(anna,"/plan-bindings",binding,200);
        int bindings=count("question_revision_contract_bindings"); long credit=credit(anna);
        var enrollment=command(UUID.randomUUID(),source); enrollment.remove("configuration");
        enrollment.put("questionRevisionId",source.questions().get(0).toString()).put("action","ENROLL").put("strategy","CUSTOM").put("confirmed",false);
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        var planSpy=(uk.gegc.quizmaker.features.learning.domain.repository.LearningPlanRepository)
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(planStore);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(count("question_revision_contract_bindings")).isEqualTo(bindings+2);
            assertThat(count("learning_prepared_publication")).isOne(); assertThat(count("learning_custom_state")).isOne();
            reached.set(true); throw new uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException(
                    uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.PERSISTENCE_UNAVAILABLE);
        }).when(planSpy).createCustom(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        try { send(anna,"/plan-cycles",enrollment,503); }
        finally { org.mockito.Mockito.doCallRealMethod().when(planSpy).createCustom(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()); }
        assertThat(reached).isTrue(); assertThat(count("question_revision_contract_bindings")).isEqualTo(bindings);
        assertThat(count("learning_prepared_publication")).isZero(); assertThat(count("learning_custom_state")).isZero();
        assertThat(credit(anna)).isEqualTo(credit);
        send(anna,"/plan-cycles",enrollment,200);
        assertThat(count("question_revision_contract_bindings")).isEqualTo(bindings+2);
        assertThat(count("learning_custom_state")).isOne();
    }

    @Test
    void dueQueryUsesOneBoundedProjectionAcrossDistinctPlanParentsAndRejectsFutureStrategies() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"One","Two","Three","Four");
        for(int i=0;i<4;i++) custom(anna,source,i,definition("FINITE",1,3));
        var observed=new LearningScheduleMySqlIntegrationTest.ObservedSource(
                new DriverManagerDataSource(serverUrl()+CATALOG+OPTIONS,ADMIN_USER,ADMIN_PASSWORD));
        var repository=new uk.gegc.quizmaker.features.learning.infra.repository.JdbcLearningDueSelectionRepository(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(observed));
        for(int limit:new int[]{1,4}) {
            observed.reset(); var page=repository.findMixed(anna.id(),domain(source),CLOCK.instant(),limit);
            assertThat(page).hasSize(limit); assertThat(observed.statements.get()).hasSize(1);
            assertThat(observed.rows.get()).isEqualTo(limit);
            assertThat(observed.statements.get().get(0)).doesNotContain("question_text","document","response_json");
        }
        System.out.println("CUSTOM_DUE_SQL statements=1; returned_rows=1/4 across four private plan parents; source metadata scan is not constant work");
        sql.update("UPDATE learning_schedule_cycle SET strategy_id='custom/v2'");
        sql.update("UPDATE learning_custom_state SET due_at='2090-01-01',completed=TRUE");
        body(perform(start(anna,source,UUID.randomUUID(),1,null,null)).andReturn(),422);
        assertThat(count("learning_session")).isZero();
    }

    @Test
    void explicitReenrollmentPreparesARetainedV125CycleWithoutReplacingItsIdentityOrHistory() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Retained V125 cycle");
        var retained=custom(anna,source,0,definition("FINITE",1,3));
        // Reachable V125 component fixture: #839 stored an immediate cycle without publication preparation.
        // No form/review exists. This fixture classifies that old storage shape, not a new writer failure.
        sql.update("DELETE FROM learning_prepared_publication WHERE revision_id=UUID_TO_BIN(?)",source.revisionId().toString());
        sql.update("DELETE FROM question_revision_contract_bindings WHERE question_revision_id=UUID_TO_BIN(?)",source.questions().get(0).toString());
        assertSame(cycle(anna,source,0),retained,"old cycle metadata stays readable without repair");
        assertThat(count("learning_prepared_publication")).isZero();
        var enrollment=command(UUID.randomUUID(),source); enrollment.remove("configuration");
        enrollment.put("questionRevisionId",source.questions().get(0).toString()).put("action","ENROLL").put("strategy","CUSTOM").put("confirmed",false);
        assertSame(send(anna,"/plan-cycles",enrollment,200).path("cycle"),retained,"explicit enrollment retains cycle identity");
        assertThat(count("learning_prepared_publication")).isOne(); assertThat(count("learning_custom_change")).isZero();
        assertThat(count("learning_schedule_cycle")).isOne();
        review(anna,source,null,"GOOD"); assertThat(count("learning_custom_change")).isOne();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"1","1.2"})
    void constantAndRoundedTailsRetainDurableEventsAndReplayAcrossNewServiceInstances(String coefficient) throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Retained tail");
        var definition=definition("INDEFINITE",7); definition.put("coefficient",new java.math.BigDecimal(coefficient));
        custom(anna,source,0,definition); review(anna,source,null,"GOOD");
        for(int i=0;i<3;i++) {
            CLOCK.set(CLOCK.instant().plusSeconds(60));
            var form=read(perform(start(anna,source,UUID.randomUUID(),1,"EARLY",source.questions().get(0))).andReturn(),200);
            UUID session=UUID.fromString(form.path("sessionId").asText()),command=UUID.randomUUID(); String item=form.path("nextItemId").asText();
            var first=body(perform(respond(anna,session,command,1,item,"GOOD",true)).andReturn(),200);
            var fresh=context.getAutowireCapableBeanFactory().createBean(uk.gegc.quizmaker.features.learning.application.impl.LearningCycleServiceImpl.class);
            var state=asPrincipal(anna,()->fresh.read(domain(source),source.questions().get(0)));
            assertThat(state.expected().sequence()).isEqualTo(i+2);
            assertSame(body(perform(respond(anna,session,command,1,item,"GOOD",true)).andReturn(),200),first,"tail response replay");
            long days=coefficient.equals("1")?7:new int[]{9,11,14}[i];
            assertThat(state.dueAt()).isEqualTo(CLOCK.instant().atZone(ZoneId.of("Europe/London")).toLocalDate()
                    .plusDays(days).atTime(9,0).atZone(ZoneId.of("Europe/London")).toInstant());
        }
        assertThat(count("learning_custom_change")).isEqualTo(4);
        assertThat(count("learning_custom_stage")).isEqualTo(coefficient.equals("1")?0:3);
    }

    @Test
    void customResponseReceiptSurvivesStrategySwitchWithoutAdvancingTheReplacement() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Historical custom response");
        custom(anna,source,0,definition("FINITE",1)); review(anna,source,null,"GOOD");
        var form=read(perform(start(anna,source,UUID.randomUUID(),1,"EARLY",source.questions().get(0))).andReturn(),200);
        UUID session=UUID.fromString(form.path("sessionId").asText()),id=UUID.randomUUID(); String item=form.path("nextItemId").asText();
        var accepted=body(perform(respond(anna,session,id,1,item,"GOOD",true)).andReturn(),200);
        var current=cycle(anna,source,0);
        var change=command(UUID.randomUUID(),source); change.remove("configuration");
        change.put("questionRevisionId",source.questions().get(0).toString()).put("action","SWITCH").put("strategy","FSRS").put("confirmed",true);
        change.set("expected",json.createObjectNode().put("cycleId",current.path("cycleId").asText())
                .put("cycleVersion",current.path("cycleVersion").asLong()).put("sequence",current.path("sequence").asLong()));
        var replacement=send(anna,"/plan-cycles",change,200).path("cycle");
        assertSame(body(perform(respond(anna,session,id,1,item,"GOOD",true)).andReturn(),200),accepted,"historical custom acknowledgement");
        assertSame(cycle(anna,source,0),replacement,"old receipt preserves replacement");
        assertThat(count("learning_review_event")).isZero(); assertThat(count("learning_custom_change")).isEqualTo(2);
    }

    @Test
    void outerRcOwnsTimezoneRollbackAndIncompatibleCallersAreRejected() throws Exception {
        var anna=persona("anna","QUIZ_UPDATE"); var source=publishedQuiz(anna,"Caller transaction");
        custom(anna,source,0,definition("FINITE",1,3)); review(anna,source,null,"GOOD");
        var preview=asPrincipal(anna,()->cycleService.preview(domain(source),source.questions().get(0),"America/New_York"));
        var before=cycle(anna,source,0); long credit=credit(anna);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        asPrincipal(anna,()->tx.execute(status -> {
            assertThat(sql.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("READ-COMMITTED");
            cycleService.confirm(UUID.randomUUID(),preview,true); assertThat(count("learning_custom_change")).isEqualTo(2);
            status.setRollbackOnly(); return null;
        }));
        assertSame(cycle(anna,source,0),before,"outer RC rollback restores due"); assertThat(credit(anna)).isEqualTo(credit);
        assertThat(count("learning_custom_change")).isOne();
        for(int isolation:new int[]{org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ,
                org.springframework.transaction.TransactionDefinition.ISOLATION_READ_UNCOMMITTED}) {
            tx.setIsolationLevel(isolation);
            org.assertj.core.api.Assertions.assertThatThrownBy(()->asPrincipal(anna,()->tx.execute(status->cycleService.read(domain(source),source.questions().get(0)))))
                    .isInstanceOf(uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.class);
        }
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setReadOnly(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(()->asPrincipal(anna,()->tx.execute(status->cycleService.read(domain(source),source.questions().get(0)))))
                .isInstanceOf(uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.class);
        assertThat(sql.queryForObject("SELECT @@transaction_read_only",Integer.class)).isZero();
    }

    private ObjectNode definition(String mode,int... days) {
        var result=json.createObjectNode().put("version",1).put("name","Private plan").put("mode",mode).put("coefficient",2)
                .put("timeZone","Europe/London").put("localTime","09:00");
        var intervals=result.putArray("intervals"); for(int day:days) intervals.addObject().put("value",day).put("unit","DAYS"); return result;
    }
    private JsonNode custom(Persona actor,Source source,int index,ObjectNode definition) throws Exception {
        var create=json.createObjectNode().put("commandId",UUID.randomUUID().toString()).put("expectedVersion",0); create.set("definition",definition);
        var plan=send(actor,"/plans",create,200).path("plan");
        var binding=command(UUID.randomUUID(),source); binding.remove("configuration"); binding.put("expectedVersion",0).put("planId",plan.path("planId").asText())
                .put("questionRevisionId",source.questions().get(index).toString()).put("planVersion",1);
        send(actor,"/plan-bindings",binding,200);
        var enroll=command(UUID.randomUUID(),source); enroll.remove("configuration");
        enroll.put("questionRevisionId",source.questions().get(index).toString()).put("action","ENROLL").put("strategy","CUSTOM").put("confirmed",false);
        return send(actor,"/plan-cycles",enroll,200).path("cycle");
    }
    private JsonNode cycle(Persona actor,Source source,int index) throws Exception {
        return read(perform(authorized(get(ROOT+"/plan-cycles/"+source.questions().get(index)).param("quizId",source.quizId().toString())
                .param("quizRevisionId",source.revisionId().toString()).param("revisionSchemaVersion",""+source.schemaVersion()),actor)).andReturn(),200);
    }
    private JsonNode review(Persona actor,Source source,String mode,String rating) throws Exception {
        var form=read(perform(start(actor,source,UUID.randomUUID(),1,mode,mode==null?null:source.questions().get(0))).andReturn(),200);
        return read(perform(respond(actor,UUID.fromString(form.path("sessionId").asText()),UUID.randomUUID(),1,
                form.path("items").get(0).path("sourceItemId").asText(),rating,true)).andReturn(),200);
    }
    private JsonNode send(Persona actor,String path,JsonNode body,int status) throws Exception {
        return read(perform(authorized(post(ROOT+path),actor).contentType("application/json").content(body.toString())).andReturn(),status);
    }
    private JsonNode read(MvcResult result,int status) throws Exception { return json.readTree(body(result,status)); }
    private String timezone(Source source,int index) { return "/plan-cycles/"+source.questions().get(index)+"/timezone"; }
    private uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source domain(Source source) {
        return new uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source(1,source.quizId(),source.revisionId(),source.schemaVersion());
    }
    private record Persona(UUID id, String name, String token) { }

    private record Source(UUID quizId, UUID revisionId, int schemaVersion, List<UUID> questions, List<UUID> authored) { }

    private Persona persona(String prefix, String... permissions) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        User user = tx.execute(status -> {
            User created = new User();
            created.setUsername(name);
            created.setEmail(name + "@example.invalid");
            created.setHashedPassword("synthetic-not-used-for-password-authentication");
            created.setActive(true);
            created.setEmailVerified(true);
            created.setRoles(new HashSet<>());
            return users.saveAndFlush(created);
        });
        for (String permission : permissions) grant(user.getId(), permission);
        // Issue from the stored row, as login does: Flyway keeps password_changed_at at whole-second precision.
        User stored = tx.execute(status -> users.findById(user.getId()).orElseThrow());
        return new Persona(user.getId(), name, authSessions.issueTokensForUser(stored).accessToken());
    }

    private void grant(UUID user, String permission) {
        tx.executeWithoutResult(status -> {
            String role = "conformance-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("""
                    INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id
                    FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?
                    """, role, permission);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?",
                    user.toString(), role);
        });
    }

    private Source publishedQuiz(Persona author, String... texts) {
        UUID quizId = tx.execute(status -> {
            Category category = new Category();
            category.setName("conformance-" + UUID.randomUUID());
            categories.saveAndFlush(category);
            Quiz quiz = new Quiz();
            quiz.setCreator(users.findById(author.id()).orElseThrow());
            quiz.setCategory(category);
            quiz.setTitle("Biology basics " + UUID.randomUUID().toString().substring(0, 8));
            quiz.setDescription("Synthetic Learning conformance source");
            quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT);
            quiz.setVisibility(Visibility.PRIVATE);
            quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false);
            quiz.setIsRepetitionEnabled(false);
            quiz.setIsDeleted(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
        List<UUID> authored = new ArrayList<>();
        for (String text : texts) {
            CreateQuestionRequest request = new CreateQuestionRequest();
            request.setType(QuestionType.TRUE_FALSE);
            request.setDifficulty(Difficulty.EASY);
            request.setQuestionText(text);
            request.setContent(QuestionContracts.example(QuestionType.TRUE_FALSE, QuestionContracts.View.AUTHOR));
            request.setQuizIds(List.of(quizId));
            authored.add(as(author, () -> authoring.createQuestion(author.name(), request)));
        }
        return publish(author, quizId, 0, authored);
    }

    /** Prepares the published source for the Learning commands through the #700 writer. */
    private Source publish(Persona author, UUID quizId, long expectedVersion, List<UUID> authored) {
        var result = as(author, () -> publications.publish(
                new QuizPublication.Command(1, quizId, UUID.randomUUID(), expectedVersion, authored)));
        var content = as(author, () -> publications.read(quizId, result.publication().revision().id())).content();
        return new Source(quizId, content.id(), content.schemaVersion(),
                content.items().stream().map(item -> item.question().id()).toList(), authored);
    }

    private void edit(Persona author, UUID question, String text) {
        var existing = questions.findById(question).orElseThrow();
        UpdateQuestionRequest request = new UpdateQuestionRequest();
        request.setQuestionText(text);
        request.setType(existing.getType());
        request.setDifficulty(existing.getDifficulty());
        request.setHint(existing.getHint());
        request.setExplanation(existing.getExplanation());
        try { request.setContent(json.readTree(existing.getContent())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new AssertionError(failure); }
        as(author, () -> authoring.updateQuestion(author.name(), question, request));
    }

    private <T> T as(Persona persona, Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(persona.name(), null, List.of()));
        try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }

    /** Attempt writers resolve an authenticated UserDetails principal, as the JWT filter supplies. */
    private <T> T asPrincipal(Persona persona, Supplier<T> work) {
        var principal = org.springframework.security.core.userdetails.User.withUsername(persona.name())
                .password("unused").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }

    private ObjectNode sourceRef(Source source) {
        ObjectNode ref = json.createObjectNode();
        ref.put("kind", "QUIZ_REVISION").put("quizId", source.quizId().toString())
                .put("quizRevisionId", source.revisionId().toString()).put("revisionSchemaVersion", source.schemaVersion());
        return ref;
    }

    private ObjectNode command(UUID commandId, Source source) {
        ObjectNode body = json.createObjectNode();
        body.put("commandId", commandId.toString());
        body.set("source", sourceRef(source));
        body.putObject("configuration").put("strategy", "fsrs/v1").put("version", 1);
        return body;
    }

    private MockHttpServletRequestBuilder enroll(Persona persona, Source source, UUID commandId, List<UUID> questionIds) {
        ObjectNode body = command(commandId, source);
        var ids = body.putArray("questionRevisionIds");
        questionIds.forEach(id -> ids.add(id.toString()));
        return authorized(post(ROOT + "/enrollments"), persona).contentType("application/json").content(body.toString());
    }

    private MockHttpServletRequestBuilder start(Persona persona, Source source, UUID commandId, int limit,
                                                String mode, UUID question) {
        ObjectNode body = command(commandId, source).put("limit", limit);
        if (mode != null) body.putObject("selection").put("mode", mode).put("questionRevisionId", question.toString());
        return authorized(post(ROOT + "/sessions"), persona).contentType("application/json").content(body.toString());
    }

    private MockHttpServletRequestBuilder resume(Persona persona, UUID session) {
        return authorized(get(ROOT + "/sessions/" + session), persona);
    }

    private MockHttpServletRequestBuilder respond(Persona persona, UUID session, UUID commandId, long version,
                                                  String item, String rating, boolean answer) {
        ObjectNode body = json.createObjectNode();
        body.put("commandId", commandId.toString()).put("expectedSessionVersion", version)
                .put("sourceItemId", item).put("rating", rating);
        body.putObject("response").put("type", "TRUE_FALSE").put("answer", answer);
        return authorized(post(ROOT + "/sessions/" + session + "/responses"), persona)
                .contentType("application/json").content(body.toString());
    }

    /**
     * MockMvc's security support keeps one context object per test thread, and the JWT filter writes into it.
     * Production starts every request with an empty context, so each simulated request does the same.
     */
    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();
        return mvc.perform(request);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, Persona persona) {
        return persona == null ? request : request.header("Authorization", "Bearer " + persona.token());
    }

    private static String body(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus()).as(describe(result)).isEqualTo(expectedStatus);
        return result.getResponse().getContentAsString();
    }

    /** Failure diagnostics name the operation, status and problem type only; IDs and bodies stay out. */
    private static String describe(MvcResult result) throws Exception {
        String route = UUID_TEXT.matcher(result.getRequest().getRequestURI()).replaceAll("{id}");
        var matcher = Pattern.compile("\"type\":\"([^\"]{1,120})\"").matcher(result.getResponse().getContentAsString());
        return result.getRequest().getMethod() + " " + route + " -> " + result.getResponse().getStatus()
                + (matcher.find() ? " " + matcher.group(1) : "");
    }

    /** Compares values without placing either one, or any response content, in the failure report. */
    private static void assertSame(Object actual, Object expected, String expectation) {
        assertThat(Objects.equals(actual, expected)).as(expectation).isTrue();
    }

    private static void assertSameElements(List<String> actual, List<String> expected, String expectation) {
        assertSame(actual.stream().sorted().toList(), expected.stream().sorted().toList(), expectation);
    }

    private static void assertExcludes(String actual, String expectation, String... forbidden) {
        for (String value : forbidden) assertThat(actual.contains(value)).as(expectation).isFalse();
    }

    /**
     * Holds the learner's account row exclusively on an independent connection until both requests wait on it,
     * so they are demonstrably in flight together before the application serializes them.
     */
    private List<MvcResult> whileLearnerIsLocked(Persona learner, Supplier<MockHttpServletRequestBuilder> first,
                                                 Supplier<MockHttpServletRequestBuilder> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection blocker = catalogConnection()) {
            blocker.setAutoCommit(false);
            List<Future<MvcResult>> requests;
            try (var lock = blocker.prepareStatement("SELECT user_id FROM users WHERE user_id=UUID_TO_BIN(?) FOR UPDATE")) {
                lock.setString(1, learner.id().toString());
                try (ResultSet rows = lock.executeQuery()) { assertThat(rows.next()).isTrue(); }
                requests = List.of(pool.submit(() -> perform(first.get()).andReturn()),
                        pool.submit(() -> perform(second.get()).andReturn()));
                awaitLockWaits(2, requests);
            } finally {
                blocker.rollback();
            }
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> request : requests) results.add(request.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitLockWaits(int expected, List<Future<MvcResult>> requests) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (true) {
            Integer waits = admin().queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
                    ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=?
                    """, Integer.class, CATALOG);
            if (waits != null && waits >= expected) return;
            for (Future<MvcResult> request : requests) {
                if (request.isDone()) throw new AssertionError("A request finished before the lock was released: "
                        + request.get().getResponse().getStatus());
            }
            if (System.nanoTime() > deadline) throw new AssertionError("Requests did not reach the learner lock");
            Thread.sleep(25);
        }
    }

    private static Connection catalogConnection() throws Exception {
        return new DriverManagerDataSource(serverUrl() + CATALOG + OPTIONS, ADMIN_USER, ADMIN_PASSWORD).getConnection();
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(serverUrl() + OPTIONS, ADMIN_USER, ADMIN_PASSWORD));
    }

    private int count(String table) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private long credit(Persona persona) {
        return sql.queryForObject("SELECT credit_units FROM learning_command_budget WHERE learner_id=UUID_TO_BIN(?)",
                Long.class, persona.id().toString());
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.now());

        void set(Instant value) { instant.set(value); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return instant.get(); }
    }
}
