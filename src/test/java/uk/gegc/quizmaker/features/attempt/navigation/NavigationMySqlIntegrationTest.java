package uk.gegc.quizmaker.features.attempt.navigation;

import uk.gegc.quizmaker.features.attempt.domain.model.navigation.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.NavigationRepository;
import uk.gegc.quizmaker.features.attempt.api.*;
import uk.gegc.quizmaker.features.attempt.api.dto.ParticipantAttemptView;

import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.infra.repository.*;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.media.application.SavedImageStorage;
import uk.gegc.quizmaker.features.media.application.impl.RetainedImageServiceImpl;
import uk.gegc.quizmaker.features.media.config.MediaStorageProperties;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageVersion;
import uk.gegc.quizmaker.features.question.application.*;
import uk.gegc.quizmaker.features.question.application.impl.*;
import uk.gegc.quizmaker.features.question.config.BaselineQuestionContractConfig;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.*;
import uk.gegc.quizmaker.features.question.infra.repository.JdbcQuestionContractBindingRepository;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizPracticeSourceServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizRevisionPublicationServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizPublication.Command;
import uk.gegc.quizmaker.features.quiz.domain.repository.*;
import uk.gegc.quizmaker.features.quiz.infra.publication.PublicationContent;
import uk.gegc.quizmaker.features.quiz.infra.repository.*;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import uk.gegc.quizmaker.features.attempt.domain.model.draft.*;
import uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraft.Reference;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractVersion;
import static uk.gegc.quizmaker.features.attempt.domain.model.draft.AnswerDraftException.Reason.*;
import static org.assertj.core.api.Assertions.*;

/** Real authorized publication/start, commands, receipts, storage, acceptance and submission; only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true", "quizmaker.attempts.participant-view-enabled=true", "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({NavigationServiceImpl.class, ParticipantAttemptViewServiceImpl.class, JdbcAttemptViewRouteRepository.class,
        InitialParticipantViewDisclosureReader.class, InitialParticipantViewMediaReader.class,
        uk.gegc.quizmaker.features.result.application.impl.ParticipantResultServiceImpl.class,
        uk.gegc.quizmaker.features.result.application.impl.ResultDisclosureServiceImpl.class,
        uk.gegc.quizmaker.features.result.infra.repository.JdbcAutomaticResultRepository.class,
        uk.gegc.quizmaker.features.result.infra.repository.JdbcResultReleaseRepository.class,
        SubmittedAttemptSourceImpl.class, JdbcSubmittedAttemptRepository.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcNavigationRepository.class, QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, PublicationContent.class, RetainedImageServiceImpl.class,
        AppPermissionEvaluator.class, QuestionHandlerFactory.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        QuestionContractRegistryImpl.class, QuestionRevisionContractServiceImpl.class, JdbcQuestionContractBindingRepository.class,
        PublishedQuestionContractServiceImpl.class, BaselineQuestionContractConfig.class,
        FixedFormMaterializerImpl.class, FormOptionOrderServiceImpl.class,
        JdbcFixedFormRepository.class, JdbcFormOptionOrderRepository.class,
        ResponseAcceptanceServiceImpl.class, JdbcAcceptedResponseRepository.class,
        AnswerDraftStoreImpl.class, JdbcAnswerDraftRepository.class, DraftCommandServiceImpl.class, JdbcDraftCommandResultRepository.class,
        AttemptSubmissionServiceImpl.class, AttemptTerminalSubmissionImpl.class, AcceptedSubmissionResponseFinalizer.class, JdbcAttemptSubmissionRepository.class,
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        NavigationMySqlIntegrationTest.Config.class})
class NavigationMySqlIntegrationTest {
    static final String SCHEMA = "flat_navigation_" + UUID.randomUUID().toString().replace("-", "");
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

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaStorageProperties mediaProperties() { return new MediaStorageProperties(); }
        @Bean SavedImageStorage storage() {
            return new SavedImageStorage() {
                public Source inspect(String key) { throw new AssertionError("Text fixture must not call storage"); }
                public void retain(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
                public void verify(SavedImageVersion v) { throw new AssertionError("Text fixture must not call storage"); }
            };
        }
        @Bean static BeanPostProcessor measuredSql() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof NavigationDataSource) ? new NavigationDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired NavigationService navigation;
    @Autowired ParticipantAttemptViewService views;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AttemptService legacy;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired AnswerDraftStore drafts;
    @Autowired DraftCommandService commands;
    @Autowired AttemptSubmissionService submissions;
    @Autowired AttemptAccessService access;
    @Autowired ResponseAcceptanceService responses;
    @Autowired AttemptDeliveryService delivery;
    @Autowired org.springframework.context.ApplicationContext context;
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
    @Autowired PublishedQuestionContractService binding;
    @Autowired QuizRevisionPublicationService publications;
    @Autowired QuizRepository quizzes;
    @Autowired QuestionRepository questions;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired JdbcTemplate sql;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired AttemptStartService starts;
    @Autowired AttemptLifecycleService lifecycle;
    @Autowired SimpleMeterRegistry metrics;
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    static final List<String> TARGET_TABLES = List.of("attempt_navigation_result", "attempt_navigation", "attempt_draft_command_result", "attempt_answer_draft", "attempt_result_release", "attempt_result_created", "attempt_automatic_grade", "attempt_automatic_result", "attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form", "attempt_policy_snapshot");
    NavigationDataSource observer() { return (NavigationDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
        username = "acceptance-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username, true);
        grant(ownerId, "ATTEMPT_READ", "ATTEMPT_CREATE");
        quizId = newQuiz();
        authenticate(username);
    }

    @AfterEach void cleanup() {
        observer().reset(); SecurityContextHolder.clearContext();
        tx.executeWithoutResult(s -> {
            TARGET_TABLES.forEach(table -> sql.update("DELETE FROM " + table));
            for (String table : List.of("question_revision_contract_bindings", "quiz_publication_receipts", "quiz_publication_heads",
                    "quiz_revision_images", "quiz_publications", "quiz_revision_items", "question_option_revisions", "question_revisions",
                    "quiz_revisions", "quiz_questions", "question_tags", "question_source_confirmations", "questions", "quizzes",
                    "categories", "user_roles", "users")) sql.update("DELETE FROM " + table);
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'acceptance-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'acceptance-%'");
        });
    }



    record Fixture(AttemptStartService.Result start, List<ParticipantForm.Item> items) {
        UUID id(int n) { return items.get(n).id(); }
    }
    private Fixture fixture(NavigationPolicy policy, int count) { return fixture(policy, count, QuestionType.TRUE_FALSE); }
    private Fixture fixture(NavigationPolicy policy, int count, QuestionType type) {
        var revision = publish(0, IntStream.range(0, count).mapToObj(i -> draft(type)).toList());
        binding.bind(quizId, revision);
        var started = starts.start(new AttemptStartService.Command(1, quizId, content(revision), 1,
                CommandReceipt.Key.from("start-" + quizId), BuiltInPracticePreset.REFERENCE,
                LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", AnswerOrder.AUTHORED, true, policy));
        return new Fixture(started, delivery.read(started.attemptId()).form().items());
    }
    private Fixture sequential(boolean skip, boolean revisit) {
        return fixture(new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, skip, revisit)), 3);
    }
    private NavigationService.Command move(Fixture f, int item, long state, Long position, String key) {
        return new NavigationService.Command(f.start().attemptId(), f.id(item), state, position, CommandReceipt.Key.from(key));
    }
    private ResponseAcceptanceService.Result answer(Fixture f, int item, long version, String key) {
        var input = json.createObjectNode(); var value = f.items().get(item);
        if (value.type() == QuestionType.TRUE_FALSE) input.put("answer", true);
        else input.put("selectedOptionId", ((ParticipantContent.Choices) value.content()).options().get(0).id().toString());
        return responses.accept(new ResponseAcceptanceService.Command(f.start().attemptId(), f.id(item), version,
                CommandReceipt.Key.from(key), value.type(), input));
    }
    private NavigationService restarted() {
        var jdbc = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source);
        return new NavigationServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), access,
                context.getBean(AttemptReadFactsService.class), lifecycle, context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository.class),
                new JdbcNavigationRepository(jdbc), new CommandReceiptServiceImpl(new JdbcCommandReceiptRepository(jdbc), manager,
                context.getBean(Clock.class), metrics), context.getBean(ExecutionTraceService.class), manager, metrics);
    }
    private ParticipantAttemptView view(Fixture f) { return (ParticipantAttemptView) views.read(f.start().attemptId()); }
    private long count(String table) { return sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }
    private Map<String, List<String>> snapshot() {
        return tx.execute(s -> {
            var result = new TreeMap<String, List<String>>();
            for (String table : TARGET_TABLES) result.put(table, sql.queryForList("SELECT * FROM " + table).stream().map(row -> {
                var ordered = new TreeMap<String, String>();
                row.forEach((k, v) -> ordered.put(k, v instanceof byte[] b ? HexFormat.of().formatHex(b) : String.valueOf(v)));
                return ordered.toString();
            }).sorted().toList());
            return result;
        });
    }
    private static void rejected(java.util.concurrent.Callable<?> action, NavigationException.Reason reason) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(NavigationException.class,
                e -> assertThat(e.reason()).isEqualTo(reason)).hasNoCause();
    }
    private void revoke(String permission) {
        tx.executeWithoutResult(s -> sql.update("""
                DELETE rp FROM role_permissions rp JOIN user_roles ur ON rp.role_id=ur.role_id
                JOIN permissions p ON rp.permission_id=p.permission_id
                WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name=?
                """, ownerId.toString(), permission));
    }

    @Test void realHttpNavigationHasAnAnswerRequiredDenialAndSuccessfulAnswerControl() throws Exception {
        var f = sequential(false, true); var before = snapshot();
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new NavigationController(navigation))
                .setControllerAdvice(new NavigationErrorHandler()).build();
        String body = json.writeValueAsString(new uk.gegc.quizmaker.features.attempt.api.dto.NavigationRequest(f.id(1), 1L, 1L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/attempts/{id}/navigation", f.start().attemptId())
                        .header("Idempotency-Key", "next").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("NAVIGATION_ANSWER_REQUIRED"));
        assertThat(snapshot()).isEqualTo(before);
        assertThat(answer(f, 0, 1, "answer").stateVersion()).isEqualTo(2);
        body = json.writeValueAsString(new uk.gegc.quizmaker.features.attempt.api.dto.NavigationRequest(f.id(1), 2L, 1L));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/attempts/{id}/navigation", f.start().attemptId())
                        .header("Idempotency-Key", "next").contentType("application/json").content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.navigation.currentFormItemId").value(f.id(1).toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.navigation.version").value(2));
        assertThat(view(f).navigation().currentFormItemId()).isEqualTo(f.id(1));
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @Test void skipAnswerReturnAndReplayPreserveRealAnswersPositionAndFinality() {
        var f = sequential(true, true); var first = move(f, 1, 1, 1L, "skip");
        var ack = navigation.navigate(first); assertThat(ack.cursor().version()).isEqualTo(2);
        assertThat(view(f).answerCollection()).isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection.NOT_STARTED);
        assertThat(count("attempt_accepted_response")).isZero();
        answer(f, 1, 1, "answer-b");
        var back = navigation.navigate(move(f, 0, 2, 2L, "return"));
        assertThat(back.cursor().version()).isEqualTo(3); assertThat(view(f).navigation().currentFormItemId()).isEqualTo(f.id(0));
        var replay = restarted().navigate(first);
        assertThat(replay.replayed()).isTrue(); assertThat(replay.cursor()).isEqualTo(ack.cursor());
        assertThat(replay.stateVersion()).isEqualTo(1);
        assertThat(view(f).navigation().version()).isEqualTo(3);
        var state = view(f); assertThat(state.answerCollection()).isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.AnswerCollection.IN_PROGRESS);
        assertThat(state.result()).isNull();
        assertThatThrownBy(() -> answer(f, 1, 2, "edit-b")).isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.class);
        assertThat(answer(f, 0, 2, "answer-a").stateVersion()).isEqualTo(3);
        submissions.submit(new AttemptSubmissionService.Command(f.start().attemptId(), 3, true, CommandReceipt.Key.from("submit")));
        assertThat(restarted().navigate(first).cursor()).isEqualTo(ack.cursor());
        rejected(() -> navigation.navigate(move(f, 2, 4, 3L, "after-complete")), NavigationException.Reason.INACTIVE);
        assertThat(count("attempt_accepted_response")).isEqualTo(2);
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void readbackAndDirectAnswerDraftRoutesEnforceCapturedIndependentRules(boolean skip, boolean revisit) throws Exception {
        var f = sequential(skip, revisit); answer(f, 0, 1, "answer-a"); navigation.navigate(move(f, 1, 2, 1L, "next"));
        assertThat(view(f).items().stream().map(ParticipantAttemptView.Item::id).toList().contains(f.id(0))).isEqualTo(revisit);
        assertThat(delivery.read(f.start().attemptId()).form().items().stream().map(ParticipantForm.Item::id).toList().contains(f.id(0))).isEqualTo(revisit);
        var text = json.writeValueAsString(view(f)); assertThat(text).doesNotContain("protected-explanation", "correctAnswer", "submittedAnswer");
        if (revisit) {
            navigation.navigate(move(f, 0, 2, 2L, "readback"));
            navigation.navigate(move(f, 1, 2, 3L, "next-again"));
        } else rejected(() -> navigation.navigate(move(f, 0, 2, 2L, "readback")), NavigationException.Reason.NOT_FOUND);
        assertThatThrownBy(() -> answer(f, 2, 2, "bypass"))
                .isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.class);
        assertThatThrownBy(() -> commands.execute(new DraftCommandService.Command(
                uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.SAVE_DRAFT, f.start().attemptId(), f.id(2),
                2, 0, CommandReceipt.Key.from("draft-bypass"), QuestionType.TRUE_FALSE, json.createObjectNode().put("answer", true))))
                .isInstanceOf(DraftCommandException.class);
        assertThat(answer(f, 1, 2, "answer-b").stateVersion()).isEqualTo(3);
        assertThat(count("attempt_accepted_response")).isEqualTo(2); assertThat(count("attempt_answer_draft")).isZero();
    }

    @Test void freeDoesNotCreateCursorOrChangeAnswerLifecycle() {
        var f = fixture(new NavigationPolicy(NavigationMode.FREE), 3); var before = view(f);
        var result = navigation.navigate(move(f, 2, 1, null, "free"));
        assertThat(result.cursor()).isNull(); assertThat(view(f)).isEqualTo(before);
        assertThat(count("attempt_navigation")).isZero(); assertThat(count("attempt_accepted_response")).isZero();
        assertThat(restarted().navigate(move(f, 2, 1, null, "free")).replayed()).isTrue();
        rejected(() -> navigation.navigate(move(f, 2, 1, 1L, "wrong-shape")), NavigationException.Reason.INVALID_INPUT);
    }

    @Test void readOnlyParticipantCanNavigateButCannotAnswerAndReplayReauthorizesRead() {
        var f = sequential(true, true); revoke("ATTEMPT_CREATE");
        var c = move(f, 1, 1, 1L, "read-only"); assertThat(navigation.navigate(c).cursor().version()).isEqualTo(2);
        assertThat(view(f).navigation().currentFormItemId()).isEqualTo(f.id(1));
        assertThatThrownBy(() -> answer(f, 1, 1, "forbidden-answer"))
                .isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.class);
        revoke("ATTEMPT_READ"); var before = snapshot();
        rejected(() -> restarted().navigate(c), NavigationException.Reason.NOT_FOUND); assertThat(snapshot()).isEqualTo(before);
        grant(ownerId, "ATTEMPT_READ"); assertThat(restarted().navigate(c).replayed()).isTrue();
    }

    @Test void invalidTargetsVersionsAndForeignSubjectsHaveNoEffects() {
        var f = sequential(true, true); var before = snapshot();
        rejected(() -> navigation.navigate(new NavigationService.Command(f.start().attemptId(), UUID.randomUUID(), 1, 1L, CommandReceipt.Key.from("foreign-item"))), NavigationException.Reason.NOT_FOUND);
        rejected(() -> navigation.navigate(move(f, 1, 0, 1L, "stale-state")), NavigationException.Reason.STALE);
        rejected(() -> navigation.navigate(move(f, 1, 1, 2L, "stale-nav")), NavigationException.Reason.STALE);
        rejected(() -> navigation.navigate(move(f, 1, 1, null, "missing-nav")), NavigationException.Reason.INVALID_INPUT);
        rejected(() -> navigation.navigate(move(f, 2, 1, 1L, "jump")), NavigationException.Reason.TRANSITION_DENIED);
        assertThat(snapshot()).isEqualTo(before);
        String foreign = "foreign-" + UUID.randomUUID(); var foreignId = user(foreign, true); grant(foreignId, "ATTEMPT_READ", "ATTEMPT_CREATE");
        authenticate(foreign); rejected(() -> navigation.navigate(move(f, 1, 1, 1L, "foreign")), NavigationException.Reason.NOT_FOUND);
        authenticate(username); SecurityContextHolder.clearContext();
        rejected(() -> navigation.navigate(move(f, 1, 1, 1L, "anonymous")), NavigationException.Reason.UNAUTHENTICATED);
        authenticate(username); assertThat(navigation.navigate(move(f, 1, 1, 1L, "control")).cursor().version()).isEqualTo(2);
        rejected(() -> navigation.navigate(move(f, 0, 1, 2L, "control")), NavigationException.Reason.CONFLICT);
    }

    @ParameterizedTest @CsvSource({"2,false", "4,false", "2,true", "4,true"})
    void concurrentCommandsHaveOneRealTransitionAndDurableReplay(int isolation, boolean identical) throws Exception {
        var f = sequential(true, true); var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<Future<Object>>();
            for (int i = 0; i < 2; i++) { int n = i; jobs.add(pool.submit(() -> {
                authenticate(username); var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                try { return transaction.execute(s -> {
                    try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
                    return navigation.navigate(move(f, 1, 1, 1L, identical ? "same" : "worker-" + n));
                }); } catch (NavigationException e) { return e.reason(); } finally { SecurityContextHolder.clearContext(); }
            })); }
            var outcomes = List.of(jobs.get(0).get(30, TimeUnit.SECONDS), jobs.get(1).get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(NavigationService.Result.class::isInstance)).hasSize(identical ? 2 : 1);
            if (!identical) assertThat(outcomes.stream().filter(NavigationException.Reason.STALE::equals)).hasSize(1);
            else assertThat(outcomes.stream().map(NavigationService.Result.class::cast).filter(NavigationService.Result::replayed)).hasSize(1);
            assertThat(view(f).navigation().version()).isEqualTo(2); assertThat(count("attempt_navigation_result")).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_trace_event WHERE event_type='ITEM_NAVIGATED'", Long.class)).isEqualTo(1);
            assertThat(count("attempt_accepted_response")).isZero();
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(strings = {"UPDATE attempt_navigation", "INTO attempt_navigation_result", "INTO attempt_trace_event", "UPDATE attempt_command_receipt"})
    void reachedWriteFailuresRollBackPositionResultTraceAndReceipt(String checkpoint) {
        var f = sequential(true, true); var before = snapshot(); var reached = new AtomicBoolean(); var c = move(f, 1, 1, 1L, "fault");
        observer().afterSql = (statement, connection) -> { if (statement.contains(checkpoint)) {
            reached.set(true); throw new SQLException("private-navigation-marker", "08006");
        }};
        rejected(() -> navigation.navigate(c), NavigationException.Reason.UNAVAILABLE);
        observer().reset(); assertThat(reached).isTrue(); assertThat(snapshot()).isEqualTo(before);
        assertThat(restarted().navigate(c).cursor().version()).isEqualTo(2); assertThat(restarted().navigate(c).replayed()).isTrue();
        assertThat(count("attempt_navigation_result")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownCommitIsReconciledAfterRestartWithoutADuplicateMove(boolean committed) {
        var f = sequential(true, true); var reached = new AtomicBoolean(); var c = move(f, 1, 1, 1L, "unknown");
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("INTO attempt_navigation_result") && reached.compareAndSet(false, true)) {
                if (committed) observer().failAfterCommit.set(true); else observer().failBeforeCommit.set(true);
            }
        };
        rejected(() -> navigation.navigate(c), NavigationException.Reason.UNKNOWN_OUTCOME);
        observer().reset(); assertThat(reached).isTrue(); assertThat(view(f).navigation().version()).isEqualTo(committed ? 2 : 1);
        var result = restarted().navigate(c); assertThat(result.replayed()).isEqualTo(committed); assertThat(result.cursor().version()).isEqualTo(2);
        assertThat(restarted().navigate(c).replayed()).isTrue(); assertThat(count("attempt_navigation_result")).isEqualTo(1);
    }

    @Test void outerRollbackAndCaughtCallbackFailureCannotCommitPartialNavigation() {
        var f = sequential(true, true); var before = snapshot(); var c = move(f, 1, 1, 1L, "outer");
        tx.executeWithoutResult(s -> { navigation.navigate(c); s.setRollbackOnly(); }); assertThat(snapshot()).isEqualTo(before);
        var reached = new AtomicBoolean(); observer().afterSql = (statement, connection) -> {
            if (statement.contains("INTO attempt_navigation_result")) { reached.set(true); throw new SQLException("private-marker", "08006"); }
        };
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> rejected(() -> navigation.navigate(c), NavigationException.Reason.UNAVAILABLE)))
                .isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        observer().reset(); assertThat(reached).isTrue(); assertThat(snapshot()).isEqualTo(before);
        assertThat(navigation.navigate(c).cursor().version()).isEqualTo(2);
    }

    @Test void multipleParentCommandsAndSerializedParticipantReadsHaveBoundedQueries() throws Exception {
        var totals = new ArrayList<Integer>(); var hibernateTotals = new ArrayList<Long>();
        var statistics = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        for (int size : List.of(1, 20)) {
            var fixtures = new ArrayList<Fixture>();
            for (int parent = 0; parent < 3; parent++) {
                quizId = newQuiz(); var f = fixture(new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, true, true)), size, QuestionType.MCQ_SINGLE);
                answer(f, 0, 1, "query-answer-" + size + "-" + parent); fixtures.add(f);
            }
            observer().reset(); statistics.clear();
            for (int n = 0; n < fixtures.size(); n++) {
                var f = fixtures.get(n); navigation.navigate(move(f, 0, 2, 1L, "query-" + size + "-" + n));
                var serialized = json.writeValueAsString(view(f)); assertThat(serialized).contains(f.id(0).toString());
            }
            totals.add(observer().selects.size()); hibernateTotals.add(statistics.getPrepareStatementCount());
        }
        assertThat(totals.get(0)).isPositive(); assertThat(totals.get(1)).isEqualTo(totals.get(0));
        assertThat(hibernateTotals.get(1)).isEqualTo(hibernateTotals.get(0));
        System.out.println("Navigation + serialized view SQL for three attempts, 1/20 items: JDBC=" + totals + ", JPA=" + hibernateTotals);
    }

    @Test void startConfigurationIsCapturedTogetherWithDraftsAndCannotBeChangedByReplay() {
        var f = sequential(true, false);
        var saved = tx.execute(s -> context.getBean(AttemptReadFactsService.class).read(f.start().attemptId(), AttemptReadFactsService.Purpose.PARTICIPANT_VIEW));
        assertThat(saved.policy().navigation()).isEqualTo(new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, true, false)));
        assertThat(saved.policy().submission().draftsEnabled()).isTrue();
        var changed = new AttemptStartService.Command(1, quizId, content(f.start().quizRevisionId()), 1,
                CommandReceipt.Key.from("start-" + quizId), BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY,
                "ALL_AT_ONCE", AnswerOrder.AUTHORED, true, new NavigationPolicy(NavigationMode.SEQUENTIAL, new NavigationRules(1, false, true)));
        assertThatThrownBy(() -> starts.start(changed)).isInstanceOfSatisfying(
                uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.class,
                e -> assertThat(e.reason()).isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.Reason.CONFLICT));
        assertThat(view(f).navigation().currentFormItemId()).isEqualTo(f.id(0)); assertThat(count("attempt_navigation")).isEqualTo(1);
    }

    @Test void freeCanProhibitAcceptedReadbackWithoutCreatingACursor() {
        var f = fixture(new NavigationPolicy(NavigationMode.FREE, new NavigationRules(1, false, false)), 3);
        answer(f, 0, 1, "answer-free"); var before = snapshot();
        rejected(() -> navigation.navigate(move(f, 0, 2, null, "hidden")), NavigationException.Reason.NOT_FOUND);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(view(f).items()).extracting(ParticipantAttemptView.Item::id).doesNotContain(f.id(0));
        assertThat(navigation.navigate(move(f, 2, 2, null, "visible")).cursor()).isNull();
        assertThat(count("attempt_navigation")).isZero(); assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @Test void anOldRepeatableReadSnapshotUsesCurrentPositionAndCurrentReadAuthority() throws Exception {
        var f = sequential(true, true); var older = new TransactionTemplate(manager); older.setIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ);
        var pool = Executors.newSingleThreadExecutor();
        try {
            older.executeWithoutResult(s -> {
                assertThat(sql.queryForObject("SELECT row_version FROM attempt_navigation WHERE attempt_id=UUID_TO_BIN(?)", Long.class, f.start().attemptId().toString())).isEqualTo(1);
                try { pool.submit(() -> {
                    authenticate(username);
                    try { return navigation.navigate(move(f, 1, 1, 1L, "outside")); } finally { SecurityContextHolder.clearContext(); }
                }).get(20, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
                // This ordinary read still sees the old snapshot; the command's locking reads must see version 2.
                assertThat(sql.queryForObject("SELECT row_version FROM attempt_navigation WHERE attempt_id=UUID_TO_BIN(?)", Long.class, f.start().attemptId().toString())).isEqualTo(1);
                assertThat(navigation.navigate(move(f, 0, 1, 2L, "from-old-snapshot")).cursor().version()).isEqualTo(3);
            });
            assertThat(view(f).navigation().version()).isEqualTo(3);
            rejected(() -> older.execute(s -> {
                sql.queryForObject("SELECT COUNT(*) FROM role_permissions", Long.class);
                try { pool.submit(() -> revoke("ATTEMPT_READ")).get(20, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
                return navigation.navigate(move(f, 1, 1, 3L, "revoked-in-old-snapshot"));
            }), NavigationException.Reason.NOT_FOUND);
            grant(ownerId, "ATTEMPT_READ"); assertThat(navigation.navigate(move(f, 1, 1, 3L, "restored")).cursor().version()).isEqualTo(4);
        } finally { pool.shutdownNow(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void navigationAndAnswerSerializeInBothOrdersWithoutAcceptingTheWrongItem(boolean navigationFirst) throws Exception {
        var f = sequential(true, true); var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var checkpoint = navigationFirst ? "UPDATE attempt_navigation" : "INTO attempt_accepted_response";
        var stopped = new AtomicBoolean(); var competing = new CountDownLatch(1);
        observer().beforeSql = (statement, connection) -> {
            if (stopped.get() && statement.contains("FROM attempt_lifecycle_state") && statement.contains("FOR UPDATE")) competing.countDown();
        };
        observer().afterSql = (statement, connection) -> {
            if (statement.contains(checkpoint) && stopped.compareAndSet(false, true)) {
                reached.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Writer barrier timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Object> nav = () -> {
            authenticate(username);
            try { return navigation.navigate(move(f, 1, 1, 1L, "competing-nav")); }
            catch (NavigationException e) { return e.reason(); } finally { SecurityContextHolder.clearContext(); }
        };
        java.util.concurrent.Callable<Object> response = () -> {
            authenticate(username);
            try { return answer(f, 0, 1, "competing-answer"); }
            catch (uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException e) { return e.reason(); }
            finally { SecurityContextHolder.clearContext(); }
        };
        try {
            var first = pool.submit(navigationFirst ? nav : response);
            assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(navigationFirst ? response : nav);
            assertThat(competing.await(10, TimeUnit.SECONDS)).isTrue(); release.countDown();
            var one = first.get(30, TimeUnit.SECONDS); var two = second.get(30, TimeUnit.SECONDS); observer().reset();
            if (navigationFirst) {
                assertThat(one).isInstanceOf(NavigationService.Result.class);
                assertThat(two).isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException.Reason.CONFLICT);
                assertThat(count("attempt_accepted_response")).isZero();
                assertThat(answer(f, 1, 1, "answer-current-b").stateVersion()).isEqualTo(2);
            } else {
                assertThat(one).isInstanceOf(ResponseAcceptanceService.Result.class);
                assertThat(two).isEqualTo(NavigationException.Reason.STALE);
                assertThat(count("attempt_accepted_response")).isEqualTo(1);
                assertThat(navigation.navigate(move(f, 1, 2, 1L, "competing-nav")).cursor().version()).isEqualTo(2);
            }
            assertThat(view(f).navigation().currentFormItemId()).isEqualTo(f.id(1)); assertThat(count("attempt_navigation_result")).isEqualTo(1);
        } finally { release.countDown(); observer().reset(); pool.shutdownNow(); }
    }
    private UUID draft(QuestionType type) {
        return tx.execute(s -> {
            var q = new Question(); q.setType(type); q.setDifficulty(Difficulty.EASY); q.setQuestionText("Frozen " + type);
            var content = (ObjectNode) QuestionContracts.example(type, QuestionContracts.View.AUTHOR);
            if (type == QuestionType.FILL_GAP)
                content.putArray("options").add("Paris").add("Rome").add("London").add("Berlin").add("Madrid").add("Lisbon").add("Vienna");
            q.setContent(content.toString()); q.setExplanation("protected-explanation");
            q.establishSourceOwner(ownerId); q.setQuizId(new ArrayList<>(List.of(quizzes.findById(quizId).orElseThrow())));
            return questions.saveAndFlush(q).getId();
        });
    }
    private UUID publish(long version, List<UUID> order) { return publications.publish(new Command(1, quizId, UUID.randomUUID(), version, order)).publication().revision().id(); }
    private UUID user(String name, boolean permission) {
        UUID id = tx.execute(s -> {
            var u = new User(); u.setUsername(name); u.setEmail(name + "@example.invalid"); u.setHashedPassword("test-only");
            u.setActive(true); u.setRoles(new HashSet<>()); return users.saveAndFlush(u).getId();
        });
        if (permission) tx.executeWithoutResult(s -> {
            String role = "acceptance-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
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
    private static ContentPolicy content(UUID revision) {
        return new ContentPolicy(revision, 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
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

}
