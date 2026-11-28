package uk.gegc.quizmaker.features.attempt.submission;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.form.ParticipantForm;
import uk.gegc.quizmaker.features.question.domain.model.contract.ParticipantContent;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.CompletionFacts;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptSubmissionRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.AcceptedResponseRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.CommandReceiptRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.interceptor.TransactionInterceptor;
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
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract;
import uk.gegc.quizmaker.features.question.application.contract.VersionedScoringContract;
import uk.gegc.quizmaker.features.question.domain.model.contract.*;
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

import uk.gegc.quizmaker.features.result.application.AutomaticResultService;
import uk.gegc.quizmaker.features.result.application.impl.AutomaticResultServiceImpl;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.repository.AutomaticResultRepository;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcAutomaticResultRepository;
import uk.gegc.quizmaker.features.result.application.ImmediateResultReleaseService;
import uk.gegc.quizmaker.features.result.application.impl.*;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcResultReleaseRepository;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcResultNotificationEventRepository;
import uk.gegc.quizmaker.features.result.domain.repository.ResultNotificationEventRepository;
import uk.gegc.quizmaker.features.result.application.ResultNotificationEventReader;
import uk.gegc.quizmaker.features.result.domain.model.notification.*;
import uk.gegc.quizmaker.features.result.application.ScheduledResultReleaseService;
import uk.gegc.quizmaker.features.result.application.ResultDisclosureService;
import uk.gegc.quizmaker.features.result.application.ReleaseConfigurationService;
import uk.gegc.quizmaker.features.result.application.impl.ScheduledResultReleaseServiceImpl;
import uk.gegc.quizmaker.features.result.application.impl.ReleaseConfigurationReaderImpl;
import uk.gegc.quizmaker.features.result.application.impl.ReleaseConfigurationServiceImpl;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfiguration;
import uk.gegc.quizmaker.features.result.domain.model.release.ScheduledRelease;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcScheduledReleaseRepository;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcReleaseConfigurationRepository;
import uk.gegc.quizmaker.features.result.infra.release.QuizReleaseConfigurationSource;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.repository.ResultReleaseRepository;
import uk.gegc.quizmaker.features.result.domain.repository.ReleaseConfigurationRepository;
import uk.gegc.quizmaker.features.result.domain.repository.ScheduledReleaseRepository;
import uk.gegc.quizmaker.features.result.api.dto.ParticipantResultView;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.api.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

/** Real publication and five-family bindings feed the real atomic start. Only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true", "quizmaker.attempts.participant-view-enabled=true",
        "spring.datasource.hikari.transaction-isolation=${result.test.isolation:TRANSACTION_REPEATABLE_READ}"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({uk.gegc.quizmaker.features.attempt.infra.repository.JdbcBranchingRepository.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcNavigationRepository.class, QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, PublicationContent.class, RetainedImageServiceImpl.class,
        AppPermissionEvaluator.class, QuestionHandlerFactory.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        QuestionContractRegistryImpl.class, QuestionRevisionContractServiceImpl.class, JdbcQuestionContractBindingRepository.class,
        PublishedQuestionContractServiceImpl.class, BaselineQuestionContractConfig.class,
        uk.gegc.quizmaker.features.question.config.OpenQuestionContractConfig.class,
        uk.gegc.quizmaker.features.question.config.MatchingQuestionContractConfig.class,
        uk.gegc.quizmaker.features.question.config.ComplianceQuestionContractConfig.class,
        uk.gegc.quizmaker.features.question.config.HotspotQuestionContractConfig.class,
        FixedFormMaterializerImpl.class, FormOptionOrderServiceImpl.class,
        JdbcFixedFormRepository.class, JdbcFormOptionOrderRepository.class,
        ResponseAcceptanceServiceImpl.class, JdbcAcceptedResponseRepository.class,
        ResponseBatchAcceptanceServiceImpl.class, JdbcResponseBatchResultRepository.class,
        AttemptSubmissionServiceImpl.class, AttemptTerminalSubmissionImpl.class, AcceptedSubmissionResponseFinalizer.class, JdbcAttemptSubmissionRepository.class,
        AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcUngradedActivityRepository.class, AttemptStartServiceImpl.class, uk.gegc.quizmaker.features.attempt.application.impl.AttemptTimingServiceImpl.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAttemptTimingRepository.class, AttemptAllowanceServiceImpl.class, JdbcAttemptAllowanceRepository.class, RetakeEligibilityServiceImpl.class, PublicationAttemptEntryReader.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        SubmittedAttemptSourceImpl.class, JdbcSubmittedAttemptRepository.class, AutomaticResultServiceImpl.class,
        JdbcAutomaticResultRepository.class, JdbcResultReleaseRepository.class, JdbcResultNotificationEventRepository.class,
        uk.gegc.quizmaker.features.result.application.impl.ResultNotificationEventReaderImpl.class,
        ImmediateResultReleaseServiceImpl.class, ResultDisclosureServiceImpl.class, ParticipantResultServiceImpl.class,
        ParticipantAttemptViewServiceImpl.class, JdbcAttemptViewRouteRepository.class,
        InitialParticipantViewDisclosureReader.class, InitialParticipantViewMediaReader.class,
        ImmediateResultReleaseMySqlIntegrationTest.Config.class})
class ImmediateResultReleaseMySqlIntegrationTest {
    static final String SCHEMA = "immediate_release_" + UUID.randomUUID().toString().replace("-", "");
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
        p.add("spring.jpa.properties.hibernate.connection.isolation", () -> switch(System.getProperty(
                "result.test.isolation","TRANSACTION_REPEATABLE_READ")) {
            case "TRANSACTION_REPEATABLE_READ" -> Connection.TRANSACTION_REPEATABLE_READ;
            case "TRANSACTION_READ_COMMITTED" -> Connection.TRANSACTION_READ_COMMITTED;
            default -> throw new IllegalArgumentException("Unsupported result test isolation");
        });
    }
    @AfterAll static void dropDatabase() { if (schemaCreated) admin.execute("DROP DATABASE " + SCHEMA); }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-05T12:00:00Z"), ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean MediaStorageProperties mediaProperties() { return new MediaStorageProperties(); }
        @Bean TestImageStorage storage() { return new TestImageStorage(); }
        @Bean VersionedResponseContract fixtureHotspotResponseV2() { return hotspotResponseV2(); }
        @Bean VersionedScoringContract fixtureHotspotScoringV2() { return hotspotScoringV2(); }
        @Bean static BeanPostProcessor measuredSql() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof SubmissionDataSource) ? new SubmissionDataSource(ds) : bean;
                }
            };
        }
    }
    static class TestImageStorage implements SavedImageStorage {
        boolean preparing;
        int calls;
        private void called() { assertThat(preparing).as("Storage I/O only during explicit image preparation").isTrue(); calls++; }
        public Source inspect(String key) { called(); return new Source("fixture-etag", 1024, "image/png"); }
        public void retain(SavedImageVersion v) { called(); }
        public void verify(SavedImageVersion v) { called(); }
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean ParticipantViewMediaReader mediaReader;
    @Autowired TestImageStorage imageStorage;
    @Autowired uk.gegc.quizmaker.features.media.application.RetainedImageService retainedImages;
    @Autowired uk.gegc.quizmaker.features.media.domain.repository.MediaAssetRepository mediaAssets;
    @Autowired AutomaticResultService results;
    @Autowired ImmediateResultReleaseService releases;
    @Autowired ParticipantAttemptViewService views;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AttemptService legacy;
    @Autowired AttemptSubmissionService submissions;
    @Autowired ResponseAcceptanceService responses;
    @Autowired ResponseBatchAcceptanceService batches;
    @Autowired AttemptDeliveryService delivery;
    @Autowired org.springframework.context.ApplicationContext context;
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    @Autowired PublishedQuestionContractService binding;
    @Autowired QuestionRevisionContractService questionContracts;
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
    static final List<String> TARGET_TABLES = List.of("attempt_result_notification_event", "attempt_scheduled_release", "result_release_configuration_receipts", "result_release_configuration_heads", "result_release_configurations", "attempt_timing", "attempt_start_binding", "attempt_allowance_scope", "attempt_unclassified_publication", "attempt_result_release", "attempt_result_created", "attempt_automatic_grade", "attempt_automatic_result", "attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form_selection", "attempt_form", "attempt_policy_snapshot");
    SubmissionDataSource observer() { return (SubmissionDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;
    String authoredExplanation;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
        imageStorage.preparing = false; imageStorage.calls = 0;
        doAnswer(call -> new InitialParticipantViewMediaReader()
                .read(call.getArgument(0), call.getArgument(1), call.getArgument(2))).when(mediaReader).read(any(), any(), any());
        authoredExplanation = "protected-explanation";
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
                    "quiz_revisions", "saved_image_bindings", "saved_image_versions", "media_assets", "quiz_questions", "question_tags", "question_source_confirmations", "questions", "quizzes",
                    "categories", "user_roles", "users")) sql.update("DELETE FROM " + table);
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'acceptance-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'acceptance-%'");
        });
    }




    record Fixture(UUID attempt,UUID submission,List<ParticipantForm.Item> items) { }
    private Fixture fixture(List<QuestionType> families,int answered,int correct) {
        quizId=newQuiz();
        var revision=publish(0,families.stream().map(this::draft).toList());
        binding.bind(quizId,revision);
        var start=starts.start(command(revision,AnswerOrder.AUTHORED,"start-"+UUID.randomUUID()));
        var items=delivery.read(start.attemptId()).form().items();
        for(int i=0;i<answered;i++) responses.accept(new ResponseAcceptanceService.Command(start.attemptId(),items.get(i).id(),i+1,
                CommandReceipt.Key.from("answer-"+UUID.randomUUID()),items.get(i).type(),input(items.get(i),i<correct)));
        var ack=submissions.submit(new AttemptSubmissionService.Command(start.attemptId(),answered+1,true,CommandReceipt.Key.from("submit-"+UUID.randomUUID())));
        return new Fixture(start.attemptId(),ack.submissionId(),items);
    }
    private Fixture fixture(int size,int answered,int correct) {
        return fixture(Collections.nCopies(size,QuestionType.TRUE_FALSE),answered,correct);
    }
    private ObjectNode authored(QuestionType type) {
        String content=switch(type) {
            case MCQ_SINGLE -> "{\"options\":[{\"id\":\"a\",\"text\":\"Paris\",\"correct\":true},{\"id\":\"b\",\"text\":\"Rome\",\"correct\":false}]}";
            case MCQ_MULTI -> "{\"options\":[{\"id\":\"a\",\"text\":\"A\",\"correct\":true},{\"id\":\"b\",\"text\":\"B\",\"correct\":false},{\"id\":\"c\",\"text\":\"C\",\"correct\":true}]}";
            case COMPLIANCE -> "{\"statements\":[{\"id\":1,\"text\":\"One\",\"compliant\":true},{\"id\":2,\"text\":\"Two\",\"compliant\":false},{\"id\":3,\"text\":\"Three\",\"compliant\":true}]}";
            case HOTSPOT -> "{\"imageUrl\":\"https://source.example.invalid/diagram\",\"imageDescription\":\"Three shapes\",\"regions\":[{\"id\":1,\"x\":0,\"y\":0,\"width\":20,\"height\":20,\"correct\":true,\"label\":\"Circle\"},{\"id\":2,\"x\":0,\"y\":0,\"width\":0,\"height\":0,\"correct\":false},{\"id\":3,\"x\":50,\"y\":10,\"width\":30,\"height\":40,\"correct\":true}]}";
            case TRUE_FALSE -> "{\"answer\":true}";
            case OPEN -> "{\"answer\":\"Paris\"}";
            case MATCHING -> "{\"left\":[{\"id\":1,\"text\":\"Apple\",\"matchId\":10},{\"id\":2,\"text\":\"Banana\",\"matchId\":11}],\"right\":[{\"id\":10,\"text\":\"Red\"},{\"id\":11,\"text\":\"Yellow\"}]}";
            case FILL_GAP -> "{\"text\":\"Capital: {1}\",\"gaps\":[{\"id\":1,\"answer\":\"Paris\"}]}";
            case ORDERING -> "{\"items\":[{\"id\":1,\"text\":\"One\"},{\"id\":2,\"text\":\"Two\"}],\"correctOrder\":[2,1]}";
            default -> throw new AssertionError(type);
        };
        try { return (ObjectNode)json.readTree(content); } catch(Exception failure) { throw new AssertionError(failure); }
    }
    private ObjectNode input(ParticipantForm.Item item,boolean correct) {
        var value=json.createObjectNode();
        switch(item.type()) {
            case TRUE_FALSE -> value.put("answer",correct);
            case OPEN -> value.put("answer", correct ? " pARIS " : "Rome");
            case MATCHING -> {
                var matching = (ParticipantContent.Matching) item.content();
                var pairs = value.putArray("matches");
                pairs.addObject().put("leftId", matching.left().get(0).id().toString())
                        .put("rightId", matching.right().get(correct ? 0 : 1).id().toString());
                pairs.addObject().put("leftId", matching.left().get(1).id().toString())
                        .put("rightId", matching.right().get(1).id().toString());
            }
            case COMPLIANCE -> {
                var statements = ((ParticipantContent.Compliance) item.content()).statements();
                var selected = value.putArray("selectedStatementIds").add(statements.get(0).id().toString());
                if (correct) selected.add(statements.get(2).id().toString());
            }
            case MCQ_SINGLE -> value.put("selectedOptionId",((ParticipantContent.Choices)item.content()).options().get(correct?0:1).id().toString());
            case MCQ_MULTI -> {
                var options=((ParticipantContent.Choices)item.content()).options();
                var selected=value.putArray("selectedOptionIds").add(options.get(0).id().toString());
                if(correct) selected.add(options.get(2).id().toString()); // Partial selections receive zero.
            }
            case ORDERING -> {
                var options=((ParticipantContent.Ordering)item.content()).items();
                value.putArray("orderedItemIds").add(options.get(correct?1:0).id().toString()).add(options.get(correct?0:1).id().toString());
            }
            case FILL_GAP -> value.putArray("answers").addObject().put("gapId",1).put("answer",correct?" Paris ":"Rome");
            default -> throw new AssertionError(item.type());
        }
        return value;
    }

    private long count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)); }
    private long releaseEvents() { return tx.execute(s -> sql.queryForObject(
            "SELECT COUNT(*) FROM attempt_trace_event WHERE event_type='RESULT_RELEASED'", Long.class)); }
    private ParticipantAttemptView view(UUID id) { return (ParticipantAttemptView) views.read(id); }
    private ImmediateResultReleaseService restarted() {
        return new ImmediateResultReleaseServiceImpl(context.getBean(SubmittedAttemptSource.class),
                new JdbcAutomaticResultRepository(context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)),
                new JdbcResultReleaseRepository(context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)),
                context.getBean(ExecutionTraceService.class),
                transactional(ResultNotificationEventRepository.class, new JdbcResultNotificationEventRepository(named()), manager),
                context.getBean(Clock.class), metrics, manager);
    }
    private MockMvc mvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ParticipantAttemptViewController(context.getBean(ParticipantAttemptViewService.class), java.util.Optional.empty()))
                .setControllerAdvice(new ParticipantAttemptViewErrorHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
    }
    private void reject(UUID id, ResultReleaseException.Reason reason) {
        assertThatThrownBy(() -> releases.release(id)).isInstanceOfSatisfying(ResultReleaseException.class,
                failure -> assertThat(failure.reason()).isEqualTo(reason)).hasNoCause();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void realFiveFamilyFlowWithholdsUntilReleaseThenProjectsSavedAnswersAndExactScore(boolean correct) throws Exception {
        var fixture = fixture(FAMILIES, 5, correct ? 5 : 0);
        assertThat(view(fixture.attempt()).result()).isNull();
        var result = results.finalizeSubmission(fixture.submission());
        assertThat(view(fixture.attempt()).result()).isNull();
        mvc().perform(get("/api/v2/attempts/{id}/review", fixture.attempt()).principal(SecurityContextHolder.getContext().getAuthentication())
                .param("includeCorrectAnswers", "true")).andExpect(status().isConflict());
        var first = releases.release(fixture.submission());
        assertThat(first.replayed()).isFalse();
        var rendered = view(fixture.attempt());
        assertThat(rendered.contentVisibility()).isEqualTo(ParticipantViewDisclosureReader.ContentVisibility.VISIBLE);
        assertThat(rendered.result().totalScore()).isEqualTo(new ParticipantResultView.Score(correct ? 5 : 0, 5, correct ? 10000 : 0));
        assertThat(rendered.result().reviewAvailable()).isTrue();
        assertThat(rendered.result().items()).extracting(ParticipantResultView.Item::formItemId)
                .containsExactlyElementsOf(fixture.items().stream().map(ParticipantForm.Item::id).toList());
        assertThat(rendered.result().items()).allSatisfy(item -> {
            assertThat(item.correctness()).isEqualTo(correct);
            assertThat(item.score()).isEqualTo(correct ? 1 : 0);
            assertThat(item.responseState()).isEqualTo(ParticipantResultView.ResponseState.ACCEPTED);
            assertThat(item.submittedAnswer()).isNotNull(); assertThat(item.correctAnswer()).isNotNull();
            assertThat(item.explanation()).isEqualTo("protected-explanation");
        });
        var node = json.valueToTree(rendered);
        assertThat(node.path("result").has("passed")).isFalse();
        assertThat(node.path("result").has("passThreshold")).isFalse();
        assertThat(node.toString()).doesNotContain("selectedOptionId", "policyHash", "sourceKey", "responseId");
        var single = rendered.result().items().get(0);
        assertThat(((ParticipantResultView.Options) single.correctAnswer()).optionIds())
                .containsExactly(((ParticipantContent.Choices) fixture.items().get(0).content()).options().get(0).id());
        var multi = ((ParticipantResultView.Options) rendered.result().items().get(1).correctAnswer()).optionIds();
        assertThat(multi).containsExactly(((ParticipantContent.Choices) fixture.items().get(1).content()).options().get(0).id(),
                ((ParticipantContent.Choices) fixture.items().get(1).content()).options().get(2).id());
        assertThat(rendered.result().items().get(2).correctAnswer()).isEqualTo(new ParticipantResultView.BooleanAnswer(true));
        assertThat(rendered.result().items().get(3).correctAnswer())
                .isEqualTo(new ParticipantResultView.Gaps(List.of(new ParticipantResultView.Gap(1, "Paris"))));
        var order = ((ParticipantContent.Ordering) fixture.items().get(4).content()).items();
        assertThat(rendered.result().items().get(4).correctAnswer())
                .isEqualTo(new ParticipantResultView.OrderedItems(List.of(order.get(1).id(), order.get(0).id())));
        tx.executeWithoutResult(s -> sql.update("UPDATE questions SET question='Later author edit',explanation='Later explanation'"));
        assertThat(view(fixture.attempt())).isEqualTo(rendered);
        var replay = restarted().release(fixture.submission());
        assertThat(replay.replayed()).isTrue(); assertThat(replay.release()).isEqualTo(first.release());
        assertThat(count("attempt_result_release")).isEqualTo(1); assertThat(releaseEvents()).isEqualTo(1);
        assertThat(results.finalizeSubmission(fixture.submission())).isEqualTo(result);
        verifyNoInteractions(legacy);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void openResponseFlowsThroughRealAcceptanceGradeReleaseAndHistoricalReview(boolean correct) throws Exception {
        var fixture = fixture(List.of(QuestionType.OPEN), 1, correct ? 1 : 0);
        var before = view(fixture.attempt());
        assertThat(before.result()).isNull();
        assertThat(before.items()).isNull(); // Submitted content stays withheld until committed release.
        assertThat(fixture.items().get(0).content()).isInstanceOf(ParticipantContent.Open.class);
        assertThat(json.writeValueAsString(fixture.items())).doesNotContain("Paris", "pARIS", "Rome", "protected-explanation");
        assertThat(json.writeValueAsString(before)).doesNotContain("Paris", "pARIS", "Rome", "protected-explanation");
        var result = results.finalizeSubmission(fixture.submission());
        assertThat(result.score().earned()).isEqualTo(correct ? 1 : 0);
        assertThat(result.items().get(0).responseContract()).isEqualTo(
                uk.gegc.quizmaker.features.question.application.contract.v1.SupportedQuestionContracts.OPEN_RESPONSE);
        assertThat(view(fixture.attempt()).result()).isNull();
        releases.release(fixture.submission());
        var rendered = view(fixture.attempt()); var item = rendered.result().items().get(0);
        assertThat(item.submittedAnswer()).isEqualTo(new ParticipantResultView.TextAnswer(correct ? " pARIS " : "Rome"));
        assertThat(item.correctAnswer()).isEqualTo(new ParticipantResultView.TextAnswer("Paris"));
        assertThat(item.correctness()).isEqualTo(correct);
        assertThat(item.explanation()).isEqualTo("protected-explanation");
        mvc().perform(get("/api/v2/attempts/{id}/review", fixture.attempt())
                .principal(SecurityContextHolder.getContext().getAuthentication()).param("includeCorrectAnswers", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].correctAnswer").doesNotExist());
        tx.executeWithoutResult(status -> sql.update("UPDATE questions SET content=JSON_OBJECT('answer','later key'), explanation='later explanation'"));
        // A new real publication still cannot rewrite the first attempt's captured key or score.
        var ids = tx.execute(status -> questions.findAll().stream().map(Question::getId).toList());
        var later = publish(1, ids); binding.bind(quizId, later);
        assertThat(view(fixture.attempt())).isEqualTo(rendered);
        assertThat(results.finalizeSubmission(fixture.submission())).isEqualTo(result);
        assertThat(restarted().release(fixture.submission()).replayed()).isTrue();
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
        verifyNoInteractions(legacy);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void matchingResponseFlowsThroughRealWritesGradeReleaseAndHistoricalReview(boolean correct) throws Exception {
        var fixture = fixture(List.of(QuestionType.MATCHING), 1, correct ? 1 : 0);
        assertThat(fixture.items().get(0).content()).isInstanceOf(ParticipantContent.Matching.class);
        assertThat(json.writeValueAsString(fixture.items())).doesNotContain("matchId", "sourceKey", "protected-explanation");
        assertThat(view(fixture.attempt()).result()).isNull();
        var result = results.finalizeSubmission(fixture.submission());
        assertThat(result.score().earned()).isEqualTo(correct ? 1 : 0);
        assertThat(result.items().get(0).responseContract()).isEqualTo(
                uk.gegc.quizmaker.features.question.application.contract.v1.SupportedQuestionContracts.MATCHING_RESPONSE);
        releases.release(fixture.submission()); var original = view(fixture.attempt());
        var matching = (ParticipantContent.Matching) fixture.items().get(0).content();
        var expected = new ParticipantResultView.MatchingPairs(List.of(
                new ParticipantResultView.MatchingPair(matching.left().get(0).id(), matching.right().get(0).id()),
                new ParticipantResultView.MatchingPair(matching.left().get(1).id(), matching.right().get(1).id())));
        assertThat(original.result().items().get(0).correctAnswer()).isEqualTo(expected);
        assertThat(original.result().items().get(0).correctness()).isEqualTo(correct);
        mvc().perform(get("/api/v2/attempts/{id}/review", fixture.attempt())
                .principal(SecurityContextHolder.getContext().getAuthentication()).param("includeCorrectAnswers", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].correctAnswer").doesNotExist());
        tx.executeWithoutResult(s -> {
            for (var q : questions.findAll()) {
                var content = authored(QuestionType.MATCHING);
                ((ObjectNode) content.path("left").get(0)).put("matchId", 11);
                q.setContent(content.toString()); questions.saveAndFlush(q);
            }
        });
        var later = publish(1, tx.execute(s -> questions.findAll().stream().map(Question::getId).toList())); binding.bind(quizId, later);
        assertThat(view(fixture.attempt())).isEqualTo(original);
        assertThat(results.finalizeSubmission(fixture.submission())).isEqualTo(result);
        assertThat(restarted().release(fixture.submission()).replayed()).isTrue();
        assertThat(count("attempt_accepted_response")).isEqualTo(1); verifyNoInteractions(legacy);
    }

    private record MatchingAttempt(UUID id, ParticipantForm.Item item) { }
    private MatchingAttempt matchingAttempt(AnswerOrder order) {
        quizId = newQuiz(); var revision = publish(0, List.of(draft(QuestionType.MATCHING))); binding.bind(quizId, revision);
        var start = starts.start(command(revision, order, "matching-start-" + UUID.randomUUID()));
        return new MatchingAttempt(start.attemptId(), delivery.read(start.attemptId()).form().items().get(0));
    }
    private ResponseAcceptanceService.Command matchingCommand(MatchingAttempt attempt, String key, com.fasterxml.jackson.databind.JsonNode payload) {
        return new ResponseAcceptanceService.Command(attempt.id(), attempt.item().id(), 1, CommandReceipt.Key.from(key), QuestionType.MATCHING, payload);
    }

    @Test void repeatedMatchingLeftIdsKeepTheirOrderThroughDurableAcceptanceAndGrading() {
        var attempt = matchingAttempt(AnswerOrder.AUTHORED); var payload = input(attempt.item(), true);
        var last = payload.withArray("matches").get(0).deepCopy();
        ((ObjectNode) payload.path("matches").get(0)).put("rightId", ((ParticipantContent.Matching) attempt.item().content()).right().get(1).id().toString());
        payload.withArray("matches").add(last); var command = matchingCommand(attempt, "duplicate-pairs", payload);
        var accepted = responses.accept(command); assertThat(responses.accept(command).replayed()).isTrue();
        var stored = tx.execute(s -> sql.queryForObject("SELECT normalized_response FROM attempt_accepted_response", String.class));
        assertThat(stored).contains("\"leftId\":1", "\"rightId\":10", "\"rightId\":11");
        var changed = payload.deepCopy(); var array = changed.withArray("matches"); var first = array.remove(0); array.add(first);
        assertThatThrownBy(() -> responses.accept(matchingCommand(attempt, "duplicate-pairs", changed))).isInstanceOf(RuntimeException.class);
        var submitted = submissions.submit(new AttemptSubmissionService.Command(attempt.id(), accepted.stateVersion(), true, CommandReceipt.Key.from("matching-submit")));
        assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isEqualTo(1);
        releases.release(submitted.submissionId());
        assertThat(((ParticipantResultView.MatchingPairs) view(attempt.id()).result().items().get(0).submittedAnswer()).matches()).hasSize(3);
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "wrong-side", "malformed"})
    void invalidMatchingReferencesLeaveNoAnswerReceiptStateOrTraceAndValidControlCommits(String defect) {
        var own = matchingAttempt(AnswerOrder.AUTHORED); var payload = input(own.item(), true);
        String id = defect.equals("foreign") ? UUID.randomUUID().toString() : ((ParticipantContent.Matching) own.item().content()).right().get(0).id().toString();
        ((ObjectNode) payload.path("matches").get(0)).put("leftId", defect.equals("malformed") ? "1" : id);
        var before = view(own.id()); var receipts = count("attempt_command_receipt"); var traces = count("attempt_trace_event");
        assertThatThrownBy(() -> responses.accept(matchingCommand(own, "invalid-match", payload))).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(count("attempt_trace_event")).isEqualTo(traces); assertThat(view(own.id())).isEqualTo(before);
        assertThat(responses.accept(matchingCommand(own, "invalid-match", input(own.item(), true))).replayed()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"attempt_accepted_response", "attempt_lifecycle_state", "attempt_trace_event", "attempt_command_receipt"})
    void reachedMatchingWriteFailuresRollBackThenRetryAndReplayOnce(String table) {
        var attempt = matchingAttempt(AnswerOrder.AUTHORED); var command = matchingCommand(attempt, "durable-matching", input(attempt.item(), true));
        var before = view(attempt.id()); var receipts = count("attempt_command_receipt"); var traces = count("attempt_trace_event");
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            boolean checkpoint = statement.contains(table) && (table.equals("attempt_accepted_response") || table.equals("attempt_trace_event")
                    ? statement.stripLeading().startsWith("INSERT") : statement.stripLeading().startsWith("UPDATE"));
            if (checkpoint && reached.compareAndSet(false, true)) throw new SQLException("matching-write-fixture", "08006");
        };
        try { assertThatThrownBy(() -> responses.accept(command)).isInstanceOf(RuntimeException.class); }
        finally { observer().reset(); }
        assertThat(reached).isTrue(); assertThat(count("attempt_accepted_response")).isZero();
        assertThat(count("attempt_command_receipt")).isEqualTo(receipts); assertThat(count("attempt_trace_event")).isEqualTo(traces);
        assertThat(view(attempt.id())).isEqualTo(before);
        var accepted = responses.accept(command);
        var recreated = new ResponseAcceptanceServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), context.getBean(AttemptAccessService.class),
                context.getBean(AttemptReadFactsService.class), context.getBean(AttemptLifecycleService.class), context.getBean(AcceptedResponseRepository.class),
                context.getBean(CommandReceiptService.class), context.getBean(ExecutionTraceService.class), context.getBean(Clock.class), metrics, manager);
        assertThat(recreated.accept(command).acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
        assertThat(recreated.accept(command).replayed()).isTrue(); assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @Test void matchingParticipatesInCallerRcRollbackAndReauthorizesReplay() {
        var own = matchingAttempt(AnswerOrder.AUTHORED); var command = matchingCommand(own, "outer-matching", input(own.item(), true));
        var outer = new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(s -> { responses.accept(command); s.setRollbackOnly(); });
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(view(own.id()).stateVersion()).isEqualTo(1);
        responses.accept(command); SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> responses.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        var foreign = user("matching-foreign-" + UUID.randomUUID().toString().substring(0, 8), true); grant(foreign, "ATTEMPT_READ", "ATTEMPT_CREATE");
        authenticate(sql.queryForObject("SELECT username FROM users WHERE user_id=UUID_TO_BIN(?)", String.class, foreign.toString()));
        assertThatThrownBy(() -> responses.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        authenticate(username); assertThat(responses.accept(command).replayed()).isTrue();
    }

    @Test void concurrentIdenticalMatchingCommandsCommitOneAcceptanceAndReplayTheWinner() throws Exception {
        var attempt = matchingAttempt(AnswerOrder.AUTHORED); var command = matchingCommand(attempt, "concurrent-matching", input(attempt.item(), true));
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        Callable<ResponseAcceptanceService.Result> work = () -> {
            authenticate(username);
            try { barrier.await(10, TimeUnit.SECONDS); return responses.accept(command); }
            finally { SecurityContextHolder.clearContext(); }
        };
        try {
            var first = pool.submit(work); var second = pool.submit(work);
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(true, false);
            assertThat(a.acceptedResponseId()).isEqualTo(b.acceptedResponseId());
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        assertThat(count("attempt_accepted_response")).isEqualTo(1); assertThat(view(attempt.id()).stateVersion()).isEqualTo(2);
    }

    @Test void mixedMatchingBatchRejectsForeignReferencesAtomicallyThenReplaysAndGradesRealWrites() {
        quizId = newQuiz(); var revision = publish(0, List.of(draft(QuestionType.TRUE_FALSE), draft(QuestionType.MATCHING))); binding.bind(quizId, revision);
        var start = starts.start(command(revision, AnswerOrder.AUTHORED, "matching-batch-start"));
        var items = delivery.read(start.attemptId()).form().items(); var payload = input(items.get(1), true);
        var truth = new ResponseBatchAcceptanceService.BatchItem(items.get(0).id(), QuestionType.TRUE_FALSE, json.createObjectNode().put("answer", true));
        var invalid = payload.deepCopy(); ((ObjectNode) invalid.path("matches").get(0)).put("rightId", UUID.randomUUID().toString());
        var key = CommandReceipt.Key.from("matching-batch"); var receipts = count("attempt_command_receipt"); var traces = count("attempt_trace_event");
        assertThatThrownBy(() -> batches.acceptBatch(new ResponseBatchAcceptanceService.BatchCommand(start.attemptId(), 1, key,
                List.of(truth, new ResponseBatchAcceptanceService.BatchItem(items.get(1).id(), QuestionType.MATCHING, invalid)))))
                .isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(count("attempt_trace_event")).isEqualTo(traces);
        var command = new ResponseBatchAcceptanceService.BatchCommand(start.attemptId(), 1, key,
                List.of(truth, new ResponseBatchAcceptanceService.BatchItem(items.get(1).id(), QuestionType.MATCHING, payload)));
        var accepted = batches.acceptBatch(command); assertThat(accepted.responses()).hasSize(2);
        assertThat(batches.acceptBatch(command).responses()).isEqualTo(accepted.responses()); assertThat(batches.acceptBatch(command).replayed()).isTrue();
        var submitted = submissions.submit(new AttemptSubmissionService.Command(start.attemptId(), accepted.stateVersion(), true, CommandReceipt.Key.from("matching-batch-submit")));
        assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isEqualTo(2);
    }

    @Test void matchingReleasedViewsKeepSqlBoundedAcrossThreeParentsAndOneVersusTwentyItems() throws Exception {
        var totals = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var fixtures = new ArrayList<Fixture>();
            for (int parent = 0; parent < 3; parent++) {
                var f = fixture(Collections.nCopies(size, QuestionType.MATCHING), size, size);
                results.finalizeSubmission(f.submission()); releases.release(f.submission()); fixtures.add(f);
            }
            observer().selects.clear();
            for (var f : fixtures) {
                var rendered = json.readTree(json.writeValueAsString(view(f.attempt())));
                assertThat(rendered.path("result").path("items")).hasSize(size);
                assertThat(rendered.at("/result/totalScore/earned").intValue()).isEqualTo(size);
                assertThat(rendered.toString()).doesNotContain("matchId", "sourceKey", "left/", "right/");
            }
            totals.add(observer().selects.size());
        }
        assertThat(totals.get(0)).isPositive().isLessThan(150); assertThat(totals.get(1)).isEqualTo(totals.get(0));
        System.out.println("MATCHING released result SQL for 3 parents with 1/20 items: " + totals);
    }

    @ParameterizedTest @ValueSource(strings = {"correct", "reversed", "partial", "empty", "extra", "duplicate"})
    void realComplianceSelectionsKeepBinaryPointsAndIndependentReviewDisclosure(String variant) throws Exception {
        var attempt = complianceAttempt(AnswerOrder.AUTHORED); var payload = input(attempt.item(), true);
        var statements = ((ParticipantContent.Compliance) attempt.item().content()).statements();
        var ids = payload.withArray("selectedStatementIds");
        switch (variant) {
            case "reversed" -> { var first = ids.remove(0); ids.add(first); }
            case "partial" -> ids.remove(1);
            case "empty" -> ids.removeAll();
            case "extra" -> ids.add(statements.get(1).id().toString());
            case "duplicate" -> ids.add(ids.get(0));
            case "correct" -> { }
            default -> throw new AssertionError(variant);
        }
        var accepted = responses.accept(complianceCommand(attempt, "selection-flow", payload));
        assertThat(responses.accept(complianceCommand(attempt, "selection-flow", payload)).replayed()).isTrue();
        assertThat(view(attempt.id()).result()).isNull();
        var submitted = submissions.submit(new AttemptSubmissionService.Command(attempt.id(), accepted.stateVersion(), true,
                CommandReceipt.Key.from("selection-flow-submit")));
        boolean correct = variant.equals("correct") || variant.equals("reversed");
        var result = results.finalizeSubmission(submitted.submissionId());
        assertThat(result.score().earned()).isEqualTo(correct ? 1 : 0);
        assertThat(result.items().get(0).responseContract()).isEqualTo(
                uk.gegc.quizmaker.features.question.application.contract.v1.SupportedQuestionContracts.COMPLIANCE_RESPONSE);
        releases.release(submitted.submissionId()); var item = view(attempt.id()).result().items().get(0);
        assertThat(item.correctness()).isEqualTo(correct);
        assertThat(((ParticipantResultView.Options) item.submittedAnswer()).optionIds()).hasSize(ids.size());
        assertThat(((ParticipantResultView.Options) item.correctAnswer()).optionIds())
                .containsExactly(statements.get(0).id(), statements.get(2).id());
        mvc().perform(get("/api/v2/attempts/{id}/review", attempt.id())
                .principal(SecurityContextHolder.getContext().getAuthentication()).param("includeCorrectAnswers", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].correctAnswer").doesNotExist());
        assertThat(count("attempt_accepted_response")).isEqualTo(1); assertThat(count("answers")).isZero();
    }

    private record ComplianceAttempt(UUID id, ParticipantForm.Item item) { }
    private ComplianceAttempt complianceAttempt(AnswerOrder order) {
        quizId = newQuiz(); var revision = publish(0, List.of(draft(QuestionType.COMPLIANCE))); binding.bind(quizId, revision);
        var start = starts.start(command(revision, order, "compliance-start-" + UUID.randomUUID()));
        return new ComplianceAttempt(start.attemptId(), delivery.read(start.attemptId()).form().items().get(0));
    }
    private ResponseAcceptanceService.Command complianceCommand(ComplianceAttempt attempt, String key, com.fasterxml.jackson.databind.JsonNode payload) {
        return new ResponseAcceptanceService.Command(attempt.id(), attempt.item().id(), 1, CommandReceipt.Key.from(key), QuestionType.COMPLIANCE, payload);
    }

    @Test void duplicateComplianceSelectionsStayAcceptedWrongAnswersThroughDurableGradingAndReview() throws Exception {
        var attempt = complianceAttempt(AnswerOrder.AUTHORED); var payload = input(attempt.item(), true);
        payload.withArray("selectedStatementIds").add(payload.path("selectedStatementIds").get(0));
        var command = complianceCommand(attempt, "duplicate-statements", payload);
        var accepted = responses.accept(command); assertThat(responses.accept(command).replayed()).isTrue();
        String storedJson = tx.execute(s -> sql.queryForObject("SELECT normalized_response FROM attempt_accepted_response", String.class));
        var stored = json.readTree(storedJson);
        assertThat(stored.path("selectedStatementIds")).hasSize(3);
        var changed = payload.deepCopy(); changed.withArray("selectedStatementIds").remove(2);
        assertThatThrownBy(() -> responses.accept(complianceCommand(attempt, "duplicate-statements", changed))).isInstanceOf(ResponseAcceptanceException.class);
        var submitted = submissions.submit(new AttemptSubmissionService.Command(attempt.id(), accepted.stateVersion(), true, CommandReceipt.Key.from("compliance-submit")));
        assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isZero(); releases.release(submitted.submissionId());
        var item = view(attempt.id()).result().items().get(0);
        assertThat(((ParticipantResultView.Options) item.submittedAnswer()).optionIds()).hasSize(3);
        assertThat(((ParticipantResultView.Options) item.correctAnswer()).optionIds()).hasSize(2);
        assertThat(item.correctness()).isFalse(); assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"foreign", "malformed"})
    void invalidComplianceReferencesLeaveNoAnswerReceiptStateOrTraceAndValidControlCommits(String defect) {
        var own = complianceAttempt(AnswerOrder.AUTHORED); var payload = input(own.item(), true);
        payload.withArray("selectedStatementIds").set(0, json.getNodeFactory().textNode(defect.equals("malformed") ? "1" : UUID.randomUUID().toString()));
        var before = view(own.id()); var receipts = count("attempt_command_receipt"); var traces = count("attempt_trace_event");
        assertThatThrownBy(() -> responses.accept(complianceCommand(own, "invalid-compliance", payload))).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(count("attempt_trace_event")).isEqualTo(traces); assertThat(view(own.id())).isEqualTo(before);
        assertThat(responses.accept(complianceCommand(own, "invalid-compliance", input(own.item(), true))).replayed()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"attempt_accepted_response", "attempt_lifecycle_state", "attempt_trace_event", "attempt_command_receipt"})
    void reachedComplianceWriteFailuresRollBackThenRetryAndReplayOnce(String table) {
        var attempt = complianceAttempt(AnswerOrder.AUTHORED); var command = complianceCommand(attempt, "durable-compliance", input(attempt.item(), true));
        var before = view(attempt.id()); var receipts = count("attempt_command_receipt"); var traces = count("attempt_trace_event");
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            boolean checkpoint = statement.contains(table) && (table.equals("attempt_accepted_response") || table.equals("attempt_trace_event")
                    ? statement.stripLeading().startsWith("INSERT") : statement.stripLeading().startsWith("UPDATE"));
            if (checkpoint && reached.compareAndSet(false, true)) throw new SQLException("compliance-write-fixture", "08006");
        };
        try { assertThatThrownBy(() -> responses.accept(command)).isInstanceOf(RuntimeException.class); }
        finally { observer().reset(); }
        assertThat(reached).isTrue(); assertThat(count("attempt_accepted_response")).isZero();
        assertThat(count("attempt_command_receipt")).isEqualTo(receipts); assertThat(count("attempt_trace_event")).isEqualTo(traces);
        assertThat(view(attempt.id())).isEqualTo(before);
        var accepted = responses.accept(command);
        var recreated = new ResponseAcceptanceServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), context.getBean(AttemptAccessService.class),
                context.getBean(AttemptReadFactsService.class), context.getBean(AttemptLifecycleService.class), context.getBean(AcceptedResponseRepository.class),
                context.getBean(CommandReceiptService.class), context.getBean(ExecutionTraceService.class), context.getBean(Clock.class), metrics, manager);
        assertThat(recreated.accept(command).acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
        assertThat(recreated.accept(command).replayed()).isTrue(); assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @Test void complianceParticipatesInCallerRcRollbackAndReauthorizesReplay() {
        var own = complianceAttempt(AnswerOrder.AUTHORED); var command = complianceCommand(own, "outer-compliance", input(own.item(), true));
        var outer = new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(s -> { responses.accept(command); s.setRollbackOnly(); });
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(view(own.id()).stateVersion()).isEqualTo(1);
        responses.accept(command); SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> responses.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        var foreign = user("compliance-foreign-" + UUID.randomUUID().toString().substring(0, 8), true); grant(foreign, "ATTEMPT_READ", "ATTEMPT_CREATE");
        authenticate(sql.queryForObject("SELECT username FROM users WHERE user_id=UUID_TO_BIN(?)", String.class, foreign.toString()));
        assertThatThrownBy(() -> responses.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        authenticate(username); assertThat(responses.accept(command).replayed()).isTrue();
    }

    @Test void concurrentIdenticalComplianceCommandsCommitOneAcceptanceAndReplayTheWinner() throws Exception {
        var attempt = complianceAttempt(AnswerOrder.AUTHORED); var command = complianceCommand(attempt, "concurrent-compliance", input(attempt.item(), true));
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        Callable<ResponseAcceptanceService.Result> work = () -> {
            authenticate(username);
            try { barrier.await(10, TimeUnit.SECONDS); return responses.accept(command); }
            finally { SecurityContextHolder.clearContext(); }
        };
        try {
            var first = pool.submit(work); var second = pool.submit(work);
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat(List.of(a.replayed(), b.replayed())).containsExactlyInAnyOrder(true, false);
            assertThat(a.acceptedResponseId()).isEqualTo(b.acceptedResponseId());
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        assertThat(count("attempt_accepted_response")).isEqualTo(1); assertThat(view(attempt.id()).stateVersion()).isEqualTo(2);
    }

    @Test void mixedComplianceBatchRejectsForeignReferencesAtomicallyThenReplaysAndGradesRealWrites() {
        quizId = newQuiz(); var revision = publish(0, List.of(draft(QuestionType.TRUE_FALSE), draft(QuestionType.COMPLIANCE))); binding.bind(quizId, revision);
        var start = starts.start(command(revision, AnswerOrder.AUTHORED, "compliance-batch-start"));
        var items = delivery.read(start.attemptId()).form().items(); var payload = input(items.get(1), true);
        var truth = new ResponseBatchAcceptanceService.BatchItem(items.get(0).id(), QuestionType.TRUE_FALSE, json.createObjectNode().put("answer", true));
        var invalid = payload.deepCopy(); invalid.withArray("selectedStatementIds").set(0, json.getNodeFactory().textNode(UUID.randomUUID().toString()));
        var key = CommandReceipt.Key.from("compliance-batch"); var receipts = count("attempt_command_receipt"); var traces = count("attempt_trace_event");
        assertThatThrownBy(() -> batches.acceptBatch(new ResponseBatchAcceptanceService.BatchCommand(start.attemptId(), 1, key,
                List.of(truth, new ResponseBatchAcceptanceService.BatchItem(items.get(1).id(), QuestionType.COMPLIANCE, invalid)))))
                .isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(count("attempt_trace_event")).isEqualTo(traces);
        var command = new ResponseBatchAcceptanceService.BatchCommand(start.attemptId(), 1, key,
                List.of(truth, new ResponseBatchAcceptanceService.BatchItem(items.get(1).id(), QuestionType.COMPLIANCE, payload)));
        var accepted = batches.acceptBatch(command); assertThat(accepted.responses()).hasSize(2);
        assertThat(batches.acceptBatch(command).responses()).isEqualTo(accepted.responses()); assertThat(batches.acceptBatch(command).replayed()).isTrue();
        var submitted = submissions.submit(new AttemptSubmissionService.Command(start.attemptId(), accepted.stateVersion(), true, CommandReceipt.Key.from("compliance-batch-submit")));
        assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isEqualTo(2);
    }

    @Test void complianceReleasedViewsKeepSqlBoundedAcrossThreeParentsAndOneVersusTwentyItems() throws Exception {
        var totals = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var fixtures = new ArrayList<Fixture>();
            for (int parent = 0; parent < 3; parent++) {
                var f = fixture(Collections.nCopies(size, QuestionType.COMPLIANCE), size, size);
                results.finalizeSubmission(f.submission()); releases.release(f.submission()); fixtures.add(f);
            }
            observer().selects.clear();
            for (var f : fixtures) {
                var rendered = json.readTree(json.writeValueAsString(view(f.attempt())));
                assertThat(rendered.path("result").path("items")).hasSize(size);
                assertThat(rendered.at("/result/totalScore/earned").intValue()).isEqualTo(size);
                assertThat(rendered.toString()).doesNotContain("compliant", "sourceKey", "selectedStatementIds");
            }
            totals.add(observer().selects.size());
        }
        assertThat(totals.get(0)).isPositive().isLessThan(150); assertThat(totals.get(1)).isEqualTo(totals.get(0));
        System.out.println("COMPLIANCE released result SQL for 3 parents with 1/20 items: " + totals);
    }

    @Test void complianceSelectionFromAnotherRealFormIsRejectedWithoutMutation() {
        var own = complianceAttempt(AnswerOrder.AUTHORED); var other = complianceAttempt(AnswerOrder.AUTHORED);
        var payload = input(own.item(), true);
        payload.withArray("selectedStatementIds").set(0, json.getNodeFactory().textNode(
                ((ParticipantContent.Compliance) other.item().content()).statements().get(0).id().toString()));
        var before = view(own.id()); var receipts = count("attempt_command_receipt"); var traces = count("attempt_trace_event");
        assertThatThrownBy(() -> responses.accept(complianceCommand(own, "cross-item", payload))).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(count("attempt_trace_event")).isEqualTo(traces); assertThat(view(own.id())).isEqualTo(before);
        assertThat(responses.accept(complianceCommand(own, "cross-item", input(own.item(), true))).replayed()).isFalse();
    }

    @ParameterizedTest @EnumSource(AnswerOrder.class)
    void complianceSavedStatementOrderResponseAndScoreSurviveAuthorEdits(AnswerOrder order) {
        var id = draft(QuestionType.COMPLIANCE); var revision = publish(0, List.of(id)); binding.bind(quizId, revision);
        var captured = publications.read(quizId, revision).content().items().get(0).question();
        var start = starts.start(command(revision, order, "compliance-order"));
        var item = delivery.read(start.attemptId()).form().items().get(0);
        var payload = json.createObjectNode(); var ids = payload.putArray("selectedStatementIds");
        captured.options().stream().filter(o -> o.sourceKey().equals("1") || o.sourceKey().equals("3"))
                .forEach(o -> ids.add(o.id().toString()));
        var accepted = responses.accept(new ResponseAcceptanceService.Command(start.attemptId(), item.id(), 1,
                CommandReceipt.Key.from("compliance-captured"), QuestionType.COMPLIANCE, payload));
        var submitted = submissions.submit(new AttemptSubmissionService.Command(start.attemptId(), accepted.stateVersion(), true,
                CommandReceipt.Key.from("compliance-captured-submit")));
        var result = results.finalizeSubmission(submitted.submissionId()); releases.release(submitted.submissionId());
        var original = view(start.attemptId());
        assertThat(result.score().earned()).isEqualTo(1);
        assertThat(original.items().get(0).content()).isEqualTo(AttemptFormResponse.items(
                new ParticipantForm(UUID.randomUUID(), 1, List.of(item))).get(0).content());
        tx.executeWithoutResult(s -> {
            var q = questions.findById(id).orElseThrow(); var content = authored(QuestionType.COMPLIANCE);
            content.path("statements").forEach(v -> ((ObjectNode) v).put("compliant", v.path("id").asInt() == 2));
            q.setContent(content.toString()); q.setQuestionText("Changed author text"); questions.saveAndFlush(q);
        });
        var later = publish(1, List.of(id)); binding.bind(quizId, later);
        assertThat(view(start.attemptId())).isEqualTo(original);
        assertThat(results.finalizeSubmission(submitted.submissionId())).isEqualTo(result);
        assertThat(restarted().release(submitted.submissionId()).replayed()).isTrue();
    }

    // HOTSPOT proves the internal response/scoring foundation. Public image/description delivery
    // is deliberately not supplied by these fixtures and remains gated by #818/#609.
    record HotspotPublication(UUID revision, UUID asset, List<UUID> questions) { }
    record HotspotAttempt(HotspotPublication publication, AttemptStartService.Result start, ParticipantForm form) { }

    private HotspotPublication hotspotPublication(int size) {
        quizId = newQuiz();
        UUID asset = tx.execute(s -> {
            var a = new uk.gegc.quizmaker.features.media.domain.model.MediaAsset(); a.setId(UUID.randomUUID());
            a.setType(uk.gegc.quizmaker.features.media.domain.model.MediaAssetType.IMAGE);
            a.setStatus(uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus.READY);
            a.setKey("hotspot/" + a.getId()); a.setMimeType("image/png"); a.setSizeBytes(1024L); a.setWidth(640); a.setHeight(480);
            a.setCreatedBy(username); return mediaAssets.saveAndFlush(a).getId();
        });
        imageStorage.preparing = true;
        try { retainedImages.prepare(Set.of(asset), username); } finally { imageStorage.preparing = false; }
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < size; i++) {
            var id = draft(QuestionType.HOTSPOT); ids.add(id);
            tx.executeWithoutResult(s -> {
                var q = questions.findById(id).orElseThrow(); q.setAttachmentAssetId(asset); questions.saveAndFlush(q);
                retainedImages.bind(id, Set.of(asset), Set.of(), username);
            });
        }
        var revision = publish(0, ids);
        assertThat(publications.read(quizId, revision).images()).hasSize(size);
        return new HotspotPublication(revision, asset, List.copyOf(ids));
    }
    private HotspotAttempt hotspotAttempt(int size, AnswerOrder order) {
        var publication = hotspotPublication(size); binding.bind(quizId, publication.revision());
        var started = starts.start(command(publication.revision(), order, "hotspot-start-" + UUID.randomUUID()));
        return new HotspotAttempt(publication, started, hotspotForm(started.attemptId()));
    }
    private ParticipantForm hotspotForm(UUID attempt) {
        var read = new TransactionTemplate(manager); read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); read.setReadOnly(true);
        return read.execute(s -> {
            var facts = context.getBean(AttemptReadFactsService.class).read(attempt, AttemptReadFactsService.Purpose.PARTICIPANT_VIEW);
            return ParticipantFormProjector.project(facts.form(), facts.revision(), facts.order(), facts.contracts());
        });
    }
    private ObjectNode hotspotInput(HotspotAttempt attempt, int item, String sourceKey) {
        var captured = publications.read(quizId, attempt.publication().revision()).content().items().get(item).question();
        var id = captured.options().stream().filter(o -> o.sourceKey().equals(sourceKey)).findFirst().orElseThrow().id();
        return json.createObjectNode().put("selectedRegionId", id.toString());
    }
    private ResponseAcceptanceService.Command hotspotCommand(HotspotAttempt attempt, String key, ObjectNode payload) {
        return new ResponseAcceptanceService.Command(attempt.start().attemptId(), attempt.form().items().get(0).id(), 1,
                CommandReceipt.Key.from(key), QuestionType.HOTSPOT, payload);
    }
    private QuestionRevisionContractService restartedHotspotContracts() {
        var registry = new QuestionContractRegistryImpl(List.of(new HotspotResponseContractV1(), hotspotResponseV2()),
                List.of(new HotspotScoringContractV1(), hotspotScoringV2()), new SimpleMeterRegistry());
        return new QuestionRevisionContractServiceImpl(new JdbcQuestionContractBindingRepository(
                context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)), registry, manager);
    }
    private static VersionedResponseContract hotspotResponseV2() {
        return new VersionedResponseContract() {
            public ContractVersion version() { return new ContractVersion("question.hotspot.response", 2); }
            public void validate(uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision q, com.fasterxml.jackson.databind.JsonNode value) {
                new HotspotResponseContractV1().validate(q, value);
            }
        };
    }
    private static VersionedScoringContract hotspotScoringV2() {
        // Deliberately distinguishable test-only version, never a production scoring-policy change.
        return new VersionedScoringContract() {
            public ContractVersion version() { return new ContractVersion("question.hotspot.binary", 2); }
            public ContractScore score(uk.gegc.quizmaker.features.quiz.domain.model.revision.QuestionRevision q, com.fasterxml.jackson.databind.JsonNode value) {
                var v1 = new HotspotScoringContractV1().score(q, value);
                return new ContractScore(java.math.BigDecimal.ONE.subtract(v1.earned()), java.math.BigDecimal.ONE);
            }
        };
    }

    @ParameterizedTest @EnumSource(AnswerOrder.class)
    void hotspotRealStartAcceptedAnswerAndExactGradeSurviveEditsAndRestart(AnswerOrder order) {
        var attempt = hotspotAttempt(1, order); var command = hotspotCommand(attempt, "hotspot-answer", hotspotInput(attempt, 0, "3"));
        int storageCalls = imageStorage.calls;
        var accepted = responses.accept(command);
        assertThat(responses.accept(command).replayed()).isTrue();
        String savedResponse = tx.execute(s -> sql.queryForObject("SELECT normalized_response FROM attempt_accepted_response WHERE id=UUID_TO_BIN(?)",
                String.class, accepted.acceptedResponseId().toString()));
        assertThat(savedResponse).isEqualTo("{\"selectedRegionId\":3}");
        var submitted = submissions.submit(new AttemptSubmissionService.Command(attempt.start().attemptId(), accepted.stateVersion(), true,
                CommandReceipt.Key.from("hotspot-submit")));
        var result = results.finalizeSubmission(submitted.submissionId()); assertThat(result.score().earned()).isEqualTo(1);
        retainedImages.remove(attempt.publication().asset(), username);
        tx.executeWithoutResult(s -> {
            var q = questions.findById(attempt.publication().questions().get(0)).orElseThrow(); var edited = authored(QuestionType.HOTSPOT);
            edited.path("regions").forEach(r -> ((ObjectNode) r).put("correct", r.path("id").asInt() == 2));
            ((ObjectNode) edited.path("regions").get(0)).put("x", 600).put("label", "Changed label");
            edited.put("imageDescription", "Changed description"); q.setContent(edited.toString()); questions.saveAndFlush(q);
        });
        var later = publish(1, attempt.publication().questions()); binding.bind(quizId, later);
        assertThat(hotspotForm(attempt.start().attemptId())).isEqualTo(attempt.form());
        var recreated = new AutomaticResultServiceImpl(context.getBean(SubmittedAttemptSource.class), context.getBean(AutomaticResultRepository.class),
                context.getBean(ExecutionTraceService.class), context.getBean(Clock.class), metrics, manager);
        assertThat(recreated.finalizeSubmission(submitted.submissionId())).isEqualTo(result);
        assertThat(restartedHotspotContracts().resolve(ownerId, attempt.publication().revision()).values())
                .allSatisfy(pair -> assertThat(pair.binding().response()).isEqualTo(SupportedQuestionContracts.HOTSPOT_RESPONSE));
        assertThat(count("attempt_accepted_response")).isEqualTo(1); assertThat(imageStorage.calls).isEqualTo(storageCalls);
        assertThatThrownBy(() -> AttemptFormResponse.items(attempt.form())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void hotspotExactSavedV1AndTestV2RemainDistinctAfterAuthorEdits() {
        var publication = hotspotPublication(1); binding.bind(quizId, publication.revision());
        tx.executeWithoutResult(s -> {
            var q = questions.findById(publication.questions().get(0)).orElseThrow(); var content = authored(QuestionType.HOTSPOT);
            content.path("regions").forEach(r -> ((ObjectNode) r).put("correct", r.path("id").asInt() == 2));
            q.setContent(content.toString()); questions.saveAndFlush(q);
        });
        var later = publish(1, publication.questions()); var q2 = publications.read(quizId, later).content().items().get(0).question();
        var bindingV2 = new QuestionContractBinding(q2.id(), hotspotResponseV2().version(), hotspotScoringV2().version());
        questionContracts.bind(ownerId, later, List.of(bindingV2));
        var q1 = publications.read(quizId, publication.revision()).content().items().get(0).question();
        var payload = json.createObjectNode().put("selectedRegionId", 1); var restarted = restartedHotspotContracts();
        assertThat(restarted.resolve(ownerId, publication.revision()).get(q1.id()).scoring().score(q1, payload).earned()).isEqualByComparingTo("1");
        assertThat(new HotspotScoringContractV1().score(q2, payload).earned()).isEqualByComparingTo("0");
        assertThat(restarted.resolve(ownerId, later).get(q2.id()).scoring().score(q2, payload).earned()).isEqualByComparingTo("1");
        assertThat(restarted.resolve(ownerId, later).get(q2.id()).binding()).isEqualTo(bindingV2);
    }

    @Test void hotspotUnknownContractAndReachedBindingFailurePreserveCommittedPublication() {
        var publication = hotspotPublication(2); var before = publications.current(quizId).orElseThrow();
        var expected = new ArrayList<>(publications.read(quizId, publication.revision()).content().items().stream()
                .map(item -> SupportedQuestionContracts.binding(item.question())).toList());
        var last = expected.get(1); expected.set(1, new QuestionContractBinding(last.questionRevisionId(), last.response(),
                new ContractVersion(last.scoring().id(), 99)));
        assertThatThrownBy(() -> questionContracts.bind(ownerId, publication.revision(), expected)).isInstanceOf(ContractResolutionException.class);
        assertThat(count("question_revision_contract_bindings")).isZero(); assertThat(publications.current(quizId).orElseThrow()).isEqualTo(before);
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO question_revision_contract_bindings") && reached.compareAndSet(false, true))
                throw new SQLException("hotspot-binding-write-failure", "08006");
        };
        assertThatThrownBy(() -> binding.bind(quizId, publication.revision())).isInstanceOf(ContractResolutionException.class);
        assertThat(reached).isTrue(); observer().reset();
        assertThat(count("question_revision_contract_bindings")).isZero(); assertThat(publications.current(quizId).orElseThrow()).isEqualTo(before);
        assertThat(count("quiz_publications")).isEqualTo(1); assertThat(count("quiz_publication_receipts")).isEqualTo(1);
        assertThat(binding.bind(quizId, publication.revision())).hasSize(2);
        assertThat(binding.bind(quizId, publication.revision())).hasSize(2);
    }

    @Test void hotspotCompetingBindingsHaveOneDurableWinnerAfterReconstruction() throws Exception {
        var publication = hotspotPublication(1); var q = publications.read(quizId, publication.revision()).content().items().get(0).question();
        var a = SupportedQuestionContracts.binding(q); var b = new QuestionContractBinding(q.id(), hotspotResponseV2().version(), hotspotScoringV2().version());
        var barrier = new CyclicBarrier(2); var pool = Executors.newFixedThreadPool(2);
        java.util.function.Function<QuestionContractBinding, Boolean> write = value -> {
            authenticate(username);
            try { barrier.await(120, TimeUnit.SECONDS); questionContracts.bind(ownerId, publication.revision(), List.of(value)); return true; }
            catch (ContractResolutionException failure) { assertThat(failure.reason()).isEqualTo(ContractResolutionException.Reason.BINDING_CONFLICT); return false; }
            catch (Exception failure) { throw new AssertionError(failure); }
            finally { SecurityContextHolder.clearContext(); }
        };
        try {
            var first = pool.submit(() -> write.apply(a)); var second = pool.submit(() -> write.apply(b));
            assertThat(List.of(first.get(180, TimeUnit.SECONDS), second.get(180, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue(); }
        var saved = restartedHotspotContracts().resolve(ownerId, publication.revision()).get(q.id()).binding();
        assertThat(saved).isIn(a, b); assertThat(count("question_revision_contract_bindings")).isEqualTo(1);
        assertThatThrownBy(() -> questionContracts.bind(ownerId, publication.revision(), List.of(saved.equals(a) ? b : a)))
                .isInstanceOf(ContractResolutionException.class);
        assertThat(restartedHotspotContracts().resolve(ownerId, publication.revision()).get(q.id()).binding()).isEqualTo(saved);
    }

    @ParameterizedTest @ValueSource(strings = {"attempt_accepted_response", "attempt_lifecycle_state", "attempt_trace_event", "attempt_command_receipt"})
    void hotspotAcceptanceRollbackReplayForeignReferencesAndAuthorityUseRealWriters(String table) {
        var attempt = hotspotAttempt(2, AnswerOrder.AUTHORED); var command = hotspotCommand(attempt, "hotspot-durable", hotspotInput(attempt, 0, "1"));
        long receipts = count("attempt_command_receipt"), traces = count("attempt_trace_event"); var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            boolean checkpoint = statement.contains(table) && (table.equals("attempt_accepted_response") || table.equals("attempt_trace_event")
                    ? statement.stripLeading().startsWith("INSERT") : statement.stripLeading().startsWith("UPDATE"));
            if (checkpoint && reached.compareAndSet(false, true))
                throw new SQLException("hotspot-accept-write-failure", "08006");
        };
        try { assertThatThrownBy(() -> responses.accept(command)).isInstanceOfAny(ResponseAcceptanceException.class,
                uk.gegc.quizmaker.features.attempt.domain.model.receipt.ReceiptException.class); }
        finally { observer().reset(); }
        assertThat(reached).isTrue();
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(count("attempt_trace_event")).isEqualTo(traces);
        assertThat(tx.execute(s -> sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state WHERE attempt_id=UUID_TO_BIN(?)",
                Long.class, attempt.start().attemptId().toString())).longValue()).isEqualTo(1);
        var foreign = hotspotInput(attempt, 1, "1"); // A real region from another item in the same form.
        assertThatThrownBy(() -> responses.accept(hotspotCommand(attempt, "hotspot-foreign", foreign))).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(count("attempt_accepted_response")).isZero();
        var accepted = responses.accept(command);
        var recreated = new ResponseAcceptanceServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), context.getBean(AttemptAccessService.class),
                context.getBean(AttemptReadFactsService.class), context.getBean(AttemptLifecycleService.class), context.getBean(AcceptedResponseRepository.class),
                context.getBean(CommandReceiptService.class), context.getBean(ExecutionTraceService.class), context.getBean(Clock.class), metrics, manager);
        assertThat(recreated.accept(command).acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
        assertThat(recreated.accept(command).replayed()).isTrue();
        SecurityContextHolder.clearContext(); assertThatThrownBy(() -> recreated.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        var other = "hotspot-other-" + UUID.randomUUID().toString().substring(0, 8); var otherId = user(other, true);
        grant(otherId, "ATTEMPT_READ", "ATTEMPT_CREATE"); authenticate(other);
        assertThatThrownBy(() -> recreated.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        authenticate(username); assertThat(recreated.accept(command).replayed()).isTrue();
        tx.executeWithoutResult(s -> sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id "
                + "JOIN user_roles ur ON ur.role_id=r.role_id JOIN permissions p ON p.permission_id=rp.permission_id "
                + "WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name='ATTEMPT_CREATE'", ownerId.toString()));
        assertThatThrownBy(() -> recreated.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        grant(ownerId, "ATTEMPT_CREATE"); assertThat(recreated.accept(command).replayed()).isTrue();
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
    }

    @Test void hotspotBatchAndCallerRollbackRetainOneAtomicOutcome() {
        var attempt = hotspotAttempt(2, AnswerOrder.SHUFFLED_PER_ATTEMPT);
        var items = IntStream.range(0, 2).mapToObj(i -> new ResponseBatchAcceptanceService.BatchItem(attempt.form().items().get(i).id(),
                QuestionType.HOTSPOT, hotspotInput(attempt, i, i == 0 ? "1" : "2"))).toList();
        var command = new ResponseBatchAcceptanceService.BatchCommand(attempt.start().attemptId(), 1, CommandReceipt.Key.from("hotspot-batch"), items);
        var outer = new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(s -> { batches.acceptBatch(command); assertThat(count("attempt_accepted_response")).isEqualTo(2); s.setRollbackOnly(); });
        assertThat(count("attempt_accepted_response")).isZero();
        var accepted = batches.acceptBatch(command); assertThat(batches.acceptBatch(command).replayed()).isTrue();
        assertThat(count("attempt_accepted_response")).isEqualTo(2);
        var submitted = submissions.submit(new AttemptSubmissionService.Command(attempt.start().attemptId(), accepted.stateVersion(), true,
                CommandReceipt.Key.from("hotspot-batch-submit")));
        assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isEqualTo(1);
    }

    @Test void hotspotInternalFormsHaveBoundedSqlAndVersionResolutionDoesNotReadContentOrStorage() throws Exception {
        var totals = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var attempts = new ArrayList<HotspotAttempt>();
            for (int parent = 0; parent < 3; parent++) attempts.add(hotspotAttempt(size, AnswerOrder.SHUFFLED_PER_ATTEMPT));
            int storageCalls = imageStorage.calls; observer().selects.clear();
            for (var attempt : attempts) {
                var rendered = json.readTree(json.writeValueAsString(hotspotForm(attempt.start().attemptId())));
                assertThat(rendered.path("items")).hasSize(size);
                assertThat(rendered.toString()).doesNotContain("correct", "imageUrl", "sourceKey", "https:");
            }
            totals.add(observer().selects.size()); assertThat(imageStorage.calls).isEqualTo(storageCalls);
            observer().selects.clear();
            assertThat(restartedHotspotContracts().resolve(ownerId, attempts.get(0).publication().revision())).hasSize(size);
            assertThat(observer().selects).isNotEmpty().hasSizeLessThan(10)
                    .allSatisfy(statement -> assertThat(statement).doesNotContain("content", "question_text", "normalized_text", "documents"));
            assertThat(imageStorage.calls).isEqualTo(storageCalls);
        }
        assertThat(totals.get(0)).isPositive().isLessThan(150); assertThat(totals.get(1)).isEqualTo(totals.get(0));
        System.out.println("HOTSPOT internal form SQL for 3 parents with 1/20 items: " + totals);
    }

    private MediaFixture complianceMediaFixture(boolean complete) {
        quizId = newQuiz();
        UUID asset = tx.execute(s -> {
            var a = new uk.gegc.quizmaker.features.media.domain.model.MediaAsset(); a.setId(UUID.randomUUID());
            a.setType(uk.gegc.quizmaker.features.media.domain.model.MediaAssetType.IMAGE);
            a.setStatus(uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus.READY);
            a.setKey("compliance/" + a.getId()); a.setMimeType("image/png"); a.setSizeBytes(1024L); a.setWidth(640); a.setHeight(480);
            a.setCreatedBy(username); return mediaAssets.saveAndFlush(a).getId();
        });
        imageStorage.preparing = true;
        try { retainedImages.prepare(Set.of(asset), username); } finally { imageStorage.preparing = false; }
        assertThat(imageStorage.calls).isPositive(); var storageCalls = imageStorage.calls;
        var id = draft(QuestionType.COMPLIANCE);
        tx.executeWithoutResult(s -> {
            var q = questions.findById(id).orElseThrow(); var content = authored(QuestionType.COMPLIANCE);
            for (int i : List.of(0, 2)) {
                var statement = (ObjectNode) content.path("statements").get(i); statement.remove("text");
                statement.putObject("media").put("assetId", asset.toString());
            }
            q.setContent(content.toString()); questions.saveAndFlush(q); retainedImages.bind(id, Set.of(asset), Set.of(), username);
        });
        var revision = publish(0, List.of(id)); binding.bind(quizId, revision);
        var publication = publications.read(quizId, revision); var question = publication.content().items().get(0).question();
        assertThat(publication.images()).hasSize(1); retainedImages.remove(asset, username);
        var start = starts.start(command(revision, AnswerOrder.SHUFFLED_PER_ATTEMPT, "compliance-media-start-" + UUID.randomUUID()));
        doAnswer(call -> {
            assertThat((UUID) call.getArgument(0)).isEqualTo(start.attemptId());
            assertThat((UUID) call.getArgument(1)).isEqualTo(revision);
            List<ParticipantViewMediaReader.Reference> refs = call.getArgument(2);
            assertThat(refs).hasSize(2); assertThat(refs.stream().map(ParticipantViewMediaReader.Reference::optionId).distinct()).hasSize(2);
            var images = new HashMap<ParticipantViewMediaReader.Reference, ParticipantViewMediaReader.Image>();
            for (var ref : refs) {
                assertThat(ref.assetId()).isEqualTo(asset); assertThat(ref.questionRevisionId()).isEqualTo(question.id());
                assertThat(ref.optionId()).isNotNull();
                images.put(ref, new ParticipantViewMediaReader.Image(ParticipantViewMediaReader.Availability.AVAILABLE, GRANTED_INLINE_URL));
            }
            return images;
        }).when(mediaReader).read(any(), any(), any());
        var delivered = delivery.read(start.attemptId()); var item = delivered.form().items().get(0);
        var form = (AttemptFormResponse.Compliance) AttemptFormResponse.from(delivered).items().get(0).content();
        assertThat(form.statements().stream().filter(s -> s.image() != null)).hasSize(2);
        form.statements().stream().filter(s -> s.image() != null).forEach(s -> {
            assertThat(s.text()).isNull(); assertThat(s.image().url()).isEqualTo(GRANTED_INLINE_URL);
            assertThat(s.image().width()).isEqualTo(640);
        });
        assertThat(((AttemptFormResponse.Compliance) view(start.attemptId()).items().get(0).content()).statements()).isEqualTo(form.statements());
        if (complete) {
            var raw = json.createObjectNode(); var selected = raw.putArray("selectedStatementIds");
            question.options().stream().filter(o -> o.sourceKey().equals("1") || o.sourceKey().equals("3"))
                    .forEach(o -> selected.add(o.id().toString()));
            var accepted = responses.accept(new ResponseAcceptanceService.Command(start.attemptId(), item.id(), 1,
                    CommandReceipt.Key.from("compliance-media-answer"), QuestionType.COMPLIANCE, raw));
            var submitted = submissions.submit(new AttemptSubmissionService.Command(start.attemptId(), accepted.stateVersion(), true,
                    CommandReceipt.Key.from("compliance-media-submit")));
            assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isEqualTo(1); releases.release(submitted.submissionId());
            assertThat(view(start.attemptId()).result().totalScore().earned()).isEqualTo(1);
        }
        assertThat(imageStorage.calls).isEqualTo(storageCalls);
        return new MediaFixture(start.attemptId(), item, asset);
    }

    @ParameterizedTest @EnumSource(ParticipantViewMediaReader.Availability.class)
    void complianceReleasedImagesUseCurrentAuthorityWithoutChangingPersistedResults(ParticipantViewMediaReader.Availability availability) throws Exception {
        var fixture = complianceMediaFixture(true); var original = view(fixture.attempt());
        var before = List.of(count("attempt_accepted_response"), count("attempt_submission"), count("attempt_automatic_result"),
                count("attempt_result_release"), count("attempt_trace_event"));
        inlineAvailability(availability); clearInvocations(mediaReader);
        var payload = json.readTree(json.writeValueAsString(view(fixture.attempt())));
        for (var statement : payload.at("/items/0/content/statements")) if (statement.has("image")) {
            assertThat(statement.at("/image/availability").asText()).isEqualTo(availability.name());
            assertThat(statement.path("image").has("url")).isEqualTo(availability == ParticipantViewMediaReader.Availability.AVAILABLE);
            if (availability == ParticipantViewMediaReader.Availability.AVAILABLE)
                assertThat(statement.at("/image/url").asText()).isEqualTo(GRANTED_INLINE_URL);
        }
        assertThat(payload.toString()).doesNotContain("retained-images/", "assetId", "cdnUrl", "compliant");
        verify(mediaReader, times(1)).read(eq(fixture.attempt()), any(), argThat(refs -> refs.size() == 2));
        assertThat(payload.at("/result/totalScore/earned").asLong()).isEqualTo(original.result().totalScore().earned());
        assertThat(List.of(count("attempt_accepted_response"), count("attempt_submission"), count("attempt_automatic_result"),
                count("attempt_result_release"), count("attempt_trace_event"))).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"UNAVAILABLE", "RESTRICTED", "missing-reader", "missing-occurrence", "foreign-occurrence"})
    void complianceMediaDenialsPreserveStateAndSuccessfulCurrentAuthorityRestoresTheSameForm(String defect) {
        var fixture = complianceMediaFixture(false); var before = view(fixture.attempt());
        switch (defect) {
            case "UNAVAILABLE", "RESTRICTED" -> inlineAvailability(ParticipantViewMediaReader.Availability.valueOf(defect));
            case "missing-reader" -> doAnswer(call -> new InitialParticipantViewMediaReader()
                    .read(call.getArgument(0), call.getArgument(1), call.getArgument(2))).when(mediaReader).read(any(), any(), any());
            default -> doAnswer(call -> {
                List<ParticipantViewMediaReader.Reference> refs = call.getArgument(2); var ref = refs.get(0);
                if (defect.equals("foreign-occurrence")) ref = new ParticipantViewMediaReader.Reference(
                        ref.formItemId(), ref.questionRevisionId(), ref.assetId(), UUID.randomUUID());
                return Map.of(ref, new ParticipantViewMediaReader.Image(ParticipantViewMediaReader.Availability.AVAILABLE, GRANTED_INLINE_URL));
            }).when(mediaReader).read(any(), any(), any());
        }
        assertThatThrownBy(() -> view(fixture.attempt())).isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class);
        assertThatThrownBy(() -> delivery.read(fixture.attempt())).isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_submission")).isZero();
        inlineAvailability(ParticipantViewMediaReader.Availability.AVAILABLE);
        assertThat(view(fixture.attempt())).isEqualTo(before);
        assertThat(AttemptFormResponse.from(delivery.read(fixture.attempt())).items()).hasSize(1);
    }

    @Test void mediaOnlyMatchingUsesRealRetentionPublicationAcceptanceAndGradeWithoutStorageIoDuringExecution() {
        matchingMediaFixture(true);
    }

    private record MediaFixture(UUID attempt, ParticipantForm.Item item, UUID asset) { }
    private static final String GRANTED_INLINE_URL = "https://display.example.invalid/reader-authorized-original";
    private MediaFixture matchingMediaFixture(boolean complete) {
        quizId = newQuiz();
        var asset = tx.execute(s -> {
            var a = new uk.gegc.quizmaker.features.media.domain.model.MediaAsset(); a.setId(UUID.randomUUID());
            a.setType(uk.gegc.quizmaker.features.media.domain.model.MediaAssetType.IMAGE);
            a.setStatus(uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus.READY);
            a.setKey("matching/" + a.getId()); a.setMimeType("image/png"); a.setSizeBytes(1024L); a.setWidth(640); a.setHeight(480);
            a.setCreatedBy(username); return mediaAssets.saveAndFlush(a).getId();
        });
        imageStorage.preparing = true;
        try { retainedImages.prepare(Set.of(asset), username); } finally { imageStorage.preparing = false; }
        assertThat(imageStorage.calls).isPositive(); var storageCalls = imageStorage.calls;
        var id = draft(QuestionType.MATCHING);
        tx.executeWithoutResult(s -> {
            var q = questions.findById(id).orElseThrow(); var content = authored(QuestionType.MATCHING);
            var left = (ObjectNode) content.path("left").get(0); left.remove("text"); left.putObject("media").put("assetId", asset.toString());
            var right = (ObjectNode) content.path("right").get(1); right.remove("text"); right.putObject("media").put("assetId", asset.toString());
            q.setContent(content.toString()); questions.saveAndFlush(q); retainedImages.bind(id, Set.of(asset), Set.of(), username);
        });
        var revision = publish(0, List.of(id)); binding.bind(quizId, revision);
        var publication = publications.read(quizId, revision);
        assertThat(publication.images()).hasSize(1);
        retainedImages.remove(asset, username);
        var started = starts.start(command(revision, AnswerOrder.SHUFFLED_PER_ATTEMPT, "matching-media-start-" + UUID.randomUUID()));
        doAnswer(call -> {
            assertThat((UUID) call.getArgument(0)).isEqualTo(started.attemptId());
            assertThat((UUID) call.getArgument(1)).isEqualTo(revision);
            List<ParticipantViewMediaReader.Reference> refs = call.getArgument(2);
            assertThat(refs).hasSize(2); assertThat(refs.stream().map(ParticipantViewMediaReader.Reference::optionId).distinct()).hasSize(2);
            var result = new HashMap<ParticipantViewMediaReader.Reference, ParticipantViewMediaReader.Image>();
            for (var ref : refs) {
                assertThat(ref.assetId()).isEqualTo(asset); assertThat(ref.optionId()).isNotNull();
                assertThat(ref.questionRevisionId()).isEqualTo(publication.content().items().get(0).question().id());
                result.put(ref, new ParticipantViewMediaReader.Image(ParticipantViewMediaReader.Availability.AVAILABLE, GRANTED_INLINE_URL));
            }
            return result;
        }).when(mediaReader).read(any(), any(), any());
        var delivered = delivery.read(started.attemptId());
        var item = delivered.form().items().get(0);
        var matching = (ParticipantContent.Matching) item.content();
        assertThat(matching.left().get(0).text()).isNull(); assertThat(matching.left().get(0).image().width()).isEqualTo(640);
        assertThat(matching.left().get(0).image().assetId()).isEqualTo(asset);
        var publicForm = AttemptFormResponse.from(delivered);
        assertThat(((AttemptFormResponse.Matching) publicForm.items().get(0).content()).left().get(0).image().url()).isEqualTo(GRANTED_INLINE_URL);
        assertThat(((AttemptFormResponse.Matching) view(started.attemptId()).items().get(0).content()).left().get(0).image().url()).isEqualTo(GRANTED_INLINE_URL);
        if (!complete) return new MediaFixture(started.attemptId(), item, asset);
        var raw = input(item, true);
        // Input helper uses displayed order. Address the exact captured right identity after shuffling.
        var rightId = publication.content().items().get(0).question().options().stream()
                .filter(o -> o.sourceKey().equals("right/10")).findFirst().orElseThrow().id();
        var secondRight = publication.content().items().get(0).question().options().stream()
                .filter(o -> o.sourceKey().equals("right/11")).findFirst().orElseThrow().id();
        ((ObjectNode) raw.path("matches").get(0)).put("rightId", rightId.toString());
        ((ObjectNode) raw.path("matches").get(1)).put("rightId", secondRight.toString());
        var accepted = responses.accept(new ResponseAcceptanceService.Command(started.attemptId(), item.id(), 1,
                CommandReceipt.Key.from("matching-media-answer"), QuestionType.MATCHING, raw));
        var submitted = submissions.submit(new AttemptSubmissionService.Command(started.attemptId(), accepted.stateVersion(), true, CommandReceipt.Key.from("matching-media-submit")));
        assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isEqualTo(1); releases.release(submitted.submissionId());
        assertThat(view(started.attemptId()).result().totalScore().earned()).isEqualTo(1);
        assertThat(imageStorage.calls).isEqualTo(storageCalls);
        return new MediaFixture(started.attemptId(), item, asset);
    }

    private void inlineAvailability(ParticipantViewMediaReader.Availability availability) {
        doAnswer(call -> {
            List<ParticipantViewMediaReader.Reference> refs = call.getArgument(2);
            assertThat(refs).hasSize(2);
            var result = new HashMap<ParticipantViewMediaReader.Reference, ParticipantViewMediaReader.Image>();
            refs.forEach(ref -> result.put(ref, new ParticipantViewMediaReader.Image(availability,
                    availability == ParticipantViewMediaReader.Availability.AVAILABLE ? GRANTED_INLINE_URL : null)));
            return result;
        }).when(mediaReader).read(any(), any(), any());
    }

    @ParameterizedTest @EnumSource(ParticipantViewMediaReader.Availability.class)
    void releasedInlineImagesUseOneCurrentAuthorityBatchAndNeverCapturedUrls(ParticipantViewMediaReader.Availability availability) throws Exception {
        var fixture = matchingMediaFixture(true); var original = view(fixture.attempt());
        var evidence = List.of(count("attempt_accepted_response"), count("attempt_submission"), count("attempt_automatic_result"),
                count("attempt_result_release"), count("attempt_trace_event"));
        inlineAvailability(availability); clearInvocations(mediaReader);
        var payload = json.readTree(mvc().perform(get("/api/v2/attempts/{id}", fixture.attempt())
                .principal(SecurityContextHolder.getContext().getAuthentication())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(payload.at("/items/0/content/left/0/image/availability").asText()).isEqualTo(availability.name());
        var columns = payload.at("/items/0/content");
        for (String side : List.of("left", "right")) for (var option : columns.path(side)) if (option.has("image")) {
            assertThat(option.path("image").has("url")).isEqualTo(availability == ParticipantViewMediaReader.Availability.AVAILABLE);
            if (availability == ParticipantViewMediaReader.Availability.AVAILABLE)
                assertThat(option.at("/image/url").asText()).isEqualTo(GRANTED_INLINE_URL);
        }
        assertThat(payload.toString()).doesNotContain("retained-images/", "assetId", "cdnUrl");
        verify(mediaReader, times(1)).read(eq(fixture.attempt()), any(), argThat(refs -> refs.size() == 2));
        assertThat(payload.at("/result/totalScore/earned").asLong()).isEqualTo(original.result().totalScore().earned());
        assertThat(List.of(count("attempt_accepted_response"), count("attempt_submission"), count("attempt_automatic_result"),
                count("attempt_result_release"), count("attempt_trace_event"))).isEqualTo(evidence);
    }

    @ParameterizedTest @EnumSource(value = ParticipantViewMediaReader.Availability.class, names = {"UNAVAILABLE", "RESTRICTED"})
    void activeInlineImagesDenyBothViewAndFormWithoutMutatingAnswersOrState(ParticipantViewMediaReader.Availability availability) throws Exception {
        var fixture = matchingMediaFixture(false); var version = view(fixture.attempt()).stateVersion();
        inlineAvailability(availability);
        mvc().perform(get("/api/v2/attempts/{id}", fixture.attempt())
                .principal(SecurityContextHolder.getContext().getAuthentication())).andExpect(status().isServiceUnavailable());
        var formMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new AttemptFormController(delivery))
                .setControllerAdvice(new AttemptDeliveryErrorHandler()).build();
        var body = formMvc.perform(get("/api/v2/attempts/{id}/form", fixture.attempt()))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(GRANTED_INLINE_URL, "retained-images/", "assetId", "cdnUrl");
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_submission")).isZero();
        inlineAvailability(ParticipantViewMediaReader.Availability.AVAILABLE);
        assertThat(view(fixture.attempt()).stateVersion()).isEqualTo(version);
        assertThat(AttemptFormResponse.from(delivery.read(fixture.attempt())).items()).hasSize(1);
    }

    @Test void missingInlineMediaIntegrationFailsClosedAfterRealSuccessfulControl() {
        var fixture = matchingMediaFixture(true);
        doAnswer(call -> new InitialParticipantViewMediaReader()
                .read(call.getArgument(0), call.getArgument(1), call.getArgument(2))).when(mediaReader).read(any(), any(), any());
        assertThatThrownBy(() -> view(fixture.attempt())).isInstanceOfSatisfying(
                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                failure -> assertThat(failure.reason().name()).isEqualTo("UNSUPPORTED"));
        inlineAvailability(ParticipantViewMediaReader.Availability.AVAILABLE);
        assertThat(view(fixture.attempt()).result().totalScore().earned()).isEqualTo(1);
        var active = matchingMediaFixture(false);
        var absent = new AttemptDeliveryServiceImpl(context.getBean(AttemptReadFactsService.class), manager, metrics, context.getBean(Clock.class));
        assertThatThrownBy(() -> absent.read(active.attempt())).isInstanceOfSatisfying(
                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                failure -> assertThat(failure.reason().name()).isEqualTo("UNSUPPORTED"));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "foreign-option"})
    void incompleteOrWrongOccurrenceInlinePermissionCannotReleaseAnyUrl(String defect) {
        var fixture = matchingMediaFixture(true);
        doAnswer(call -> {
            List<ParticipantViewMediaReader.Reference> refs = call.getArgument(2);
            var first = refs.get(0);
            var key = defect.equals("missing") ? first : new ParticipantViewMediaReader.Reference(
                    first.formItemId(), first.questionRevisionId(), first.assetId(), UUID.randomUUID());
            return Map.of(key, new ParticipantViewMediaReader.Image(ParticipantViewMediaReader.Availability.AVAILABLE, GRANTED_INLINE_URL));
        }).when(mediaReader).read(any(), any(), any());
        assertThatThrownBy(() -> view(fixture.attempt())).isInstanceOfSatisfying(
                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                failure -> assertThat(failure.reason().name()).isEqualTo("CORRUPT"));
    }

    private record OpenAttempt(UUID id, ParticipantForm.Item item) { }
    private OpenAttempt openAttempt() {
        quizId = newQuiz();
        var revision = publish(0, List.of(draft(QuestionType.OPEN))); binding.bind(quizId, revision);
        var start = starts.start(command(revision, AnswerOrder.AUTHORED, "open-start-" + UUID.randomUUID()));
        return new OpenAttempt(start.attemptId(), delivery.read(start.attemptId()).form().items().get(0));
    }
    private ResponseAcceptanceService.Command openCommand(OpenAttempt attempt, String key, com.fasterxml.jackson.databind.JsonNode payload) {
        return new ResponseAcceptanceService.Command(attempt.id(), attempt.item().id(), 1, CommandReceipt.Key.from(key), QuestionType.OPEN, payload);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "{\"answer\":null}", "{\"answer\":42}", "{\"answer\":true}"})
    void invalidOpenPayloadNeverCreatesAnAcceptanceReceiptOrStateChange(String raw) throws Exception {
        var attempt = openAttempt(); var state = view(attempt.id());
        var beforeReceipts = count("attempt_command_receipt"); var beforeTrace = count("attempt_trace_event");
        assertThatThrownBy(() -> responses.accept(openCommand(attempt, "invalid-open", json.readTree(raw))))
                .isInstanceOf(ResponseAcceptanceException.class);
        assertThat(count("attempt_accepted_response")).isZero();
        assertThat(count("attempt_command_receipt")).isEqualTo(beforeReceipts);
        assertThat(count("attempt_trace_event")).isEqualTo(beforeTrace);
        assertThat(view(attempt.id())).isEqualTo(state);
        assertThat(responses.accept(openCommand(attempt, "invalid-open", json.createObjectNode().put("answer", "Paris"))).replayed()).isFalse();
    }

    @Test void openReachedAcceptanceWriteRollsBackThenExactReplaySurvivesServiceRecreation() {
        var attempt = openAttempt(); var command = openCommand(attempt, "durable-open", json.createObjectNode().put("answer", " Paris "));
        var receipts = count("attempt_command_receipt"); var trace = count("attempt_trace_event");
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO attempt_accepted_response") && reached.compareAndSet(false, true))
                throw new SQLException("private-open-write-failure", "08006");
        };
        assertThatThrownBy(() -> responses.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(reached).isTrue(); observer().reset();
        assertThat(count("attempt_accepted_response")).isZero();
        assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(count("attempt_trace_event")).isEqualTo(trace);
        assertThat(view(attempt.id()).stateVersion()).isEqualTo(1);
        var accepted = responses.accept(command);
        var recreated = new ResponseAcceptanceServiceImpl(context.getBean(AuthenticatedSubjectResolver.class), context.getBean(AttemptAccessService.class),
                context.getBean(AttemptReadFactsService.class), context.getBean(AttemptLifecycleService.class), context.getBean(AcceptedResponseRepository.class),
                context.getBean(CommandReceiptService.class), context.getBean(ExecutionTraceService.class), context.getBean(Clock.class), metrics, manager);
        var replay = recreated.accept(command);
        assertThat(replay.replayed()).isTrue(); assertThat(replay.acceptedResponseId()).isEqualTo(accepted.acceptedResponseId());
        assertThat(count("attempt_accepted_response")).isEqualTo(1);
        assertThatThrownBy(() -> recreated.accept(openCommand(attempt, "durable-open", json.createObjectNode().put("answer", "Rome"))))
                .isInstanceOf(ResponseAcceptanceException.class);
        var other = user("open-other-" + UUID.randomUUID().toString().substring(0, 8), true);
        grant(other, "ATTEMPT_READ", "ATTEMPT_CREATE");
        authenticate(sql.queryForObject("SELECT username FROM users WHERE user_id=UUID_TO_BIN(?)", String.class, other.toString()));
        assertThatThrownBy(() -> recreated.accept(command)).isInstanceOf(ResponseAcceptanceException.class);
        authenticate(username); assertThat(recreated.accept(command).replayed()).isTrue();
    }

    @Test void openAcceptanceJoinsOuterRcAndRollsBackWithItsCaller() {
        var attempt = openAttempt(); var receipts = count("attempt_command_receipt");
        var outer = new TransactionTemplate(manager); outer.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        outer.executeWithoutResult(status -> {
            assertThat(responses.accept(openCommand(attempt, "outer-open", json.createObjectNode().put("answer", "Paris"))).replayed()).isFalse();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_accepted_response", Integer.class)).isEqualTo(1);
            assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
            status.setRollbackOnly();
        });
        assertThat(count("attempt_accepted_response")).isZero();
        assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        assertThat(view(attempt.id()).stateVersion()).isEqualTo(1);
    }

    @Test void openAnswerCannotBorrowAnotherAttemptsFormItemAndValidOwnersStillSucceed() {
        var own = openAttempt(); var foreign = openAttempt();
        var payload = json.createObjectNode().put("answer", "Paris");
        var before = count("attempt_command_receipt");
        assertThatThrownBy(() -> responses.accept(new ResponseAcceptanceService.Command(own.id(), foreign.item().id(), 1,
                CommandReceipt.Key.from("foreign-open-item"), QuestionType.OPEN, payload))).isInstanceOf(ResponseAcceptanceException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(before);
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> responses.accept(openCommand(own, "own-open", payload))).isInstanceOf(ResponseAcceptanceException.class);
        authenticate(username);
        assertThat(responses.accept(openCommand(own, "own-open", payload)).replayed()).isFalse();
        assertThat(responses.accept(openCommand(foreign, "other-own-open", payload)).replayed()).isFalse();
        assertThat(count("attempt_accepted_response")).isEqualTo(2);
    }

    @Test void mixedOpenBatchRejectsAllItemsThenAcceptsReplaysAndGradesTheRealWritesOnce() {
        quizId = newQuiz();
        var revision = publish(0, List.of(draft(QuestionType.TRUE_FALSE), draft(QuestionType.OPEN))); binding.bind(quizId, revision);
        var start = starts.start(command(revision, AnswerOrder.AUTHORED, "open-batch-start"));
        var items = delivery.read(start.attemptId()).form().items();
        var key = CommandReceipt.Key.from("open-batch");
        var truth = new ResponseBatchAcceptanceService.BatchItem(items.get(0).id(), QuestionType.TRUE_FALSE, json.createObjectNode().put("answer", true));
        var invalid = new ResponseBatchAcceptanceService.BatchItem(items.get(1).id(), QuestionType.OPEN, json.createObjectNode().putNull("answer"));
        var receipts = count("attempt_command_receipt");
        assertThatThrownBy(() -> batches.acceptBatch(new ResponseBatchAcceptanceService.BatchCommand(start.attemptId(), 1, key, List.of(truth, invalid))))
                .isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseBatchException.class);
        assertThat(count("attempt_accepted_response")).isZero(); assertThat(count("attempt_command_receipt")).isEqualTo(receipts);
        var text = new ResponseBatchAcceptanceService.BatchItem(items.get(1).id(), QuestionType.OPEN, json.createObjectNode().put("answer", " Paris "));
        var command = new ResponseBatchAcceptanceService.BatchCommand(start.attemptId(), 1, key, List.of(truth, text));
        var accepted = batches.acceptBatch(command); assertThat(accepted.responses()).hasSize(2);
        assertThat(batches.acceptBatch(command).responses()).isEqualTo(accepted.responses());
        assertThat(batches.acceptBatch(command).replayed()).isTrue(); assertThat(count("attempt_accepted_response")).isEqualTo(2);
        var submitted = submissions.submit(new AttemptSubmissionService.Command(start.attemptId(), accepted.stateVersion(), true, CommandReceipt.Key.from("open-batch-submit")));
        assertThat(results.finalizeSubmission(submitted.submissionId()).score().earned()).isEqualTo(2);
        releases.release(submitted.submissionId());
        assertThat(view(start.attemptId()).result().items().get(1).submittedAnswer()).isEqualTo(new ParticipantResultView.TextAnswer(" Paris "));
    }

    @Test void openReleasedViewsHaveBoundedQueriesAcrossThreeParentsAndDifferentItemCounts() throws Exception {
        var totals = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var fixtures = new ArrayList<Fixture>();
            for (int parent = 0; parent < 3; parent++) {
                var f = fixture(Collections.nCopies(size, QuestionType.OPEN), size, size);
                results.finalizeSubmission(f.submission()); releases.release(f.submission()); fixtures.add(f);
            }
            observer().selects.clear();
            for (var f : fixtures) {
                var rendered = json.readTree(json.writeValueAsString(view(f.attempt())));
                assertThat(rendered.path("result").path("items")).hasSize(size);
                assertThat(rendered.path("result").path("totalScore").path("earned").intValue()).isEqualTo(size);
            }
            totals.add(observer().selects.size());
        }
        assertThat(totals.get(0)).isPositive().isLessThan(150); assertThat(totals.get(1)).isEqualTo(totals.get(0));
        System.out.println("OPEN released result SQL for 3 parents with 1/20 items: " + totals);
    }

    @Test void unansweredAndAbsentExplanationAreNotFabricatedIncorrectResponses() {
        authoredExplanation = null;
        var fixture = fixture(3, 2, 1);
        results.finalizeSubmission(fixture.submission()); releases.release(fixture.submission());
        var result = view(fixture.attempt()).result();
        assertThat(result.totalScore()).isEqualTo(new ParticipantResultView.Score(1, 3, 3333));
        assertThat(result.items().get(0).correctness()).isTrue();
        assertThat(result.items().get(1).correctness()).isFalse();
        var unanswered = result.items().get(2);
        assertThat(unanswered.responseState()).isEqualTo(ParticipantResultView.ResponseState.UNANSWERED);
        assertThat(unanswered.submittedAnswer()).isNull(); assertThat(unanswered.correctness()).isNull();
        assertThat(unanswered.score()).isZero(); assertThat(unanswered.correctAnswer()).isNotNull();
        assertThat(result.items()).allSatisfy(item -> assertThat(item.explanation()).isNull());
    }

    @Test void actualHttpRoutesIgnoreEscalatingFlagsAndOnlyNarrowReviewFields() throws Exception {
        var fixture = fixture(FAMILIES, 5, 5);
        results.finalizeSubmission(fixture.submission());
        var mvc = mvc(); var principal = SecurityContextHolder.getContext().getAuthentication();
        mvc.perform(get("/api/v2/attempts/{id}", fixture.attempt()).principal(principal)
                        .param("includeCorrectAnswers", "true").param("includeCorrectness", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").doesNotExist()).andExpect(jsonPath("$.items").doesNotExist());
        releases.release(fixture.submission());
        String plain = mvc.perform(get("/api/v2/attempts/{id}", fixture.attempt()).principal(principal))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String flags = mvc.perform(get("/api/v2/attempts/{id}", fixture.attempt()).principal(principal)
                        .param("includeCorrectAnswer", "true").param("includeExplanation", "true").param("passed", "true")
                        .param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(flags).isEqualTo(plain);
        mvc.perform(get("/api/v2/attempts/{id}/review", fixture.attempt()).principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.representation").value("POLICY_DRIVEN"))
                .andExpect(jsonPath("$.result.items[0].correctAnswer.kind").value("OPTIONS"));
        mvc.perform(get("/api/v2/attempts/{id}/review", fixture.attempt()).principal(principal)
                        .param("includeUserAnswers", "false").param("includeCorrectAnswers", "false").param("includeQuestionContext", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].submittedAnswer").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].correctAnswer").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].explanation").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].responseState").doesNotExist())
                .andExpect(jsonPath("$.result.totalScore.earned").value(5));
        mvc.perform(get("/api/v2/attempts/{id}/answer-key", fixture.attempt()).principal(principal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.items[0].submittedAnswer").doesNotExist())
                .andExpect(jsonPath("$.result.items[0].correctAnswer").exists());
        verifyNoInteractions(legacy);
    }

    @Test void currentAuthorizationIsRequiredBeforeEveryReleasedRead() throws Exception {
        var fixture = fixture(2, 2, 2); results.finalizeSubmission(fixture.submission()); releases.release(fixture.submission());
        var mvc = mvc();
        mvc.perform(get("/api/v2/attempts/{id}", fixture.attempt()).principal(SecurityContextHolder.getContext().getAuthentication()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalScore.earned").value(2));
        var foreign = "acceptance-other-" + UUID.randomUUID().toString().substring(0, 8);
        var id = user(foreign, true); grant(id, "ATTEMPT_READ", "ATTEMPT_CREATE");
        authenticate(foreign);
        for (String suffix : List.of("", "/review", "/answer-key"))
            mvc.perform(get("/api/v2/attempts/" + fixture.attempt() + suffix).principal(SecurityContextHolder.getContext().getAuthentication()))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v2/attempts/{id}", fixture.attempt())).andExpect(status().isUnauthorized());
        authenticate(username);
        tx.executeWithoutResult(s -> sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id "
                + "JOIN user_roles ur ON ur.role_id=r.role_id JOIN permissions p ON p.permission_id=rp.permission_id "
                + "WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name='ATTEMPT_READ'", ownerId.toString()));
        mvc.perform(get("/api/v2/attempts/{id}", fixture.attempt()).principal(SecurityContextHolder.getContext().getAuthentication()))
                .andExpect(status().isNotFound());
        assertThat(count("attempt_result_release")).isEqualTo(1); verifyNoInteractions(legacy);
    }

    @Test void concurrentReleaseCommandsBothSucceedAndConvergeOnOneDurableReceipt() throws Exception {
        var fixture = fixture(3, 3, 2); results.finalizeSubmission(fixture.submission());
        var ready = new CyclicBarrier(2); var seen = ConcurrentHashMap.<Integer>newKeySet();
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("FROM attempt_finalization_intent i JOIN") && seen.add(connection)) {
                try { ready.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> releases.release(fixture.submission()));
            var second = pool.submit(() -> restarted().release(fixture.submission()));
            var a = first.get(20, TimeUnit.SECONDS); var b = second.get(20, TimeUnit.SECONDS);
            assertThat(a.release()).isEqualTo(b.release()); assertThat(a.replayed()).isNotEqualTo(b.replayed());
        } finally { observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        assertThat(count("attempt_result_release")).isEqualTo(1); assertThat(releaseEvents()).isEqualTo(1);
    }

    @Test void differentSubmissionsBothReleaseAfterConcurrentMissingPublicationReads() throws Exception {
        var first = fixture(2, 2, 2); var firstUser = username; var firstOwner = ownerId; var firstQuiz = quizId;
        var firstResult = results.finalizeSubmission(first.submission());
        username = "acceptance-" + UUID.randomUUID().toString().substring(0, 12);
        ownerId = user(username, true); grant(ownerId, "ATTEMPT_READ", "ATTEMPT_CREATE"); authenticate(username);
        var second = fixture(3, 3, 2); var secondUser = username;
        var secondResult = results.finalizeSubmission(second.submission());
        assertThat(ownerId).isNotEqualTo(firstOwner); assertThat(quizId).isNotEqualTo(firstQuiz);
        assertThat(first.submission()).isNotEqualTo(second.submission());
        assertThat(firstResult.quizRevisionId()).isNotEqualTo(secondResult.quizRevisionId());
        assertThat(count("attempt_result_release")).isZero();
        SecurityContextHolder.clearContext();

        var missingReads = new CyclicBarrier(2); var seen = ConcurrentHashMap.<Integer>newKeySet();
        var failures = new CopyOnWriteArrayList<ResultReleaseException>();
        observer().afterSql = (statement, connection) -> {
            // Both real locking reads finish on the empty unique-index range before either insert.
            if (statement.contains("FROM attempt_result_release r") && seen.add(connection)) {
                try { missingReads.await(10, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        java.util.function.Function<UUID, ImmediateResultReleaseService.Acknowledgement> publish = id -> {
            try { return releases.release(id); }
            catch (ResultReleaseException failure) { failures.add(failure); return null; }
        };
        List<ImmediateResultReleaseService.Acknowledgement> acknowledgements;
        try {
            var a = pool.submit(() -> publish.apply(first.submission()));
            var b = pool.submit(() -> publish.apply(second.submission()));
            var firstAck = a.get(20, TimeUnit.SECONDS); var secondAck = b.get(20, TimeUnit.SECONDS);
            assertThat(seen).hasSize(2);
            assertThat(failures).as("Independent releases must both commit; SQL errors: %s", observer().sqlErrorCodes).isEmpty();
            acknowledgements = List.of(firstAck, secondAck);
            assertThat(acknowledgements).allSatisfy(ack -> assertThat(ack.replayed()).isFalse());
            assertThat(firstAck.release().id()).isNotEqualTo(secondAck.release().id());
            assertThat(observer().sqlErrorCodes).doesNotContain(1213);
        } finally { observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }

        for (int i = 0; i < 2; i++) {
            var fixture = List.of(first, second).get(i); var result = List.of(firstResult, secondResult).get(i);
            var expected = acknowledgements.get(i).release();
            assertThat(restarted().release(fixture.submission()).release()).isEqualTo(expected);
            assertThat(releases.release(fixture.submission()).replayed()).isTrue();
            assertThat(results.finalizeSubmission(fixture.submission())).isEqualTo(result);
            long publications = tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM attempt_result_release WHERE result_id=UUID_TO_BIN(?)",
                    Long.class, result.id().toString()));
            long events = tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM attempt_trace_event WHERE event_type='RESULT_RELEASED' AND command_id=UUID_TO_BIN(?)",
                    Long.class, result.id().toString()));
            assertThat(publications).isEqualTo(1L); assertThat(events).isEqualTo(1L);
            authenticate(List.of(firstUser, secondUser).get(i));
            assertThat(view(fixture.attempt()).result().totalScore().earned()).isEqualTo(2);
        }
        assertThat(count("attempt_result_release")).isEqualTo(2); assertThat(releaseEvents()).isEqualTo(2);
    }

    @Test void releaseIsolationIsLocalAndCommitSurvivesSuspendedRepeatableReadCallerRollback() {
        var fixture = fixture(2, 2, 2); results.finalizeSubmission(fixture.submission());
        String serverIsolation = tx.execute(s -> sql.queryForObject("SELECT @@global.transaction_isolation", String.class));
        var observed = new ArrayList<String>();
        observer().afterSql = (statement, connection) -> {
            // Observe the release lookup, not the notification INSERT ... SELECT from that row.
            if (statement.stripLeading().startsWith("SELECT") && statement.contains("FROM attempt_result_release r"))
                observed.add(sql.queryForObject("SELECT @@transaction_isolation", String.class));
        };
        var caller = new TransactionTemplate(manager);
        caller.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            caller.executeWithoutResult(status -> {
                assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_result_release", Long.class)).isZero();
                assertThat(releases.release(fixture.submission()).replayed()).isFalse();
                assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                // The nonlocking outer snapshot remains old; v2 reads reject that caller before work.
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_result_release", Long.class)).isZero();
                assertThatThrownBy(() -> view(fixture.attempt())).isInstanceOf(
                        uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException.class);
                assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
                status.setRollbackOnly();
            });
        } finally { observer().reset(); }
        assertThat(observed).containsExactly("READ-COMMITTED");
        assertThat(view(fixture.attempt()).result().totalScore().earned()).isEqualTo(2);
        String remainingServerIsolation = tx.execute(s -> sql.queryForObject("SELECT @@global.transaction_isolation", String.class));
        assertThat(remainingServerIsolation).isEqualTo(serverIsolation);
        assertThat(count("attempt_result_release")).isEqualTo(1); assertThat(releaseEvents()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"trace", "release", "commit"})
    void reachedWriteFailuresRollbackPublicationAndEvidenceThenRetrySucceeds(String checkpoint) {
        var fixture = fixture(3, 3, 2); var result = results.finalizeSubmission(fixture.submission());
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            boolean selected = checkpoint.equals("trace") ? statement.startsWith("INSERT INTO attempt_trace_event")
                    : statement.startsWith("INSERT INTO attempt_result_release");
            if (selected && reached.compareAndSet(false, true)) {
                if (checkpoint.equals("commit")) observer().failBeforeCommit.set(true);
                else throw new SQLException("private-release-marker", "08006");
            }
        };
        reject(fixture.submission(), ResultReleaseException.Reason.UNAVAILABLE);
        assertThat(reached).isTrue(); observer().reset();
        assertThat(count("attempt_result_release")).isZero(); assertThat(releaseEvents()).isZero();
        assertThat(view(fixture.attempt()).result()).isNull();
        assertThat(results.finalizeSubmission(fixture.submission())).isEqualTo(result);
        assertThat(restarted().release(fixture.submission()).replayed()).isFalse();
        assertThat(count("attempt_result_release")).isEqualTo(1); assertThat(releaseEvents()).isEqualTo(1);
    }

    @Test void lostCommitAcknowledgementReconcilesTheSameReleaseAfterServiceRecreation() {
        var fixture = fixture(3, 3, 2); results.finalizeSubmission(fixture.submission());
        observer().afterSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO attempt_result_release")) observer().failAfterCommit.set(true);
        };
        reject(fixture.submission(), ResultReleaseException.Reason.UNAVAILABLE);
        observer().reset();
        assertThat(count("attempt_result_release")).isEqualTo(1); assertThat(releaseEvents()).isEqualTo(1);
        var read = view(fixture.attempt());
        assertThat(restarted().release(fixture.submission()).replayed()).isTrue();
        assertThat(view(fixture.attempt())).isEqualTo(read); assertThat(releaseEvents()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentReaderCannotSeeAnUncommittedReleaseAndObservesItsActualOutcome(boolean commit) throws Exception {
        var fixture = fixture(2, 2, 2); results.finalizeSubmission(fixture.submission());
        var written = new CountDownLatch(1); var finish = new CountDownLatch(1); var reading = new CountDownLatch(1);
        observer().afterSql = (statement, connection) -> {
            if (statement.startsWith("INSERT INTO attempt_result_release")) {
                written.countDown(); await(finish);
                if (!commit) throw new SQLException("private-release-marker", "08006");
            }
        };
        observer().beforeSql = (statement, connection) -> {
            if (written.getCount() == 0 && statement.contains("attempt_participant_subject")) reading.countDown();
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var writing = pool.submit(() -> {
                try { return releases.release(fixture.submission()); }
                catch (ResultReleaseException failure) {
                    assertThat(commit).isFalse(); assertThat(failure.reason()).isEqualTo(ResultReleaseException.Reason.UNAVAILABLE); return null;
                }
            });
            assertThat(written.await(10, TimeUnit.SECONDS)).isTrue();
            var reader = pool.submit(() -> {
                authenticate(username);
                try { return view(fixture.attempt()); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue(); assertThat(reader.isDone()).isFalse();
            finish.countDown();
            var acknowledged = writing.get(20, TimeUnit.SECONDS); var rendered = reader.get(20, TimeUnit.SECONDS);
            assertThat(acknowledged != null).isEqualTo(commit); assertThat(rendered.result() != null).isEqualTo(commit);
        } finally {
            finish.countDown(); observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(count("attempt_result_release")).isEqualTo(commit ? 1 : 0); assertThat(releaseEvents()).isEqualTo(commit ? 1 : 0);
    }

    @ParameterizedTest @CsvSource({"release,0,UNAVAILABLE", "release,1213,RETRYABLE", "release,1205,BUSY",
            "read,0,UNAVAILABLE", "read,1213,UNAVAILABLE", "read,1205,BUSY",
            "access,0,UNAVAILABLE", "access,1213,UNAVAILABLE", "access,1205,BUSY"})
    void databaseFailuresAreSanitizedBeforeTransactionalDebugLogging(String operation, int sqlCode, String reason) {
        var fixture = fixture(2, 2, 2); results.finalizeSubmission(fixture.submission());
        if (operation.equals("read")) releases.release(fixture.submission());
        var logger = (Logger) LoggerFactory.getLogger(org.springframework.transaction.support.TransactionTemplate.class);
        var previous = logger.getLevel(); var captured = new ListAppender<ILoggingEvent>(); captured.start();
        logger.setLevel(Level.DEBUG); logger.addAppender(captured); var reached = new AtomicBoolean(); var accessReads = new AtomicInteger();
        observer().beforeSql = (statement, connection) -> {
            boolean selected = operation.equals("access") ? statement.contains("FROM attempt_lifecycle_state l") && accessReads.incrementAndGet() == 2
                    : statement.contains(operation.equals("release") ? "INSERT INTO attempt_result_release" : "FROM attempt_result_release");
            if (selected
                    && reached.compareAndSet(false, true)) throw new SQLException("private-release-marker", "40001", sqlCode);
        };
        try {
            if (operation.equals("release")) reject(fixture.submission(), ResultReleaseException.Reason.valueOf(reason));
            else assertThatThrownBy(() -> view(fixture.attempt())).isInstanceOfSatisfying(
                    uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                    e -> assertThat(e.reason().name()).isEqualTo(reason)).hasNoCause();
            assertThat(reached).isTrue();
            assertThat(captured.list.stream().map(e -> e.getFormattedMessage() + " " + ThrowableProxyUtil.asString(e.getThrowableProxy())))
                    .noneMatch(text -> text.contains("private-release-marker"));
        } finally { observer().reset(); logger.detachAppender(captured); logger.setLevel(previous); captured.stop(); }
        assertThat(releases.release(fixture.submission()).replayed()).isEqualTo(operation.equals("read"));
    }

    @Test void missingResultAndForeignSubmissionNeverCreateARelease() {
        var fixture = fixture(2, 2, 2);
        reject(fixture.submission(), ResultReleaseException.Reason.NOT_READY);
        reject(UUID.randomUUID(), ResultReleaseException.Reason.NOT_FOUND);
        reject(null, ResultReleaseException.Reason.INVALID_INPUT);
        assertThat(count("attempt_result_release")).isZero(); assertThat(releaseEvents()).isZero();
        results.finalizeSubmission(fixture.submission()); assertThat(releases.release(fixture.submission()).replayed()).isFalse();
    }

    @Test void mismatchedCommittedReleaseEventFailsClosedWithoutLegacyFallback() {
        var fixture = fixture(2, 2, 2); results.finalizeSubmission(fixture.submission()); releases.release(fixture.submission());
        tx.executeWithoutResult(s -> sql.update("UPDATE attempt_trace_event SET command_id=UUID_TO_BIN(?) WHERE event_type='RESULT_RELEASED'", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> view(fixture.attempt())).isInstanceOfSatisfying(
                uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class,
                e -> assertThat(e.reason()).isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.Reason.CORRUPT));
        reject(fixture.submission(), ResultReleaseException.Reason.CORRUPT);
        assertThat(count("attempt_result_release")).isEqualTo(1); verifyNoInteractions(legacy);
    }

    @Test void multipleReleasedParentsSerializeWithBoundedQueries() throws Exception {
        var totals = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            var fixtures = new ArrayList<Fixture>();
            for (int parent = 0; parent < 3; parent++) {
                var fixture = fixture(size, 1, 1); results.finalizeSubmission(fixture.submission()); releases.release(fixture.submission());
                fixtures.add(fixture);
            }
            observer().selects.clear();
            for (var fixture : fixtures) {
                var rendered = json.readTree(json.writeValueAsString(view(fixture.attempt())));
                assertThat(rendered.path("result").path("items")).hasSize(size);
            }
            totals.add(observer().selects.size());
        }
        assertThat(totals.get(1)).isEqualTo(totals.get(0)); assertThat(totals.get(0)).isLessThan(150);
        System.out.println("Released result SQL for 3 parents with 1/20 items: " + totals);
    }
    ResultNotificationEventReader eventReader() { return context.getBean(ResultNotificationEventReader.class); }
    ResultNotificationEventReader reconstructedEventReader() {
        var independent = new DataSourceTransactionManager(source);
        return new ResultNotificationEventReaderImpl(transactional(ResultNotificationEventRepository.class,
                new JdbcResultNotificationEventRepository(named()), independent), independent);
    }
    @EnableTransactionManagement static class EventReaderTransactions { }
    Optional<ResultNotificationEvent> eventFromRecreatedContext(UUID id) {
        try (var application = new AnnotationConfigApplicationContext()) {
            application.getBeanFactory().registerSingleton("dataSource", source);
            application.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(source));
            application.registerBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class,
                    () -> new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source));
            application.register(EventReaderTransactions.class, JdbcResultNotificationEventRepository.class, ResultNotificationEventReaderImpl.class);
            application.refresh();
            assertThat(AopUtils.isAopProxy(application.getBean(ResultNotificationEventRepository.class))).isTrue();
            return application.getBean(ResultNotificationEventReader.class).find(id);
        }
    }
    long notificationEvents() { return count("attempt_result_notification_event"); }

    @Test void eventsRealReleaseIsReadableAfterReconstructionWithoutConsumerAcknowledgement() throws Exception {
        var f = fixture(3, 3, 2); var result = results.finalizeSubmission(f.submission());
        assertThat(eventReader().scan(null, 10).events()).isEmpty();
        var eventIsolation = new AtomicReference<String>();
        observer().afterSql = (statement, connection) -> {
            if (statement.stripLeading().startsWith("INSERT INTO attempt_result_notification_event"))
                eventIsolation.set(sql.queryForObject("SELECT @@transaction_isolation", String.class));
        };
        ResultRelease release;
        try { release = releases.release(f.submission()).release(); }
        finally { observer().reset(); }
        assertThat(eventIsolation.get()).isEqualTo("READ-COMMITTED");
        var event = eventReader().find(release.id()).orElseThrow();
        assertThat(event.id()).isEqualTo(release.id()); assertThat(event.source().id()).isEqualTo(release.id());
        assertThat(event.source().version()).isEqualTo(1); assertThat(event.targetId()).isEqualTo(result.id());
        assertThat(event.kind()).isEqualTo(ResultNotificationEvent.Kind.RESULT_OPENED);
        assertThat(event.categories()).containsExactly(uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.Category.TOTAL_SCORE);
        assertThat(event.occurredAt()).isEqualTo(release.releasedAt());
        assertThat(view(f.attempt()).result().totalScore()).isEqualTo(new ParticipantResultView.Score(2, 3, 6667));
        var reconstructed = reconstructedEventReader();
        // A local acknowledgement double loses its acknowledgement. Nothing deletes source evidence.
        var observations = new ArrayList<UUID>();
        for (int scan = 0; scan < 2; scan++) observations.add(reconstructed.scan(null, 1).events().get(0).id());
        assertThat(observations).containsExactly(release.id(), release.id());
        assertThat(restarted().release(f.submission()).release()).isEqualTo(release);
        assertThat(reconstructed.find(release.id())).contains(event); assertThat(notificationEvents()).isEqualTo(1);
        assertThat(eventFromRecreatedContext(release.id())).contains(event);
        var encoded = json.readTree(json.writeValueAsBytes(event));
        assertThat(encoded.has("total")).isFalse(); assertThat(encoded.has("passed")).isFalse();
        assertThat(encoded.toString()).doesNotContain(authoredExplanation, username, "answer", "email");
        assertThat(metrics.get("quizmaker.result.notification.event").tags("version", "1", "kind", "result_opened").counter().count()).isPositive();
    }

    @ParameterizedTest @ValueSource(strings = {"after-release", "before-event", "after-event", "commit"})
    void eventsReachedFailuresRollBackSourceTraceAndOutboxThenSamePathRetryCommits(String checkpoint) {
        var f = fixture(3, 3, 2); var result = results.finalizeSubmission(f.submission());
        var reached = new AtomicBoolean();
        SubmissionDataSource.SqlHook hook = (statement, connection) -> {
            boolean selected = statement.stripLeading().startsWith(checkpoint.equals("after-release")
                    ? "INSERT INTO attempt_result_release" : "INSERT INTO attempt_result_notification_event");
            if (selected && reached.compareAndSet(false, true)) {
                if (checkpoint.equals("commit")) observer().failBeforeCommit.set(true);
                else throw new SQLException("private-event-write", "08006");
            }
        };
        if (checkpoint.equals("before-event")) observer().beforeSql = hook; else observer().afterSql = hook;
        reject(f.submission(), ResultReleaseException.Reason.UNAVAILABLE);
        assertThat(reached).isTrue(); observer().reset();
        assertThat(notificationEvents()).isZero(); assertThat(count("attempt_result_release")).isZero(); assertThat(releaseEvents()).isZero();
        assertThat(eventReader().scan(null, 10).events()).isEmpty(); assertThat(view(f.attempt()).result()).isNull();
        assertThat(results.finalizeSubmission(f.submission())).isEqualTo(result);
        var success = restarted().release(f.submission());
        assertThat(success.replayed()).isFalse(); assertThat(restarted().release(f.submission()).release()).isEqualTo(success.release());
        assertThat(eventReader().find(success.release().id())).isPresent(); assertThat(notificationEvents()).isEqualTo(1);
    }

    @Test void eventsLostActualCommitAcknowledgementRetainsOneSourceAndSemanticIdentity() {
        var f = fixture(3, 3, 2); results.finalizeSubmission(f.submission()); var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.stripLeading().startsWith("INSERT INTO attempt_result_notification_event")) {
                reached.set(true); observer().failAfterCommit.set(true);
            }
        };
        reject(f.submission(), ResultReleaseException.Reason.UNAVAILABLE); assertThat(reached).isTrue(); observer().reset();
        var saved = reconstructedEventReader().scan(null, 10).events(); assertThat(saved).hasSize(1);
        var replay = restarted().release(f.submission()); assertThat(replay.replayed()).isTrue();
        assertThat(replay.release().id()).isEqualTo(saved.get(0).id());
        assertThat(reconstructedEventReader().scan(null, 10).events()).isEqualTo(saved);
        assertThat(eventFromRecreatedContext(saved.get(0).id())).contains(saved.get(0));
        assertThat(notificationEvents()).isEqualTo(1); assertThat(releaseEvents()).isEqualTo(1);
    }

    @Test void eventsCompetingReleasesConvergeWhileAnotherParticipantOnSameRevisionCommits() throws Exception {
        var first = fixture(2, 2, 1); var firstResult = results.finalizeSubmission(first.submission());
        var peerName = "acceptance-" + UUID.randomUUID().toString().substring(0, 12); var peerUser = user(peerName, false);
        grant(peerUser, "ATTEMPT_READ", "ATTEMPT_CREATE", "QUESTION_UPDATE", "QUESTION_ADMIN"); authenticate(peerName);
        var started = starts.start(command(firstResult.quizRevisionId(), AnswerOrder.AUTHORED, "event-peer"));
        var peerItems = delivery.read(started.attemptId()).form().items();
        responses.accept(new ResponseAcceptanceService.Command(started.attemptId(), peerItems.get(0).id(), 1,
                CommandReceipt.Key.from("event-peer-answer"), peerItems.get(0).type(), input(peerItems.get(0), true)));
        var peerSubmission = submissions.submit(new AttemptSubmissionService.Command(started.attemptId(), 2, true, CommandReceipt.Key.from("event-peer-submit")));
        var peerResult = results.finalizeSubmission(peerSubmission.submissionId());
        assertThat(peerResult.quizRevisionId()).isEqualTo(firstResult.quizRevisionId());
        assertThat(peerUser).isNotEqualTo(ownerId); authenticate(username);
        var inserted = new CountDownLatch(1); var finish = new CountDownLatch(1); var waiting = new CountDownLatch(1);
        var firstThread = new AtomicReference<Thread>(); var duplicateThread = new AtomicReference<Thread>();
        observer().afterSql = (statement, connection) -> {
            if (Thread.currentThread() == firstThread.get() && statement.stripLeading().startsWith("INSERT INTO attempt_result_notification_event")) {
                inserted.countDown(); await(finish);
            }
        };
        observer().beforeSql = (statement, connection) -> {
            if (Thread.currentThread() == duplicateThread.get() && statement.contains("FROM attempt_finalization_intent i JOIN")) waiting.countDown();
        };
        var pool = Executors.newFixedThreadPool(3);
        try {
            var holder = pool.submit(() -> { firstThread.set(Thread.currentThread()); return releases.release(first.submission()); });
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(eventReader().scan(null, 10).events()).isEmpty();
            var duplicate = pool.submit(() -> { duplicateThread.set(Thread.currentThread()); return restarted().release(first.submission()); });
            assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue(); assertThat(duplicate.isDone()).isFalse();
            var peer = pool.submit(() -> releases.release(peerSubmission.submissionId()));
            var independent = peer.get(20, TimeUnit.SECONDS); assertThat(independent.replayed()).isFalse();
            assertThat(eventReader().scan(null, 10).events()).extracting(ResultNotificationEvent::id).containsExactly(independent.release().id());
            assertThat(holder.isDone()).isFalse(); finish.countDown();
            var a = holder.get(20, TimeUnit.SECONDS); var b = duplicate.get(20, TimeUnit.SECONDS);
            assertThat(a.replayed()).isFalse(); assertThat(b.replayed()).isTrue(); assertThat(a.release()).isEqualTo(b.release());
            assertThat(reconstructedEventReader().find(a.release().id())).isPresent();
        } finally { finish.countDown(); observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
        assertThat(notificationEvents()).isEqualTo(2); assertThat(count("attempt_result_release")).isEqualTo(2);
        assertThat(reconstructedEventReader().scan(null, 10).events()).hasSize(2);
    }

    @Test void eventsRealLaterPublicationAndResultDoNotRewriteEarlierReferenceOrCategories() {
        var first = fixture(2, 2, 1); var firstResult = results.finalizeSubmission(first.submission());
        var released = releases.release(first.submission()).release(); var original = eventReader().find(released.id()).orElseThrow();
        var bytes = json.valueToTree(original);
        draft(QuestionType.TRUE_FALSE);
        var authoredIds = tx.execute(status -> questions.findAllByQuizId_IdOrderById(quizId).stream().map(Question::getId).toList());
        var newer = publish(1, authoredIds); binding.bind(quizId, newer);
        var started = starts.start(new AttemptStartService.Command(1, quizId, content(newer), 2, CommandReceipt.Key.from("event-later"),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", AnswerOrder.AUTHORED));
        var items = delivery.read(started.attemptId()).form().items();
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            responses.accept(new ResponseAcceptanceService.Command(started.attemptId(), item.id(), i + 1,
                    CommandReceipt.Key.from("event-later-answer-" + i), item.type(), input(item, true)));
        }
        var submitted = submissions.submit(new AttemptSubmissionService.Command(started.attemptId(), items.size() + 1, true, CommandReceipt.Key.from("event-later-submit")));
        var next = results.finalizeSubmission(submitted.submissionId()); releases.release(submitted.submissionId());
        assertThat(next.quizRevisionId()).isNotEqualTo(firstResult.quizRevisionId()); assertThat(next.score().basisPoints()).isEqualTo(10000);
        assertThat(reconstructedEventReader().find(released.id())).contains(original);
        com.fasterxml.jackson.databind.JsonNode reread = json.valueToTree(eventReader().find(released.id()).orElseThrow());
        assertThat(reread).isEqualTo(bytes);
        assertThat(restarted().release(first.submission()).release()).isEqualTo(released);
        assertThat(view(first.attempt()).result().totalScore().basisPoints()).isEqualTo(5000); assertThat(notificationEvents()).isEqualTo(2);
        // This proves immutable references across real versions, not #550 correction/selection integration.
    }

    @Test void eventsBoundedPagesReplayWithoutDuplicatesAndDoNotReadGradeOrQuestionCollections() {
        var statementCounts = new ArrayList<Integer>();
        for (int size : List.of(1, 20)) {
            for (int i = 0; i < 3; i++) { var f = fixture(size, size, size); results.finalizeSubmission(f.submission()); releases.release(f.submission()); }
            observer().selects.clear();
            var page = eventReader().scan(null, 2); assertThat(page.events()).hasSize(2); assertThat(page.nextCursor()).isNotNull();
            var all = new ArrayList<>(page.events());
            while (page.nextCursor() != null) { page = eventReader().scan(page.nextCursor(), 2); all.addAll(page.events()); }
            assertThat(all).extracting(ResultNotificationEvent::id).doesNotHaveDuplicates();
            assertThat(all).hasSize(size == 1 ? 3 : 6);
            observer().selects.clear();
            assertThat(eventReader().scan(null, 500).events()).hasSize(size == 1 ? 3 : 6);
            statementCounts.add(observer().selects.size());
            assertThat(observer().selects).allSatisfy(statement -> assertThat(statement).doesNotContain(
                    "attempt_automatic_grade", "question_revisions", "canonical_content", "attempt_accepted_response", "normalized_documents"));
        }
        assertThat(statementCounts).containsExactly(1, 1);
        var plan = rc(() -> sql.queryForMap("EXPLAIN SELECT id FROM attempt_result_notification_event WHERE id>UUID_TO_BIN(?) ORDER BY id LIMIT 2", new UUID(0, 0).toString()));
        assertThat(plan.get("key")).isEqualTo("PRIMARY"); assertThat(String.valueOf(plan.get("Extra"))).doesNotContainIgnoringCase("filesort");
        System.out.println("Notification event scans, 3 parents with 1/20 items: " + statementCounts);
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 501})
    void eventsInvalidReadInputsFailBeforeAnyDomainSql(int limit) {
        observer().selects.clear();
        assertThatThrownBy(() -> eventReader().scan(null, limit)).isInstanceOf(ResultEventException.class);
        assertThatThrownBy(() -> eventReader().find(null)).isInstanceOf(ResultEventException.class);
        assertThat(observer().selects).isEmpty(); assertThat(eventReader().find(UUID.randomUUID())).isEmpty();
    }

    @Test void eventsReaderOwnsRcRejectsRrBeforeSqlAndPreservesCallerTransaction() {
        var f = fixture(1, 1, 1); results.finalizeSubmission(f.submission()); var release = releases.release(f.submission()).release();
        var observed = new ArrayList<String>();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("FROM attempt_result_notification_event")) observed.add(sql.queryForObject("SELECT @@transaction_isolation", String.class));
        };
        assertThat(eventReader().find(release.id())).isPresent(); assertThat(observed).containsExactly("READ-COMMITTED"); observer().reset();
        tx.executeWithoutResult(status -> {
            assertThatThrownBy(() -> eventReader().find(release.id())).isInstanceOf(uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException.class);
            assertThat(observer().selects).isEmpty();
        });
        var rc = new TransactionTemplate(manager); rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        rc.executeWithoutResult(status -> {
            sql.update("UPDATE quizzes SET description='event-reader-provisional' WHERE quiz_id=UUID_TO_BIN(?)", quizId.toString());
            assertThat(eventReader().find(release.id())).isPresent(); status.setRollbackOnly();
        });
        String description = tx.execute(status -> sql.queryForObject("SELECT description FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", String.class, quizId.toString()));
        assertThat(description).isNotEqualTo("event-reader-provisional");
        assertThat(notificationEvents()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"insert", "find", "scan"})
    void eventsSanitizeReachedRepositoryFailuresBeforeNestedTransactionLogging(String operation) {
        var f = fixture(1, 1, 1); results.finalizeSubmission(f.submission());
        UUID id = operation.equals("insert") ? UUID.randomUUID() : releases.release(f.submission()).release().id();
        var logger = (Logger) LoggerFactory.getLogger(TransactionInterceptor.class);
        var level = logger.getLevel(); var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs); logger.setLevel(Level.TRACE);
        var reached = new AtomicBoolean();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("attempt_result_notification_event") && reached.compareAndSet(false, true)) {
                var failure = new SQLException("private-event-driver user@example.invalid", "08006");
                failure.initCause(new IllegalStateException("private-event-cause")); failure.addSuppressed(new IllegalStateException("private-event-suppressed"));
                failure.setNextException(new SQLException("private-event-chain")); throw failure;
            }
        };
        try {
            if (operation.equals("insert")) reject(f.submission(), ResultReleaseException.Reason.UNAVAILABLE);
            else assertThatThrownBy(() -> { if (operation.equals("find")) reconstructedEventReader().find(id); else reconstructedEventReader().scan(null, 10); })
                    .isInstanceOf(ResultEventException.class);
            assertThat(reached).isTrue();
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("JdbcResultNotificationEventRepository." + operation, "after exception"));
            for (var event : logs.list) {
                assertThat(event.getFormattedMessage()).doesNotContain("private-event-", "user@example.invalid", "SQL [");
                if (event.getThrowableProxy() != null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain("private-event-", "user@example.invalid", "SQL [");
            }
        } finally { observer().reset(); logger.detachAppender(logs); logger.setLevel(level); logs.stop(); }
        var success = releases.release(f.submission()); assertThat(eventReader().find(success.release().id())).isPresent();
        assertThat(notificationEvents()).isEqualTo(1);
    }

    @Test void eventsSourceBindingAndSchemaConstraintsRejectUnsupportedOrInconsistentFacts() {
        var first = fixture(1, 1, 1); results.finalizeSubmission(first.submission()); var release = releases.release(first.submission()).release();
        var original = eventReader().find(release.id()).orElseThrow();
        assertThatThrownBy(() -> rc(() -> { context.getBean(ResultNotificationEventRepository.class).insert(original); return null; }))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThatThrownBy(() -> rc(() -> sql.update("UPDATE attempt_result_notification_event SET schema_version=2")))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                        e -> assertThat(((SQLException) e.getRootCause()).getErrorCode()).isEqualTo(3819));
        var second = fixture(1, 1, 1); var other = results.finalizeSubmission(second.submission());
        rc(() -> sql.update("UPDATE attempt_result_notification_event SET target_id=UUID_TO_BIN(?)", other.id().toString()));
        assertThatThrownBy(() -> eventReader().find(original.id())).isInstanceOfSatisfying(ResultEventException.class,
                e -> assertThat(e.reason()).isEqualTo(ResultEventException.Reason.CORRUPT));
        rc(() -> sql.update("UPDATE attempt_result_notification_event SET target_id=UUID_TO_BIN(?)", original.targetId().toString()));
        assertThat(eventReader().find(original.id())).contains(original);
    }

    @Test void eventsScheduledSourceRemainsOutsideEnabledNotificationProducer() {
        var f = scheduled(1); time.set(due);
        assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
        assertThat(notificationEvents()).isZero();
        var control = fixture(1, 1, 1); results.finalizeSubmission(control.submission()); releases.release(control.submission());
        assertThat(notificationEvents()).isEqualTo(1);
    }

    /** These are real result/configuration/worker compositions, not a formal-start activation claim.
     * The existing real practice producer supplies committed binary result fixtures allowed by #634.
     * #668 separately proves that a formal start captures this configuration before participation. */
    final Instant due=Instant.parse("2026-11-05T13:00:00Z");
    final AtomicReference<Instant> time=new AtomicReference<>(due.minusSeconds(60));
    final Clock clock=new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(),zone); }
        public Instant instant() { return time.get(); }
    };
    org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named() {
        return context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
    }
    JdbcScheduledReleaseRepository repository() { return new JdbcScheduledReleaseRepository(named()); }
    ScheduledResultReleaseService worker() {
        return worker(manager,context.getBean(ExecutionTraceService.class),context.getBean(ResultReleaseRepository.class));
    }
    ScheduledResultReleaseService reconstructedWorker() {
        var independent=new DataSourceTransactionManager(source);
        var trace=new ExecutionTraceServiceImpl(transactional(uk.gegc.quizmaker.features.attempt.domain.repository.ExecutionTraceRepository.class,
                new JdbcExecutionTraceRepository(named()),independent),independent,clock,metrics);
        return worker(independent,trace,transactional(ResultReleaseRepository.class,new JdbcResultReleaseRepository(named()),independent));
    }
    ScheduledResultReleaseService worker(PlatformTransactionManager transactions,ExecutionTraceService trace,ResultReleaseRepository releases) {
        assertThat(AopUtils.isAopProxy(releases)).as("Exercise the nested release transaction interceptor").isTrue();
        var configurations=transactional(ReleaseConfigurationRepository.class,new JdbcReleaseConfigurationRepository(named()),transactions);
        var reader=transactional(uk.gegc.quizmaker.features.result.application.ReleaseConfigurationReader.class,
                new ReleaseConfigurationReaderImpl(configurations,transactions),transactions);
        return new ScheduledResultReleaseServiceImpl(transactional(ScheduledReleaseRepository.class,repository(),transactions),
                reader,releases,trace,clock,metrics,transactions);
    }
    <T> T transactional(Class<T> contract,Object target,PlatformTransactionManager transactions) {
        var proxy=new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions,new AnnotationTransactionAttributeSource()));
        return contract.cast(proxy.getProxy());
    }
    ReleaseConfigurationService author() {
        return new ReleaseConfigurationServiceImpl(new QuizReleaseConfigurationSource(context.getBean(QuizPublicationRepository.class)),
                new JdbcReleaseConfigurationRepository(named()),context.getBean(Clock.class),metrics,manager);
    }
    ReleaseConfiguration rule(Instant at) {
        return ReleaseConfiguration.formal(1,Set.of(ReleaseConfiguration.Trigger.SCHEDULED),null,
                new ReleaseConfiguration.ScheduleInput(LocalDateTime.ofInstant(at,ZoneOffset.UTC),"Europe/London",ZoneOffset.UTC));
    }
    ReleaseConfigurationService.Publication publishRule(AutomaticResult result,long version,ReleaseConfiguration rule) {
        return author().publish(new ReleaseConfigurationService.Command(quizId,result.quizRevisionId(),UUID.randomUUID(),version,rule));
    }
    record ScheduledFixture(Fixture attempt,AutomaticResult result,ReleaseConfigurationService.Publication configuration,ScheduledRelease work) { }
    ScheduledFixture scheduled(int size) {
        var f=fixture(size,size,size); var r=results.finalizeSubmission(f.submission()); var c=publishRule(r,0,rule(due));
        return new ScheduledFixture(f,r,c,worker().register(f.submission(),r.id(),c.id()));
    }
    ScheduledRelease saved(ScheduledFixture f) { return rc(()->repository().find(f.attempt().submission(),false).orElseThrow()); }
    <T> T rc(java.util.function.Supplier<T> action) {
        return uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions.readCommitted(manager).execute(s->action.get());
    }
    ResultDisclosure.Decisions decisions(ScheduledFixture f,UUID requester) {
        return rc(()->{
            var persisted=context.getBean(uk.gegc.quizmaker.features.result.domain.repository.AutomaticResultRepository.class)
                    .find(f.attempt().submission()).orElseThrow();
            var b=ResultDisclosure.Binding.of(persisted);
            var rule=new JdbcResultReleaseRepository(named()).scheduledRule(persisted.id()).orElseThrow();
            var release=new JdbcResultReleaseRepository(named()).find(persisted.id());
            return context.getBean(ResultDisclosureService.class).evaluate(new ResultDisclosure.Context(1,
                    new ResultDisclosure.Audience(ResultDisclosure.Access.VERIFIED,b.attemptId(),requester,ownerId),
                    new ResultDisclosure.Policy(1,b,rule.family(),rule.categories()),
                    new ResultDisclosure.AttemptFacts(b,lifecycle.lock(b.attemptId()).state()),
                    ResultDisclosure.ResultFacts.finalized(persisted),new ResultDisclosure.ReleaseFacts(1,b,persisted.id(),
                        release.isPresent()?ResultDisclosure.ReleaseState.RELEASED:ResultDisclosure.ReleaseState.PENDING),
                    ResultDisclosure.ReviewAccess.WITHHELD,EnumSet.allOf(ResultDisclosure.Category.class)));
        });
    }
    @Test void scheduledSavedInstantAndRuleControlRealReleaseAndDisclosureAcrossRestartAndTimezoneChange() {
        var f=scheduled(3); var later=publishRule(f.result(),1,rule(due.plusSeconds(3600)));
        assertThat(later.id()).isNotEqualTo(f.configuration().id());
        time.set(due.minusMillis(1));
        assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.NOT_DUE);
        assertThat(saved(f)).isEqualTo(f.work());
        assertThat(view(f.attempt().attempt()).result()).isNull();
        assertThat(decisions(f,ownerId).category(ResultDisclosure.Category.TOTAL_SCORE).outcome()).isEqualTo(ResultDisclosure.Outcome.PENDING);
        var original=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo")); time.set(due);
                var released=reconstructedWorker().execute(f.attempt().submission());
            assertThat(released.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
            assertThat(released.release().releasedAt()).isEqualTo(due);
            assertThat(released.release().id()).isEqualTo(f.work().eventId());
            time.set(due.plusSeconds(7200));
            assertThat(worker().execute(f.attempt().submission()).release()).isEqualTo(released.release());
        } finally { TimeZone.setDefault(original); }
        assertThat(saved(f).configurationId()).isEqualTo(f.configuration().id());
        assertThat(saved(f).state()).isEqualTo(ScheduledRelease.State.RELEASED);
        assertThat(releaseEvents()).isEqualTo(1);
        var allowed=decisions(f,ownerId);
        assertThat(allowed.allows(ResultDisclosure.Category.TOTAL_SCORE)).isTrue();
        assertThat(allowed.allows(ResultDisclosure.Category.PASS_FAIL)).isTrue();
        for(var category:ResultDisclosure.Category.values()) if(category!=ResultDisclosure.Category.TOTAL_SCORE && category!=ResultDisclosure.Category.PASS_FAIL)
            assertThat(allowed.allows(category)).as(category.name()).isFalse();
        assertThat(decisions(f,UUID.randomUUID()).categories().values()).allMatch(d->d.outcome()==ResultDisclosure.Outcome.DENY);
        var rendered=view(f.attempt().attempt()).result();
        assertThat(rendered.totalScore().earned()).isEqualTo(3);
        assertThat(rendered.items()).isNull(); assertThat(rendered.reviewAvailable()).isNull();
        assertThat(rc(()->context.getBean(uk.gegc.quizmaker.features.result.domain.repository.AutomaticResultRepository.class)
                .find(f.attempt().submission()).orElseThrow())).isEqualTo(f.result());
    }
    @Test void scheduledOrdinaryImmediateWorkerCannotBypassScheduleAndBindingCannotReplaceAlreadyReleasedResult() {
        var f=scheduled(1);
        reject(f.attempt().submission(),ResultReleaseException.Reason.UNSUPPORTED);
        assertThat(count("attempt_result_release")).isZero();
        assertThat(worker().register(f.attempt().submission(),f.result().id(),f.configuration().id())).isEqualTo(f.work());
        var other=fixture(1,1,1); var result=results.finalizeSubmission(other.submission());
        releases.release(other.submission()); var config=publishRule(result,0,rule(due));
        assertThatThrownBy(()->worker().register(other.submission(),result.id(),config.id())).isInstanceOf(ResultReleaseException.class);
        time.set(due); assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
    }
    @Test void scheduledWrongConfigurationResultAndDualTriggerAreRejectedWithoutRegisteringWork() {
        var f=fixture(1,1,1); var result=results.finalizeSubmission(f.submission());
        var manual=ReleaseConfiguration.formal(1,Set.of(ReleaseConfiguration.Trigger.MANUAL),null,null);
        var c=publishRule(result,0,manual);
        assertThatThrownBy(()->worker().register(f.submission(),result.id(),c.id())).isInstanceOf(ResultReleaseException.class);
        assertThatThrownBy(()->worker().register(f.submission(),result.id(),UUID.randomUUID())).isInstanceOf(ResultReleaseException.class);
        var good=publishRule(result,1,rule(due));
        assertThatThrownBy(()->worker().register(f.submission(),UUID.randomUUID(),good.id())).isInstanceOf(ResultReleaseException.class);
        assertThat(count("attempt_scheduled_release")).isZero();
        var dual=ReleaseConfiguration.formal(1,Set.of(ReleaseConfiguration.Trigger.MANUAL,ReleaseConfiguration.Trigger.SCHEDULED),null,
                new ReleaseConfiguration.ScheduleInput(LocalDateTime.ofInstant(due,ZoneOffset.UTC),"Europe/London",null));
        var both=publishRule(result,2,dual);
        assertThatThrownBy(()->worker().register(f.submission(),result.id(),both.id())).isInstanceOf(ResultReleaseException.class);
        assertThat(worker().register(f.submission(),result.id(),good.id()).state()).isEqualTo(ScheduledRelease.State.PENDING);
    }
    @Test void scheduledCorruptedCurrentResultIsQuarantinedWithoutGuessingAndCannotAutoResume() {
        var f=scheduled(2); time.set(due);
        sql.update("UPDATE attempt_automatic_result SET policy_hash=? WHERE id=UUID_TO_BIN(?)","b".repeat(64),f.result().id().toString());
        var refused=worker().execute(f.attempt().submission());
        assertThat(refused.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REVIEW);
        assertThat(saved(f).lastFailure()).isEqualTo(ResultReleaseException.Reason.CORRUPT);
        assertThat(count("attempt_result_release")).isZero();
        sql.update("UPDATE attempt_automatic_result SET policy_hash=? WHERE id=UUID_TO_BIN(?)",f.result().policyHash(),f.result().id().toString());
        assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REVIEW);
        var control=scheduled(1); assertThat(worker().execute(control.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
    }
    @ParameterizedTest @ValueSource(strings={"trace","release","work"})
    void scheduledReachedWriteFailureRollsBackReleaseTraceAndCompletionBeforeOneSuccessfulRetry(String stage) {
        var f=scheduled(2); time.set(due); var reached=new AtomicBoolean();
        observer().afterSql=(statement,connection)->{
            String prefix=switch(stage) { case "trace"->"INSERT INTO attempt_trace_event"; case "release"->"INSERT INTO attempt_result_release"; default->"UPDATE attempt_scheduled_release"; };
            if(statement.stripLeading().startsWith(prefix) && reached.compareAndSet(false,true)) throw new SQLException("private-scheduled-fault","08006");
        };
        assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
        assertThat(reached).isTrue(); observer().reset();
        assertThat(count("attempt_result_release")).isZero(); assertThat(releaseEvents()).isZero();
        var retry=saved(f); assertThat(retry.state()).isEqualTo(ScheduledRelease.State.RETRY); assertThat(retry.failures()).isEqualTo(1);
        assertThat(retry.nextAt()).isEqualTo(due.plusSeconds(60));
        assertThat(view(f.attempt().attempt()).result()).isNull();
        time.set(retry.nextAt()); var success=worker().execute(f.attempt().submission());
        assertThat(success.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
        assertThat(success.release().id()).isEqualTo(f.work().eventId());
        assertThat(worker().execute(f.attempt().submission()).release()).isEqualTo(success.release());
        assertThat(releaseEvents()).isEqualTo(1);
    }
    @Test void scheduledLostCommitAcknowledgementReconcilesCommittedWorkWithoutSpendingRetryBudget() {
        var f=scheduled(1); time.set(due); var reached=new AtomicBoolean();
        observer().afterSql=(statement,connection)->{
            if(statement.stripLeading().startsWith("UPDATE attempt_scheduled_release") && reached.compareAndSet(false,true)) observer().failAfterCommit.set(true);
        };
        var result=worker().execute(f.attempt().submission()); observer().reset();
        assertThat(reached).isTrue(); assertThat(result.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REPLAYED);
        assertThat(saved(f).failures()).isZero(); assertThat(releaseEvents()).isEqualTo(1);
        assertThat(reconstructedWorker().execute(f.attempt().submission()).release()).isEqualTo(result.release());
    }
    @Test void scheduledThreeRetriesAndExhaustionSurviveReconstructionAndNeverOpenResultEarly() {
        var f=scheduled(1); time.set(due);
        for(int i=0;i<4;i++) {
            var at=time.get();
            observer().afterSql=(statement,connection)->{ if(statement.stripLeading().startsWith("INSERT INTO attempt_result_release")) throw new SQLException("private-retry","08006"); };
            var failed=worker().execute(f.attempt().submission()); observer().reset();
            var state=saved(f); assertThat(state.failures()).isEqualTo(i+1);
            if(i<3) {
                assertThat(failed.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
                assertThat(state.nextAt()).isEqualTo(at.plusSeconds(List.of(60,300,900).get(i)));
                time.set(state.nextAt().minusMillis(1));
                assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.NOT_DUE);
                assertThat(saved(f)).isEqualTo(state); time.set(state.nextAt());
            } else assertThat(failed.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REVIEW);
        }
        assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REVIEW);
        assertThat(count("attempt_result_release")).isZero(); assertThat(releaseEvents()).isZero();
    }
    @Test void scheduledDuplicateWorkersConvergeAfterReachedReleaseWrite() throws Exception {
        var f=scheduled(1); time.set(due);
        var inserted=new CountDownLatch(1); var finish=new CountDownLatch(1); var competing=new CountDownLatch(1);
        observer().afterSql=(statement,connection)->{ if(statement.stripLeading().startsWith("INSERT INTO attempt_result_release")) { inserted.countDown(); await(finish); } };
        observer().beforeSql=(statement,connection)->{ if(inserted.getCount()==0 && statement.contains("FOR UPDATE OF i,l")) competing.countDown(); };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(()->worker().execute(f.attempt().submission())); await(inserted);
            var peer=pool.submit(()->worker().execute(f.attempt().submission())); await(competing);
            assertThat(peer.isDone()).isFalse(); assertThat(count("attempt_result_release")).isZero(); finish.countDown();
            var a=holder.get(20,TimeUnit.SECONDS); var b=peer.get(20,TimeUnit.SECONDS);
            assertThat(a.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
            assertThat(b.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REPLAYED);
            assertThat(a.release()).isEqualTo(b.release());
        } finally { finish.countDown(); observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        assertThat(releaseEvents()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void scheduledIndependentParticipantsCommitWithSharedOrUnrelatedRevisions(boolean shared) throws Exception {
        var first=scheduled(2);
        var firstParticipant=ownerId;
        username="acceptance-scheduled-peer-"+UUID.randomUUID().toString().substring(0,8);
        ownerId=user(username,true);
        // The current dark practice boundary admits the owner or an effective full-content editor.
        // This is a real second authorized participant, not a replacement subject fixture.
        grant(ownerId,"ATTEMPT_READ","ATTEMPT_CREATE","QUESTION_UPDATE","QUIZ_MODERATE"); authenticate(username);
        UUID peerSubmission;
        AutomaticResult result;
        if(shared) {
            var start=starts.start(command(first.result().quizRevisionId(),AnswerOrder.AUTHORED,"scheduled-peer-"+UUID.randomUUID()));
            var items=delivery.read(start.attemptId()).form().items();
            for(int i=0;i<items.size();i++) responses.accept(new ResponseAcceptanceService.Command(start.attemptId(),items.get(i).id(),i+1,
                    CommandReceipt.Key.from("scheduled-peer-answer-"+UUID.randomUUID()),items.get(i).type(),input(items.get(i),true)));
            var submit=submissions.submit(new AttemptSubmissionService.Command(start.attemptId(),3,true,CommandReceipt.Key.from("scheduled-peer-submit-"+UUID.randomUUID())));
            peerSubmission=submit.submissionId(); result=results.finalizeSubmission(peerSubmission);
            worker().register(peerSubmission,result.id(),first.configuration().id());
        } else {
            var second=scheduled(2); peerSubmission=second.attempt().submission(); result=second.result();
        }
        assertThat(ownerId).isNotEqualTo(firstParticipant);
        assertThat(sql.queryForObject("SELECT BIN_TO_UUID(user_id) FROM attempt_participant_subject WHERE attempt_id=UUID_TO_BIN(?)",String.class,result.attemptId().toString())).isEqualTo(ownerId.toString());
        assertThat(sql.queryForObject("SELECT BIN_TO_UUID(user_id) FROM attempt_participant_subject WHERE attempt_id=UUID_TO_BIN(?)",String.class,first.result().attemptId().toString())).isEqualTo(firstParticipant.toString());
        assertThat(result.quizRevisionId().equals(first.result().quizRevisionId())).isEqualTo(shared);
        time.set(due);
        var inserted=new CountDownLatch(1); var finish=new CountDownLatch(1); var firstWrite=new AtomicBoolean();
        observer().afterSql=(statement,connection)->{ if(statement.stripLeading().startsWith("INSERT INTO attempt_result_release") && firstWrite.compareAndSet(false,true)) { inserted.countDown(); await(finish); } };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var holder=pool.submit(()->worker().execute(first.attempt().submission())); await(inserted);
            var peer=pool.submit(()->worker().execute(peerSubmission));
            assertThat(peer.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
            assertThat(holder.isDone()).isFalse(); assertThat(releaseEvents()).isEqualTo(1);
            finish.countDown(); assertThat(holder.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
        } finally { finish.countDown(); observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        assertThat(releaseEvents()).isEqualTo(2);
    }
    @Test void scheduledBoundedBatchesDoNotStarveHealthyJobsBehindPoisonAndFutureRows() {
        var poison=scheduled(1); var good=scheduled(1); var future=scheduled(1);
        sql.update("UPDATE attempt_automatic_result SET policy_hash=? WHERE id=UUID_TO_BIN(?)","b".repeat(64),poison.result().id().toString());
        // The third row is not yet due; its original captured schedule stays immutable.
        sql.update("UPDATE attempt_scheduled_release SET work_state='RETRY',failures=1,row_version=1,last_failure='UNAVAILABLE',next_at=? WHERE submission_id=UUID_TO_BIN(?)",
                Timestamp.from(due.plusSeconds(60)),future.attempt().submission().toString());
        time.set(due); ScheduledResultReleaseService.Cursor cursor=null; int reviewed=0,released=0,examined=0;
        do {
            var page=worker().runBatch(1,cursor); reviewed+=page.review(); released+=page.released(); examined+=page.examined(); cursor=page.next();
        } while(cursor!=null);
        assertThat(reviewed).isEqualTo(1); assertThat(released).isEqualTo(1); assertThat(examined).isEqualTo(2);
        assertThat(saved(poison).state()).isEqualTo(ScheduledRelease.State.REVIEW);
        assertThat(saved(good).state()).isEqualTo(ScheduledRelease.State.RELEASED);
        assertThat(saved(future).state()).isEqualTo(ScheduledRelease.State.RETRY);
    }
    @Test void scheduledReleaseIsIndependentRcAndConnectionIsolationReturnsToOuterRrAfterRollback() {
        var f=scheduled(1); time.set(due); var reached=new AtomicBoolean();
        observer().beforeSql=(statement,connection)->{ if(statement.contains("FOR UPDATE OF i,l")) {
            assertThat(DataSourceUtils.getConnection(source).getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            reached.set(true);
        } };
        var rr=new TransactionTemplate(manager); rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        rr.executeWithoutResult(s->{
            assertThat(sql.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ");
            worker().execute(f.attempt().submission());
            assertThat(sql.queryForObject("SELECT @@transaction_isolation",String.class)).isEqualTo("REPEATABLE-READ"); s.setRollbackOnly();
        });
        observer().reset(); assertThat(reached).isTrue();
        assertThat(saved(f).state()).isEqualTo(ScheduledRelease.State.RELEASED); assertThat(releaseEvents()).isEqualTo(1);
    }
    @Test void scheduledWorkQueriesStayBoundedForThreeParentsWithOneOrTwentyItems() {
        var totals=new ArrayList<Integer>();
        var readTotals=new ArrayList<Integer>();
        for(int size:List.of(1,20)) {
            var fixtures=new ArrayList<ScheduledFixture>();
            for(int i=0;i<3;i++) fixtures.add(scheduled(size));
            time.set(due); observer().reset();
            for(var f:fixtures) assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
            totals.add(observer().selects.size());
            assertThat(observer().selects).noneMatch(s->s.contains("normalized_response") || s.contains("canonical_json")
                    || s.contains("question_text") || s.contains("attempt_automatic_grade") || s.contains("documents"));
            observer().reset();
            for(var f:fixtures) {
                var projected=view(f.attempt().attempt()).result();
                assertThat(projected.totalScore().possible()).isEqualTo(size); assertThat(projected.items()).isNull();
            }
            readTotals.add(observer().selects.size());
        }
        assertThat(totals.get(1)).isEqualTo(totals.get(0)); assertThat(totals.get(0)).isLessThan(70);
        assertThat(readTotals.get(1)).isEqualTo(readTotals.get(0)); assertThat(readTotals.get(0)).isLessThan(150);
        System.out.println("Scheduled release SQL for three parents at 1/20 items: "+totals);
        System.out.println("Scheduled participant-read SQL for three parents at 1/20 items: "+readTotals);
    }
    @Test void scheduledDatabaseRejectsBindingMutationAndMissingRetryTimeAndValidWorkStillRuns() {
        var f=scheduled(1);
        assertThatThrownBy(()->sql.update("UPDATE attempt_scheduled_release SET configuration_hash=? WHERE submission_id=UUID_TO_BIN(?)","b".repeat(64),f.attempt().submission().toString()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->sql.update("UPDATE attempt_scheduled_release SET next_at=NULL WHERE submission_id=UUID_TO_BIN(?)",f.attempt().submission().toString()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        time.set(due); assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
    }
    @Test void scheduledCurrentHttpAuthorityAndWithheldFieldsRemainIndependentOfCommittedRelease() throws Exception {
        var f=scheduled(2); time.set(due); worker().execute(f.attempt().submission()); var mvc=mvc();
        mvc.perform(get("/api/v2/attempts/{id}",f.attempt().attempt()).principal(SecurityContextHolder.getContext().getAuthentication()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalScore.earned").value(2))
                .andExpect(jsonPath("$.result.items").doesNotExist()).andExpect(jsonPath("$.result.reviewAvailable").doesNotExist());
        var foreign="acceptance-scheduled-"+UUID.randomUUID().toString().substring(0,8);
        var other=user(foreign,true); grant(other,"ATTEMPT_READ","ATTEMPT_CREATE"); authenticate(foreign);
        mvc.perform(get("/api/v2/attempts/{id}",f.attempt().attempt()).principal(SecurityContextHolder.getContext().getAuthentication()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v2/attempts/{id}",f.attempt().attempt())).andExpect(status().isUnauthorized());
        authenticate(username);
        sql.update("DELETE rp FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id JOIN permissions p ON p.permission_id=rp.permission_id WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name='ATTEMPT_READ'",ownerId.toString());
        mvc.perform(get("/api/v2/attempts/{id}",f.attempt().attempt()).principal(SecurityContextHolder.getContext().getAuthentication()))
                .andExpect(status().isNotFound());
        grant(ownerId,"ATTEMPT_READ");
        mvc.perform(get("/api/v2/attempts/{id}",f.attempt().attempt()).principal(SecurityContextHolder.getContext().getAuthentication()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.totalScore.earned").value(2));
        assertThat(releaseEvents()).isEqualTo(1); verifyNoInteractions(legacy);
    }
    @Test void scheduledUnknownFutureConfigurationWithValidDigestNeverFallsBackToImmediate() {
        var f=fixture(1,1,1); var result=results.finalizeSubmission(f.submission()); var c=publishRule(result,0,rule(due));
        var format=new uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat();
        var future=format.encode(c.rule()).replace("\"version\":1","\"version\":2");
        var hash=uk.gegc.quizmaker.features.result.infra.release.ReleaseConfigurationFormat.hash(future);
        var id=UUID.randomUUID();
        sql.update("INSERT INTO result_release_configurations(id,quiz_id,revision_id,publication_version,configuration_hash,canonical_configuration,published_by,published_at) SELECT UUID_TO_BIN(?),quiz_id,revision_id,publication_version+1,?,?,published_by,published_at FROM result_release_configurations WHERE id=UUID_TO_BIN(?)",
                id.toString(),hash,future,c.id().toString());
        assertThatThrownBy(()->worker().register(f.submission(),result.id(),id)).isInstanceOf(ResultReleaseException.class);
        assertThat(count("attempt_scheduled_release")).isZero();
        // A persisted newer-writer fixture proves old-worker refusal, not a v2 configuration writer.
        rc(()->{ repository().insert(new ScheduledRelease(f.submission(),result.id(),id,hash,UUID.randomUUID(),due,time.get(),
                ScheduledRelease.State.PENDING,0,0,due,null)); return null; });
        time.set(due);
        assertThat(worker().execute(f.submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REVIEW);
        assertThat(count("attempt_result_release")).isZero();
        assertThatThrownBy(()->view(f.attempt())).isInstanceOf(uk.gegc.quizmaker.features.attempt.domain.model.form.AttemptDeliveryException.class);
        var control=scheduled(1); assertThat(worker().execute(control.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
    }
    @Test void scheduledRegistrationAndImmediateWorkerSerializeBeforeAnyEarlyRelease() throws Exception {
        var f=fixture(1,1,1); var result=results.finalizeSubmission(f.submission()); var c=publishRule(result,0,rule(due));
        var inserted=new CountDownLatch(1); var finish=new CountDownLatch(1); var competing=new CountDownLatch(1);
        observer().afterSql=(statement,connection)->{ if(statement.stripLeading().startsWith("INSERT INTO attempt_scheduled_release")) { inserted.countDown(); await(finish); } };
        observer().beforeSql=(statement,connection)->{ if(inserted.getCount()==0 && statement.contains("FROM attempt_finalization_intent i") && !statement.contains("FOR UPDATE OF i,l")) competing.countDown(); };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var registering=pool.submit(()->worker().register(f.submission(),result.id(),c.id())); await(inserted);
            var immediate=pool.submit(()->{ try { releases.release(f.submission()); return null; } catch(ResultReleaseException e) { return e.reason(); } });
            await(competing); assertThat(immediate.isDone()).isFalse(); finish.countDown();
            assertThat(registering.get(20,TimeUnit.SECONDS).resultId()).isEqualTo(result.id());
            assertThat(immediate.get(20,TimeUnit.SECONDS)).isEqualTo(ResultReleaseException.Reason.UNSUPPORTED);
        } finally { finish.countDown(); observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        assertThat(count("attempt_result_release")).isZero();
    }
    @Test void scheduledLostRegistrationAcknowledgementRetainsOneImmutableIdentityOnRetry() {
        var f=fixture(1,1,1); var result=results.finalizeSubmission(f.submission()); var c=publishRule(result,0,rule(due)); var reached=new AtomicBoolean();
        observer().afterSql=(statement,connection)->{ if(statement.stripLeading().startsWith("INSERT INTO attempt_scheduled_release") && reached.compareAndSet(false,true)) observer().failAfterCommit.set(true); };
        assertThatThrownBy(()->worker().register(f.submission(),result.id(),c.id())).isInstanceOf(ResultReleaseException.class);
        observer().reset(); assertThat(reached).isTrue();
        var stored=rc(()->repository().find(f.submission(),false).orElseThrow());
        assertThat(worker().register(f.submission(),result.id(),c.id())).isEqualTo(stored);
        assertThat(count("attempt_scheduled_release")).isEqualTo(1);
        time.set(due); assertThat(worker().execute(f.submission()).release().id()).isEqualTo(stored.eventId());
    }
    @Test void scheduledRegistrationFailureAfterInsertRollsBackAndSamePathCanRegister() {
        var f=fixture(1,1,1); var result=results.finalizeSubmission(f.submission()); var c=publishRule(result,0,rule(due)); var reached=new AtomicBoolean();
        observer().afterSql=(statement,connection)->{ if(statement.stripLeading().startsWith("INSERT INTO attempt_scheduled_release") && reached.compareAndSet(false,true)) throw new SQLException("private-register-fault","08006"); };
        assertThatThrownBy(()->worker().register(f.submission(),result.id(),c.id())).isInstanceOf(ResultReleaseException.class);
        observer().reset(); assertThat(reached).isTrue(); assertThat(count("attempt_scheduled_release")).isZero();
        assertThat(count("attempt_result_release")).isZero();
        var registered=worker().register(f.submission(),result.id(),c.id());
        time.set(due); assertThat(reconstructedWorker().execute(f.submission()).release().id()).isEqualTo(registered.eventId());
    }
    @Test void scheduledDueCursorUsesCoveringOrderWithoutFilesort() {
        scheduled(1); scheduled(1); scheduled(1);
        for(String suffix:List.of("", " AND (next_at>? OR (next_at=? AND submission_id>UUID_TO_BIN(?)))")) {
            Object[] args=suffix.isEmpty()?new Object[]{Timestamp.from(due)}:
                    new Object[]{Timestamp.from(due),Timestamp.from(due.minusSeconds(1)),Timestamp.from(due.minusSeconds(1)),UUID.randomUUID().toString()};
            var plan=sql.queryForMap("EXPLAIN SELECT next_at,BIN_TO_UUID(submission_id) submission FROM attempt_scheduled_release FORCE INDEX (ix_scheduled_due) WHERE work_state IN ('PENDING','RETRY') AND next_at<=?"+suffix+" ORDER BY next_at,submission_id LIMIT 1",args);
            assertThat(plan.get("key")).isEqualTo("ix_scheduled_due");
            assertThat(String.valueOf(plan.get("Extra"))).doesNotContainIgnoringCase("filesort");
            System.out.println("Scheduled due query plan: key="+plan.get("key")+", type="+plan.get("type")+", extra="+plan.get("Extra"));
        }
    }
    @Test void scheduledDriverFailureIsRedactedBeforeTransactionLoggingAndRetryStillSucceeds() {
        var f=scheduled(1); time.set(due); var reached=new AtomicBoolean();
        var logger=(Logger)LoggerFactory.getLogger("org.springframework.transaction.support.TransactionTemplate");
        var old=logger.getLevel(); var logs=new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs); logger.setLevel(Level.TRACE);
        try {
            observer().afterSql=(statement,connection)->{ if(statement.contains("FOR UPDATE OF i,l") && reached.compareAndSet(false,true)) throw new SQLException("private-scheduled-driver-marker","08006"); };
            assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
            assertThat(reached).isTrue();
            assertThat(logs.list).anyMatch(e->e.getThrowableProxy()!=null);
            assertThat(logs.list).noneMatch(e->e.getFormattedMessage().contains("private-scheduled-driver-marker")
                    || e.getThrowableProxy()!=null && ThrowableProxyUtil.asString(e.getThrowableProxy()).contains("private-scheduled-driver-marker"));
        } finally { observer().reset(); logger.detachAppender(logs); logger.setLevel(old); logs.stop(); }
        time.set(saved(f).nextAt());
        assertThat(reconstructedWorker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
    }
    @ParameterizedTest @CsvSource({"find,0,UNAVAILABLE","insert,0,UNAVAILABLE",
            "find,1205,BUSY","insert,1205,BUSY","find,1213,RETRYABLE","insert,1213,RETRYABLE"})
    void scheduledReleaseFailureIsSanitizedBeforeNestedProxyLogging(String operation,int code,ResultReleaseException.Reason reason) {
        var f=scheduled(1); time.set(due); var reached=new AtomicBoolean();
        var requestThread=Thread.currentThread();
        var captured=new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if(Thread.currentThread()==requestThread) { event.prepareForDeferredProcessing(); super.append(event); }
            }
        };
        var root=(Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var loggers=List.of((Logger)LoggerFactory.getLogger(TransactionInterceptor.class),
                (Logger)LoggerFactory.getLogger(TransactionTemplate.class));
        var levels=new HashMap<Logger,Level>();
        captured.setContext(root.getLoggerContext()); captured.start(); root.addAppender(captured);
        loggers.forEach(logger->{ levels.put(logger,logger.getLevel()); logger.setLevel(Level.TRACE); });
        try {
            observer().afterSql=(statement,connection)->{
                boolean target=operation.equals("find")?statement.contains("FROM attempt_result_release r JOIN")
                        :statement.stripLeading().startsWith("INSERT INTO attempt_result_release");
                if(target && reached.compareAndSet(false,true)) {
                    var failure=new SQLException("private-release-driver-message",code==1213?"40001":code==1205?"HY000":"08006",code);
                    failure.initCause(new IllegalStateException("private-release-driver-cause"));
                    failure.addSuppressed(new IllegalStateException("private-release-driver-suppressed"));
                    failure.setNextException(new SQLException("private-release-driver-next","08006")); throw failure;
                }
            };
            var failed=worker().execute(f.attempt().submission()); observer().reset();
            assertThat(reached).isTrue(); assertThat(failed.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
            assertThat(failed.reason()).isEqualTo(reason);
            assertThat(count("attempt_result_release")).isZero(); assertThat(releaseEvents()).isZero();
            var retry=saved(f); assertThat(retry.state()).isEqualTo(ScheduledRelease.State.RETRY); assertThat(retry.failures()).isEqualTo(1);
            time.set(retry.nextAt()); var success=reconstructedWorker().execute(f.attempt().submission());
            assertThat(success.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
            assertThat(worker().execute(f.attempt().submission()).release()).isEqualTo(success.release());
            assertThat(releaseEvents()).isEqualTo(1);
            // The participant submission lookup is another entry point on the same repository proxy.
            var lookupReached=new AtomicBoolean();
            observer().afterSql=(statement,connection)->{
                if(statement.contains("FROM attempt_submission WHERE attempt_id=") && lookupReached.compareAndSet(false,true))
                    throw new SQLException("private-release-driver-lookup","08006");
            };
            assertThatThrownBy(()->rc(()->context.getBean(ResultReleaseRepository.class).submission(f.attempt().attempt())))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            observer().reset(); assertThat(lookupReached).isTrue();
            assertThat(rc(()->context.getBean(ResultReleaseRepository.class).submission(f.attempt().attempt())))
                    .contains(f.attempt().submission());
            assertThat(captured.list).anySatisfy(event->{
                assertThat(event.getLoggerName()).isEqualTo(TransactionInterceptor.class.getName());
                assertThat(event.getLevel()).isEqualTo(Level.TRACE);
                assertThat(event.getFormattedMessage()).contains("JdbcResultReleaseRepository."+operation+"]","after exception");
            });
            assertThat(captured.list).anySatisfy(event->{
                assertThat(event.getLoggerName()).isEqualTo(TransactionInterceptor.class.getName());
                assertThat(event.getFormattedMessage()).contains("JdbcResultReleaseRepository.submission]","after exception");
            });
            assertThat(captured.list).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).doesNotContain("private-release-driver-","SQL [");
                if(event.getThrowableProxy()!=null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain("private-release-driver-","SQL [");
            });
        } finally {
            observer().reset(); loggers.forEach(logger->logger.setLevel(levels.get(logger)));
            root.detachAppender(captured); captured.stop();
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void scheduledLateRegistrationWaitsForClockRecoveryAndKeepsReplayConsistent(boolean loseAcknowledgement) {
        var registered=due.plusSeconds(10); time.set(registered); var f=scheduled(1);
        assertThat(f.work().registeredAt()).isEqualTo(registered);
        time.set(due.plusSeconds(9));
        assertThat(reconstructedWorker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.NOT_DUE);
        time.set(registered.minusNanos(1));
        assertThat(worker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.NOT_DUE);
        assertThat(saved(f)).isEqualTo(f.work());
        assertThat(count("attempt_result_release")).isZero(); assertThat(releaseEvents()).isZero();
        assertThat(view(f.attempt().attempt()).result()).isNull();
        var reached=new AtomicBoolean();
        observer().afterSql=(statement,connection)->{
            if(statement.stripLeading().startsWith("UPDATE attempt_scheduled_release") && reached.compareAndSet(false,true)
                    && loseAcknowledgement) observer().failAfterCommit.set(true);
        };
        time.set(registered);
        var success=reconstructedWorker().execute(f.attempt().submission()); observer().reset();
        assertThat(reached).isTrue();
        assertThat(success.outcome()).isEqualTo(loseAcknowledgement?ScheduledResultReleaseService.Outcome.REPLAYED:ScheduledResultReleaseService.Outcome.RELEASED);
        assertThat(success.release().releasedAt()).isEqualTo(registered);
        assertThat(success.release().id()).isEqualTo(f.work().eventId());
        var completed=saved(f); assertThat(completed.state()).isEqualTo(ScheduledRelease.State.RELEASED); assertThat(completed.failures()).isZero();
        for(var at:List.of(registered.plusSeconds(20),registered.minusSeconds(1))) {
            time.set(at); var replay=worker().execute(f.attempt().submission());
            assertThat(replay.outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.REPLAYED);
            assertThat(replay.release()).isEqualTo(success.release()); assertThat(saved(f)).isEqualTo(completed);
        }
        assertThat(releaseEvents()).isEqualTo(1);
        assertThat(view(f.attempt().attempt()).result().totalScore().earned()).isEqualTo(1);
    }
    @Test void scheduledConcurrentFailuresOfSameWorkVersionSpendOnlyOneRetry() throws Exception {
        var f=scheduled(1); time.set(due);
        var firstThread=new AtomicReference<Thread>(); var reads=new AtomicInteger();
        var recovering=new CountDownLatch(1); var resume=new CountDownLatch(1);
        observer().afterSql=(statement,connection)->{ if(statement.stripLeading().startsWith("INSERT INTO attempt_result_release")) throw new SQLException("private-peer-fault","08006"); };
        observer().beforeSql=(statement,connection)->{
            if(Thread.currentThread()==firstThread.get() && statement.contains("FROM attempt_scheduled_release WHERE")
                    && statement.contains("FOR UPDATE") && reads.incrementAndGet()==2) { recovering.countDown(); await(resume); }
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{ firstThread.set(Thread.currentThread()); return worker().execute(f.attempt().submission()); });
            await(recovering);
            var peer=pool.submit(()->worker().execute(f.attempt().submission()));
            assertThat(peer.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
            assertThat(first.isDone()).isFalse(); resume.countDown();
            assertThat(first.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
        } finally { resume.countDown(); observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        var retry=saved(f); assertThat(retry.failures()).isEqualTo(1); assertThat(retry.nextAt()).isEqualTo(due.plusSeconds(60));
        assertThat(releaseEvents()).isZero(); time.set(retry.nextAt());
        assertThat(reconstructedWorker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
    }
    @Test void scheduledStaleDiscoveryDoesNotHideFailureOfANewlyDueRetry() throws Exception {
        var f=scheduled(1); time.set(due);
        var firstThread=new AtomicReference<Thread>(); var held=new AtomicBoolean();
        var discovered=new CountDownLatch(1); var resume=new CountDownLatch(1);
        observer().afterSql=(statement,connection)->{
            if(statement.stripLeading().startsWith("INSERT INTO attempt_result_release")) throw new SQLException("private-retry-version","08006");
            if(Thread.currentThread()==firstThread.get() && statement.contains("FROM attempt_scheduled_release WHERE")
                    && !statement.contains("FOR UPDATE") && held.compareAndSet(false,true)) { discovered.countDown(); await(resume); }
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            var delayed=pool.submit(()->{ firstThread.set(Thread.currentThread()); return worker().execute(f.attempt().submission()); }); await(discovered);
            var peer=pool.submit(()->worker().execute(f.attempt().submission()));
            assertThat(peer.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
            var first=saved(f); assertThat(first.failures()).isEqualTo(1); time.set(first.nextAt());
            resume.countDown(); assertThat(delayed.get(20,TimeUnit.SECONDS).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.DEFERRED);
        } finally { resume.countDown(); observer().reset(); pool.shutdownNow(); assertThat(pool.awaitTermination(10,TimeUnit.SECONDS)).isTrue(); }
        var second=saved(f); assertThat(second.failures()).isEqualTo(2); assertThat(second.nextAt()).isEqualTo(due.plusSeconds(360));
        time.set(second.nextAt()); assertThat(reconstructedWorker().execute(f.attempt().submission()).outcome()).isEqualTo(ScheduledResultReleaseService.Outcome.RELEASED);
    }

    private static void await(CountDownLatch latch) {
        try { if(!latch.await(10,TimeUnit.SECONDS)) throw new AssertionError("Test barrier timeout"); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private UUID draft(QuestionType type) {
        return tx.execute(s -> {
            var q = new Question(); q.setType(type); q.setDifficulty(Difficulty.EASY); q.setQuestionText("Frozen " + type);
            var content = authored(type);
            if (type == QuestionType.FILL_GAP)
                content.putArray("options").add("Paris").add("Rome").add("London").add("Berlin").add("Madrid").add("Lisbon").add("Vienna");
            q.setContent(content.toString()); q.setExplanation(authoredExplanation);
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
    private AttemptStartService.Command command(UUID revision, AnswerOrder order, String key) {
        return new AttemptStartService.Command(1, quizId, content(revision), 1, CommandReceipt.Key.from(key),
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", order);
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
