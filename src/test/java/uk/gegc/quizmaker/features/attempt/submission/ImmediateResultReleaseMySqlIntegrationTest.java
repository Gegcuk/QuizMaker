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
import uk.gegc.quizmaker.features.result.application.ImmediateResultReleaseService;
import uk.gegc.quizmaker.features.result.application.impl.*;
import uk.gegc.quizmaker.features.result.infra.repository.JdbcResultReleaseRepository;
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
        JdbcAutomaticResultRepository.class, JdbcResultReleaseRepository.class,
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
    @Autowired ImmediateResultReleaseService releases;
    @Autowired ParticipantAttemptViewService views;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AttemptService legacy;
    @Autowired AttemptSubmissionService submissions;
    @Autowired ResponseAcceptanceService responses;
    @Autowired AttemptDeliveryService delivery;
    @Autowired org.springframework.context.ApplicationContext context;
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
    static final List<String> TARGET_TABLES = List.of("attempt_timing", "attempt_start_binding", "attempt_allowance_scope", "attempt_unclassified_publication", "attempt_result_release", "attempt_result_created", "attempt_automatic_grade", "attempt_automatic_result", "attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form_selection", "attempt_form", "attempt_policy_snapshot");
    SubmissionDataSource observer() { return (SubmissionDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;
    String authoredExplanation;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
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

    private long count(String table) { return tx.execute(s -> sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)); }
    private long releaseEvents() { return tx.execute(s -> sql.queryForObject(
            "SELECT COUNT(*) FROM attempt_trace_event WHERE event_type='RESULT_RELEASED'", Long.class)); }
    private ParticipantAttemptView view(UUID id) { return (ParticipantAttemptView) views.read(id); }
    private ImmediateResultReleaseService restarted() {
        return new ImmediateResultReleaseServiceImpl(context.getBean(SubmittedAttemptSource.class),
                new JdbcAutomaticResultRepository(context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)),
                new JdbcResultReleaseRepository(context.getBean(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class)),
                context.getBean(ExecutionTraceService.class), context.getBean(Clock.class), metrics, manager);
    }
    private MockMvc mvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ParticipantAttemptViewController(context.getBean(ParticipantAttemptViewService.class)))
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

    @Test void releaseIsolationIsLocalAndAnOlderRepeatableReadCallerStillReadsThePublication() {
        var fixture = fixture(2, 2, 2); results.finalizeSubmission(fixture.submission());
        String serverIsolation = tx.execute(s -> sql.queryForObject("SELECT @@global.transaction_isolation", String.class));
        var observed = new ArrayList<String>();
        observer().afterSql = (statement, connection) -> {
            if (statement.contains("FROM attempt_result_release r"))
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
                // The nonlocking snapshot is genuinely old; the real participant reader uses current evidence.
                assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_result_release", Long.class)).isZero();
                assertThat(view(fixture.attempt()).result().totalScore().earned()).isEqualTo(2);
                assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("REPEATABLE-READ");
            });
        } finally { observer().reset(); }
        assertThat(observed).containsExactly("READ-COMMITTED", "REPEATABLE-READ");
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
