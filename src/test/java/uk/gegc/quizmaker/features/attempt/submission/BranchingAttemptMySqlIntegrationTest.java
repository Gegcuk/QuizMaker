package uk.gegc.quizmaker.features.attempt.submission;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.SQLException;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.validation.Validator;
import uk.gegc.quizmaker.features.attempt.api.AttemptSubmissionController;
import uk.gegc.quizmaker.features.attempt.api.AttemptSubmissionErrorHandler;
import uk.gegc.quizmaker.features.attempt.api.AttemptSubmissionJsonConfig;
import uk.gegc.quizmaker.features.attempt.api.BranchProgressController;
import uk.gegc.quizmaker.features.attempt.api.BranchProgressErrorHandler;
import uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewController;
import uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewErrorHandler;
import uk.gegc.quizmaker.features.attempt.api.ResponseAcceptanceController;
import uk.gegc.quizmaker.features.attempt.api.ResponseAcceptanceErrorHandler;
import uk.gegc.quizmaker.features.attempt.api.ResponseAcceptanceJsonConfig;
import uk.gegc.quizmaker.features.attempt.application.impl.BranchParticipantServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.impl.InitialParticipantViewDisclosureReader;
import uk.gegc.quizmaker.features.result.application.ImmediateResultReleaseService;
import uk.gegc.quizmaker.features.result.application.ParticipantResultService;
import uk.gegc.quizmaker.features.result.application.impl.ImmediateResultReleaseServiceImpl;
import uk.gegc.quizmaker.features.result.application.impl.ParticipantResultServiceImpl;
import uk.gegc.quizmaker.features.result.application.impl.ResultDisclosureServiceImpl;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcResultReleaseRepository;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcResultNotificationEventRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.BranchingAttemptServiceImpl;
import uk.gegc.quizmaker.features.attempt.application.transaction.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.BranchingException;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest;
import uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.*;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.application.contract.v1.SupportedQuestionContracts;
import uk.gegc.quizmaker.features.question.config.ComplianceQuestionContractConfig;
import uk.gegc.quizmaker.features.question.config.MatchingQuestionContractConfig;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.quiz.application.QuizScenarioRevisionService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizScenarioRevisionServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.result.application.AutomaticResultService;
import uk.gegc.quizmaker.features.result.domain.model.AutomaticResultException;
import static uk.gegc.quizmaker.features.attempt.application.BranchingAttemptService.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("db-serial")
@DataJpaTest(showSql = false)
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "quizmaker.attempts.branch-participant-enabled=true", "quizmaker.attempts.participant-view-enabled=true",
        "quizmaker.attempts.response-acceptance-enabled=true", "quizmaker.attempts.submission-enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({AttemptSubmissionMySqlIntegrationTest.Config.class, QuizScenarioRevisionServiceImpl.class,
        BranchingAttemptServiceImpl.class, BranchParticipantServiceImpl.class, BranchingAttemptMySqlIntegrationTest.ReviewConfig.class,
        ImmediateResultReleaseServiceImpl.class, ParticipantResultServiceImpl.class, ResultDisclosureServiceImpl.class,
        JdbcResultReleaseRepository.class, JdbcResultNotificationEventRepository.class,
        MatchingQuestionContractConfig.class, ComplianceQuestionContractConfig.class})
class BranchingAttemptMySqlIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    static class ReviewConfig {
        @Bean @Primary ParticipantViewDisclosureReader branchDisclosure(ParticipantResultService results) {
            return new InitialParticipantViewDisclosureReader(results);
        }
    }

    static final String SCHEMA = "attempt_branching_" + UUID.randomUUID().toString().replace("-", "");
    static JdbcTemplate admin;
    static boolean schemaCreated;
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        String port = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
        if (!port.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        String base = "jdbc:mysql://127.0.0.1:" + port + "/";
        String options = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000";
        String user = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
        String password = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
        admin = new JdbcTemplate(new DriverManagerDataSource(base + options, user, password));
        admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        schemaCreated = true;
        p.add("spring.datasource.url", () -> base + SCHEMA + options);
        p.add("spring.datasource.username", () -> user); p.add("spring.datasource.password", () -> password);
    }
    @AfterAll static void dropDatabase() { if (schemaCreated) admin.execute("DROP DATABASE " + SCHEMA); }


    @Autowired ApplicationContext context;
    @Autowired BranchingAttemptService branches;
    @Autowired BranchParticipantService participantCommands;
    @Autowired ParticipantAttemptViewService participantViews;
    @Autowired ImmediateResultReleaseService release;
    @Autowired Validator validator;
    private final ObjectMapper httpJson = new ObjectMapper();
    @Autowired ResponseAcceptanceService.Scenario branchResponses;
    @Autowired ResponseAcceptanceService responses;
    @Autowired AttemptSubmissionService.Scenario branchSubmissions;
    @Autowired AttemptSubmissionService submissions;
    @Autowired AttemptExpiryService expiry;
    @Autowired AttemptLifecycleService lifecycle;
    @Autowired QuizScenarioRevisionService publisher;
    @Autowired QuestionRevisionContractService contracts;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource source;
    @Autowired Clock clock;
    @Autowired UserRepository users;
    @Autowired QuizRepository quizzes;
    @Autowired CategoryRepository categories;
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;
    SubmissionDataSource observer() { return (SubmissionDataSource)source; }

    @BeforeEach void setup() {
        ((AttemptSubmissionMySqlIntegrationTest.AdjustableClock)clock).now = Instant.parse("2026-11-05T12:00:00Z");
        tx = AttemptTransactions.readCommitted(manager);
        username = "branch-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username);
        grant(ownerId, "ATTEMPT_CREATE", "ATTEMPT_READ");
        quizId = newQuiz();
        authenticate(username);
    }
    @AfterEach void cleanup() { observer().reset(); SecurityContextHolder.clearContext(); }

    private UUID user(String name) {
        var user = new User(); user.setUsername(name); user.setEmail(name + "@example.invalid");
        user.setHashedPassword("test-only"); user.setActive(true); user.setRoles(new HashSet<>());
        return users.saveAndFlush(user).getId();
    }
    private UUID newQuiz() {
        return tx.execute(s -> {
            var category = new Category(); category.setName("acceptance-" + UUID.randomUUID()); categories.saveAndFlush(category);
            var quiz = new Quiz(); quiz.setCreator(users.findById(ownerId).orElseThrow()); quiz.setCategory(category);
            quiz.setTitle("Start fixture " + UUID.randomUUID()); quiz.setDescription("Synthetic source"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
    }
    private static void authenticate(String name) {
        var principal = org.springframework.security.core.userdetails.User.withUsername(name).password("test-only").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private void grant(UUID userId, String... names) {
        tx.executeWithoutResult(s -> {
            String role = "acceptance-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            for (String name : names) assertThat(sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, name)).isEqualTo(1);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", userId.toString(), role);
        });
    }


    private QuizRevision graph() {
        return graph(null, 1);
    }

    private QuizRevision graph(String attachedQuestion, int entrySteps) {
        var questions = List.of("Fork", "Advanced", "Practice").stream()
                .map(text -> trueFalseQuestion(text, text.equals(attachedQuestion) ? "https://example.invalid/question.png" : null))
                .toList();
        return publishGraph(questions, entrySteps);
    }

    private QuestionRevision trueFalseQuestion(String text, String attachmentUrl) {
        return new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.TRUE_FALSE,
                Difficulty.EASY, text, "{\"answer\":true}", null, "private explanation",
                attachmentUrl, null, clock.instant(), List.of());
    }

    private QuizRevision publishGraph(List<QuestionRevision> questions, int entrySteps) {
        var nodes = new ArrayList<ScenarioGraph.Node>();
        var items = new ArrayList<QuizRevision.Item>();
        for (var question : questions) {
            var item = new QuizRevision.Item(UUID.randomUUID(), items.size(), question);
            items.add(item);
            nodes.add(new ScenarioGraph.Node(UUID.randomUUID(), nodes.size(), ScenarioGraph.NodeType.ITEM, item.id()));
        }
        var fork = nodes.get(0).id();
        var terminal = UUID.randomUUID();
        nodes.add(new ScenarioGraph.Node(terminal, 3, ScenarioGraph.NodeType.TERMINAL, null));
        var edges = new ArrayList<ScenarioGraph.Edge>();
        var entry = fork;
        for (int i = 0; i < entrySteps; i++) {
            var route = UUID.randomUUID();
            nodes.add(new ScenarioGraph.Node(route, nodes.size(), ScenarioGraph.NodeType.ROUTING, null));
            edges.add(edge(route, entry));
            entry = route;
        }
        edges.add(new ScenarioGraph.Edge(UUID.randomUUID(), fork, nodes.get(1).id(), 1,
                ScenarioGraph.EdgeType.CONDITIONAL, 1, "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\"}"));
        edges.add(edge(fork, nodes.get(2).id()));
        edges.add(edge(nodes.get(1).id(), terminal));
        edges.add(edge(nodes.get(2).id(), terminal));
        var graph = new ScenarioGraph(1, entry, nodes, edges);
        var revision = publisher.publish(ownerId, new QuizRevision(UUID.randomUUID(), quizId, ownerId, 4,
                "Scenario", null, clock.instant(), items, List.of(), List.of(), graph));
        contracts.bind(ownerId, revision.id(), items.stream().map(i -> SupportedQuestionContracts.binding(i.question())).toList());
        return revision;
    }
    private static ScenarioGraph.Edge edge(UUID from, UUID to) {
        return new ScenarioGraph.Edge(UUID.randomUUID(), from, to, 0, ScenarioGraph.EdgeType.DEFAULT, null, null);
    }
    record Start(BranchingAttemptService.Admission admission, UUID first) {
        UUID attempt() { return admission.attemptId(); }
        BranchingAttemptService.Command step(long state, long form) {
            return new BranchingAttemptService.Command(attempt(), UUID.randomUUID(), state, form);
        }
    }

    /** The launcher's exact revision admission is the sole future input. All other writers are real. */
    private Start launch(QuizRevision revision, boolean timed) {
        return tx.execute(status -> {
            var content = new ContentPolicy(revision.id(), 4, FormMode.PROGRESSIVE, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
            var timing = new TimingPolicy(TimingMode.TOTAL_ATTEMPT, Duration.ofMinutes(30), null);
            var overrides = timed ? List.of(new SourceOverrides(OverrideSource.AUTHOR, 1,
                    new AllowedChoices(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(timing)),
                    List.of(new TimingChoice(timing)))) : List.<SourceOverrides>of();
            var base = context.getBean(AttemptPolicyResolver.class).resolve(new PolicyResolutionRequest(1, 1,
                    PolicyResolutionRequest.HASH_ALGORITHM, BuiltInPracticePreset.REFERENCE, content, overrides));
            var p = base.policy();
            var s = p.submission();
            var required = new SubmissionPolicy(false, false, true, true, s.mode(), s.retries(), s.finalSubmitPayload(), s.unanswered());
            var policy = new ResolvedAttemptPolicy(1, content, p.questionSelection(), p.questionOrdering(), p.answerOrdering(),
                    p.delivery(), p.navigation(), required, p.answerChange(), p.disclosure(), p.hints(),
                    p.timing(),
                    p.pause(), p.attempts(), p.access(), p.grading(), p.review(), p.results(), p.accessibility(), p.integrity());
            var resolution = new CanonicalPolicyFormat().encode(BuiltInPracticePreset.REFERENCE, policy, base.provenance());
            var snapshot = context.getBean(PolicySnapshotService.class).createOrReplay(ownerId, new PolicySnapshot(UUID.randomUUID(), resolution));
            var attempt = UUID.randomUUID();
            var subject = context.getBean(AuthenticatedSubjectResolver.class).resolve(attempt);
            context.getBean(AttemptSubjectService.class).bindOrReplay(subject.userId(), subject);
            lifecycle.initialize(attempt);
            var state = lifecycle.save(attempt, 0, new AttemptLifecycle.State(1, AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.NOT_STARTED));
            context.getBean(AttemptTimingService.class).capture(snapshot, state);
            var a = new BranchingAttemptService.Admission(attempt, subject.userId(), quizId, ownerId, revision.id(), snapshot.id(), UUID.randomUUID(), UUID.randomUUID());
            var first = branches.bind(a);
            assertThat(first.outcome()).isEqualTo(APPENDED);
            return new Start(a, first.formItemId());
        });
    }
    private ResponseAcceptanceService.Command answer(Start a, UUID item, long version, boolean value) {
        return new ResponseAcceptanceService.Command(a.attempt(), item, version, CommandReceipt.Key.from(UUID.randomUUID().toString()),
                QuestionType.TRUE_FALSE, JsonNodeFactory.instance.objectNode().put("answer", value));
    }
    private AttemptSubmissionService.Command submit(Start a, long version) {
        return new AttemptSubmissionService.Command(a.attempt(), version, true, CommandReceipt.Key.from(UUID.randomUUID().toString()));
    }
    private int count(String table, String key, UUID id) {
        return sql.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+key+"=UUID_TO_BIN(?)", Integer.class, id.toString());
    }

    private MockMvc participantHttp() {
        var standard = new MappingJackson2HttpMessageConverter();
        var converters = new ArrayList<HttpMessageConverter<?>>(List.of(standard));
        new ResponseAcceptanceJsonConfig().extendMessageConverters(converters);
        new AttemptSubmissionJsonConfig().extendMessageConverters(converters);
        return MockMvcBuilders.standaloneSetup(new ParticipantAttemptViewController(participantViews, Optional.empty()),
                        new BranchProgressController(branches),
                        new ResponseAcceptanceController(responses, httpJson, Optional.empty(), validator, Optional.of(participantCommands)),
                        new AttemptSubmissionController(submissions, Optional.empty(), validator, Optional.of(participantCommands)))
                .setControllerAdvice(new ParticipantAttemptViewErrorHandler(), new BranchProgressErrorHandler(),
                        new ResponseAcceptanceErrorHandler(), new AttemptSubmissionErrorHandler())
                .setMessageConverters(converters.toArray(HttpMessageConverter[]::new)).build();
    }

    private JsonNode http(MockMvc mvc, RequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        return httpJson.readTree(response.getContentAsString());
    }

    private static RequestBuilder command(String path, UUID key, JsonNode body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key.toString())
                .content(body.toString());
    }

    private static JsonNode answerBody(UUID item, long stateVersion, boolean value) {
        var body = JsonNodeFactory.instance.objectNode().put("formItemId", item.toString()).put("expectedStateVersion", stateVersion);
        body.putObject("response").put("type", "TRUE_FALSE").put("answer", value);
        return body;
    }

    private static JsonNode advanceBody(long stateVersion, long formVersion) {
        return JsonNodeFactory.instance.objectNode().put("expectedStateVersion", stateVersion).put("expectedFormVersion", formVersion);
    }

    @Test void participantHttpResumesOnlyCommittedPathAndExplicitlySubmitsWithRequireAll() throws Exception {
        var revision = graph();
        var started = launch(revision, false);
        var path = "/api/v2/attempts/" + started.attempt();
        var mvc = participantHttp();
        var before = http(mvc, get(path), 200);
        assertThat(before.at("/branchProgress/deliveredFormItemIds").toString()).isEqualTo("[\"" + started.first() + "\"]");
        assertThat(before.at("/branchProgress/canAdvance").asBoolean()).isFalse();
        assertThat(before.path("allowedActions").toString()).contains("SUBMIT_ANSWER").doesNotContain("SUBMIT_ATTEMPT");
        var nextPath = path + "/branch-progress/next";
        assertThat(http(mvc, command(nextPath, UUID.randomUUID(), advanceBody(1, 1)), 409).path("code").asText())
                .isEqualTo("BRANCH_ANSWER_REQUIRED");
        assertThat(count("attempt_branch_decision", "form_id", started.admission().formId())).isOne();

        var responseKey = UUID.randomUUID();
        var response = answerBody(started.first(), 1, true);
        var accepted = http(mvc, command(path + "/responses", responseKey, response), 200);
        long version = accepted.path("stateVersion").asLong();
        var key = UUID.randomUUID();
        var input = advanceBody(version, 1);
        var next = http(mvc, command(nextPath, key, input), 200);
        var delivered = UUID.fromString(next.path("formItemId").asText());
        assertThat(next.path("outcome").asText()).isEqualTo("APPENDED");
        var resumed = http(participantHttp(), get(path), 200);
        assertThat(resumed.at("/branchProgress/currentFormItemId").asText()).isEqualTo(delivered.toString());
        assertThat(resumed.at("/branchProgress/deliveredFormItemIds").size()).isEqualTo(2);
        assertThat(resumed.path("items").size()).isEqualTo(2);
        assertThat(resumed.toString()).doesNotContain(revision.items().get(2).id().toString(), "SCORE_IS", "private explanation");
        assertThat(http(mvc, command(nextPath, key, input), 200)).isEqualTo(next);
        assertThat(http(mvc, command(nextPath, key, advanceBody(version, 2)), 409).path("code").asText())
                .isEqualTo("BRANCH_CONFLICT");
        assertThat(http(mvc, command(path + "/responses", responseKey, response), 200).path("replayed").asBoolean()).isTrue();

        var hidden = revision.items().get(2).id();
        var hiddenResponse = http(mvc, command(path + "/responses", UUID.randomUUID(), answerBody(hidden, version, false)), 404);
        var absentResponse = http(mvc, command(path + "/responses", UUID.randomUUID(), answerBody(UUID.randomUUID(), version, false)), 404);
        for (String field : List.of("type", "title", "status", "detail", "instance", "code"))
            assertThat(hiddenResponse.path(field)).as(field).isEqualTo(absentResponse.path(field));
        assertThat(hiddenResponse.toString()).doesNotContain(hidden.toString());
        assertThat(count("attempt_accepted_response", "attempt_id", started.attempt())).isOne();

        var terminal = advanceBody(version, 2);
        var terminalKey = UUID.randomUUID();
        var terminalAck = http(mvc, command(nextPath, terminalKey, terminal), 200);
        assertThat(terminalAck.path("outcome").asText()).isEqualTo("TERMINAL_READY");
        assertThat(terminalAck.has("formItemId")).isFalse();
        var ready = http(mvc, get(path), 200);
        assertThat(ready.at("/branchProgress/terminalReady").asBoolean()).isTrue();
        assertThat(ready.at("/branchProgress/currentFormItemId").isMissingNode()).isTrue();
        assertThat(ready.path("execution").asText()).isEqualTo("IN_PROGRESS");
        assertThat(count("attempt_submission", "attempt_id", started.attempt())).isZero();
        var premature = JsonNodeFactory.instance.objectNode().put("expectedStateVersion", version).put("completionConfirmed", true);
        var incomplete = http(mvc, command(path + "/submit", UUID.randomUUID(), premature), 422);
        assertThat(incomplete.path("code").asText()).isEqualTo("SUBMISSION_INCOMPLETE");
        assertThat(incomplete.path("missingFormItemIds")).hasSize(1);
        assertThat(incomplete.at("/missingFormItemIds/0").asText()).isEqualTo(delivered.toString());
        var last = http(mvc, command(path + "/responses", UUID.randomUUID(), answerBody(delivered, version, true)), 200);
        var submit = JsonNodeFactory.instance.objectNode().put("expectedStateVersion", last.path("stateVersion").asLong())
                .put("completionConfirmed", true);
        var submitKey = UUID.randomUUID();
        var done = http(mvc, command(path + "/submit", submitKey, submit), 200);
        var repeated = http(mvc, command(path + "/submit", submitKey, submit), 200);
        assertThat(repeated.path("submissionId")).isEqualTo(done.path("submissionId"));
        assertThat(repeated.path("replayed").asBoolean()).isTrue();
        assertThat(http(mvc, command(nextPath, terminalKey, terminal), 200)).isEqualTo(terminalAck);
        assertThat(count("attempt_branch_decision", "form_id", started.admission().formId())).isEqualTo(3);
        assertThat(count("attempt_submission", "attempt_id", started.attempt())).isOne();
        assertThat(http(mvc, get(path), 200).has("result")).isFalse();

        var submissionId = UUID.fromString(done.path("submissionId").asText());
        context.getBean(AutomaticResultService.class).finalizeSubmission(submissionId);
        release.release(submissionId);
        var review = http(mvc, get(path + "/review"), 200);
        assertThat(review.at("/result/items").size()).isEqualTo(2);
        assertThat(review.toString()).doesNotContain(hidden.toString(), "SCORE_IS");
    }

    @Test void foreignParticipantCannotReadAdvanceAnswerOrSubmitTheCommittedPath() throws Exception {
        var started = launch(graph(), false);
        var path = "/api/v2/attempts/" + started.attempt();
        var missing = "/api/v2/attempts/" + UUID.randomUUID();
        var mvc = participantHttp();
        assertThat(http(mvc, get(path), 200).at("/branchProgress/currentFormItemId").asText())
                .isEqualTo(started.first().toString());

        var peer = "branch-peer-" + UUID.randomUUID().toString().substring(0, 10);
        grant(user(peer), "ATTEMPT_READ", "ATTEMPT_CREATE", "QUESTION_UPDATE", "QUIZ_MODERATE");
        authenticate(peer);
        assertThat(http(mvc, get(path), 404).path("detail")).isEqualTo(http(mvc, get(missing), 404).path("detail"));
        assertThat(http(mvc, command(path + "/branch-progress/next", UUID.randomUUID(), advanceBody(1, 1)), 404).path("code"))
                .isEqualTo(http(mvc, command(missing + "/branch-progress/next", UUID.randomUUID(), advanceBody(1, 1)), 404).path("code"));
        http(mvc, command(path + "/responses", UUID.randomUUID(), answerBody(started.first(), 1, true)), 404);
        var submit = JsonNodeFactory.instance.objectNode().put("expectedStateVersion", 1).put("completionConfirmed", true);
        http(mvc, command(path + "/submit", UUID.randomUUID(), submit), 404);

        authenticate(username);
        assertThat(count("attempt_accepted_response", "attempt_id", started.attempt())).isZero();
        assertThat(count("attempt_submission", "attempt_id", started.attempt())).isZero();
        assertThat(count("attempt_branch_decision", "form_id", started.admission().formId())).isOne();
        assertThat(http(mvc, command(path + "/responses", UUID.randomUUID(), answerBody(started.first(), 1, true)), 200)
                .path("stateVersion").asLong()).isEqualTo(2);
    }

    @Test void participantReadUsesBoundedQueriesAndCorruptEvidenceDeniesBeforeProgress(TestReporter reporter) throws Exception {
        var revision = graph();
        var shorter = launch(revision, false);
        var longer = launch(revision, false);
        branchResponses.acceptBranch(answer(longer, longer.first(), 1, true));
        branches.advance(longer.step(2, 1));
        var mvc = participantHttp();
        var prefix = "/api/v2/attempts/";
        observer().reset();
        assertThat(http(mvc, get(prefix + shorter.attempt()), 200).path("items").size()).isOne();
        int selects = observer().selects.size();
        assertThat(selects).isPositive();
        observer().reset();
        assertThat(http(mvc, get(prefix + longer.attempt()), 200).path("items").size()).isEqualTo(2);
        assertThat(observer().selects).hasSize(selects).noneMatch(query -> query.contains("normalized_response"));
        reporter.publishEntry("participant SELECTs for one and two delivered questions", Integer.toString(selects));

        sql.update("UPDATE attempt_branch_path SET terminal_ready=TRUE WHERE attempt_id=UUID_TO_BIN(?)", longer.attempt().toString());
        var broken = http(mvc, get(prefix + longer.attempt()), 500);
        assertThat(broken.toString()).doesNotContain(longer.first().toString(), "SCORE_IS");
        assertThat(http(mvc, command(prefix + longer.attempt() + "/branch-progress/next",
                UUID.randomUUID(), advanceBody(2, 2)), 500).path("code").asText()).isEqualTo("BRANCH_CORRUPT");
        assertThat(count("attempt_branch_decision", "form_id", longer.admission().formId())).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void conditionalWaitsForAcceptanceAndOnlyDeliveredQuestionsCount(boolean correct) {
        var revision = graph();
        var start = launch(revision, false);
        var waiting = start.step(1, 1);
        assertThat(branches.advance(waiting).outcome()).isEqualTo(WAITING_FOR_ANSWER);
        assertThat(branches.resume(start.attempt()).deliveredFormItems()).containsExactly(start.first());
        assertThat(count("attempt_branch_decision","form_id", start.admission().formId())).isOne();
        assertThatThrownBy(() -> branchSubmissions.submitBranch(submit(start, 1))).isInstanceOf(AttemptSubmissionException.class).hasMessageContaining("INACTIVE");
        var accepted = branchResponses.acceptBranch(answer(start, start.first(), 1, correct));
        var choose = start.step(accepted.stateVersion(), 1);
        BranchingAttemptService.Result next;
        try (var logs = new AttemptSubmissionMySqlIntegrationTest.TransactionLogs(
                "org.springframework.jdbc.core.StatementCreatorUtils", "org.springframework.transaction")) {
            next = branches.advance(choose);
            assertThat(logs.rendered()).doesNotContain("SCORE_IS", "private explanation", "FULL_CREDIT",
                    "{\"answer\":", revision.scenarioGraph().edges().get(0).id().toString());
        }
        assertThat(next.outcome()).isEqualTo(APPENDED);
        var chosen = revision.items().get(correct ? 1 : 2).id();
        var fork = revision.scenarioGraph().nodes().get(0).id();
        var winningEdge = revision.scenarioGraph().edges().stream().filter(e -> e.fromNodeId().equals(fork)
                && e.type() == (correct ? ScenarioGraph.EdgeType.CONDITIONAL : ScenarioGraph.EdgeType.DEFAULT))
                .findFirst().orElseThrow();
        String inputs = String.join("|", "scenario-input/v1", revision.id().toString(), start.attempt().toString(),
                "2", "1", fork.toString(), accepted.acceptedResponseId().toString());
        assertThat(sql.queryForObject("""
                SELECT COUNT(*) FROM attempt_branch_decision d JOIN attempt_branch_edge e
                  ON e.form_id=d.form_id AND e.decision_id=d.decision_id
                WHERE d.decision_id=UUID_TO_BIN(?) AND d.accepted_response_id=UUID_TO_BIN(?)
                  AND d.input_fingerprint=UNHEX(SHA2(?,256)) AND e.edge_position=0 AND e.edge_id=UUID_TO_BIN(?)
                """, Integer.class, choose.decisionId().toString(), accepted.acceptedResponseId().toString(),
                inputs, winningEdge.id().toString())).isOne();
        assertThat(sql.queryForObject("SELECT BIN_TO_UUID(source_item_id) FROM attempt_form_item WHERE id=UUID_TO_BIN(?)",
                String.class, next.formItemId().toString())).isEqualTo(chosen.toString());
        var hidden = revision.items().get(correct ? 2 : 1).id();
        assertThatThrownBy(() -> branchResponses.acceptBranch(answer(start, hidden, 2, true))).isInstanceOf(ResponseAcceptanceException.class).hasMessageContaining("NOT_FOUND");
        var finish = start.step(2, 2);
        assertThat(branches.advance(finish).outcome()).isEqualTo(TERMINAL_READY);
        assertThat(count("attempt_submission","attempt_id", start.attempt())).isZero();
        assertThat(count("attempt_accepted_response","attempt_id", start.attempt())).isOne();
        assertThatThrownBy(() -> branchSubmissions.submitBranch(submit(start, 2))).isInstanceOfSatisfying(AttemptSubmissionException.class,
                failure -> assertThat(failure.missing()).containsExactly(next.formItemId()));
        branchResponses.acceptBranch(answer(start, next.formItemId(), 2, true));
        var cmd = submit(start, 3);
        var submitted = branchSubmissions.submitBranch(cmd);
        assertThat(branchSubmissions.submitBranch(cmd).replayed()).isTrue();
        var grade = context.getBean(AutomaticResultService.class).finalizeSubmission(submitted.submissionId());
        assertThat(grade.items()).extracting(i -> i.formItemId()).containsExactly(start.first(), next.formItemId());
        assertThat(branches.advance(choose)).isEqualTo(next);
        assertThat(branches.advance(finish).outcome()).isEqualTo(TERMINAL_READY);
        assertThat(branches.resume(start.attempt()).deliveredFormItems()).containsExactly(start.first(), next.formItemId());
    }

    @Test void unsupportedQuestionCannotEnterTheCommittedPrefix() {
        var revision = graph("Advanced", 0);
        var start = launch(revision, false);
        var prefix = branches.resume(start.attempt());
        assertThat(prefix.deliveredFormItems()).containsExactly(start.first());
        assertThat(prefix.formVersion()).isOne();
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var command = start.step(2, 1);
        assertThatThrownBy(() -> branches.advance(command)).isInstanceOf(BranchingException.class).hasMessageContaining("UNSUPPORTED");
        assertThat(branches.resume(start.attempt())).isEqualTo(prefix);
        assertThat(count("attempt_branch_decision", "decision_id", command.decisionId())).isZero();
        assertThat(count("attempt_trace_event", "command_id", command.decisionId())).isZero();
        assertThat(count("attempt_form_item", "form_id", start.admission().formId())).isOne();
        assertThat(count("attempt_accepted_response", "attempt_id", start.attempt())).isOne();

        // The same revision still permits the other participant path.
        var other = launch(revision, false);
        branchResponses.acceptBranch(answer(other, other.first(), 1, false));
        var next = branches.advance(other.step(2, 1));
        branchResponses.acceptBranch(answer(other, next.formItemId(), 2, true));
        assertThat(branches.advance(other.step(3, 2)).outcome()).isEqualTo(TERMINAL_READY);
        assertThat(branchSubmissions.submitBranch(submit(other, 3)).replayed()).isFalse();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void selectedMcqWithInlineOptionImageIsUnsupportedEvenOnRetry(boolean inlineImage) {
        var media = inlineImage ? ",\"media\":{\"assetId\":\"" + UUID.randomUUID() + "\"}" : "";
        var content = """
                {"options":[{"id":"a","text":"A","correct":true%s},{"id":"b","text":"B","correct":false}]}
                """.formatted(media);
        var question = new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.MCQ_SINGLE,
                Difficulty.EASY, "Advanced", content, null, null, null, null, clock.instant(),
                List.of(new QuestionRevision.Option(UUID.randomUUID(), "a", 0), new QuestionRevision.Option(UUID.randomUUID(), "b", 1)));
        var revision = publishGraph(List.of(trueFalseQuestion("Fork", null), question, trueFalseQuestion("Practice", null)), 1);
        var start = launch(revision, false);
        var accepted = branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var prefix = branches.resume(start.attempt());
        var command = start.step(accepted.stateVersion(), prefix.formVersion());

        if (inlineImage) {
            for (int i = 0; i < 2; i++) {
                assertThatThrownBy(() -> branches.advance(command)).isInstanceOfSatisfying(BranchingException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(BranchingException.Reason.UNSUPPORTED));
            }
            assertThat(branches.resume(start.attempt())).isEqualTo(prefix);
            assertThat(count("attempt_form_item", "form_id", start.admission().formId())).isOne();
            assertThat(count("attempt_branch_decision", "decision_id", command.decisionId())).isZero();
            assertThat(count("attempt_trace_event", "command_id", command.decisionId())).isZero();
        } else {
            var result = branches.advance(command);
            assertThat(result.outcome()).isEqualTo(APPENDED);
            assertThat(branches.advance(command)).isEqualTo(result);
            assertThat(branches.resume(start.attempt()).deliveredFormItems()).containsExactly(start.first(), result.formItemId());
        }
    }

    @ParameterizedTest
    @EnumSource(value = QuestionType.class, names = {"MATCHING", "COMPLIANCE"})
    void httpRejectsUnsupportedInlineMediaBeforeCommittingBranchProgress(QuestionType type) throws Exception {
        var revision = publishGraph(List.of(trueFalseQuestion("Fork", null), inlineMediaQuestion(type, true),
                trueFalseQuestion("Practice", null)), 1);
        var start = launch(revision, false);
        var path = "/api/v2/attempts/" + start.attempt();
        var mvc = participantHttp();
        var accepted = http(mvc, command(path + "/responses", UUID.randomUUID(), answerBody(start.first(), 1, true)), 200);
        var before = http(mvc, get(path), 200);
        var request = advanceBody(accepted.path("stateVersion").asLong(), 1);
        var key = UUID.randomUUID();

        assertThat(http(mvc, command(path + "/branch-progress/next", key, request), 422).path("code").asText())
                .isEqualTo("BRANCH_UNSUPPORTED");
        assertThat(http(mvc, command(path + "/branch-progress/next", key, request), 422).path("code").asText())
                .isEqualTo("BRANCH_UNSUPPORTED");
        assertThat(http(mvc, get(path), 200)).isEqualTo(before);
        assertThat(count("attempt_form_item", "form_id", start.admission().formId())).isOne();
        assertThat(count("attempt_form_item_option_order", "form_id", start.admission().formId())).isOne();
        assertThat(count("attempt_branch_decision", "decision_id", key)).isZero();
        assertThat(count("attempt_branch_edge", "decision_id", key)).isZero();
        assertThat(count("attempt_trace_event", "command_id", key)).isZero();
        assertThat(count("attempt_accepted_response", "attempt_id", start.attempt())).isOne();

        var textRevision = publishGraph(List.of(trueFalseQuestion("Fork", null), inlineMediaQuestion(type, false),
                trueFalseQuestion("Practice", null)), 1);
        var textStart = launch(textRevision, false);
        var textPath = "/api/v2/attempts/" + textStart.attempt();
        var textAnswer = http(mvc, command(textPath + "/responses", UUID.randomUUID(), answerBody(textStart.first(), 1, true)), 200);
        var textRequest = advanceBody(textAnswer.path("stateVersion").asLong(), 1);
        var textKey = UUID.randomUUID();
        var next = http(mvc, command(textPath + "/branch-progress/next", textKey, textRequest), 200);
        assertThat(next.path("outcome").asText()).isEqualTo("APPENDED");
        assertThat(http(mvc, command(textPath + "/branch-progress/next", textKey, textRequest), 200)).isEqualTo(next);
        var view = http(mvc, get(textPath), 200);
        assertThat(view.at("/items/1/type").asText()).isEqualTo(type.name());
        assertThat(view.at("/items/1/id")).isEqualTo(next.path("formItemId"));
    }

    private QuestionRevision inlineMediaQuestion(QuestionType type, boolean withImage) {
        String media = withImage ? """
                ,"media":{"assetId":"%s","width":64,"height":32,"mimeType":"image/png"}
                """.formatted(UUID.randomUUID()) : "";
        String content;
        List<String> keys;
        if (type == QuestionType.MATCHING) {
            content = """
                    {"left":[{"id":1,"matchId":3,"text":"A"%s},{"id":2,"matchId":4,"text":"B"}],
                     "right":[{"id":3,"text":"C"},{"id":4,"text":"D"}]}
                    """.formatted(media);
            keys = List.of("left/1", "left/2", "right/3", "right/4");
        } else {
            content = """
                    {"statements":[{"id":1,"text":"A","compliant":true%s},{"id":2,"text":"B","compliant":false}]}
                    """.formatted(media);
            keys = List.of("1", "2");
        }
        var options = new ArrayList<QuestionRevision.Option>();
        for (String key : keys) options.add(new QuestionRevision.Option(UUID.randomUUID(), key, options.size()));
        return new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, type, Difficulty.EASY,
                "Advanced", content, null, null, null, null, clock.instant(), options);
    }

    @Test void mvcTraceRedactsBranchAcknowledgementOnAdvanceAndExactReplay() throws Exception {
        var start = launch(graph(), false);
        var path = "/api/v2/attempts/" + start.attempt();
        var mvc = participantHttp();
        var accepted = http(mvc, command(path + "/responses", UUID.randomUUID(), answerBody(start.first(), 1, true)), 200);
        var request = advanceBody(accepted.path("stateVersion").asLong(), 1);
        var key = UUID.randomUUID();
        var logger = "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor";
        try (var logs = new AttemptSubmissionMySqlIntegrationTest.TransactionLogs(logger)) {
            var advanced = http(mvc, command(path + "/branch-progress/next", key, request), 200);
            assertThat(advanced.path("outcome").asText()).isEqualTo("APPENDED");
            String delivered = advanced.path("formItemId").asText();
            assertThat(delivered).isNotBlank();
            assertThat(http(mvc, command(path + "/branch-progress/next", key, request), 200)).isEqualTo(advanced);

            var writes = logs.events.list.stream().filter(event -> event.getLoggerName().equals(logger))
                    .map(event -> event.getFormattedMessage())
                    .filter(message -> message.startsWith("Writing [BranchAdvanceAcknowledgement")).toList();
            assertThat(writes).hasSize(2).allSatisfy(message -> assertThat(message).doesNotContain(delivered, "formItemId"));
        }
    }

    @Test void unsupportedEntryQuestionRollsBackAdmission() {
        var revision = graph("Fork", 1);
        assertThatThrownBy(() -> launch(revision, false)).isInstanceOf(BranchingException.class).hasMessageContaining("UNSUPPORTED");
        assertThat(count("attempt_form", "quiz_revision_id", revision.id())).isZero();
        assertThat(count("attempt_branch_path", "quiz_revision_id", revision.id())).isZero();
        assertThat(count("attempt_branch_decision", "quiz_revision_id", revision.id())).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1205, 1213})
    void lostCommitAcknowledgementAndFailedReconciliationRequireTheOriginalDecisionId(int sqlCode) {
        var start = launch(graph(), false);
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var command = start.step(2, 1);
        var reads = new AtomicInteger();
        observer().afterSql = (query, connection) -> {
            if (query.contains("SELECT d.*,i.position") && reads.incrementAndGet() == 2)
                throw new SQLException("synthetic receipt read failure", "HY000", sqlCode);
        };
        observer().failAfterCommit.set(true);
        assertThatThrownBy(() -> branches.advance(command)).isInstanceOf(BranchingException.class).hasMessageContaining("UNKNOWN_OUTCOME");
        assertThat(reads).hasValue(2);
        observer().reset();
        var path = branches.resume(start.attempt());
        assertThat(path.deliveredFormItems()).hasSize(2);
        var replay = restarted().advance(command);
        assertThat(replay.outcome()).isEqualTo(APPENDED);
        assertThat(replay.formItemId()).isEqualTo(path.deliveredFormItems().get(1));
        assertThat(replay.formVersion()).isEqualTo(path.formVersion());
        assertThat(count("attempt_trace_event", "command_id", command.decisionId())).isOne();
        assertThat(count("attempt_branch_decision", "decision_id", command.decisionId())).isOne();
    }

    @Test void reconciliationRequiresCurrentParticipantAuthority() {
        var start = launch(graph(), false);
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var command = start.step(2, 1);
        observer().afterSql = (query, connection) -> {
            if (query.contains("UPDATE attempt_branch_path")) SecurityContextHolder.clearContext();
        };
        observer().failAfterCommit.set(true);
        assertThatThrownBy(() -> branches.advance(command)).isInstanceOf(BranchingException.class).hasMessageContaining("DENIED");
        observer().reset();
        authenticate(username);
        assertThat(branches.advance(command).outcome()).isEqualTo(APPENDED);
        assertThat(count("attempt_branch_decision", "decision_id", command.decisionId())).isOne();
    }

    @ParameterizedTest @CsvSource({"0,UNAVAILABLE", "1205,BUSY", "1213,RETRYABLE"})
    void gradingPathReadRedactsDriverDiagnosticsAndPreservesConflictClassification(int code, AutomaticResultException.Reason reason) {
        var start = launch(graph(), true);
        ((AttemptSubmissionMySqlIntegrationTest.AdjustableClock)clock).now = clock.instant().plusSeconds(1800);
        var submission = expiry.expire(start.attempt()).acknowledgement().resultId();
        var results = context.getBean(AutomaticResultService.class);
        var reached = new AtomicBoolean();
        observer().afterSql = (query, connection) -> {
            if (query.contains("SELECT p.current_node_id") && reached.compareAndSet(false, true)) {
                var failure = new SQLException("private-path-driver", "HY000", code, new IllegalStateException("private-path-cause"));
                failure.addSuppressed(new IllegalStateException("private-path-suppressed"));
                throw failure;
            }
        };
        try (var logs = new AttemptSubmissionMySqlIntegrationTest.TransactionLogs("org.springframework.transaction")) {
            assertThatThrownBy(() -> results.finalizeSubmission(submission)).isInstanceOfSatisfying(AutomaticResultException.class,
                    failure -> assertThat(failure.reason()).isEqualTo(reason));
            assertThat(reached).isTrue();
            assertThat(logs.rendered()).contains("SubmittedAttemptSourceImpl.lock")
                    .doesNotContain("private-path-driver", "private-path-cause", "private-path-suppressed");
        } finally { observer().reset(); }
        assertThat(count("attempt_automatic_result", "submission_id", submission)).isZero();
        var grade = results.finalizeSubmission(submission);
        assertThat(grade.items()).extracting(i -> i.formItemId()).containsExactly(start.first());
        assertThat(results.finalizeSubmission(submission).id()).isEqualTo(grade.id());
        assertThat(count("attempt_automatic_result", "submission_id", submission)).isOne();
    }

    private BranchingAttemptService restarted() {
        return context.getAutowireCapableBeanFactory().createBean(BranchingAttemptServiceImpl.class);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentCommandsSerializeAndExactReplaySurvivesRestart(boolean identical) throws Exception {
        var revision = graph();
        var start = launch(revision, false);
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var first = start.step(2, 1);
        var other = identical ? first : start.step(2, 1);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> race(first, ready, go));
            var b = workers.submit(() -> race(other, ready, go));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var x = a.get(15, TimeUnit.SECONDS);
            var y = b.get(15, TimeUnit.SECONDS);
            if (identical) assertThat(x).isEqualTo(y).isInstanceOf(BranchingAttemptService.Result.class);
            else assertThat(List.of(x, y)).filteredOn(o -> o == BranchingException.Reason.STALE).hasSize(1);
            var won = x instanceof BranchingAttemptService.Result ? first : other;
            var result = (BranchingAttemptService.Result)(x instanceof BranchingAttemptService.Result ? x : y);
            var fresh = restarted();
            observer().reset();
            assertThat(fresh.advance(won)).isEqualTo(result);
            assertThat(fresh.resume(start.attempt()).deliveredFormItems()).containsExactly(start.first(), result.formItemId());
            assertThat(observer().selects).noneMatch(q -> q.contains("normalized_response") || q.contains("condition_payload"));
            assertThat(count("attempt_branch_decision","form_id", start.admission().formId())).isEqualTo(2);
            var changed = new BranchingAttemptService.Command(start.attempt(), won.decisionId(), 3, 2);
            assertThatThrownBy(() -> fresh.advance(changed)).isInstanceOf(BranchingException.class).hasMessageContaining("CONFLICT");
        } finally { go.countDown(); workers.shutdownNow(); }
    }
    private Object race(BranchingAttemptService.Command command, CountDownLatch ready, CountDownLatch go) throws Exception {
        authenticate(username);
        try {
            ready.countDown();
            assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
            return branches.advance(command);
        } catch (BranchingException e) { return e.reason(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    @ParameterizedTest @ValueSource(strings = {"INSERT INTO attempt_branch_decision", "INSERT INTO attempt_branch_edge",
            "UPDATE attempt_branch_path SET"})
    void failedWritesLeaveOriginalPathAndOnlyOneRetryCanAppend(String checkpoint) {
        var start = launch(graph(), false);
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var command = start.step(2, 1);
        var hit = new java.util.concurrent.atomic.AtomicBoolean();
        observer().afterSql = (q, c) -> {
            if (q.contains(checkpoint) && hit.compareAndSet(false, true)) throw new SQLException("synthetic fault");
        };
        try {
            assertThatThrownBy(() -> branches.advance(command)).isInstanceOf(BranchingException.class).hasMessageContaining("UNAVAILABLE");
        } finally { observer().reset(); }
        assertThat(hit).isTrue();
        assertThat(count("attempt_trace_event","command_id", command.decisionId())).isZero();
        assertThat(count("attempt_branch_decision","form_id", start.admission().formId())).isOne();
        assertThat(count("attempt_form_item_option_order","form_id", start.admission().formId())).isOne();
        assertThat(branches.resume(start.attempt()).deliveredFormItems()).containsExactly(start.first());
        assertThat(branches.advance(command).formVersion()).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownAcknowledgementIsResolvedOnlyFromTheDurableReceipt(boolean committed) {
        var start = launch(graph(), false);
        branchResponses.acceptBranch(answer(start, start.first(), 1, false));
        var command = start.step(2, 1);
        (committed ? observer().failAfterCommit : observer().failBeforeCommit).set(true);
        if (committed) assertThat(branches.advance(command).outcome()).isEqualTo(APPENDED);
        else {
            assertThatThrownBy(() -> branches.advance(command)).isInstanceOf(BranchingException.class).hasMessageContaining("UNKNOWN_OUTCOME");
            assertThat(branches.resume(start.attempt()).formVersion()).isOne();
        }
        var winner = restarted().advance(command);
        assertThat(branches.advance(command)).isEqualTo(winner);
        assertThat(count("attempt_trace_event","command_id", command.decisionId())).isOne();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void anotherParticipantCanAdvanceBeforeTheFirstCallerRollsBack(boolean sameRevision) {
        var revision = graph();
        var first = launch(revision, false);
        branchResponses.acceptBranch(answer(first, first.first(), 1, true));
        var peerName = "editor-" + UUID.randomUUID().toString().substring(0, 10);
        grant(user(peerName), "ATTEMPT_READ", "ATTEMPT_CREATE", "QUESTION_UPDATE", "QUIZ_MODERATE");
        if (!sameRevision) quizId = newQuiz();
        var peerRevision = sameRevision ? revision : graph();
        authenticate(peerName);
        var peer = launch(peerRevision, false);
        branchResponses.acceptBranch(answer(peer, peer.first(), 1, false));
        authenticate(username);
        var command = first.step(2, 1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                assertThat(branches.advance(command).formVersion()).isEqualTo(2);
                var independent = worker.submit(() -> {
                    authenticate(peerName);
                    try { return branches.advance(peer.step(2, 1)); }
                    finally { SecurityContextHolder.clearContext(); }
                });
                try { assertThat(independent.get(8, TimeUnit.SECONDS).outcome()).isEqualTo(APPENDED); }
                catch (Exception failed) { throw new AssertionError(failed); }
                status.setRollbackOnly();
            });
        } finally { worker.shutdownNow(); }
        assertThat(branches.resume(first.attempt()).deliveredFormItems()).containsExactly(first.first());
        authenticate(peerName);
        assertThat(branches.resume(peer.attempt()).formVersion()).isEqualTo(2);
        authenticate(username);
        assertThat(count("attempt_trace_event","command_id", command.decisionId())).isZero();
        var rr = new TransactionTemplate(manager);
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        rr.executeWithoutResult(s -> {
            observer().reset();
            assertThatThrownBy(() -> branches.advance(command)).isInstanceOf(AttemptTransactionException.class);
            assertThat(observer().selects).isEmpty();
        });
    }

    @Test void onlyTheBoundSubjectWithCurrentPermissionCanReadReplayOrAdvance() {
        var start = launch(graph(), false);
        var receipt = new BranchingAttemptService.Command(start.attempt(), start.admission().launchCommandId(), 1, 0);
        // Existing participant/graded endpoints invoke only their ordinary fixed-form services.
        assertThatThrownBy(() -> context.getBean(AttemptDeliveryService.class).read(start.attempt())).hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> responses.accept(answer(start, start.first(), 1, true))).hasMessageContaining("NOT_FOUND");
        assertThatThrownBy(() -> submissions.submit(submit(start, 1))).hasMessageContaining("NOT_FOUND");
        var name = "peer-" + UUID.randomUUID().toString().substring(0, 10);
        grant(user(name), "ATTEMPT_READ", "ATTEMPT_CREATE");
        authenticate(name);
        assertThatThrownBy(() -> branches.resume(start.attempt())).isInstanceOf(BranchingException.class).hasMessageContaining("DENIED");
        assertThatThrownBy(() -> branches.advance(receipt)).isInstanceOf(BranchingException.class).hasMessageContaining("DENIED");
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> branches.advance(receipt)).isInstanceOf(BranchingException.class).hasMessageContaining("DENIED");
        authenticate(username);
        tx.executeWithoutResult(s -> sql.update("DELETE ur FROM user_roles ur WHERE ur.user_id=UUID_TO_BIN(?)", ownerId.toString()));
        assertThatThrownBy(() -> branches.advance(receipt)).isInstanceOf(BranchingException.class).hasMessageContaining("DENIED");
        assertThatThrownBy(() -> branches.resume(start.attempt())).isInstanceOf(BranchingException.class).hasMessageContaining("DENIED");
        assertThat(count("attempt_branch_decision","form_id", start.admission().formId())).isOne();
    }

    @ParameterizedTest @ValueSource(strings = {"wrongStart", "wrongTarget", "reversed", "gap"})
    void resumeAndExactReplayRejectBrokenEdgeProvenance(String damage) {
        var revision = graph(null, 2);
        var start = launch(revision, false);
        var g = revision.scenarioGraph();
        var firstEdge = g.edges().stream().filter(e -> e.fromNodeId().equals(g.entryNodeId())).findFirst().orElseThrow();
        var secondEdge = g.edges().stream().filter(e -> e.fromNodeId().equals(firstEdge.toNodeId())).findFirst().orElseThrow();
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var command = damage.equals("wrongTarget") ? start.step(2, 1)
                : new BranchingAttemptService.Command(start.attempt(), start.admission().launchCommandId(), 1, 0);
        var committed = branches.advance(command);
        var form = start.admission().formId().toString();
        var decision = command.decisionId().toString();
        assertThat(branches.resume(start.attempt()).deliveredFormItems()).contains(committed.formItemId());
        tx.executeWithoutResult(status -> {
            if (damage.equals("wrongTarget")) {
                var alternate = g.edges().stream().filter(e -> e.fromNodeId().equals(secondEdge.toNodeId())
                        && e.type() == ScenarioGraph.EdgeType.DEFAULT).findFirst().orElseThrow();
                replaceEdge(form, decision, revision.id(), 0, 0, alternate.id());
            } else if (damage.equals("gap")) {
                replaceEdge(form, decision, revision.id(), 1, 2, secondEdge.id());
            } else {
                replaceEdge(form, decision, revision.id(), 0, 0, secondEdge.id());
                if (damage.equals("reversed")) replaceEdge(form, decision, revision.id(), 1, 1, firstEdge.id());
            }
        });
        assertThatThrownBy(() -> branches.resume(start.attempt())).isInstanceOf(BranchingException.class).hasMessageContaining("CORRUPT");
        assertThatThrownBy(() -> restarted().advance(command)).isInstanceOf(BranchingException.class).hasMessageContaining("CORRUPT");
        assertThat(sql.queryForObject("""
                SELECT COUNT(*) FROM attempt_trace_event WHERE command_id=UUID_TO_BIN(?) AND event_type='BRANCH_DECIDED'
                """, Integer.class, decision)).isOne();
    }

    private void replaceEdge(String form, String decision, UUID revision, int position, int newPosition, UUID edge) {
        assertThat(sql.update("DELETE FROM attempt_branch_edge WHERE form_id=UUID_TO_BIN(?) AND decision_id=UUID_TO_BIN(?) AND edge_position=?",
                form, decision, position)).isOne();
        sql.update("""
                INSERT INTO attempt_branch_edge(form_id,decision_id,quiz_revision_id,edge_position,edge_id)
                VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,UUID_TO_BIN(?))
                """, form, decision, revision.toString(), newPosition, edge.toString());
    }

    @Test void resumeAndReplayRejectDamagedPathWithoutReturningAnyAlternate() {
        var start = launch(graph(), false);
        sql.update("UPDATE attempt_branch_path SET terminal_ready=TRUE WHERE attempt_id=UUID_TO_BIN(?)", start.attempt().toString());
        assertThatThrownBy(() -> branches.resume(start.attempt())).isInstanceOf(BranchingException.class).hasMessageContaining("CORRUPT");
        assertThatThrownBy(() -> branches.advance(start.step(1, 1))).isInstanceOf(BranchingException.class).hasMessageContaining("CORRUPT");
        assertThat(count("attempt_branch_decision","form_id", start.admission().formId())).isOne();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void terminalAdvanceSubmitAndDeadlineWorkerConvergeOnOneFinalization(boolean voluntary) throws Exception {
        var start = launch(graph(), true);
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var next = branches.advance(start.step(2, 1));
        branchResponses.acceptBranch(answer(start, next.formItemId(), 2, false));
        var finish = start.step(3, 2);
        var submit = submit(start, 3);
        var worker = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                assertThat(branches.advance(finish).outcome()).isEqualTo(TERMINAL_READY);
                if (voluntary) assertThat(branchSubmissions.submitBranch(submit).replayed()).isFalse();
                ((AttemptSubmissionMySqlIntegrationTest.AdjustableClock)clock).now = clock.instant().plusSeconds(1800);
                try {
                    assertThat(worker.submit(() -> expiry.expire(start.attempt())).get(8, TimeUnit.SECONDS).outcome())
                            .isEqualTo(AttemptExpiryService.Outcome.BUSY);
                } catch (Exception failed) { throw new AssertionError(failed); }
            });
            if (voluntary) {
                assertThat(branchSubmissions.submitBranch(submit).replayed()).isTrue();
                assertThat(expiry.expire(start.attempt()).outcome()).isEqualTo(AttemptExpiryService.Outcome.ALREADY_TERMINAL);
            } else {
                assertThatThrownBy(() -> branchSubmissions.submitBranch(submit)).hasMessageContaining("INACTIVE");
                assertThat(expiry.expire(start.attempt()).outcome()).isEqualTo(AttemptExpiryService.Outcome.EXPIRED);
                assertThat(expiry.expire(start.attempt()).outcome()).isEqualTo(AttemptExpiryService.Outcome.REPLAYED);
            }
            assertThat(branches.advance(finish).outcome()).isEqualTo(TERMINAL_READY);
            assertThat(count("attempt_submission","attempt_id", start.attempt())).isOne();
            assertThat(count("attempt_finalization_intent","attempt_id", start.attempt())).isOne();
            assertThat(count("attempt_accepted_response","attempt_id", start.attempt())).isEqualTo(2);
        } finally { worker.shutdownNow(); }
    }

    @Test void expiryAtUnansweredForkPreservesTheDeliveredPrefixAndNeverChoosesABranch() {
        var start = launch(graph(), true);
        ((AttemptSubmissionMySqlIntegrationTest.AdjustableClock)clock).now = clock.instant().plusSeconds(1800);
        assertThatThrownBy(() -> branches.advance(start.step(1, 1))).hasMessageContaining("INACTIVE");
        observer().reset();
        var expired = expiry.expire(start.attempt());
        assertThat(observer().selects).filteredOn(q -> q.contains("SELECT p.current_node_id")).hasSize(1);
        assertThat(expired.outcome()).isEqualTo(AttemptExpiryService.Outcome.EXPIRED);
        var grade = context.getBean(AutomaticResultService.class).finalizeSubmission(expired.acknowledgement().resultId());
        assertThat(grade.items()).extracting(i -> i.formItemId()).containsExactly(start.first());
        assertThat(count("attempt_accepted_response","attempt_id", start.attempt())).isZero();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void restoredStructurallySealedGraphNeedsExecutableRulesBeforeAdmission(boolean unknownVersion) {
        var published = graph();
        var original = published.id().toString();
        var old = UUID.randomUUID();
        // Reproduce a retained #572 graph using the real database seals, bypassing only the later #573 publisher.
        tx.executeWithoutResult(status -> {
            sql.update("""
                    INSERT INTO quiz_revisions(id,quiz_id,owner_id,schema_version,title,created_at)
                    SELECT UUID_TO_BIN(?),quiz_id,owner_id,4,title,created_at FROM quiz_revisions WHERE id=UUID_TO_BIN(?)
                    """, old.toString(), original);
            sql.update("""
                    INSERT INTO quiz_scenario_graphs(quiz_revision_id,schema_version,item_count,node_count,edge_count)
                    SELECT UUID_TO_BIN(?),schema_version,item_count,node_count,edge_count-?
                    FROM quiz_scenario_graphs WHERE quiz_revision_id=UUID_TO_BIN(?)
                    """, old.toString(), unknownVersion ? 0 : 1, original);
            sql.update("""
                    INSERT INTO quiz_revision_items(id,quiz_revision_id,question_revision_id,authored_position,scenario_content_evidence)
                    SELECT id,UUID_TO_BIN(?),question_revision_id,authored_position,scenario_content_evidence
                    FROM quiz_revision_items WHERE quiz_revision_id=UUID_TO_BIN(?)
                    """, old.toString(), original);
            sql.update("""
                    INSERT INTO quiz_scenario_nodes(quiz_revision_id,id,authored_position,node_type,item_id)
                    SELECT UUID_TO_BIN(?),id,authored_position,node_type,item_id FROM quiz_scenario_nodes WHERE quiz_revision_id=UUID_TO_BIN(?)
                    """, old.toString(), original);
            sql.update("""
                    INSERT INTO quiz_scenario_edges(quiz_revision_id,id,from_node_id,to_node_id,authored_position,edge_type,condition_version,condition_payload)
                    SELECT UUID_TO_BIN(?),id,from_node_id,to_node_id,authored_position,edge_type,
                           IF(edge_type='CONDITIONAL' AND ?,2,condition_version),condition_payload
                    FROM quiz_scenario_edges WHERE quiz_revision_id=UUID_TO_BIN(?) AND (? OR from_node_id<>UUID_TO_BIN(?))
                    """, old.toString(), unknownVersion, original, unknownVersion, published.scenarioGraph().entryNodeId().toString());
            sql.update("UPDATE quiz_scenario_graphs SET entry_node_id=UUID_TO_BIN(?) WHERE quiz_revision_id=UUID_TO_BIN(?)",
                    published.scenarioGraph().entryNodeId().toString(), old.toString());
            sql.update("INSERT INTO quiz_scenario_publication_keys(quiz_revision_id,published_revision_id) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?))",
                    old.toString(), old.toString());
        });
        var revision = context.getBean(uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository.class)
                .findById(ownerId, old).orElseThrow();
        assertThatThrownBy(() -> launch(revision, false)).isInstanceOf(BranchingException.class).hasMessageContaining("CORRUPT");
        assertThat(count("attempt_branch_path","quiz_revision_id", old)).isZero();
        assertThat(count("attempt_form","quiz_revision_id", old)).isZero();
        assertThat(count("attempt_policy_snapshot","quiz_revision_id", old)).isZero();
    }

    @Test void queryCountForResumeIsIndependentOfDeliveredItemCountAndReadsOnlyMetadata(TestReporter reporter) {
        var revision = graph();
        var shortPath = launch(revision, false);
        var longer = launch(revision, false);
        branchResponses.acceptBranch(answer(longer, longer.first(), 1, true));
        branches.advance(longer.step(2, 1));
        observer().reset();
        assertThat(branches.resume(shortPath.attempt()).deliveredFormItems()).hasSize(1);
        int first = observer().selects.size();
        assertThat(first).isPositive();
        observer().reset();
        assertThat(branches.resume(longer.attempt()).deliveredFormItems()).hasSize(2);
        assertThat(observer().selects).hasSize(first).noneMatch(q -> q.contains("normalized_response")
                || q.contains("question_text") || q.contains("condition_payload"));
        reporter.publishEntry("resume SELECTs for one and two delivered questions", Integer.toString(first));
    }

    @Test void admissionPinsOneOfTwoSealedRevisionsOnTheSameQuiz() {
        var first = graph();
        var second = graph();
        var admitted = launch(second, false);
        assertThat(count("attempt_branch_path","quiz_revision_id", first.id())).isZero();
        assertThat(count("attempt_branch_path","quiz_revision_id", second.id())).isOne();
        assertThat(count("quiz_publication_heads","quiz_id", quizId)).isZero();
        var forkItem = sql.queryForObject("SELECT BIN_TO_UUID(source_item_id) FROM attempt_form_item WHERE id=UUID_TO_BIN(?)",
                String.class, admitted.first().toString());
        assertThat(forkItem).isEqualTo(second.items().get(0).id().toString());
        var start = new AttemptStartService.Command(1, quizId, new ContentPolicy(second.id(), 4, FormMode.PROGRESSIVE,
                SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED), 1, CommandReceipt.Key.from(UUID.randomUUID().toString()),
                BuiltInPracticePreset.REFERENCE, uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest.LaunchContext.ORDINARY,
                "ALL_AT_ONCE", AnswerOrder.AUTHORED);
        assertThatThrownBy(() -> context.getBean(AttemptStartService.class).start(start)).hasMessageContaining("UNSUPPORTED_CAPABILITY");
        assertThat(count("attempt_branch_path","quiz_revision_id", second.id())).isOne();
    }

    @Test void answerAcceptanceRedactsNormalizedInputFromJdbcDiagnostics() {
        var start = launch(graph(), false);
        try (var logs = new AttemptSubmissionMySqlIntegrationTest.TransactionLogs("org.springframework.jdbc.core.StatementCreatorUtils")) {
            branchResponses.acceptBranch(answer(start, start.first(), 1, true));
            assertThat(logs.rendered()).contains("[private SQL text]").doesNotContain("{\"answer\":true}");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentResumeObservesTheOwnersCommittedPrefix(boolean commit) throws Exception {
        var start = launch(graph(), false);
        branchResponses.acceptBranch(answer(start, start.first(), 1, true));
        var reachedRead = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var reader = tx.execute(status -> {
                var appended = branches.advance(start.step(2, 1));
                assertThat(appended.formVersion()).isEqualTo(2);
                observer().beforeSql = (query, connection) -> {
                    if (query.contains("FROM attempt_lifecycle_state l FORCE INDEX (PRIMARY)")) reachedRead.countDown();
                };
                var pending = worker.submit(() -> {
                    authenticate(username);
                    try { return branches.resume(start.attempt()); }
                    finally { SecurityContextHolder.clearContext(); }
                });
                try { assertThat(reachedRead.await(10, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                assertThat(pending.isDone()).isFalse();
                if (!commit) status.setRollbackOnly();
                return pending;
            });
            var path = reader.get(10, TimeUnit.SECONDS);
            assertThat(path.formVersion()).isEqualTo(commit ? 2 : 1);
            assertThat(path.deliveredFormItems()).isEqualTo(branches.resume(start.attempt()).deliveredFormItems());
        } finally { observer().reset(); worker.shutdownNow(); }
    }
}
