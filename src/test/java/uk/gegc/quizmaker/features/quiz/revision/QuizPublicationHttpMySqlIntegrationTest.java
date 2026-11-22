package uk.gegc.quizmaker.features.quiz.revision;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.auth.application.AuthSessionService;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.Command;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Ordinary authoring and publication through the real MVC, JWT, Flyway and MySQL boundaries. */
@Tag("db-serial")
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestExecutionListeners(listeners = QuizPublicationHttpMySqlIntegrationTest.SchemaCleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "stripe.price-small=", "stripe.price-medium=", "stripe.price-large=",
        "logging.level.uk.gegc.quizmaker.features.auth.infra.security.JwtAuthenticationFilter=ERROR",
        "logging.level.uk.gegc.quizmaker.features.result.application.impl.QuizAnalyticsServiceImpl=WARN"})
class QuizPublicationHttpMySqlIntegrationTest {
    private static final String API = "/api/v1/quizzes";
    private static final String PUBLICATIONS = API + "/{quizId}/publications";
    private static final String CATALOG = "publication_http_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PORT = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
    private static final String ADMIN_USER = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserRepository users;
    @Autowired AuthSessionService auth;
    @Autowired QuizPublicationRepository publications;
    @Autowired PublicationFaults faults;
    private TransactionTemplate tx;

    @DynamicPropertySource
    static void ownCatalog(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> server() + CATALOG + OPTIONS + "&createDatabaseIfNotExist=true");
        properties.add("spring.datasource.username", () -> ADMIN_USER);
        properties.add("spring.datasource.password", () -> ADMIN_PASSWORD);
    }

    public static class SchemaCleanup extends AbstractTestExecutionListener {
        @Override public int getOrder() { return 1000; }

        @Override
        public void afterTestClass(TestContext ignored) {
            admin().execute("DROP DATABASE IF EXISTS " + CATALOG);
        }
    }

    @BeforeEach
    void transactionTemplate() {
        tx = new TransactionTemplate(transactions);
    }

    @AfterEach
    void clearRequestAndFaultState() {
        SecurityContextHolder.clearContext();
        faults.mode = null;
        faults.contenders = null;
        faults.reached = false;
        faults.gatedThreads.clear();
    }

    @Test
    void publicationRequiresOneCompleteJsonDocumentBeforeWriting() throws Exception {
        var author = author("QUIZ_UPDATE");
        var draft = draft(author, 1);
        var command = publicationCommand(UUID.randomUUID(), 0, draft.questions()).toString();

        for (String suffix : List.of(" {}", " {")) {
            problem(publishJson(author, draft.id(), command + suffix), 400, "INVALID_INPUT");
            assertThat(state(draft.id())).isEqualTo(new State(0, null, 0, 0));
            assertThat(fresh(() -> count("quiz_revisions", draft.id()))).isZero();
        }
        expect(request(author, post(PUBLICATIONS, draft.id()).contentType("text/plain").content(command), null), 415);
        assertThat(state(draft.id())).isEqualTo(new State(0, null, 0, 0));

        var ack = body(publishJson(author, draft.id(), command + " \n\t"), 200);
        assertAcknowledgement(ack, draft.id(), 1, draft.questions());
        assertThat(state(draft.id())).isEqualTo(new State(1, uuid(ack, "quizRevisionId"), 1, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEBUG", "TRACE"})
    void springMvcDiagnosticsDoNotLogPublicationRequestOrAcknowledgementValues(String level) throws Exception {
        var author = author("QUIZ_UPDATE");
        var draft = draft(author, 2);
        var key = UUID.randomUUID();
        var command = publicationCommand(key, 0, draft.questions());
        String invalidUuid = "PRIVATE_PUBLICATION_UUID";
        long invalidVersion = -543219876;
        var loggers = List.of((Logger) LoggerFactory.getLogger("org.springframework.web.servlet.mvc.method.annotation"),
                (Logger) LoggerFactory.getLogger("org.springframework.web.method"));
        var previousLevels = loggers.stream().map(Logger::getLevel).toList();
        var previousAdditivity = loggers.stream().map(Logger::isAdditive).toList();
        Thread requestThread = Thread.currentThread();
        var captured = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                if (Thread.currentThread() == requestThread) {
                    event.prepareForDeferredProcessing();
                    super.append(event);
                }
            }
        };
        captured.setContext(loggers.get(0).getLoggerContext());
        captured.start();
        loggers.forEach(logger -> {
            logger.addAppender(captured);
            logger.setAdditive(false);
            logger.setLevel(Level.toLevel(level));
        });
        try {
            var ack = body(publishJson(author, draft.id(), command.toString()), 200);
            assertAcknowledgement(ack, draft.id(), 1, draft.questions());
            var malformed = command.deepCopy().put("commandId", invalidUuid);
            var invalid = command.deepCopy().put("expectedPublicationVersion", invalidVersion);
            problem(publishJson(author, draft.id(), malformed.toString()), 400, "INVALID_INPUT");
            problem(publishJson(author, draft.id(), invalid.toString()), 400, "INVALID_INPUT");
            assertThat(state(draft.id())).isEqualTo(new State(1, uuid(ack, "quizRevisionId"), 1, 1));

            var messages = captured.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages.stream().anyMatch(message -> message.contains("PublishQuizRevisionRequest[redacted]")))
                    .as("MVC recorded a diagnostic for the valid command").isTrue();
            assertThat(messages.stream().filter(message -> message.contains("Could not resolve parameter")).count())
                    .as("MVC recorded both argument failures").isEqualTo(2);
            var diagnostics = captured.list.stream().map(event -> event.getFormattedMessage() +
                    (event.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(event.getThrowableProxy()))).toList();
            var forbidden = new ArrayList<>(List.of(key.toString(), invalidUuid, Long.toString(invalidVersion),
                    "commandId=", "questionOrder=", "expectedPublicationVersion="));
            draft.questions().forEach(id -> forbidden.add(id.toString()));
            assertThat(diagnostics.stream().noneMatch(message -> forbidden.stream().anyMatch(message::contains)))
                    .as("MVC diagnostics do not expose publication request fields").isTrue();

            var publishedIds = new ArrayList<String>();
            ack.required("questionRevisionIds").forEach(id -> publishedIds.add(id.asText()));
            assertThat(diagnostics.stream().noneMatch(message -> publishedIds.stream().anyMatch(message::contains)))
                    .as("MVC diagnostics do not expose the published question revision IDs returned in JSON").isTrue();
            assertThat(captured.list.stream().anyMatch(event -> event.getLoggerName().endsWith(".HttpEntityMethodProcessor")
                            && event.getFormattedMessage().contains("Writing [QuizRevisionPublicationResponse")))
                    .as("MVC logged a diagnostic while writing the acknowledgement").isTrue();
        } finally {
            for (int i = 0; i < loggers.size(); i++) {
                var logger = loggers.get(i);
                logger.detachAppender(captured);
                logger.setLevel(previousLevels.get(i));
                logger.setAdditive(previousAdditivity.get(i));
            }
            captured.stop();
        }
    }

    @Test
    void ordinaryAuthorPublishesInTheRequestedOrderAndCanReplayAfterAReplacement() throws Exception {
        var author = author("QUIZ_UPDATE", "ATTEMPT_CREATE", "ATTEMPT_READ");
        var moderator = persona("QUIZ_MODERATE");
        var draft = draft(author, 3);
        var changedTitle = json.createObjectNode().put("title", "Cells and their organelles");
        var savedDraft = body(request(author, patch(API + "/{quiz}", draft.id()), changedTitle), 200);
        assertThat(savedDraft.path("title").asText()).isEqualTo("Cells and their organelles");
        assertThat(state(draft.id())).isEqualTo(new State(0, null, 0, 0));

        expect(request(author, post(API + "/{quiz}/submit-for-review", draft.id()), null), 204);
        expect(request(moderator, post("/api/v1/admin/quizzes/{quiz}/approve", draft.id()), null), 204);
        assertThat(state(draft.id())).isEqualTo(new State(0, null, 0, 0));
        var started = body(request(author, post("/api/v1/attempts/quizzes/{quiz}", draft.id()),
                json.createObjectNode().put("mode", "ALL_AT_ONCE")), 201);
        assertThat(started.path("totalQuestions").asInt()).isEqualTo(3);
        UUID attempt = uuid(started, "attemptId");

        var order = List.of(draft.questions().get(2), draft.questions().get(0), draft.questions().get(1));
        UUID key = UUID.randomUUID();
        var first = publishBody(author, draft.id(), key, 0, order);
        assertAcknowledgement(first, draft.id(), 1, order);
        UUID firstRevision = uuid(first, "quizRevisionId");
        assertThat(state(draft.id())).isEqualTo(new State(1, firstRevision, 1, 1));

        var second = publishBody(author, draft.id(), UUID.randomUUID(), 1, draft.questions());
        assertAcknowledgement(second, draft.id(), 2, draft.questions());
        assertThat(uuid(second, "quizRevisionId")).isNotEqualTo(firstRevision);
        assertThat(publishBody(author, draft.id(), key, 0, order)).isEqualTo(first);
        problem(publish(author, draft.id(), key, 0, draft.questions()), 409, "COMMAND_CONFLICT");
        problem(publish(author, draft.id(), UUID.randomUUID(), 0, order), 409, "STALE_PUBLICATION");
        assertThat(state(draft.id())).isEqualTo(new State(2, uuid(second, "quizRevisionId"), 2, 2));

        var answer = json.createObjectNode().put("questionId", order.get(0).toString());
        answer.set("response", contract(QuestionContracts.View.RESPONSE));
        var feedback = body(request(author, post("/api/v1/attempts/{attempt}/answers", attempt), answer), 200);
        assertThat(feedback.path("score").doubleValue()).isEqualTo(1.0);
        assertThat(feedback.path("correctAnswerAvailability").asText()).isEqualTo("NOT_INCLUDED");
        var completed = body(request(author, post("/api/v1/attempts/{attempt}/complete", attempt), null), 200);
        assertThat(completed.path("correctCount").asInt()).isOne();
        assertThat(state(draft.id())).isEqualTo(new State(2, uuid(second, "quizRevisionId"), 2, 2));
    }

    @Test
    void quizAndQuestionAuthorityDenyWithoutDisclosingExistenceOrWritingPublicationState() throws Exception {
        var owner = author("QUIZ_UPDATE");
        var peer = author("QUIZ_UPDATE");
        var restricted = author();
        var moderator = persona("QUIZ_MODERATE", "QUIZ_UPDATE");
        var owned = draft(owner, 2);
        var foreign = draft(peer, 1);
        var noPermission = draft(restricted, 1);
        UUID key = UUID.randomUUID();
        var unauthenticated = publish(null, owned.id(), key, 0, owned.questions());
        expect(unauthenticated, 401);
        assertThat(unauthenticated.getResponse().getContentType()).startsWith("application/problem+json");

        var missing = problem(publish(owner, UUID.randomUUID(), key, 0, owned.questions()), 404, "UNAVAILABLE");
        assertThat(problem(publish(peer, owned.id(), key, 0, owned.questions()), 404, "UNAVAILABLE")).isEqualTo(missing);
        assertThat(problem(publish(restricted, noPermission.id(), key, 0, noPermission.questions()), 404, "UNAVAILABLE"))
                .isEqualTo(missing);
        problem(publish(owner, owned.id(), key, 0, List.of(owned.questions().get(0))), 422, "INVALID_CONTENT");
        problem(publish(owner, owned.id(), key, 0, List.of(owned.questions().get(0), owned.questions().get(0))),
                400, "INVALID_INPUT");
        assertThat(state(owned.id())).isEqualTo(new State(0, null, 0, 0));
        assertThat(state(noPermission.id())).isEqualTo(new State(0, null, 0, 0));

        UUID foreignQuestion = foreign.questions().get(0);
        expect(request(moderator, post(API + "/{quiz}/questions/{question}", owned.id(), foreignQuestion), null), 204);
        var combined = new ArrayList<>(owned.questions());
        combined.add(foreignQuestion);
        assertThat(problem(publish(owner, owned.id(), key, 0, combined), 404, "UNAVAILABLE")).isEqualTo(missing);
        assertThat(state(owned.id())).isEqualTo(new State(0, null, 0, 0));
        var admitted = publishBody(moderator, owned.id(), UUID.randomUUID(), 0, combined);
        assertAcknowledgement(admitted, owned.id(), 1, combined);

        // A draft can become public through existing moderation authority; its owner still cannot publish it.
        body(request(moderator, patch(API + "/{quiz}", foreign.id()),
                json.createObjectNode().put("visibility", "PUBLIC")), 200);
        assertThat(problem(publish(peer, foreign.id(), key, 0, foreign.questions()), 404, "UNAVAILABLE")).isEqualTo(missing);
        assertThat(state(foreign.id())).isEqualTo(new State(0, null, 0, 0));
        assertAcknowledgement(publishBody(moderator, foreign.id(), key, 0, foreign.questions()),
                foreign.id(), 1, foreign.questions());
        var ownerControl = draft(owner, 1);
        assertAcknowledgement(publishBody(owner, ownerControl.id(), key, 0, ownerControl.questions()),
                ownerControl.id(), 1, ownerControl.questions());
    }

    @Test
    void aReachedWriteRollsBackAndALostCommitAcknowledgementReplaysTheDurableResult() throws Exception {
        var author = author("QUIZ_UPDATE");
        var draft = draft(author, 2);
        UUID key = UUID.randomUUID();
        faults.mode = FaultMode.ROLLBACK;
        problem(publish(author, draft.id(), key, 0, draft.questions()), 503, "PERSISTENCE_UNAVAILABLE");
        assertThat(faults.reached).isTrue();
        assertThat(state(draft.id())).isEqualTo(new State(0, null, 0, 0));
        assertThat(fresh(() -> count("quiz_revisions", draft.id()))).isZero();

        faults.mode = null;
        var first = publishBody(author, draft.id(), key, 0, draft.questions());
        assertAcknowledgement(first, draft.id(), 1, draft.questions());
        var otherOrder = List.of(draft.questions().get(1), draft.questions().get(0));
        UUID replacement = UUID.randomUUID();
        faults.reached = false;
        faults.mode = FaultMode.LOSE_ACKNOWLEDGEMENT;
        problem(publish(author, draft.id(), replacement, 1, otherOrder), 503, "UNKNOWN_OUTCOME");
        assertThat(faults.reached).isTrue();
        var committed = state(draft.id());
        assertThat(committed).isEqualTo(new State(2, committed.revisionId(), 2, 2));
        assertThat(committed.revisionId()).isNotEqualTo(uuid(first, "quizRevisionId"));

        faults.mode = null;
        var replay = publishBody(author, draft.id(), replacement, 1, otherOrder);
        assertAcknowledgement(replay, draft.id(), 2, otherOrder);
        assertThat(uuid(replay, "quizRevisionId")).isEqualTo(committed.revisionId());
        assertThat(state(draft.id())).isEqualTo(committed);
    }

    @Test
    void concurrentDifferentOrdersFromTheSameVersionCommitExactlyOneWinner() throws Exception {
        var author = author("QUIZ_UPDATE");
        var draft = draft(author, 2);
        var reversed = List.of(draft.questions().get(1), draft.questions().get(0));
        faults.contenders = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        MvcResult first;
        MvcResult second;
        try {
            var a = pool.submit(() -> publish(author, draft.id(), UUID.randomUUID(), 0, draft.questions()));
            var b = pool.submit(() -> publish(author, draft.id(), UUID.randomUUID(), 0, reversed));
            first = a.get(75, TimeUnit.SECONDS);
            second = b.get(75, TimeUnit.SECONDS);
        } finally {
            faults.contenders = null;
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 409);
        boolean firstWon = first.getResponse().getStatus() == 200;
        var winner = body(firstWon ? first : second, 200);
        problem(firstWon ? second : first, 409, "STALE_PUBLICATION");
        assertAcknowledgement(winner, draft.id(), 1, firstWon ? draft.questions() : reversed);
        assertThat(state(draft.id())).isEqualTo(new State(1, uuid(winner, "quizRevisionId"), 1, 1));
    }

    @Test
    void acknowledgementSelectsOnlyOneSmallMetadataRowPerItem() throws Exception {
        var author = author("QUIZ_UPDATE");
        for (int size : List.of(2, 12)) {
            var draft = draft(author, size);
            var ack = publishBody(author, draft.id(), UUID.randomUUID(), 0, draft.questions());
            tx.executeWithoutResult(status -> {
                long connection = sql.queryForObject("SELECT CONNECTION_ID()", Long.class);
                var observer = admin();
                var before = wire(observer, connection);
                var projected = publications.revisionItems(draft.id(), uuid(ack, "quizRevisionId")).orElseThrow();
                var after = wire(observer, connection);
                long statements = after.statements() - before.statements();
                long rows = after.rows() - before.rows();
                long bytes = after.bytes() - before.bytes();
                assertThat(projected.questionRevisionIds()).hasSize(size);
                assertThat(statements).isOne();
                assertThat(rows).isEqualTo(size);
                // The text alone in the larger fixture is over 9 KB.
                assertThat(bytes).isBetween(1L, 2048L + size * 128L);
                System.out.printf("935 acknowledgement projection: %d items, %d SELECT, %d rows, %d bytes%n",
                        size, statements, rows, bytes);
            });
        }
    }

    private static Wire wire(JdbcTemplate admin, long connection) {
        return admin.queryForObject("""
                SELECT SUM(e.COUNT_STAR) AS statements, SUM(e.SUM_ROWS_SENT) AS rows_sent,
                       CAST(s.VARIABLE_VALUE AS UNSIGNED) AS bytes_sent
                FROM performance_schema.threads t
                JOIN performance_schema.events_statements_summary_by_thread_by_event_name e USING (THREAD_ID)
                JOIN performance_schema.status_by_thread s USING (THREAD_ID)
                WHERE t.PROCESSLIST_ID = ? AND e.EVENT_NAME = 'statement/sql/select' AND s.VARIABLE_NAME = 'Bytes_sent'
                GROUP BY s.VARIABLE_VALUE
                """, (row, index) -> new Wire(row.getLong("statements"), row.getLong("rows_sent"),
                row.getLong("bytes_sent")), connection);
    }

    private Draft draft(Persona author, int size) throws Exception {
        var quiz = json.createObjectNode().put("title", "Biology " + UUID.randomUUID())
                .put("visibility", "PRIVATE").put("estimatedTime", 5).put("timerDuration", 5);
        UUID id = uuid(body(request(author, post(API), quiz), 201), "quizId");
        List<UUID> questions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            var question = json.createObjectNode().put("type", "TRUE_FALSE").put("difficulty", "EASY")
                    .put("questionText", ("Synthetic question " + i + " ").repeat(39));
            question.set("content", contract(QuestionContracts.View.AUTHOR));
            question.putArray("quizIds").add(id.toString());
            questions.add(uuid(body(request(author, post("/api/v1/questions"), question), 201), "questionId"));
        }
        return new Draft(id, questions);
    }

    private JsonNode contract(QuestionContracts.View view) {
        return QuestionContracts.example(QuestionType.TRUE_FALSE, view);
    }

    private Persona author(String... extra) {
        var permissions = new ArrayList<>(List.of("QUIZ_CREATE", "QUESTION_CREATE"));
        permissions.addAll(List.of(extra));
        return persona(permissions.toArray(String[]::new));
    }

    private Persona persona(String... permissions) {
        String name = "publication-" + UUID.randomUUID().toString().substring(0, 12);
        UUID id = fresh(() -> {
            var user = new User();
            user.setUsername(name);
            user.setEmail(name + "@example.invalid");
            user.setHashedPassword("synthetic-hash");
            user.setActive(true);
            user.setEmailVerified(true);
            user.setRoles(new HashSet<>());
            UUID userId = users.saveAndFlush(user).getId();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", name);
            for (String permission : permissions) {
                assertThat(sql.update("""
                        INSERT INTO role_permissions(role_id,permission_id)
                        SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p
                        WHERE r.role_name=? AND p.permission_name=?
                        """, name, permission)).isOne();
            }
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?",
                    userId.toString(), name);
            return userId;
        });
        // Match login by issuing from the stored password-change timestamp.
        return new Persona(auth.issueTokensForUser(fresh(() -> users.findById(id).orElseThrow())).accessToken());
    }

    private MvcResult publish(Persona persona, UUID quiz, UUID command, long expected, List<UUID> order) throws Exception {
        return publishJson(persona, quiz, publicationCommand(command, expected, order).toString());
    }

    private ObjectNode publicationCommand(UUID command, long expected, List<UUID> order) {
        var body = json.createObjectNode().put("commandId", command.toString()).put("expectedPublicationVersion", expected);
        var questions = body.putArray("questionOrder");
        order.forEach(id -> questions.add(id.toString()));
        return body;
    }

    private MvcResult publishJson(Persona persona, UUID quiz, String body) throws Exception {
        return request(persona, post(PUBLICATIONS, quiz).contentType("application/json").content(body), null);
    }

    private JsonNode publishBody(Persona persona, UUID quiz, UUID key, long version, List<UUID> order) throws Exception {
        MvcResult result = publish(persona, quiz, key, version, order);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        return body(result, 200);
    }

    private MvcResult request(Persona persona, MockHttpServletRequestBuilder request, JsonNode body) throws Exception {
        SecurityContextHolder.clearContext();
        if (persona != null) {
            request.header("Authorization", "Bearer " + persona.token());
        }
        if (body != null) {
            request.contentType("application/json").content(body.toString());
        }
        return mvc.perform(request).andReturn();
    }

    private void assertAcknowledgement(JsonNode ack, UUID quiz, long version, List<UUID> order) {
        assertThat(ack.size()).isEqualTo(5);
        assertThat(uuid(ack, "quizId")).isEqualTo(quiz);
        assertThat(ack.path("publicationVersion").longValue()).isEqualTo(version);
        var saved = fresh(() -> sql.query("""
                SELECT r.schema_version, BIN_TO_UUID(q.question_id), BIN_TO_UUID(q.id)
                FROM quiz_revisions r JOIN quiz_revision_items i ON i.quiz_revision_id = r.id
                JOIN question_revisions q ON q.id = i.question_revision_id
                WHERE r.id = UUID_TO_BIN(?) AND r.quiz_id = UUID_TO_BIN(?) ORDER BY i.authored_position
                """, (row, index) -> new Item(row.getInt(1), UUID.fromString(row.getString(2)),
                UUID.fromString(row.getString(3))), uuid(ack, "quizRevisionId").toString(), quiz.toString()));
        assertThat(saved).extracting(Item::questionId).containsExactlyElementsOf(order);
        assertThat(saved).extracting(Item::schemaVersion).containsOnly(ack.path("revisionSchemaVersion").intValue());
        assertThat(ack.path("questionRevisionIds")).extracting(id -> UUID.fromString(id.asText()))
                .containsExactlyElementsOf(saved.stream().map(Item::revisionId).toList());
    }

    private State state(UUID quiz) {
        return fresh(() -> {
            var head = sql.query("""
                    SELECT publication_version, BIN_TO_UUID(revision_id)
                    FROM quiz_publication_heads WHERE quiz_id = UUID_TO_BIN(?)
                    """, (row, i) -> Map.entry(row.getLong(1), UUID.fromString(row.getString(2))), quiz.toString());
            return new State(head.isEmpty() ? 0 : head.get(0).getKey(), head.isEmpty() ? null : head.get(0).getValue(),
                    count("quiz_publications", quiz), count("quiz_publication_receipts", quiz));
        });
    }

    private int count(String table, UUID quiz) {
        return sql.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE quiz_id = UUID_TO_BIN(?)",
                Integer.class, quiz.toString());
    }

    private <T> T fresh(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    private static void expect(MvcResult result, int status) {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
    }

    private JsonNode body(MvcResult result, int expectedStatus) throws Exception {
        expect(result, expectedStatus);
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode problem(MvcResult result, int status, String code) throws Exception {
        var body = (ObjectNode) body(result, status);
        assertThat(result.getResponse().getContentType()).startsWith("application/problem+json");
        assertThat(body.path("code").asText()).isEqualTo("QUIZ_PUBLICATION_" + code);
        assertThat(body.toString()).doesNotContain("Exception", "select ", "checkpoint", "SQL");
        body.remove(List.of("instance", "timestamp"));
        return body;
    }

    private static UUID uuid(JsonNode node, String field) {
        return UUID.fromString(node.path(field).asText());
    }

    private static String server() {
        if (!PORT.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("Invalid test MySQL port");
        }
        return "jdbc:mysql://127.0.0.1:" + PORT + "/";
    }

    private static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(server() + OPTIONS, ADMIN_USER, ADMIN_PASSWORD));
    }

    private record Persona(String token) { }
    private record Draft(UUID id, List<UUID> questions) { }
    private record Item(int schemaVersion, UUID questionId, UUID revisionId) { }
    private record State(long version, UUID revisionId, int revisions, int receipts) { }
    private record Wire(long statements, long rows, long bytes) { }
    private enum FaultMode { ROLLBACK, LOSE_ACKNOWLEDGEMENT }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean PublicationFaults publicationFaults(JdbcTemplate sql) { return new PublicationFaults(sql); }
    }

    @Aspect
    static class PublicationFaults {
        private final JdbcTemplate sql;
        volatile FaultMode mode;
        volatile CyclicBarrier contenders;
        volatile boolean reached;
        final Set<Long> gatedThreads = ConcurrentHashMap.newKeySet();

        PublicationFaults(JdbcTemplate sql) { this.sql = sql; }

        @Around("execution(* uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository.draft(..))")
        Object beforeDraftLock(ProceedingJoinPoint call) throws Throwable {
            CyclicBarrier barrier = contenders;
            if (barrier != null && Boolean.TRUE.equals(call.getArgs()[1])
                    && gatedThreads.add(Thread.currentThread().getId())) {
                barrier.await(60, TimeUnit.SECONDS);
            }
            return call.proceed();
        }

        @Around("execution(* uk.gegc.quizmaker.features.quiz.domain.repository.QuizPublicationRepository.record(..))")
        Object afterReceiptWrite(ProceedingJoinPoint call) throws Throwable {
            Object result = call.proceed();
            if (mode == FaultMode.ROLLBACK) {
                var command = (Command) call.getArgs()[0];
                assertThat(sql.queryForObject("""
                        SELECT COUNT(*) FROM quiz_publication_receipts r JOIN quiz_publication_heads h
                          ON h.quiz_id = r.quiz_id AND h.revision_id = r.revision_id
                        WHERE r.quiz_id = UUID_TO_BIN(?) AND r.command_id = UUID_TO_BIN(?)
                        """, Integer.class, command.quizId().toString(), command.commandId().toString())).isOne();
                reached = true;
                throw new IllegalStateException("Reached publication write checkpoint");
            }
            if (mode == FaultMode.LOSE_ACKNOWLEDGEMENT) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        reached = true;
                        throw new IllegalStateException("Lost commit acknowledgement after checkpoint");
                    }
                });
            }
            return result;
        }
    }
}
