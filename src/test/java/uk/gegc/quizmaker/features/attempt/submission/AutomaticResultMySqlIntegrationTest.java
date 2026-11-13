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

import uk.gegc.quizmaker.features.result.application.AutomaticResultService;
import uk.gegc.quizmaker.features.result.application.impl.AutomaticResultServiceImpl;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.repository.AutomaticResultRepository;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcAutomaticResultRepository;
import static org.assertj.core.api.Assertions.*;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

/** Real publication and five-family bindings feed the real atomic start. Only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true",
        "spring.datasource.hikari.transaction-isolation=${result.test.isolation:TRANSACTION_REPEATABLE_READ}"})
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
        SubmittedAttemptSourceImpl.class, JdbcSubmittedAttemptRepository.class, AutomaticResultServiceImpl.class,
        JdbcAutomaticResultRepository.class, AutomaticResultMySqlIntegrationTest.Config.class})
class AutomaticResultMySqlIntegrationTest {
    static final String SCHEMA = "automatic_result_" + UUID.randomUUID().toString().replace("-", "");
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
                    return bean instanceof DataSource ds && !(bean instanceof SubmissionDataSource) ? new SubmissionDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired AutomaticResultService results;
    @Autowired AttemptSubmissionService submissions;
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
    static final List<String> TARGET_TABLES = List.of("attempt_timing", "attempt_start_binding", "attempt_allowance_scope", "attempt_unclassified_publication", "attempt_result_created", "attempt_automatic_grade", "attempt_automatic_result", "attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form_selection", "attempt_form", "attempt_policy_snapshot");
    SubmissionDataSource observer() { return (SubmissionDataSource) source; }
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
            case TRUE_FALSE -> "{\"answer\":true}";
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
    private AutomaticResultService recreated() {
        return new AutomaticResultServiceImpl(context.getBean(SubmittedAttemptSource.class),
                new JdbcAutomaticResultRepository(context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)),
                context.getBean(ExecutionTraceService.class),context.getBean(Clock.class),metrics,manager);
    }
    private long count(String table) { return tx.execute(s->sql.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)); }
    private void reject(UUID submission,AutomaticResultException.Reason reason) {
        assertThatThrownBy(()->results.finalizeSubmission(submission)).isInstanceOfSatisfying(AutomaticResultException.class,
                e->assertThat(e.reason()).isEqualTo(reason)).hasNoCause();
    }
    private void noResult(Fixture f) {
        assertThat(count("attempt_automatic_result")).isZero();
        assertThat(count("attempt_automatic_grade")).isZero();
        assertThat(count("attempt_result_created")).isZero();
        long traces=tx.execute(s->sql.queryForObject("SELECT COUNT(*) FROM attempt_trace_event WHERE event_type='AUTOMATIC_GRADE_PRODUCED'",Long.class));
        assertThat(traces).isZero();
        assertThat(results.pending(100)).contains(f.submission());
    }
    @Test void actualGradeDoesNotRefundAStartOrPreventAnOtherwiseEligibleUnlimitedRetake() {
        var f = fixture(FAMILIES, 5, 5);
        var result = results.finalizeSubmission(f.submission());
        var revision = result.quizRevisionId();
        var next = starts.start(command(revision, AnswerOrder.AUTHORED, "after-real-grade"));
        assertThat(next.attemptId()).isNotEqualTo(f.attempt());
        assertThat(count("attempt_start_binding")).isEqualTo(2);
        assertThat(count("attempt_automatic_result")).isEqualTo(1);
        assertThat(recreated().finalizeSubmission(f.submission()).id()).isEqualTo(result.id());
    }

    @Test void realConnectionsUseTheRequestedIsolation() {
        String actual=tx.execute(s->sql.queryForObject("SELECT @@transaction_isolation",String.class));
        String expected=System.getProperty("result.test.isolation","TRANSACTION_REPEATABLE_READ")
                .substring("TRANSACTION_".length()).replace('_','-');
        assertThat(actual).isEqualTo(expected);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void fiveRealFamiliesPersistExactFactsAndSurviveAuthorEditsAndRestart(boolean correct) {
        var f=fixture(FAMILIES,5,correct?5:0);
        SecurityContextHolder.clearContext(); // Trusted system grading needs no participant session.
        var result=results.finalizeSubmission(f.submission());
        assertThat(result.score()).isEqualTo(AutomaticResult.Score.calculate(correct?5:0,5,7000));
        assertThat(result.items()).allSatisfy(i->{
            assertThat(i.responseId()).isNotNull(); assertThat(i.earned()).isEqualTo(correct?1:0);
            assertThat(i.responseContract().version()).isEqualTo(1); assertThat(i.scoringContract().version()).isEqualTo(1);
        });
        assertThat(result.items().stream().map(AutomaticResult.Item::formItemId)).containsExactlyElementsOf(f.items().stream().map(i->i.id()).toList());
        tx.executeWithoutResult(s->{
            sql.update("UPDATE questions SET content=JSON_OBJECT('answer',false),explanation='changed draft' WHERE type='TRUE_FALSE'");
            sql.update("DELETE FROM attempt_command_receipt");
        });
        assertThat(recreated().finalizeSubmission(f.submission())).isEqualTo(result);
        assertThat(count("attempt_automatic_result")).isEqualTo(1); assertThat(count("attempt_result_created")).isEqualTo(1);
        assertThat(count("attempt_automatic_grade")).isEqualTo(5); assertThat(results.pending(100)).isEmpty();
        String event=tx.execute(s->sql.queryForObject("SELECT event_type FROM attempt_trace_event WHERE event_id=UUID_TO_BIN(?)",String.class,result.id().toString()));
        assertThat(event).isEqualTo("AUTOMATIC_GRADE_PRODUCED");
    }
    @ParameterizedTest @CsvSource({"10,8,7,7000,true","3,3,2,6667,false","3,0,0,0,false"})
    void unansweredItemsRemainUnansweredAndInTheDenominator(int size,int answered,int correct,int bps,boolean passed) {
        var f=fixture(size,answered,correct); long before=count("attempt_accepted_response");
        var r=results.finalizeSubmission(f.submission());
        assertThat(r.score().earned()).isEqualTo(correct); assertThat(r.score().possible()).isEqualTo(size);
        assertThat(r.score().basisPoints()).isEqualTo(bps); assertThat(r.score().passed()).isEqualTo(passed);
        assertThat(r.items().stream().filter(i->i.responseId()==null)).hasSize(size-answered).allSatisfy(i->assertThat(i.earned()).isZero());
        assertThat(count("attempt_accepted_response")).isEqualTo(before);
    }
    @Test void recoveryDiscoveryIsBoundedAndDoesNotMutateOrDispatch() {
        var a=fixture(3,1,1); var b=fixture(3,1,1); var c=fixture(3,1,1);
        assertThat(results.pending(1)).hasSize(1);
        assertThat(results.pending(100)).containsExactlyInAnyOrder(a.submission(),b.submission(),c.submission());
        recreated().finalizeSubmission(b.submission());
        assertThat(recreated().pending(100)).containsExactlyInAnyOrder(a.submission(),c.submission());
        assertThatThrownBy(()->results.pending(0)).isInstanceOf(AutomaticResultException.class);
        assertThatThrownBy(()->results.pending(101)).isInstanceOf(AutomaticResultException.class);
        reject(null,AutomaticResultException.Reason.INVALID_INPUT); reject(UUID.randomUUID(),AutomaticResultException.Reason.NOT_FOUND);
    }
    @ParameterizedTest @ValueSource(strings={"result","grades","trace","event"})
    void reachedWriteFailureRollsBackEveryResultFactAndAllowsDurableRetry(String checkpoint) {
        var f=fixture(5,3,2); var reached=new AtomicBoolean();
        var accepted=tx.execute(s->sql.queryForList("SELECT BIN_TO_UUID(id) id,normalized_response FROM attempt_accepted_response ORDER BY id"));
        String table=switch(checkpoint){case "result"->"attempt_automatic_result";case "grades"->"attempt_automatic_grade";
            case "trace"->"attempt_trace_event";default->"attempt_result_created";};
        observer().afterSql=(statement,connection)->{
            if(statement.stripLeading().startsWith("INSERT INTO "+table) && reached.compareAndSet(false,true)) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_automatic_result",Long.class)).isEqualTo(1);
                throw new SQLException("private-graded-write-marker","08006");
            }
        };
        var logger=(Logger)LoggerFactory.getLogger(TransactionTemplate.class);
        Level previous=logger.getLevel(); Thread requestThread=Thread.currentThread();
        var captured=new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if(Thread.currentThread()==requestThread) {
                    event.prepareForDeferredProcessing(); super.append(event);
                }
            }
        };
        captured.setContext(logger.getLoggerContext()); captured.start();
        logger.addAppender(captured); logger.setLevel(Level.DEBUG);
        try {
            reject(f.submission(),AutomaticResultException.Reason.UNAVAILABLE);
            observer().afterSql=null; assertThat(reached).isTrue(); noResult(f);
            var after=tx.execute(s->sql.queryForList("SELECT BIN_TO_UUID(id) id,normalized_response FROM attempt_accepted_response ORDER BY id"));
            assertThat(after).isEqualTo(accepted);
            assertThat(recreated().finalizeSubmission(f.submission()).score().earned()).isEqualTo(2);
            // A real rollback diagnostic must be captured; disabling logs is not redaction.
            assertThat(captured.list).anySatisfy(event->{
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy()).isNotNull();
            });
            assertThat(captured.list).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).doesNotContain("private-graded-write-marker");
                if(event.getThrowableProxy()!=null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain("private-graded-write-marker");
            });
        } finally {
            observer().afterSql=null; logger.detachAppender(captured); logger.setLevel(previous); captured.stop();
        }
    }
    @Test void partialGradeBatchFailureRollsBackAnActuallyReachedPrefix() {
        var f=fixture(501,0,0); var reached=new AtomicBoolean();
        observer().afterSql=(statement,connection)->{
            if(statement.stripLeading().startsWith("INSERT INTO attempt_automatic_grade") && reached.compareAndSet(false,true)) {
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_automatic_grade",Integer.class)).isEqualTo(500);
                throw new SQLException("private-partial-batch-marker","08006");
            }
        };
        reject(f.submission(),AutomaticResultException.Reason.UNAVAILABLE);
        observer().afterSql=null; assertThat(reached).isTrue(); noResult(f);
        assertThat(recreated().finalizeSubmission(f.submission()).items()).hasSize(501);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void unknownCommitOutcomeIsReconciledByTheSameSubmission(boolean committed) {
        var f=fixture(3,3,2);
        (committed?observer().failAfterCommit:observer().failBeforeCommit).set(true);
        reject(f.submission(),AutomaticResultException.Reason.UNAVAILABLE); observer().reset();
        assertThat(count("attempt_automatic_result")).isEqualTo(committed?1:0);
        var saved=recreated().finalizeSubmission(f.submission());
        assertThat(recreated().finalizeSubmission(f.submission())).isEqualTo(saved);
        assertThat(count("attempt_result_created")).isEqualTo(1);
    }
    @Test void concurrentRealFinalizersBothSucceedAndConverge() throws Exception {
        var f=fixture(5,5,4); var ready=new CountDownLatch(2); var go=new CountDownLatch(1);
        observer().beforeSql=(statement,connection)->{if(statement.contains("WHERE i.submission_id=UUID_TO_BIN") && statement.contains("FOR UPDATE OF i")) {
            ready.countDown(); await(go);
        }};
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->recreated().finalizeSubmission(f.submission()));
            var second=pool.submit(()->recreated().finalizeSubmission(f.submission()));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(first.get(30,TimeUnit.SECONDS)).isEqualTo(second.get(30,TimeUnit.SECONDS));
            assertThat(count("attempt_automatic_result")).isEqualTo(1); assertThat(count("attempt_result_created")).isEqualTo(1);
        } finally { go.countDown(); observer().beforeSql=null; pool.shutdownNow(); }
    }
    @ParameterizedTest @ValueSource(strings={"count","binding","payload","late-evidence","wrong-scope"})
    void contradictoryTerminalEvidenceDoesNotBecomeAZeroScore(String fault) {
        var f=fixture(3,3,3);
        var foreign="wrong-scope".equals(fault)?fixture(3,3,3):null;
        tx.executeWithoutResult(s->{switch(fault) {
            case "count"->sql.update("UPDATE attempt_submission SET accepted_count=2,unanswered_count=1");
            case "binding"->sql.update("UPDATE attempt_accepted_response SET response_contract_version=2");
            case "payload"->sql.update("UPDATE attempt_accepted_response SET normalized_response=JSON_OBJECT('answer','private-corrupt-value')");
            case "late-evidence"->sql.update("UPDATE attempt_accepted_response SET accepted_at=DATE_ADD(accepted_at,INTERVAL 1 DAY)");
            default->sql.update("""
                    UPDATE attempt_submission SET policy_snapshot_id=(SELECT policy_snapshot_id FROM attempt_trace_stream WHERE attempt_id=UUID_TO_BIN(?))
                    WHERE id=UUID_TO_BIN(?)
                    """,foreign.attempt().toString(),f.submission().toString());
        }});
        reject(f.submission(),AutomaticResultException.Reason.CORRUPT); noResult(f);
    }
    @ParameterizedTest @ValueSource(strings={"UNGRADED","MANUAL_PENDING","UNKNOWN"})
    void unsupportedPersistedGradingModesNeverInventAScore(String mode) {
        var f=fixture(3,3,3);
        // These are unsupported stored contracts, not a claim that a manual/ungraded writer exists.
        tx.executeWithoutResult(s->sql.update("UPDATE attempt_policy_snapshot SET canonical_json=REPLACE(canonical_json,'\"mode\":\"BINARY\"',?)",
                "\"mode\":\""+mode+"\""));
        reject(f.submission(),AutomaticResultException.Reason.CORRUPT); noResult(f);
    }
    @ParameterizedTest @EnumSource(value=QuestionType.class,names={"MCQ_SINGLE","MCQ_MULTI","ORDERING","FILL_GAP"})
    void wellShapedButImpossibleStoredResponseCannotBecomeAnIncorrectGrade(QuestionType type) {
        var f=fixture(List.of(type),1,1);
        String payload=switch(type) {
            case MCQ_SINGLE -> "{\"selectedOptionId\":\"foreign-source-key\"}";
            case MCQ_MULTI -> "{\"selectedOptionIds\":[\"a\",\"c\",\"c\"]}";
            case ORDERING -> "{\"orderedItemIds\":[1,999]}";
            default -> "{\"answers\":[{\"gapId\":999,\"answer\":\"Paris\"}]}";
        };
        tx.executeWithoutResult(s->sql.update("UPDATE attempt_accepted_response SET normalized_response=?",payload));
        reject(f.submission(),AutomaticResultException.Reason.CORRUPT); noResult(f);
    }
    @ParameterizedTest @EnumSource(value=QuestionType.class,names={"MCQ_MULTI","ORDERING","FILL_GAP"})
    void realAcceptedEmptyArraysRetainTheirIncorrectAnswerSemantics(QuestionType type) {
        quizId=newQuiz(); var revision=publish(0,List.of(draft(type))); binding.bind(quizId,revision);
        var start=starts.start(command(revision,AnswerOrder.AUTHORED,"empty-start-"+UUID.randomUUID()));
        var item=delivery.read(start.attemptId()).form().items().get(0);
        var empty=json.createObjectNode();
        empty.putArray(switch(type){case MCQ_MULTI->"selectedOptionIds";case ORDERING->"orderedItemIds";default->"answers";});
        var accepted=responses.accept(new ResponseAcceptanceService.Command(start.attemptId(),item.id(),1,
                CommandReceipt.Key.from("empty-answer-"+UUID.randomUUID()),type,empty));
        var submitted=submissions.submit(new AttemptSubmissionService.Command(start.attemptId(),2,true,
                CommandReceipt.Key.from("empty-submit-"+UUID.randomUUID())));
        var result=results.finalizeSubmission(submitted.submissionId());
        assertThat(result.score().earned()).isZero(); assertThat(result.score().possible()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(i->{
            assertThat(i.responseId()).isEqualTo(accepted.acceptedResponseId()); assertThat(i.earned()).isZero();
        });
    }
    @ParameterizedTest @ValueSource(ints={1,2})
    void unknownScoringVersionAndMissingGradesFailClosed(int removedPosition) {
        var f=fixture(3,3,3);
        // Model a deployment lacking the retained scorer using the actual registry and JDBC
        // reader. Keep the committed contract and all foreign keys intact.
        var catalogue=new QuestionContractRegistryImpl(new ArrayList<>(context.getBeansOfType(
                uk.gegc.quizmaker.features.question.application.contract.VersionedResponseContract.class).values()),List.of(),metrics);
        var contracts=new QuestionRevisionContractServiceImpl(context.getBean(
                uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository.class),catalogue,manager);
        var sourceReader=new SubmittedAttemptSourceImpl(context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.SubmittedAttemptRepository.class),
                lifecycle,context.getBean(PolicySnapshotService.class),context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository.class),
                context.getBean(QuizRevisionRepository.class),contracts);
        var unavailable=new AutomaticResultServiceImpl(sourceReader,context.getBean(AutomaticResultRepository.class),
                context.getBean(ExecutionTraceService.class),context.getBean(Clock.class),metrics,manager);
        assertThatThrownBy(()->unavailable.finalizeSubmission(f.submission())).isInstanceOfSatisfying(AutomaticResultException.class,
                e->assertThat(e.reason()).isEqualTo(AutomaticResultException.Reason.UNSUPPORTED));
        noResult(f);
        results.finalizeSubmission(f.submission());
        // Exercise both a missing middle item and an incomplete final total deterministically.
        tx.executeWithoutResult(s->assertThat(sql.update("DELETE FROM attempt_automatic_grade WHERE position=?",removedPosition)).isEqualTo(1));
        reject(f.submission(),AutomaticResultException.Reason.CORRUPT);
    }
    @Test void persistedResultNaturalUniquenessAndExactScoreConstraintsAreReal() {
        var f=fixture(3,3,2); var r=results.finalizeSubmission(f.submission());
        assertThatThrownBy(()->tx.executeWithoutResult(s->context.getBean(AutomaticResultRepository.class).insert(r)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->sql.update("UPDATE attempt_automatic_result SET basis_points=6666")))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).rootCause()
                .isInstanceOfSatisfying(SQLException.class,e->assertThat(e.getErrorCode()).isEqualTo(3819));
        assertThatThrownBy(()->tx.executeWithoutResult(s->sql.update("DELETE FROM attempt_trace_event WHERE event_id=UUID_TO_BIN(?)",r.id().toString())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        tx.executeWithoutResult(s->{
            // At four decimal places this is 0.5000 basis points, but the exact fraction
            // is below one half. The database guard must avoid intermediate rounding.
            assertThat(sql.update("UPDATE attempt_automatic_result SET earned_points=10000,possible_points=200000001,basis_points=0,passed=FALSE")).isEqualTo(1);
            s.setRollbackOnly();
        });
        assertThat(recreated().finalizeSubmission(f.submission())).isEqualTo(r);
    }
    static java.util.stream.Stream<Arguments> readOutages() {
        return java.util.stream.Stream.of("lifecycle","policy","declare","resolve","accepted-control").flatMap(checkpoint->
                java.util.stream.Stream.of(Arguments.of(checkpoint,0,"UNAVAILABLE"),Arguments.of(checkpoint,1213,"RETRYABLE"),
                        Arguments.of(checkpoint,1205,"BUSY")));
    }
    @ParameterizedTest(name="{0}, SQL {1} → {2}") @MethodSource("readOutages")
    void readOutagesRetainPendingIdentityAndSanitizedFailure(String checkpoint,int code,String reason) {
        var f=fixture(3,2,1); var reached=new AtomicBoolean();
        var completion=new AtomicInteger(-1);
        observer().beforeSql=(statement,connection)->{
            boolean selected=switch(checkpoint) {
                case "lifecycle"->statement.contains("FROM attempt_lifecycle_state WHERE") && statement.contains("FOR UPDATE");
                case "policy"->statement.contains("FROM attempt_policy_snapshot s");
                case "declare"->statement.contains("FROM quiz_revisions r LEFT JOIN quiz_revision_items i");
                case "resolve"->statement.contains("FROM question_revision_contract_bindings b");
                default->statement.contains("attempt_accepted_response");
            };
            if(selected && reached.compareAndSet(false,true)) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) { completion.set(status); }
                        });
                throw new SQLException("private-read-marker",code==1213?"40001":code==1205?"HY000":"08006",code);
            }
        };
        var logger=(Logger)LoggerFactory.getLogger(TransactionTemplate.class);
        Level previous=logger.getLevel(); Thread requestThread=Thread.currentThread();
        var captured=new ListAppender<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                if(Thread.currentThread()==requestThread) {
                    event.prepareForDeferredProcessing(); super.append(event);
                }
            }
        };
        captured.setContext(logger.getLoggerContext()); captured.start();
        logger.addAppender(captured); logger.setLevel(Level.DEBUG);
        try {
            reject(f.submission(),AutomaticResultException.Reason.valueOf(reason)); observer().beforeSql=null;
            assertThat(reached).isTrue();
            assertThat(completion).hasValue(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            noResult(f);
            var saved=recreated().finalizeSubmission(f.submission());
            assertThat(saved.score().earned()).isEqualTo(1);
            assertThat(recreated().finalizeSubmission(f.submission())).isEqualTo(saved);
            assertThat(count("attempt_result_created")).isEqualTo(1);
            assertThat(captured.list).anySatisfy(event->{
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getThrowableProxy()).isNotNull();
            });
            assertThat(captured.list).allSatisfy(event->{
                assertThat(event.getFormattedMessage()).doesNotContain("private-read-marker");
                if(event.getThrowableProxy()!=null) assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy()))
                        .doesNotContain("private-read-marker");
            });
        } finally {
            observer().beforeSql=null; logger.detachAppender(captured); logger.setLevel(previous); captured.stop();
        }
    }
    @Test void multipleParentReadsHaveMeasuredBoundedSql(TestReporter reporter) {
        var small=new ArrayList<Fixture>(); var large=new ArrayList<Fixture>();
        for(int i=0;i<3;i++) { small.add(fixture(5,2,1)); large.add(fixture(25,2,1)); }
        observer().selects.clear(); small.forEach(f->results.finalizeSubmission(f.submission())); int a=observer().selects.size();
        observer().selects.clear(); large.forEach(f->results.finalizeSubmission(f.submission())); int b=observer().selects.size();
        assertThat(b).isEqualTo(a); assertThat(b).isLessThan(150);
        observer().selects.clear(); small.forEach(f->results.finalizeSubmission(f.submission())); int replaySmall=observer().selects.size();
        observer().selects.clear(); large.forEach(f->results.finalizeSubmission(f.submission())); int replayLarge=observer().selects.size();
        assertThat(replayLarge).isEqualTo(replaySmall);
        reporter.publishEntry("automatic-result-sql","three parents, 5 vs 25 items: finalize="+a+"/"+b+", replay="+replaySmall+"/"+replayLarge);
        System.out.println("Automatic result SQL: three parents, 5 vs 25 items: finalize="+a+"/"+b+", replay="+replaySmall+"/"+replayLarge);
    }
    @Test void telemetryCollisionAfterCommitCannotReplaceOrRepeatAResult() {
        var f=fixture(3,3,2);
        var collision=new SimpleMeterRegistry();
        try {
            // A real counter/timer name collision makes Timer registration throw after commit.
            collision.counter("quizmaker.result.automatic","version","1","operation","finalize","outcome","committed");
            var service=new AutomaticResultServiceImpl(context.getBean(SubmittedAttemptSource.class),context.getBean(AutomaticResultRepository.class),
                    context.getBean(ExecutionTraceService.class),context.getBean(Clock.class),collision,manager);
            var r=service.finalizeSubmission(f.submission());
            assertThat(recreated().finalizeSubmission(f.submission())).isEqualTo(r);
            assertThat(count("attempt_result_created")).isEqualTo(1);
        } finally { collision.close(); }
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
