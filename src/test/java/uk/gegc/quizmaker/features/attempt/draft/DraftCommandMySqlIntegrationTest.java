package uk.gegc.quizmaker.features.attempt.draft;

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
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({uk.gegc.quizmaker.features.attempt.infra.repository.JdbcNavigationRepository.class, QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
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
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, uk.gegc.quizmaker.features.attempt.application.impl.AttemptTimingServiceImpl.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAttemptTimingRepository.class, AttemptAllowanceServiceImpl.class, JdbcAttemptAllowanceRepository.class, RetakeEligibilityServiceImpl.class, PublicationAttemptEntryReader.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        DraftCommandMySqlIntegrationTest.Config.class})
class DraftCommandMySqlIntegrationTest {
    static final String SCHEMA = "answer_drafts_" + UUID.randomUUID().toString().replace("-", "");
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
                    return bean instanceof DataSource ds && !(bean instanceof DraftDataSource) ? new DraftDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired AnswerDraftStore drafts;
    @Autowired DraftCommandService commands;
    @Autowired AttemptSubmissionService submissions;
    @Autowired AttemptAccessService access;
    @Autowired ResponseAcceptanceService responses;
    @Autowired AttemptDeliveryService delivery;
    @Autowired org.springframework.context.ApplicationContext context;
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
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
    static final List<String> TARGET_TABLES = List.of("attempt_timing", "attempt_start_binding", "attempt_allowance_scope", "attempt_unclassified_publication", "attempt_draft_command_result", "attempt_answer_draft", "attempt_result_release", "attempt_result_created", "attempt_automatic_grade", "attempt_automatic_result", "attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form_selection", "attempt_form", "attempt_policy_snapshot");
    DraftDataSource observer() { return (DraftDataSource) source; }
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


    /** Starts through the real server-authorized opt-in; no policy/subject/form row is fabricated. */
    record Fixture(AttemptStartService.Result start, Reference reference) { }

    private Fixture fixture() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "draft-start"));
        var items = delivery.read(started.attemptId()).form().items();
        // A real accepted control already exists, on a different item of this attempt.
        responses.accept(answer(started.attemptId(), items.get(0), 1, "control-answer"));
        return new Fixture(started, reference(started, items.get(2)));
    }

    private Reference reference(AttemptStartService.Result start, ParticipantForm.Item item) {
        return tx.execute(s -> sql.queryForObject("""
                SELECT BIN_TO_UUID(o.question_revision_id) question_id,b.response_contract_id,b.response_contract_version
                FROM attempt_form_item_option_order o JOIN question_revision_contract_bindings b
                ON b.question_revision_id=o.question_revision_id
                WHERE o.form_id=UUID_TO_BIN(?) AND o.item_id=UUID_TO_BIN(?)
                """, (r, n) -> new Reference(start.attemptId(), start.formId(), item.id(),
                UUID.fromString(r.getString("question_id")), new ContractVersion(r.getString("response_contract_id"),
                r.getInt("response_contract_version"))), start.formId().toString(), item.id().toString()));
    }

    private AnswerDraftStore recreated() {
        return new AnswerDraftStoreImpl(new JdbcAnswerDraftRepository(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source)),
                lifecycle, context.getBean(QuestionContractRegistry.class), manager, context.getBean(Clock.class), metrics);
    }

    private DraftCommandService.Command save(Fixture f, long state, long version, String key, boolean value) {
        return new DraftCommandService.Command(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.SAVE_DRAFT,
                f.start().attemptId(), f.reference().formItemId(), state, version, CommandReceipt.Key.from(key),
                QuestionType.TRUE_FALSE, json.createObjectNode().put("answer", value));
    }
    private DraftCommandService.Command delete(Fixture f, long state, long version, String key) {
        return new DraftCommandService.Command(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.DELETE_DRAFT,
                f.start().attemptId(), f.reference().formItemId(), state, version, CommandReceipt.Key.from(key), null, null);
    }
    private DraftCommandService restarted() {
        var jdbc = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source);
        return new DraftCommandServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), access,
                context.getBean(AttemptReadFactsService.class), lifecycle, new JdbcAnswerDraftRepository(jdbc), recreated(),
                new CommandReceiptServiceImpl(new JdbcCommandReceiptRepository(jdbc), manager, context.getBean(Clock.class), metrics),
                new JdbcDraftCommandResultRepository(jdbc), context.getBean(ExecutionTraceService.class), manager, metrics, context.getBean(Clock.class));
    }
    private static void commandRejected(java.util.concurrent.Callable<?> action, DraftCommandException.Reason reason) {
        assertThatThrownBy(action::call).isInstanceOfSatisfying(DraftCommandException.class, e -> {
            assertThat(e.reason()).isEqualTo(reason); assertThat(e.getSuppressed()).isEmpty();
        }).hasNoCause();
    }
    private Map<String, List<String>> protectedSnapshot() {
        var result = new TreeMap<>(targetSnapshot());
        for (var table : List.of("attempt_draft_command_result", "attempt_command_receipt", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream"))
            result.remove(table);
        return result;
    }

    @Test void realHttpCommandsPersistAndReplayWithoutMockingApplicationCollaborators() throws Exception {
        var f = fixture(); var before = protectedSnapshot();
        var converters = new ArrayList<org.springframework.http.converter.HttpMessageConverter<?>>();
        converters.add(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter());
        new uk.gegc.quizmaker.features.attempt.api.ResponseAcceptanceJsonConfig().extendMessageConverters(converters);
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new uk.gegc.quizmaker.features.attempt.api.DraftCommandController(commands, json))
                .setControllerAdvice(new uk.gegc.quizmaker.features.attempt.api.DraftCommandErrorHandler())
                .setMessageConverters(converters.toArray(org.springframework.http.converter.HttpMessageConverter[]::new)).build();
        String path = "/api/v2/attempts/" + f.start().attemptId() + "/drafts/" + f.reference().formItemId();
        String body = "{\"expectedStateVersion\":2,\"expectedDraftVersion\":0,\"response\":{\"type\":\"TRUE_FALSE\",\"answer\":true}}";
        var put = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                .header("Idempotency-Key", "http-save").contentType("application/json").content(body);
        http.perform(put).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.draftVersion").value(1));
        http.perform(put).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.replayed").value(true));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("Idempotency-Key", "http-save")
                        .contentType("application/json").content(body.replace("true", "false")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path).header("Idempotency-Key", "http-delete")
                        .param("expectedStateVersion", "2").param("expectedDraftVersion", "1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("DELETED"));
        var committed = targetSnapshot(); SecurityContextHolder.clearContext();
        http.perform(put).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        authenticate(username);
        assertThat(targetSnapshot()).isEqualTo(committed); assertThat(protectedSnapshot()).isEqualTo(before);
        assertThat(recreated().read(ownerId, f.reference()).orElseThrow().normalizedResponse()).isNull();
    }

    @Test void reachedTransactionalFailureDoesNotLogPrivateDriverOrResponseDiagnostics() {
        var f = fixture(); var reached = new AtomicBoolean();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TransactionTemplate.class);
        var oldLevel = logger.getLevel();
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("INTO attempt_draft_command_result")) {
                reached.set(true); throw new SQLException("draft-secret-driver-marker", "08006");
            }
        };
        try {
            commandRejected(() -> commands.execute(save(f, 2, 0, "private-receipt-marker", true)), DraftCommandException.Reason.UNAVAILABLE);
            assertThat(reached).isTrue();
            assertThat(events.list).anyMatch(e -> e.getThrowableProxy() != null);
            for (var event : events.list) {
                String diagnostic = event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                        : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy()));
                assertThat(diagnostic).doesNotContain("draft-secret-driver-marker", "private-receipt-marker", "normalized_response");
            }
        } finally { observer().reset(); logger.detachAppender(events); logger.setLevel(oldLevel); events.stop(); }
        assertThat(count("attempt_answer_draft")).isZero(); assertThat(count("attempt_draft_command_result")).isZero();
    }

    @Test void replayKeepsOriginalAcknowledgementAfterNewerSaveDeleteCompletionAndRestart() {
        var f = fixture(); var protectedBefore = protectedSnapshot();
        var first = save(f, 2, 0, "first", true);
        var ack = commands.execute(first);
        assertThat(ack.draftVersion()).isEqualTo(1); assertThat(ack.stateVersion()).isEqualTo(2);
        assertThat(ack.status()).isEqualTo(AnswerDraft.Status.PRESENT); assertThat(ack.replayed()).isFalse();
        assertThat(restarted().execute(save(f, 2, 1, "second", false)).draftVersion()).isEqualTo(2);
        var beforeReplay = targetSnapshot();
        var replay = restarted().execute(first);
        assertThat(replay).isEqualTo(new DraftCommandService.Result(ack.attemptId(), ack.formItemId(), 2, 1, AnswerDraft.Status.PRESENT, true));
        assertThat(recreated().read(ownerId, f.reference()).orElseThrow().normalizedResponse()).isEqualTo("{\"answer\":false}");
        assertThat(targetSnapshot()).isEqualTo(beforeReplay);
        commandRejected(() -> commands.execute(save(f, 2, 0, "first", false)), DraftCommandException.Reason.CONFLICT);
        commandRejected(() -> commands.execute(delete(f, 2, 0, "first")), DraftCommandException.Reason.CONFLICT);
        var removal = delete(f, 2, 2, "delete");
        assertThat(commands.execute(removal).draftVersion()).isEqualTo(3);
        assertThat(restarted().execute(removal).replayed()).isTrue();
        assertThat(protectedSnapshot()).isEqualTo(protectedBefore);
        submissions.submit(new AttemptSubmissionService.Command(f.start().attemptId(), 2, true, CommandReceipt.Key.from("terminal")));
        var terminal = targetSnapshot();
        assertThat(restarted().execute(first).draftVersion()).isEqualTo(1);
        assertThat(restarted().execute(removal).status()).isEqualTo(AnswerDraft.Status.DELETED);
        commandRejected(() -> commands.execute(save(f, 3, 3, "late-new", true)), DraftCommandException.Reason.INACTIVE);
        assertThat(targetSnapshot()).isEqualTo(terminal);
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT accepted_count FROM attempt_submission", Integer.class)).isEqualTo(1);
    }

    @Test void deleteNeverSavedItemCommitsTombstoneAndBlocksDelayedFirstSave() {
        var f = fixture(); var before = protectedSnapshot();
        var removal = delete(f, 2, 0, "absent-delete");
        var ack = commands.execute(removal);
        assertThat(ack.draftVersion()).isEqualTo(1); assertThat(ack.status()).isEqualTo(AnswerDraft.Status.DELETED);
        assertThat(restarted().execute(removal).replayed()).isTrue();
        commandRejected(() -> commands.execute(save(f, 2, 0, "delayed-first", true)), DraftCommandException.Reason.CONFLICT);
        assertThat(recreated().read(ownerId, f.reference()).orElseThrow().normalizedResponse()).isNull();
        assertThat(restarted().execute(save(f, 2, 1, "intentional-recreate", true)).draftVersion()).isEqualTo(2);
        commandRejected(() -> commands.execute(delete(f, 2, 1, "stale-delete")), DraftCommandException.Reason.CONFLICT);
        assertThat(protectedSnapshot()).isEqualTo(before);
    }

    @Test void outerRollbackCannotPublishADraftOrItsAcknowledgement() {
        var f = fixture(); var c = save(f, 2, 0, "outer-rollback", true); var before = targetSnapshot(); var reached = new AtomicBoolean();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            assertThat(commands.execute(c).draftVersion()).isEqualTo(1); reached.set(true);
            throw new IllegalStateException("test outer rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reached).isTrue(); assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(recreated().read(ownerId, f.reference())).isEmpty();
        assertThat(restarted().execute(c).replayed()).isFalse();
    }

    @Test void receiptExtensionConstraintsAndCorruptMetadataNeverProduceAFalseReplay() {
        var f = fixture(); var c = save(f, 2, 0, "extension", true); commands.execute(c);
        for (String assignment : List.of("draft_version=0", "draft_status='UNKNOWN'")) {
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> sql.update("UPDATE attempt_draft_command_result SET " + assignment)))
                    .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class, e ->
                            assertThat(e.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
                                    failure -> assertThat(failure.getErrorCode()).isEqualTo(3819)));
        }
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> sql.update("INSERT INTO attempt_draft_command_result SELECT * FROM attempt_draft_command_result")))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        tx.executeWithoutResult(s -> sql.update("UPDATE attempt_draft_command_result SET draft_version=2"));
        var corrupt = targetSnapshot(); commandRejected(() -> restarted().execute(c), DraftCommandException.Reason.CORRUPT);
        assertThat(targetSnapshot()).isEqualTo(corrupt);
        tx.executeWithoutResult(s -> sql.update("UPDATE attempt_draft_command_result SET draft_version=1"));
        assertThat(restarted().execute(c).draftVersion()).isEqualTo(1);
        assertThat(recreated().read(ownerId, f.reference()).orElseThrow().version()).isEqualTo(1);
    }

    @Test void allFiveFamiliesUseRealContractsAndNeverAcceptOrGradeSavedValues() {
        var start = starts.start(command(prepared(), AnswerOrder.SHUFFLED_PER_ATTEMPT, "families"));
        var before = protectedSnapshot();
        for (var item : delivery.read(start.attemptId()).form().items()) {
            var c = new DraftCommandService.Command(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.SAVE_DRAFT,
                    start.attemptId(), item.id(), 1, 0, CommandReceipt.Key.from("family-" + item.type()), item.type(), stableInput(item));
            var saved = commands.execute(c);
            assertThat(restarted().execute(c).draftVersion()).isEqualTo(1);
            assertThat(saved.status()).isEqualTo(AnswerDraft.Status.PRESENT);
            assertThat(recreated().read(ownerId, reference(start, item))).isPresent();
        }
        assertThat(count("attempt_answer_draft")).isEqualTo(5);
        assertThat(count("attempt_draft_command_result")).isEqualTo(5);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(protectedSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"unauthenticated", "foreign", "permission", "deleted", "private", "item", "accepted", "invalid", "oversized", "stale"})
    void rejectedRequestsLeaveAllEvidenceAndNewerDraftUnchanged(String variant) {
        var f = fixture(); var original = commands.execute(save(f, 2, 0, "original", true));
        var before = targetSnapshot(); var value = recreated().read(ownerId, f.reference());
        var c = save(f, 2, 1, "denied", false);
        var expected = DraftCommandException.Reason.NOT_FOUND;
        switch (variant) {
            case "unauthenticated" -> { SecurityContextHolder.clearContext(); expected = DraftCommandException.Reason.UNAUTHENTICATED; }
            case "foreign" -> { var name = "foreign-" + UUID.randomUUID(); user(name, true); authenticate(name); }
            case "permission" -> tx.executeWithoutResult(s -> sql.update("""
                    DELETE rp FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id
                    JOIN permissions p ON p.permission_id=rp.permission_id WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name='ATTEMPT_CREATE'
                    """, ownerId.toString()));
            case "deleted" -> tx.executeWithoutResult(s -> sql.update("UPDATE users SET is_deleted=TRUE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
            case "private" -> tx.executeWithoutResult(s -> sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?) WHERE quiz_id=UUID_TO_BIN(?)", user("other-" + UUID.randomUUID(), true).toString(), quizId.toString()));
            case "item", "accepted" -> {
                var item = variant.equals("item") ? UUID.randomUUID() : delivery.read(f.start().attemptId()).form().items().get(0).id();
                c = new DraftCommandService.Command(c.operation(), c.attemptId(), item, 2, 0, c.key(), c.type(), c.response());
                if (variant.equals("accepted")) expected = DraftCommandException.Reason.INACTIVE;
            }
            case "invalid", "oversized" -> {
                ((ObjectNode)c.response()).put(variant.equals("invalid") ? "answer" : "extra", variant.equals("invalid") ? "true" : "x".repeat(65537));
                expected = DraftCommandException.Reason.INVALID_INPUT;
            }
            case "stale" -> { c = save(f, 1, 1, "stale-state", false); expected = DraftCommandException.Reason.STALE; }
        }
        var rejected = c; commandRejected(() -> commands.execute(rejected), expected);
        authenticate(username);
        if (variant.equals("deleted")) tx.executeWithoutResult(s -> sql.update("UPDATE users SET is_deleted=FALSE WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        if (variant.equals("private")) tx.executeWithoutResult(s -> sql.update("UPDATE quizzes SET creator_id=UUID_TO_BIN(?) WHERE quiz_id=UUID_TO_BIN(?)", ownerId.toString(), quizId.toString()));
        if (variant.equals("permission")) grant(ownerId, "ATTEMPT_CREATE");
        assertThat(recreated().read(ownerId, f.reference())).isEqualTo(value);
        assertThat(targetSnapshot()).isEqualTo(before);
        assertThat(commands.execute(save(f, 2, original.draftVersion(), "permitted-control", false)).draftVersion()).isEqualTo(2);
    }

    @Test void startOptInIsExplicitAuthorizedImmutableAndPartOfCommandIdentity() {
        var base = command(prepared(), AnswerOrder.AUTHORED, "opt-in");
        var disabled = new AttemptStartService.Command(base.version(), base.quizId(), base.content(), base.publicationVersion(), base.key(),
                base.preset(), base.context(), base.legacyMode(), base.answerOrder());
        var started = starts.start(disabled);
        var item = delivery.read(started.attemptId()).form().items().get(2);
        var denied = new Fixture(started, reference(started, item)); var before = targetSnapshot();
        commandRejected(() -> commands.execute(save(denied, 1, 0, "disabled", true)), DraftCommandException.Reason.INACTIVE);
        assertThatThrownBy(() -> starts.start(base)).isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.class);
        assertThat(targetSnapshot()).isEqualTo(before);
        quizId = newQuiz();
        var enabled = fixture();
        assertThat(commands.execute(save(enabled, 2, 0, "enabled", true)).draftVersion()).isEqualTo(1);
        var existing = context.getBean(PolicySnapshotService.class).readCurrent(ownerId, sql.queryForObject(
                "SELECT BIN_TO_UUID(policy_snapshot_id) FROM attempt_trace_stream WHERE attempt_id=UUID_TO_BIN(?)", UUID.class, started.attemptId().toString()));
        assertThat(existing.resolution().policy().submission().draftsEnabled()).isFalse();
    }

    @ParameterizedTest @CsvSource({"2,create", "4,create", "2,delete-save", "4,delete-save", "2,absent-delete", "4,absent-delete", "2,identical", "4,identical"})
    void independentWritersHaveAPermittedWinnerAndStableReplay(int isolation, String variant) throws Exception {
        var f = fixture();
        long version = variant.equals("delete-save") ? commands.execute(save(f, 2, 0, "seed", true)).draftVersion() : 0;
        var before = protectedSnapshot(); var gate = new CyclicBarrier(2); var workers = Executors.newFixedThreadPool(2);
        try {
            var jobs = new ArrayList<Future<Object>>();
            for (int n = 0; n < 2; n++) {
                int index = n;
                jobs.add(workers.submit(() -> {
                    authenticate(username);
                    var transaction = new TransactionTemplate(manager); transaction.setIsolationLevel(isolation);
                    try { return transaction.execute(s -> {
                        try { gate.await(10, TimeUnit.SECONDS); } catch (Exception failure) { throw new AssertionError(failure); }
                        String key = variant.equals("identical") ? "same" : "worker-" + index;
                        return commands.execute((variant.equals("delete-save") && index == 0) || variant.equals("absent-delete")
                                ? delete(f, 2, version, key) : save(f, 2, version, key, variant.equals("identical") || index == 0));
                    }); } catch (DraftCommandException e) { return e.reason(); }
                    finally { SecurityContextHolder.clearContext(); }
                }));
            }
            var outcomes = List.of(jobs.get(0).get(30, TimeUnit.SECONDS), jobs.get(1).get(30, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(DraftCommandService.Result.class::isInstance)).hasSize(variant.equals("identical") ? 2 : 1);
            if (!variant.equals("identical")) assertThat(outcomes.stream().filter(DraftCommandException.Reason.CONFLICT::equals)).hasSize(1);
            else assertThat(outcomes.stream().map(DraftCommandService.Result.class::cast).filter(DraftCommandService.Result::replayed)).hasSize(1);
            assertThat(recreated().read(ownerId, f.reference()).orElseThrow().version()).isEqualTo(version + 1);
            assertThat(count("attempt_draft_command_result")).isEqualTo(variant.equals("delete-save") ? 2 : 1);
            assertThat(protectedSnapshot()).isEqualTo(before);
        } finally { workers.shutdownNow(); }
    }

    @ParameterizedTest @CsvSource({"save,draft", "save,result", "save,trace", "save,receipt", "delete,draft", "delete,result", "delete,trace", "delete,receipt"})
    void reachedFailureRollsBackDraftResultTraceAndReceiptThenRetryCommitsOnce(String operation, String checkpoint) {
        var f = fixture(); commands.execute(save(f, 2, 0, "seed", true));
        var before = targetSnapshot(); var value = recreated().read(ownerId, f.reference()); var reached = new AtomicBoolean();
        var c = operation.equals("delete") ? delete(f, 2, 1, "failed") : save(f, 2, 1, "failed", false);
        observer().afterSql = (statement, connection) -> {
            if (statement.contains(switch (checkpoint) {
                case "draft" -> "UPDATE attempt_answer_draft"; case "result" -> "INTO attempt_draft_command_result";
                case "trace" -> "INTO attempt_trace_event"; default -> "UPDATE attempt_command_receipt";
            })) { reached.set(true); throw new SQLException("private-draft-transaction-marker", "08006"); }
        };
        commandRejected(() -> commands.execute(c), DraftCommandException.Reason.UNAVAILABLE);
        observer().reset(); assertThat(reached).isTrue();
        assertThat(targetSnapshot()).isEqualTo(before); assertThat(recreated().read(ownerId, f.reference())).isEqualTo(value);
        assertThat(restarted().execute(c).draftVersion()).isEqualTo(2);
        assertThat(restarted().execute(c).replayed()).isTrue();
        assertThat(count("attempt_draft_command_result")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void unknownCommitIsReconciledFromDurableReceiptAfterServiceRestart(boolean committed) {
        var f = fixture(); var c = save(f, 2, 0, "unknown", true);
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("INTO attempt_draft_command_result") && reached.compareAndSet(false, true)) {
                if (committed) observer().failAfterCommit.set(true); else observer().failBeforeCommit.set(true);
            }
        };
        commandRejected(() -> commands.execute(c), DraftCommandException.Reason.UNKNOWN_OUTCOME);
        observer().reset(); assertThat(reached).isTrue();
        assertThat(count("attempt_answer_draft")).isEqualTo(committed ? 1 : 0);
        var retry = restarted().execute(c);
        assertThat(retry.replayed()).isEqualTo(committed); assertThat(retry.draftVersion()).isEqualTo(1);
        assertThat(count("attempt_draft_command_result")).isEqualTo(1);
        assertThat(restarted().execute(c).replayed()).isTrue();
    }

    @Test void commandReadQueriesAreBoundedAcrossMultipleAttemptsAndGrowingForms() {
        var totals = new ArrayList<Integer>();
        var deleteTotals = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var fixtures = new ArrayList<Fixture>();
            for (int parent = 0; parent < 3; parent++) {
                quizId = newQuiz();
                var revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList());
                binding.bind(quizId, revision);
                var start = starts.start(command(revision, AnswerOrder.AUTHORED, "bounded-" + size + "-" + parent));
                fixtures.add(new Fixture(start, reference(start, delivery.read(start.attemptId()).form().items().get(0))));
            }
            observer().reset();
            for (int i = 0; i < fixtures.size(); i++) commands.execute(save(fixtures.get(i), 1, 0, "bounded-save-" + size + "-" + i, true));
            totals.add(observer().selects.size());
            observer().reset();
            for (int i = 0; i < fixtures.size(); i++) commands.execute(delete(fixtures.get(i), 1, 1, "bounded-delete-" + size + "-" + i));
            deleteTotals.add(observer().selects.size());
        }
        assertThat(totals.get(0)).isPositive(); assertThat(totals.get(1)).isEqualTo(totals.get(0));
        assertThat(deleteTotals.get(0)).isPositive(); assertThat(deleteTotals.get(1)).isEqualTo(deleteTotals.get(0));
        System.out.println("Draft command SQL for three attempts, 1/20 questions: save=" + totals + ", delete=" + deleteTotals);
    }
    private static void assertRejected(java.util.concurrent.Callable<?> call, AnswerDraftException.Reason reason) {
        assertThatThrownBy(call::call).isInstanceOfSatisfying(AnswerDraftException.class,
                e -> { assertThat(e.reason()).isEqualTo(reason); assertThat(e.getSuppressed()).isEmpty(); }).hasNoCause();
    }

    private long count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)); }
    private ResponseAcceptanceService.Command answer(UUID attempt, ParticipantForm.Item item, long version, String key) {
        return new ResponseAcceptanceService.Command(attempt, item.id(), version, CommandReceipt.Key.from(key), item.type(), stableInput(item));
    }
    private ObjectNode stableInput(ParticipantForm.Item item) {
        var value = json.createObjectNode();
        switch (item.type()) {
            case TRUE_FALSE -> value.put("answer", true);
            case MCQ_SINGLE -> value.put("selectedOptionId", ((ParticipantContent.Choices) item.content()).options().get(0).id().toString());
            case MCQ_MULTI -> value.putArray("selectedOptionIds").add(((ParticipantContent.Choices) item.content()).options().get(0).id().toString());
            case ORDERING -> { var ids = value.putArray("orderedItemIds"); ((ParticipantContent.Ordering) item.content()).items().forEach(o -> ids.add(o.id().toString())); }
            case FILL_GAP -> { var entries = value.putArray("answers"); ((ParticipantContent.FillGap) item.content()).gaps()
                    .forEach(g -> entries.addObject().put("gapId", g.id()).put("answer", "Paris")); }
            default -> throw new AssertionError(item.type());
        }
        return value;
    }

    private Map<String, List<String>> targetSnapshot() {
        return tx.execute(status -> {
            Map<String, List<String>> result = new TreeMap<>();
            TARGET_TABLES.stream().filter(t -> !t.equals("attempt_answer_draft")).forEach(table -> result.put(table, sql.queryForList("SELECT * FROM " + table).stream().map(row -> {
                var ordered = new TreeMap<String, String>();
                row.forEach((key, value) -> ordered.put(key, value instanceof byte[] b ? HexFormat.of().formatHex(b) : String.valueOf(value)));
                return ordered.toString();
            }).sorted().toList()));
            return result;
        });
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Test barrier timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
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
    private UUID prepared() {
        var revision = publish(0, FAMILIES.stream().map(this::draft).toList());
        binding.bind(quizId, revision);
        return revision;
    }
    private static ContentPolicy content(UUID revision) {
        return new ContentPolicy(revision, 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
    }
    private AttemptStartService.Command command(UUID revision, AnswerOrder order, String key) {
        return new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from(key),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", order, true);
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
