package uk.gegc.quizmaker.features.attempt.submission;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.SQLException;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
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
import uk.gegc.quizmaker.features.question.application.contract.v1.BaselineQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.quiz.application.QuizScenarioRevisionService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizScenarioRevisionServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.result.application.AutomaticResultService;
import static uk.gegc.quizmaker.features.attempt.application.BranchingAttemptService.Outcome.*;
import static org.assertj.core.api.Assertions.*;

@Tag("db-serial")
@DataJpaTest(showSql = false)
@ImportAutoConfiguration(ValidationAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({AttemptSubmissionMySqlIntegrationTest.Config.class, QuizScenarioRevisionServiceImpl.class, BranchingAttemptServiceImpl.class})
class BranchingAttemptMySqlIntegrationTest {
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
        var nodes = new ArrayList<ScenarioGraph.Node>();
        var items = new ArrayList<QuizRevision.Item>();
        for (String text : List.of("Fork", "Advanced", "Practice")) {
            var question = new QuestionRevision(UUID.randomUUID(), UUID.randomUUID(), 1, QuestionType.TRUE_FALSE,
                    Difficulty.EASY, text, "{\"answer\":true}", null, "private explanation", null, null, clock.instant(), List.of());
            var item = new QuizRevision.Item(UUID.randomUUID(), items.size(), question);
            items.add(item);
            nodes.add(new ScenarioGraph.Node(UUID.randomUUID(), nodes.size(), ScenarioGraph.NodeType.ITEM, item.id()));
        }
        var fork = nodes.get(0).id();
        var terminal = UUID.randomUUID();
        var entry = UUID.randomUUID();
        nodes.add(new ScenarioGraph.Node(terminal, 3, ScenarioGraph.NodeType.TERMINAL, null));
        nodes.add(new ScenarioGraph.Node(entry, 4, ScenarioGraph.NodeType.ROUTING, null));
        var edges = List.of(edge(entry, fork), new ScenarioGraph.Edge(UUID.randomUUID(), fork, nodes.get(1).id(), 1,
                ScenarioGraph.EdgeType.CONDITIONAL, 1, "{\"operator\":\"SCORE_IS\",\"value\":\"FULL_CREDIT\"}"),
                edge(fork, nodes.get(2).id()), edge(nodes.get(1).id(), terminal), edge(nodes.get(2).id(), terminal));
        var graph = new ScenarioGraph(1, entry, nodes, edges);
        var revision = publisher.publish(ownerId, new QuizRevision(UUID.randomUUID(), quizId, ownerId, 4,
                "Scenario", null, clock.instant(), items, List.of(), List.of(), graph));
        contracts.bind(ownerId, revision.id(), items.stream().map(i -> BaselineQuestionContracts.binding(i.question())).toList());
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
        var expired = expiry.expire(start.attempt());
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
