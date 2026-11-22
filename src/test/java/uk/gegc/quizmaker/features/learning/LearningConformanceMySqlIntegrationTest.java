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
 * First single-quiz FSRS Learning conformance (#667).
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
@TestExecutionListeners(listeners = LearningConformanceMySqlIntegrationTest.SchemaCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "quizmaker.learning.sessions-enabled=true",
        "quizmaker.learning.due.max-batch-size=20",
        // These existing loggers print request URIs and quiz IDs; this suite's diagnostics stay ID-free.
        "logging.level.uk.gegc.quizmaker.features.auth.infra.security.JwtAuthenticationFilter=ERROR",
        "logging.level.uk.gegc.quizmaker.features.result.application.impl.QuizAnalyticsServiceImpl=WARN"
})
class LearningConformanceMySqlIntegrationTest {
    private static final String CATALOG = "learning_conformance_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PORT = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
    private static final String ADMIN_USER = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    private static final String ROOT = "/api/v1/learning";
    private static final long TOKEN = 60_000_000L;
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    /** Learning-owned rows in foreign-key deletion order; used for class-local cleanup only. */
    private static final List<String> LEARNING_TABLES = List.of("learning_enrollment_item", "learning_enrollment",
            "learning_prepared_publication", "learning_session_response", "learning_session_start", "learning_cycle_session",
            "learning_review_event", "learning_fsrs_state", "learning_schedule_receipt", "learning_schedule_cycle",
            "learning_schedule_slot", "learning_form_option", "learning_form_item", "learning_form",
            "learning_session_create_receipt", "learning_session", "learning_command_budget");
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
        tx.executeWithoutResult(status -> {
            sql.update("UPDATE learning_schedule_slot SET active_cycle_id=NULL,cycle_version=0");
            for (String table : LEARNING_TABLES) sql.update("DELETE FROM " + table);
        });
    }

    @Test
    void firstLearnerStartsFromEmptyStorageAndAdvancesEachScheduleOnceThroughTheRealApplication() throws Exception {
        Persona anna = persona("anna", "QUIZ_UPDATE");
        Source biology = publishedQuiz(anna, "Cells have a membrane", "Mitochondria store DNA", "Ribosomes build proteins");
        for (String table : LEARNING_TABLES) assertThat(count(table)).as(table).isZero();
        for (String table : List.of("spaced_repetition_entry", "repetition_review_log")) assertThat(count(table)).as(table).isZero();
        assertThat(bindings(biology)).isZero();
        Map<String, Long> outside = checksumsOutsideLearning();
        Map<String, Integer> outsideRows = rowsOutsideLearning();

        // LEARNING-EMPTY-START: two identical first enrollments are in flight together, then one commits.
        UUID enrollment = UUID.randomUUID();
        List<MvcResult> raced = whileLearnerIsLocked(anna,
                () -> enroll(anna, biology, enrollment, biology.questions()),
                () -> enroll(anna, biology, enrollment, biology.questions()));
        assertThat(raced).allSatisfy(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
        String enrolled = raced.get(0).getResponse().getContentAsString();
        assertSame(raced.get(1).getResponse().getContentAsString(), enrolled, "identical concurrent enrollment acknowledgements");
        JsonNode cycles = json.readTree(enrolled).path("items");
        assertThat(cycles.size()).isEqualTo(3);
        assertSame(textValues(cycles, "questionRevisionId"), strings(biology.questions()), "enrolled questions in requested order");
        assertThat(count("learning_enrollment")).isOne();
        assertThat(count("learning_schedule_cycle")).isEqualTo(3);
        assertThat(count("learning_schedule_slot")).isEqualTo(3);
        assertThat(bindings(biology)).isEqualTo(3);
        assertThat(credit(anna)).isEqualTo(19 * TOKEN);

        // A second device enrolling the same questions reuses the same active cycles.
        String again = body(perform(enroll(anna, biology, UUID.randomUUID(), biology.questions())).andReturn(), 200);
        assertSame(textValues(json.readTree(again).path("items"), "cycleId"), textValues(cycles, "cycleId"), "second enrollment reuses the active cycles");
        assertThat(count("learning_schedule_cycle")).isEqualTo(3);
        assertThat(count("learning_enrollment")).isEqualTo(2);

        UUID startCommand = UUID.randomUUID();
        String started = body(perform(start(anna, biology, startCommand, 20, null, null)).andReturn(), 200);
        JsonNode form = json.readTree(started);
        assertThat(form.path("outcome").asText()).isEqualTo("READY");
        assertThat(form.path("items").size()).isEqualTo(3);
        assertSameElements(textValues(form.path("items"), "questionRevisionId"), strings(biology.questions()), "due session holds every enrolled question");
        assertExcludes(started, "participant view exposes no answer key or learner identity", "\"correct\"", "\"explanation\"", "\"hint\"",
                anna.id().toString(), anna.name());
        UUID session = UUID.fromString(form.path("sessionId").asText());
        assertSame(body(perform(resume(anna, session)).andReturn(), 200), started, "saved start view");
        assertSame(body(perform(start(anna, biology, startCommand, 20, null, null)).andReturn(), 200), started, "saved start view");

        List<String> ratings = List.of("GOOD", "AGAIN", "EASY");
        List<Instant> expectedDue = List.of(now.plusSeconds(600), now.plusSeconds(60), now.plus(16, ChronoUnit.DAYS));
        String firstAcknowledgement = null;
        UUID firstCommand = UUID.randomUUID();
        for (int position = 0; position < 3; position++) {
            UUID command = position == 0 ? firstCommand : UUID.randomUUID();
            JsonNode item = form.path("items").get(position);
            String acknowledgement = body(perform(respond(anna, session, command, position + 1,
                    item.path("sourceItemId").asText(), ratings.get(position), true)).andReturn(), 200);
            JsonNode ack = json.readTree(acknowledgement);
            // The pinned manifest's first-review intervals, not a recalculation by this test.
            assertThat(Instant.parse(ack.path("nextDueAt").asText())).isEqualTo(expectedDue.get(position));
            assertThat(ack.path("sessionVersion").asLong()).isEqualTo(position + 2);
            assertThat(ack.path("completed").asBoolean()).isEqualTo(position == 2);
            if (position == 0) {
                firstAcknowledgement = acknowledgement;
                // The response was lost: the same command returns the original acknowledgement without a second review.
                assertSame(body(perform(respond(anna, session, command, 1, item.path("sourceItemId").asText(),
                        "GOOD", true)).andReturn(), 200), acknowledgement, "original acknowledgement");
                assertThat(count("learning_review_event")).isOne();
            }
        }
        assertSame(body(perform(respond(anna, session, firstCommand, 1,
                form.path("items").get(0).path("sourceItemId").asText(), "GOOD", true)).andReturn(), 200),
                firstAcknowledgement, "original acknowledgement after completion");
        JsonNode completed = json.readTree(body(perform(resume(anna, session)).andReturn(), 200));
        assertThat(completed.path("completed").asBoolean()).isTrue();
        assertThat(completed.path("reviewedItems").asInt()).isEqualTo(3);
        assertThat(completed.path("allowedActions").size()).isOne();
        assertThat(count("learning_review_event")).isEqualTo(3);
        assertThat(count("learning_session_response")).isEqualTo(3);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM learning_cycle_session WHERE released=1", Integer.class)).isEqualTo(3);
        assertThat(sql.queryForList("SELECT sequence FROM learning_fsrs_state", Long.class)).containsOnly(1L);
        // Two enrollments, one start and three answers. Reads and exact replays spend no credit.
        assertThat(credit(anna)).isEqualTo(14 * TOKEN);

        // Nothing is due at the same instant; this is an explicit empty session, not an error.
        JsonNode empty = json.readTree(body(perform(start(anna, biology, UUID.randomUUID(), 20, null, null)).andReturn(), 200));
        assertThat(empty.path("outcome").asText()).isEqualTo("EMPTY_COMPLETE");
        assertThat(empty.path("items").size()).isZero();
        assertThat(credit(anna)).isEqualTo(13 * TOKEN);

        // Sixty seconds later only the question rated AGAIN is due.
        String againQuestion = form.path("items").get(1).path("questionRevisionId").asText();
        String goodQuestion = form.path("items").get(0).path("questionRevisionId").asText();
        CLOCK.set(now.plusSeconds(60));
        JsonNode second = json.readTree(body(perform(start(anna, biology, UUID.randomUUID(), 20, null, null)).andReturn(), 200));
        assertSame(textValues(second.path("items"), "questionRevisionId"), List.of(againQuestion), "only the AGAIN question is due");
        JsonNode secondAck = json.readTree(body(perform(respond(anna, UUID.fromString(second.path("sessionId").asText()),
                UUID.randomUUID(), 1, second.path("items").get(0).path("sourceItemId").asText(), "GOOD", true)).andReturn(), 200));
        assertThat(Instant.parse(secondAck.path("nextDueAt").asText())).isAfter(now.plusSeconds(60));
        assertThat(sequence(anna, biology, againQuestion)).isEqualTo(2);

        // Other entry points: explicit early review advances its own cycle once; extra practice changes no schedule.
        JsonNode early = json.readTree(body(perform(start(anna, biology, UUID.randomUUID(), 1, "EARLY",
                UUID.fromString(goodQuestion))).andReturn(), 200));
        assertThat(early.path("mode").asText()).isEqualTo("EARLY");
        body(perform(respond(anna, UUID.fromString(early.path("sessionId").asText()), UUID.randomUUID(), 1,
                early.path("items").get(0).path("sourceItemId").asText(), "GOOD", true)).andReturn(), 200);
        assertThat(sequence(anna, biology, goodQuestion)).isEqualTo(2);
        String scheduleBeforePractice = fsrsState(anna, biology, goodQuestion);
        int eventsBeforePractice = count("learning_review_event");
        JsonNode practice = json.readTree(body(perform(start(anna, biology, UUID.randomUUID(), 1, "PRACTICE",
                UUID.fromString(goodQuestion))).andReturn(), 200));
        JsonNode practiceAck = json.readTree(body(perform(respond(anna, UUID.fromString(practice.path("sessionId").asText()),
                UUID.randomUUID(), 1, practice.path("items").get(0).path("sourceItemId").asText(), "AGAIN", false)).andReturn(), 200));
        assertThat(practiceAck.path("nextDueAt").isNull()).isTrue();
        assertThat(practiceAck.path("completed").asBoolean()).isTrue();
        assertSame(fsrsState(anna, biology, goodQuestion), scheduleBeforePractice, "extra practice keeps the FSRS state");
        assertThat(count("learning_review_event")).isEqualTo(eventsBeforePractice);
        assertThat(count("learning_schedule_cycle")).isEqualTo(3);
        // The minute refilled the burst; six more accepted commands followed.
        assertThat(credit(anna)).isEqualTo(14 * TOKEN);

        // LEARNING_BOUNDARY-01: outside Learning, only the documented first-enrollment contract bindings were added.
        Map<String, Integer> rowsAfter = rowsOutsideLearning();
        assertThat(rowsAfter.get("question_revision_contract_bindings") - outsideRows.get("question_revision_contract_bindings")).isEqualTo(3);
        assertThat(changed(outside, checksumsOutsideLearning())).containsExactly("question_revision_contract_bindings");
        assertBoundedLearningSignals();
    }

    @Test
    void deniedUnsupportedAndCorruptRequestsChangeNoLearningStateOrCredit() throws Exception {
        Persona anna = persona("anna", "QUIZ_UPDATE");
        Persona boris = persona("boris");
        Persona clara = persona("clara", "QUIZ_UPDATE");
        Source biology = publishedQuiz(anna, "Cells have a membrane");
        Source chemistry = publishedQuiz(clara, "Water boils at 100 degrees");
        tx.executeWithoutResult(status -> sql.update("UPDATE quizzes SET visibility='PUBLIC', status='PUBLISHED' WHERE quiz_id=UUID_TO_BIN(?)",
                chemistry.quizId().toString()));
        // Positive control through the same routes.
        body(perform(enroll(anna, biology, UUID.randomUUID(), biology.questions())).andReturn(), 200);
        UUID startCommand = UUID.randomUUID();
        String started = body(perform(start(anna, biology, startCommand, 20, null, null)).andReturn(), 200);
        JsonNode form = json.readTree(started);
        UUID session = UUID.fromString(form.path("sessionId").asText());
        String item = form.path("items").get(0).path("sourceItemId").asText();
        Map<String, Long> learning = learningChecksums();
        long annaCredit = credit(anna);

        // Missing or forged bearer tokens stop at the real security filter chain.
        Persona forged = new Persona(anna.id(), anna.name(), "not-a-valid-jwt");
        for (Persona caller : java.util.Arrays.asList(null, forged)) {
            for (var request : List.of(enroll(caller, biology, UUID.randomUUID(), biology.questions()),
                    start(caller, biology, UUID.randomUUID(), 1, null, null), resume(caller, session),
                    respond(caller, session, UUID.randomUUID(), 1, item, "GOOD", true))) {
                assertProblem(perform(request).andReturn(), 401, ErrorTypes.UNAUTHORIZED);
            }
        }

        // LEARNING_BOUNDARY-03: identity is server-resolved; visibility or a known ID is not Learning authority.
        for (var request : List.of(enroll(boris, biology, UUID.randomUUID(), biology.questions()),
                start(boris, biology, UUID.randomUUID(), 1, null, null), resume(boris, session),
                respond(boris, session, UUID.randomUUID(), 1, item, "GOOD", true),
                enroll(boris, chemistry, UUID.randomUUID(), chemistry.questions()),
                start(boris, chemistry, UUID.randomUUID(), 1, null, null),
                enroll(anna, chemistry, UUID.randomUUID(), chemistry.questions()),
                resume(anna, UUID.randomUUID()))) {
            assertProblem(perform(request).andReturn(), 404, ErrorTypes.RESOURCE_NOT_FOUND);
        }
        assertThat(budgetRows(boris)).isZero();

        // LEARNING_BOUNDARY-04: unavailable strategies, source kinds and modes fail explicitly before any write.
        ObjectNode custom = command(UUID.randomUUID(), biology).put("limit", 1);
        custom.putObject("configuration").put("strategy", "custom/v1").put("version", 1);
        ObjectNode futureVersion = command(UUID.randomUUID(), biology).put("limit", 1);
        futureVersion.putObject("configuration").put("strategy", "fsrs/v1").put("version", 2);
        ObjectNode nested = command(UUID.randomUUID(), biology).put("limit", 1);
        ((ObjectNode) nested.path("source")).put("kind", "NESTED_SET");
        ObjectNode card = command(UUID.randomUUID(), biology).put("limit", 1);
        card.putObject("selection").put("mode", "CARD").put("questionRevisionId", biology.questions().get(0).toString());
        assertProblem(perform(authorized(post(ROOT + "/sessions"), anna).contentType("application/json")
                .content(custom.toString())).andReturn(), 422, ErrorTypes.INVALID_ARGUMENT);
        assertProblem(perform(authorized(post(ROOT + "/sessions"), anna).contentType("application/json")
                .content(futureVersion.toString())).andReturn(), 422, ErrorTypes.INVALID_ARGUMENT);
        assertProblem(perform(authorized(post(ROOT + "/sessions"), anna).contentType("application/json")
                .content(nested.toString())).andReturn(), 400, null);
        assertProblem(perform(authorized(post(ROOT + "/sessions"), anna).contentType("application/json")
                .content(card.toString())).andReturn(), 400, null);
        assertThat(learningChecksums()).isEqualTo(learning);
        assertThat(credit(anna)).isEqualTo(annaCredit);

        // An invalid or stale item, or a response of another question family, changes nothing either.
        assertProblem(perform(respond(anna, session, UUID.randomUUID(), 1, UUID.randomUUID().toString(), "GOOD", true))
                .andReturn(), 409, ErrorTypes.OPTIMISTIC_LOCK_CONFLICT);
        assertProblem(perform(respond(anna, session, UUID.randomUUID(), 2, item, "GOOD", true)).andReturn(),
                409, ErrorTypes.OPTIMISTIC_LOCK_CONFLICT);
        ObjectNode wrongFamily = json.createObjectNode().put("commandId", UUID.randomUUID().toString())
                .put("expectedSessionVersion", 1).put("sourceItemId", item).put("rating", "GOOD");
        wrongFamily.putObject("response").put("type", "OPEN").put("answer", "membrane");
        assertProblem(perform(authorized(post(ROOT + "/sessions/" + session + "/responses"), anna)
                .contentType("application/json").content(wrongFamily.toString())).andReturn(), 400, ErrorTypes.INVALID_ARGUMENT);
        assertThat(learningChecksums()).isEqualTo(learning);
        assertThat(credit(anna)).isEqualTo(annaCredit);

        // FSRS-04: damaged retained schedule evidence is an explicit error on read and on review, with no fallback.
        String cycle = sql.queryForObject("SELECT BIN_TO_UUID(id) FROM learning_schedule_cycle WHERE learner_id=UUID_TO_BIN(?)",
                String.class, anna.id().toString());
        String original = sql.queryForObject("SELECT state FROM learning_fsrs_state WHERE cycle_id=UUID_TO_BIN(?)", String.class, cycle);
        assertThat(original.contains("0.2172")).as("saved state holds the approved first weight").isTrue();
        Map<String, String[]> damages = new LinkedHashMap<>();
        damages.put("unknown strategy", new String[] {"UPDATE learning_schedule_cycle SET strategy_id='fsrs/v99' WHERE id=UUID_TO_BIN(?)",
                "UPDATE learning_schedule_cycle SET strategy_id='fsrs/v1' WHERE id=UUID_TO_BIN(?)"});
        damages.put("changed FSRS weight", new String[] {"UPDATE learning_fsrs_state SET state=REPLACE(state,'0.2172','0.3172') WHERE cycle_id=UUID_TO_BIN(?)",
                "UPDATE learning_fsrs_state SET state=REPLACE(state,'0.3172','0.2172') WHERE cycle_id=UUID_TO_BIN(?)"});
        for (var damage : damages.entrySet()) {
            tx.executeWithoutResult(status -> assertThat(sql.update(damage.getValue()[0], cycle)).as(damage.getKey()).isOne());
            Map<String, Long> damaged = learningChecksums();
            assertThat(damaged).as(damage.getKey()).isNotEqualTo(learning);
            // The active view no longer offers RESPOND for an item that cannot be reviewed.
            assertProblem(perform(resume(anna, session)).andReturn(), 422, ErrorTypes.INVALID_ARGUMENT);
            assertProblem(perform(respond(anna, session, UUID.randomUUID(), 1, item, "GOOD", true)).andReturn(),
                    422, ErrorTypes.INVALID_ARGUMENT);
            // An exact start retry remains the original acknowledgement of the accepted command.
            assertSame(body(perform(start(anna, biology, startCommand, 20, null, null)).andReturn(), 200), started,
                    "original start acknowledgement");
            // Compared before the fixture is restored: no event, progress, credit or self-repair.
            assertThat(learningChecksums()).as(damage.getKey()).isEqualTo(damaged);
            tx.executeWithoutResult(status -> assertThat(sql.update(damage.getValue()[1], cycle)).as(damage.getKey()).isOne());
            assertThat(learningChecksums()).as(damage.getKey()).isEqualTo(learning);
            assertSame(body(perform(resume(anna, session)).andReturn(), 200), started, "saved start view");
        }
        assertSame(sql.queryForObject("SELECT state FROM learning_fsrs_state WHERE cycle_id=UUID_TO_BIN(?)", String.class, cycle),
                original, "restored FSRS state");
        assertThat(credit(anna)).isEqualTo(annaCredit);

        JsonNode accepted = json.readTree(body(perform(respond(anna, session, UUID.randomUUID(), 1, item, "GOOD", true)).andReturn(), 200));
        assertThat(Instant.parse(accepted.path("nextDueAt").asText())).isEqualTo(now.plusSeconds(600));
        assertThat(count("learning_review_event")).isOne();
        assertThat(credit(anna)).isEqualTo(annaCredit - TOKEN);
        assertThat(budgetRows(boris)).isZero();
        assertThat(budgetRows(clara)).isZero();

        // A completed session stays readable as history even if its retained schedule is later damaged.
        String history = body(perform(resume(anna, session)).andReturn(), 200);
        assertThat(json.readTree(history).path("completed").asBoolean()).isTrue();
        tx.executeWithoutResult(status -> sql.update(damages.get("unknown strategy")[0], cycle));
        try {
            assertSame(body(perform(resume(anna, session)).andReturn(), 200), history, "completed session history");
        } finally {
            tx.executeWithoutResult(status -> sql.update(damages.get("unknown strategy")[1], cycle));
        }
    }

    @Test
    void resumeOffersTheFinalAnswerOnlyWhenEveryCapturedScheduleCanBeReleased() throws Exception {
        Persona anna = persona("anna", "QUIZ_UPDATE");
        Map<String, String[]> damages = new LinkedHashMap<>();
        damages.put("unknown strategy", new String[] {"UPDATE learning_schedule_cycle SET strategy_id='fsrs/v99' WHERE id=UUID_TO_BIN(?)",
                "UPDATE learning_schedule_cycle SET strategy_id='fsrs/v1' WHERE id=UUID_TO_BIN(?)"});
        damages.put("changed FSRS weight", new String[] {"UPDATE learning_fsrs_state SET state=REPLACE(state,'0.2172','0.3172') WHERE cycle_id=UUID_TO_BIN(?)",
                "UPDATE learning_fsrs_state SET state=REPLACE(state,'0.3172','0.2172') WHERE cycle_id=UUID_TO_BIN(?)"});
        int events = 0;
        for (var damage : damages.entrySet()) {
            Source biology = publishedQuiz(anna, "Cells have a membrane", "Mitochondria store DNA", "Ribosomes build proteins");
            body(perform(enroll(anna, biology, UUID.randomUUID(), biology.questions())).andReturn(), 200);
            JsonNode form = json.readTree(body(perform(start(anna, biology, UUID.randomUUID(), 20, null, null)).andReturn(), 200));
            JsonNode items = form.path("items");
            UUID session = UUID.fromString(form.path("sessionId").asText());
            body(perform(respond(anna, session, UUID.randomUUID(), 1, items.get(0).path("sourceItemId").asText(), "GOOD", true))
                    .andReturn(), 200);
            events++;
            // The schedule of the question Anna already answered is damaged.
            String answered = activeCycle(anna, biology, items.get(0).path("questionRevisionId").asText());
            tx.executeWithoutResult(status -> assertThat(sql.update(damage.getValue()[0], answered)).as(damage.getKey()).isOne());

            // A middle answer reads only its own schedule, so resume still offers it and it is accepted.
            JsonNode middle = json.readTree(body(perform(resume(anna, session)).andReturn(), 200));
            assertThat(middle.path("allowedActions").size()).as(damage.getKey()).isEqualTo(2);
            body(perform(respond(anna, session, UUID.randomUUID(), 2, items.get(1).path("sourceItemId").asText(), "GOOD", true))
                    .andReturn(), 200);
            events++;

            // The final answer releases every capture, so both routes refuse instead of offering RESPOND.
            Map<String, Long> damaged = learningChecksums();
            UUID finalCommand = UUID.randomUUID();
            String finalItem = items.get(2).path("sourceItemId").asText();
            assertProblem(perform(resume(anna, session)).andReturn(), 422, ErrorTypes.INVALID_ARGUMENT);
            assertProblem(perform(respond(anna, session, finalCommand, 3, finalItem, "GOOD", true)).andReturn(),
                    422, ErrorTypes.INVALID_ARGUMENT);
            assertThat(learningChecksums()).as(damage.getKey()).isEqualTo(damaged);

            // After restoration the refused command itself is retried: its key stayed unused by the rolled-back attempt.
            tx.executeWithoutResult(status -> assertThat(sql.update(damage.getValue()[1], answered)).as(damage.getKey()).isOne());
            JsonNode last = json.readTree(body(perform(resume(anna, session)).andReturn(), 200));
            assertSame(last.path("nextItemId").asText(), finalItem, "final item is offered again");
            String finished = body(perform(respond(anna, session, finalCommand, 3, finalItem, "GOOD", true)).andReturn(), 200);
            assertThat(json.readTree(finished).path("completed").asBoolean()).as(damage.getKey()).isTrue();
            events++;
            assertSame(body(perform(respond(anna, session, finalCommand, 3, finalItem, "GOOD", true)).andReturn(), 200),
                    finished, "exact retry returns the original final acknowledgement");
            assertThat(count("learning_review_event")).as(damage.getKey()).isEqualTo(events);
        }
    }

    @Test
    void exhaustedCommandBudgetRejectsNewCommandsUntilRefillWhileExactReplayStillWorks() throws Exception {
        Persona dana = persona("dana", "QUIZ_UPDATE");
        Source botany = publishedQuiz(dana, "Plants photosynthesise");
        UUID first = UUID.randomUUID();
        String firstStart = null;
        for (int command = 0; command < 20; command++) {
            String started = body(perform(start(dana, botany, command == 0 ? first : UUID.randomUUID(), 1, null, null))
                    .andReturn(), 200);
            assertThat(json.readTree(started).path("outcome").asText()).isEqualTo("EMPTY_COMPLETE");
            if (command == 0) firstStart = started;
        }
        assertThat(credit(dana)).isZero();
        Map<String, Long> learning = learningChecksums();
        for (var request : List.of(start(dana, botany, UUID.randomUUID(), 1, null, null),
                enroll(dana, botany, UUID.randomUUID(), botany.questions()))) {
            MvcResult denied = perform(request).andReturn();
            assertProblem(denied, 429, ErrorTypes.RATE_LIMIT_EXCEEDED);
            assertThat(denied.getResponse().getHeader("Retry-After")).isEqualTo("1");
        }
        assertThat(learningChecksums()).isEqualTo(learning);
        assertThat(count("learning_session")).isEqualTo(20);
        // An exact retry of an accepted command is not a new command.
        assertSame(body(perform(start(dana, botany, first, 1, null, null)).andReturn(), 200), firstStart, "original start acknowledgement");
        assertThat(credit(dana)).isZero();

        CLOCK.set(now.plusSeconds(1));
        body(perform(enroll(dana, botany, UUID.randomUUID(), botany.questions())).andReturn(), 200);
        assertThat(count("learning_schedule_cycle")).isOne();
        assertThat(credit(dana)).isZero();
    }

    @Test
    void ordinaryTrainingFormalResultsAllowanceAndReportsStayUnchangedByLearning() throws Exception {
        Persona anna = persona("anna", "QUIZ_UPDATE", "ATTEMPT_READ", "ATTEMPT_CREATE");
        Source biology = publishedQuiz(anna, "Cells have a membrane", "Mitochondria store DNA");
        body(perform(enroll(anna, biology, UUID.randomUUID(), biology.questions())).andReturn(), 200);
        JsonNode form = json.readTree(body(perform(start(anna, biology, UUID.randomUUID(), 20, null, null)).andReturn(), 200));
        UUID session = UUID.fromString(form.path("sessionId").asText());
        body(perform(respond(anna, session, UUID.randomUUID(), 1, form.path("items").get(0).path("sourceItemId").asText(),
                "GOOD", true)).andReturn(), 200);
        String openSession = body(perform(resume(anna, session)).andReturn(), 200);
        Map<String, Long> learning = learningChecksums();

        // LEARNING_BOUNDARY-01: the existing v1 training routes keep their envelopes on the same quiz.
        String started = body(perform(authorized(post("/api/v1/attempts/quizzes/{quiz}", biology.quizId()), anna)
                .contentType("application/json").content("{\"mode\":\"ALL_AT_ONCE\"}")).andReturn(), 201);
        JsonNode attempt = json.readTree(started);
        assertThat(attempt.size()).isEqualTo(6);
        assertThat(attempt.path("totalQuestions").asInt()).isEqualTo(2);
        String legacyAttempt = attempt.path("attemptId").asText();
        String path = "/api/v1/attempts/" + legacyAttempt;
        ObjectNode answer = json.createObjectNode().put("questionId", biology.authored().get(0).toString());
        answer.set("response", QuestionContracts.example(QuestionType.TRUE_FALSE, QuestionContracts.View.RESPONSE));
        JsonNode feedback = json.readTree(body(perform(authorized(post(path + "/answers"), anna)
                .contentType("application/json").content(answer.toString())).andReturn(), 200));
        // Without the deprecated include flags the server keeps its existing protected feedback envelope.
        assertThat(feedback.path("score").asDouble()).isEqualTo(1.0);
        assertThat(feedback.path("correctAnswerAvailability").asText()).isEqualTo("NOT_INCLUDED");
        JsonNode result = json.readTree(body(perform(authorized(post(path + "/complete"), anna)).andReturn(), 200));
        assertThat(result.path("correctCount").asInt()).isEqualTo(1);
        assertThat(result.path("totalScore").asDouble()).isEqualTo(1.0);
        awaitAnalyticsSnapshot(biology);
        JsonNode due = json.readTree(body(perform(authorized(get("/api/v1/repetition/due"), anna)).andReturn(), 200));
        assertThat(due.path("content").size()).isZero();

        // A formal policy result through the real v2 start, response and submission writers, with a finite allowance.
        UUID formal = formalAttempt(anna, biology, 3);
        Map<String, Object> earned = sql.queryForMap("""
                SELECT earned_points, possible_points FROM attempt_automatic_result WHERE attempt_id=UUID_TO_BIN(?)
                """, formal.toString());
        assertThat(((Number) earned.get("earned_points")).longValue()).isPositive();
        Map<String, Object> allowance = sql.queryForMap("""
                SELECT maximum_attempts, JSON_LENGTH(start_ids) AS used FROM attempt_allowance_scope
                WHERE user_id=UUID_TO_BIN(?) AND revision_id=UUID_TO_BIN(?)
                """, anna.id().toString(), biology.revisionId().toString());
        assertThat(((Number) allowance.get("maximum_attempts")).longValue()).isEqualTo(3);
        assertThat(((Number) allowance.get("used")).intValue()).isOne();
        assertThat(sql.queryForObject("""
                SELECT COUNT(*) FROM attempt_result_release r JOIN attempt_automatic_result a ON a.id=r.result_id
                WHERE a.attempt_id=UUID_TO_BIN(?)
                """, Integer.class, formal.toString())).isOne();

        // Ordinary practice and formal results did not touch Learning, and Learning created no repetition entries.
        assertThat(learningChecksums()).isEqualTo(learning);
        assertSame(body(perform(resume(anna, session)).andReturn(), 200), openSession, "unchanged open session");
        for (String table : List.of("spaced_repetition_entry", "repetition_review_log")) assertThat(count(table)).as(table).isZero();

        // Earned results and reports read before the final Learning answer...
        List<MockHttpServletRequestBuilder> reports = List.of(
                authorized(get(path + "/review"), anna),
                authorized(get("/api/v1/quizzes/{quiz}/results", biology.quizId()), anna),
                authorized(get("/api/v1/quizzes/{quiz}/attempts/{attempt}/stats", biology.quizId(), legacyAttempt), anna));
        List<String> reportBodies = new ArrayList<>();
        for (var report : reports) reportBodies.add(body(perform(report).andReturn(), 200));
        assertThat(json.readTree(reportBodies.get(1)).path("attemptsCount").asInt()).isOne();
        Map<String, Long> outside = checksumsOutsideLearning();

        // ...remain identical after the answer that completes Learning and after its exact replay.
        UUID last = UUID.randomUUID();
        String finished = body(perform(respond(anna, session, last, 2,
                form.path("items").get(1).path("sourceItemId").asText(), "GOOD", true)).andReturn(), 200);
        assertThat(json.readTree(finished).path("completed").asBoolean()).isTrue();
        assertSame(body(perform(respond(anna, session, last, 2,
                form.path("items").get(1).path("sourceItemId").asText(), "GOOD", true)).andReturn(), 200), finished, "original final acknowledgement");
        assertThat(changed(outside, checksumsOutsideLearning())).isEmpty();
        for (int index = 0; index < reports.size(); index++) {
            assertSame(body(perform(reports.get(index)).andReturn(), 200), reportBodies.get(index), "unchanged earned result or report");
        }
        assertThat(sql.queryForMap("SELECT earned_points, possible_points FROM attempt_automatic_result WHERE attempt_id=UUID_TO_BIN(?)",
                formal.toString())).isEqualTo(earned);
        assertThat(sql.queryForMap("""
                SELECT maximum_attempts, JSON_LENGTH(start_ids) AS used FROM attempt_allowance_scope
                WHERE user_id=UUID_TO_BIN(?) AND revision_id=UUID_TO_BIN(?)
                """, anna.id().toString(), biology.revisionId().toString())).isEqualTo(allowance);
    }

    /** Real v2 writers: finite owner-configured allowance, every item accepted, submission, result and release. */
    private UUID formalAttempt(Persona author, Source source, long maximumAttempts) {
        return asPrincipal(author, () -> {
            var content = new ResolvedAttemptPolicy.ContentPolicy(source.revisionId(), source.schemaVersion(),
                    ResolvedAttemptPolicy.FormMode.PRECOMPUTED, ResolvedAttemptPolicy.SectionMode.IMPLICIT_FLAT,
                    ResolvedAttemptPolicy.CaseMode.NOT_CONFIGURED);
            var start = attemptStarts.start(new AttemptStartService.Command(1, source.quizId(), content, 1,
                    CommandReceipt.Key.from("formal-" + UUID.randomUUID()), BuiltInPracticePreset.REFERENCE,
                    LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", ResolvedAttemptPolicy.AnswerOrder.AUTHORED,
                    false, new ResolvedAttemptPolicy.NavigationPolicy(ResolvedAttemptPolicy.NavigationMode.FREE),
                    ResolvedAttemptPolicy.DeliveryMode.ALL_AT_ONCE, QuestionSelection.Configuration.all(),
                    new ResolvedAttemptPolicy.AttemptLimitPolicy(ResolvedAttemptPolicy.AttemptLimitMode.FIXED_COUNT,
                            maximumAttempts, Duration.ZERO)));
            var items = attemptDelivery.read(start.attemptId()).form().items();
            long version = start.stateVersion();
            for (var item : items) {
                version = attemptResponses.accept(new ResponseAcceptanceService.Command(start.attemptId(), item.id(), version,
                        CommandReceipt.Key.from("formal-answer-" + UUID.randomUUID()), item.type(),
                        json.createObjectNode().put("answer", true))).stateVersion();
            }
            var submitted = attemptSubmissions.submit(new AttemptSubmissionService.Command(start.attemptId(), version, true,
                    CommandReceipt.Key.from("formal-submit-" + UUID.randomUUID())));
            automaticResults.finalizeSubmission(submitted.submissionId());
            resultReleases.release(submitted.submissionId());
            return start.attemptId();
        });
    }

    /** The legacy analytics snapshot is refreshed asynchronously after the completion commit. */
    private void awaitAnalyticsSnapshot(Source source) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (sql.queryForObject("SELECT COUNT(*) FROM quiz_analytics_snapshot WHERE quiz_id=UUID_TO_BIN(?)",
                Integer.class, source.quizId().toString()) == 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("Legacy analytics snapshot was not refreshed");
            Thread.sleep(25);
        }
    }

    @Test
    void openSessionStaysOnRevisionAWhileRevisionBStartsSeparateStateWithoutTransfer() throws Exception {
        Persona anna = persona("anna", "QUIZ_UPDATE");
        Source revisionA = publishedQuiz(anna, "Cells have a membrane", "Mitochondria store DNA");
        body(perform(enroll(anna, revisionA, UUID.randomUUID(), revisionA.questions())).andReturn(), 200);
        String started = body(perform(start(anna, revisionA, UUID.randomUUID(), 20, null, null)).andReturn(), 200);
        JsonNode form = json.readTree(started);
        UUID session = UUID.fromString(form.path("sessionId").asText());
        body(perform(respond(anna, session, UUID.randomUUID(), 1, form.path("items").get(0).path("sourceItemId").asText(),
                "GOOD", true)).andReturn(), 200);

        // LEARNING_BOUNDARY-02: the author edits the still-unanswered question and publishes revision B.
        JsonNode pending = form.path("items").get(1);
        int index = revisionA.questions().indexOf(UUID.fromString(pending.path("questionRevisionId").asText()));
        String originalText = pending.path("questionText").asText();
        edit(anna, revisionA.authored().get(index), "Edited after publication A");
        Source revisionB = publish(anna, revisionA.quizId(), 1, revisionA.authored());
        assertThat(revisionB.revisionId().equals(revisionA.revisionId())).as("publication B is a new revision").isFalse();
        assertThat(revisionB.questions().get(index).equals(revisionA.questions().get(index))).as("edited question gets a new revision").isFalse();

        JsonNode resumed = json.readTree(body(perform(resume(anna, session)).andReturn(), 200));
        assertSame(resumed.path("items"), form.path("items"), "open session keeps the revision A items");
        assertSame(resumed.path("items").get(1).path("questionText").asText(), originalText, "open session keeps the revision A text");
        JsonNode completed = json.readTree(body(perform(respond(anna, session, UUID.randomUUID(), 2,
                pending.path("sourceItemId").asText(), "GOOD", true)).andReturn(), 200));
        assertThat(completed.path("completed").asBoolean()).isTrue();
        for (UUID question : revisionA.questions()) assertThat(sequence(anna, revisionA, question.toString())).isOne();

        // Learning B is opt-in and starts fresh, even for content identical to A.
        JsonNode enrolledB = json.readTree(body(perform(enroll(anna, revisionB, UUID.randomUUID(), revisionB.questions()))
                .andReturn(), 200));
        List<String> cyclesA = sql.queryForList("SELECT BIN_TO_UUID(id) FROM learning_schedule_cycle WHERE quiz_revision_id=UUID_TO_BIN(?)",
                String.class, revisionA.revisionId().toString());
        assertThat(cyclesA.size()).isEqualTo(2);
        assertThat(java.util.Collections.disjoint(cyclesA, textValues(enrolledB.path("items"), "cycleId")))
                .as("revision B starts new cycles").isTrue();
        for (UUID question : revisionB.questions()) assertThat(sequence(anna, revisionB, question.toString())).isZero();
        for (UUID question : revisionA.questions()) assertThat(sequence(anna, revisionA, question.toString())).isOne();
        JsonNode formB = json.readTree(body(perform(start(anna, revisionB, UUID.randomUUID(), 20, null, null)).andReturn(), 200));
        assertSameElements(textValues(formB.path("items"), "questionRevisionId"), strings(revisionB.questions()), "revision B session uses B questions");
        assertThat(textValues(formB.path("items"), "questionText").contains("Edited after publication A")).as("revision B shows the edited text").isTrue();
        assertThat(count("learning_review_event")).isEqualTo(2);
    }

    private void assertBoundedLearningSignals() {
        var timers = metrics.find("quizmaker.learning.study").timers();
        assertThat(timers).isNotEmpty();
        Set<String> operations = new HashSet<>();
        for (var timer : timers) {
            var tags = timer.getId().getTags();
            assertThat(tags).extracting(io.micrometer.core.instrument.Tag::getKey)
                    .containsExactlyInAnyOrder("operation", "outcome", "strategy", "version");
            for (var tag : tags) {
                assertThat(UUID_TEXT.matcher(tag.getValue()).find() || tag.getValue().contains("@") || tag.getValue().length() >= 40)
                        .as("bounded Learning metric tag " + tag.getKey()).isFalse();
            }
            assertThat(timer.getId().getTag("strategy")).isEqualTo("fsrs");
            operations.add(timer.getId().getTag("operation"));
        }
        assertThat(operations).contains("enroll", "start", "resume", "respond");
        List<String> routes = metrics.find("http.server.requests").meters().stream()
                .map(meter -> meter.getId().getTag("uri")).filter(Objects::nonNull).filter(uri -> uri.startsWith(ROOT)).toList();
        assertThat(routes.size()).isPositive();
        for (String uri : routes) assertThat(UUID_TEXT.matcher(uri).find()).as("HTTP metric records a route template").isFalse();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Fixtures and HTTP helpers
    // ---------------------------------------------------------------------------------------------------------

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

    private int bindings(Source source) {
        return sql.queryForObject("SELECT COUNT(*) FROM question_revision_contract_bindings WHERE BIN_TO_UUID(question_revision_id) IN ("
                + String.join(",", java.util.Collections.nCopies(source.questions().size(), "?")) + ")",
                Integer.class, strings(source.questions()).toArray());
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

    private String activeCycle(Persona learner, Source source, String questionRevision) {
        return sql.queryForObject("""
                SELECT BIN_TO_UUID(active_cycle_id) FROM learning_schedule_slot
                WHERE learner_id=UUID_TO_BIN(?) AND quiz_revision_id=UUID_TO_BIN(?) AND question_revision_id=UUID_TO_BIN(?)
                """, String.class, learner.id().toString(), source.revisionId().toString(), questionRevision);
    }

    private long sequence(Persona learner, Source source, String questionRevision) {
        return sql.queryForObject("""
                SELECT f.sequence FROM learning_fsrs_state f JOIN learning_schedule_slot s ON s.active_cycle_id=f.cycle_id
                WHERE s.learner_id=UUID_TO_BIN(?) AND s.quiz_revision_id=UUID_TO_BIN(?) AND s.question_revision_id=UUID_TO_BIN(?)
                """, Long.class, learner.id().toString(), source.revisionId().toString(), questionRevision);
    }

    private String fsrsState(Persona learner, Source source, String questionRevision) {
        return sql.queryForObject("""
                SELECT f.state FROM learning_fsrs_state f JOIN learning_schedule_slot s ON s.active_cycle_id=f.cycle_id
                WHERE s.learner_id=UUID_TO_BIN(?) AND s.quiz_revision_id=UUID_TO_BIN(?) AND s.question_revision_id=UUID_TO_BIN(?)
                """, String.class, learner.id().toString(), source.revisionId().toString(), questionRevision);
    }

    private int budgetRows(Persona persona) {
        return sql.queryForObject("SELECT COUNT(*) FROM learning_command_budget WHERE learner_id=UUID_TO_BIN(?)",
                Integer.class, persona.id().toString());
    }

    private static void assertProblem(MvcResult result, int status, java.net.URI type) throws Exception {
        String body = result.getResponse().getContentAsString();
        String operation = describe(result);
        assertThat(result.getResponse().getStatus()).as(operation).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).as(operation).startsWith("application/problem+json");
        if (type != null) assertThat(body.contains("\"type\":\"" + type + "\"")).as(operation + " expected " + type).isTrue();
        for (String leaked : List.of("Exception", "SQL", "fsrs/v99", "0.3172", "at uk.gegc")) {
            assertThat(body.contains(leaked)).as(operation + " exposes internal detail " + leaked).isFalse();
        }
    }

    private List<String> tables(boolean learning) {
        return sql.queryForList("""
                SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'
                AND (TABLE_NAME LIKE 'learning\\_%') = ? ORDER BY TABLE_NAME
                """, String.class, learning);
    }

    private Map<String, Long> checksums(boolean learning) {
        Map<String, Long> result = new TreeMap<>();
        for (String table : tables(learning)) {
            Map<String, Object> row = sql.queryForMap("CHECKSUM TABLE `" + table + "`");
            Object checksum = row.get("Checksum");
            result.put(table, checksum == null ? -1L : ((Number) checksum).longValue());
        }
        return result;
    }

    private Map<String, Long> checksumsOutsideLearning() { return checksums(false); }

    private Map<String, Long> learningChecksums() { return checksums(true); }

    private Map<String, Integer> rowsOutsideLearning() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String table : tables(false)) result.put(table, count(table));
        return result;
    }

    private static List<String> changed(Map<String, Long> before, Map<String, Long> after) {
        assertThat(after.keySet()).isEqualTo(before.keySet());
        return before.keySet().stream().filter(table -> !before.get(table).equals(after.get(table))).toList();
    }

    private static List<String> textValues(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    private static List<String> strings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    /** A controllable clock shared by application time and D33 admission accounting. */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.now());

        void set(Instant value) { instant.set(value); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override public Clock withZone(ZoneId zone) { return this; }

        @Override public Instant instant() { return instant.get(); }
    }
}
