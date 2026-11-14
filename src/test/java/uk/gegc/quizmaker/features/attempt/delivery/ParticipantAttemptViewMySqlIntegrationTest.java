package uk.gegc.quizmaker.features.attempt.delivery;

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
import org.junit.jupiter.params.provider.EnumSource;
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

import static org.assertj.core.api.Assertions.*;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException;
import static uk.gegc.quizmaker.features.attempt.domain.model.access.AttemptAccessException.Reason.*;

/** Real publication and five-family bindings feed the real atomic start. Only external storage is fake. */
@Tag("db-serial")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test-mysql")
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "spring.flyway.enabled=true", "quizmaker.attempts.participant-view-enabled=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({uk.gegc.quizmaker.features.attempt.infra.repository.JdbcNavigationRepository.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAcceptedResponseRepository.class, QuizRevisionPublicationServiceImpl.class, JdbcQuizPublicationRepository.class, JdbcQuizRevisionRepository.class,
        JdbcQuestionSourceScopeRepository.class, PublicationContent.class, RetainedImageServiceImpl.class,
        AppPermissionEvaluator.class, QuestionHandlerFactory.class,
        McqSingleHandler.class, McqMultiHandler.class, TrueFalseHandler.class, FillGapHandler.class, OrderingHandler.class,
        HotspotHandler.class, OpenQuestionHandler.class, MatchingHandler.class, ComplianceHandler.class,
        QuestionContractRegistryImpl.class, QuestionRevisionContractServiceImpl.class, JdbcQuestionContractBindingRepository.class,
        PublishedQuestionContractServiceImpl.class, BaselineQuestionContractConfig.class,
        FixedFormMaterializerImpl.class, FormOptionOrderServiceImpl.class,
        JdbcFixedFormRepository.class, JdbcFormOptionOrderRepository.class,
        uk.gegc.quizmaker.features.result.application.impl.ParticipantResultServiceImpl.class,
        uk.gegc.quizmaker.features.result.application.impl.ResultDisclosureServiceImpl.class,
        uk.gegc.quizmaker.features.result.infra.repository.JdbcAutomaticResultRepository.class,
        uk.gegc.quizmaker.features.result.infra.repository.JdbcResultReleaseRepository.class,
        SubmittedAttemptSourceImpl.class, JdbcSubmittedAttemptRepository.class,
        AttemptSubmissionServiceImpl.class, AttemptTerminalSubmissionImpl.class,
        AcceptedSubmissionResponseFinalizer.class, JdbcAttemptSubmissionRepository.class,
        ParticipantAttemptViewServiceImpl.class, JdbcAttemptViewRouteRepository.class, InitialParticipantViewDisclosureReader.class, InitialParticipantViewMediaReader.class, AttemptReadFactsServiceImpl.class, AttemptDeliveryServiceImpl.class, AttemptAccessServiceImpl.class, JdbcAttemptAccessRepository.class, AttemptStartServiceImpl.class, uk.gegc.quizmaker.features.attempt.application.impl.AttemptTimingServiceImpl.class, uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAttemptTimingRepository.class, AttemptAllowanceServiceImpl.class, JdbcAttemptAllowanceRepository.class, RetakeEligibilityServiceImpl.class, PublicationAttemptEntryReader.class, QuizPracticeSourceServiceImpl.class, JdbcQuizPracticeSourceRepository.class,
        AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class,
        AttemptPresetServiceImpl.class, AttemptPolicyResolverImpl.class, PolicySnapshotServiceImpl.class,
        JdbcPolicySnapshotRepository.class, QuestionSelectionServiceImpl.class,
        uk.gegc.quizmaker.features.attempt.infra.selection.HmacSelectionSeedSource.class,
        AttemptSubjectServiceImpl.class, JdbcAttemptSubjectRepository.class,
        AttemptLifecycleServiceImpl.class, JdbcLifecycleStateRepository.class,
        ExecutionTraceServiceImpl.class, JdbcExecutionTraceRepository.class,
        CommandReceiptServiceImpl.class, JdbcCommandReceiptRepository.class,
        ParticipantAttemptViewMySqlIntegrationTest.Config.class})
class ParticipantAttemptViewMySqlIntegrationTest {
    static final String SCHEMA = "attempt_view_" + UUID.randomUUID().toString().replace("-", "");
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
                public Source inspect(String key) { return new Source("synthetic-etag", 1, "image/png"); }
                public void retain(SavedImageVersion v) { }
                public void verify(SavedImageVersion v) { }
            };
        }
        @Bean static BeanPostProcessor measuredSql() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds && !(bean instanceof DeliveryDataSource) ? new DeliveryDataSource(ds) : bean;
                }
            };
        }
    }
    @Autowired ParticipantAttemptViewService views;
    @org.springframework.test.context.bean.override.mockito.MockitoBean AttemptService legacy;
    @Autowired AttemptAccessService access;
    @Autowired AttemptDeliveryService delivery;
    @Autowired org.springframework.context.ApplicationContext context;
    final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
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
    @Autowired AttemptSubmissionService submissions;
    @Autowired AttemptLifecycleService lifecycle;
    @Autowired SimpleMeterRegistry metrics;
    static final List<QuestionType> FAMILIES = List.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    static final List<String> TARGET_TABLES = List.of("attempt_finalization_intent", "attempt_submission", "attempt_accepted_response", "attempt_timing", "attempt_start_binding", "attempt_allowance_scope", "attempt_unclassified_publication", "attempt_trace_event", "attempt_trace_identity", "attempt_trace_stream",
            "attempt_command_receipt", "attempt_participant_subject", "attempt_lifecycle_state", "attempt_form_option",
            "attempt_form_item_option_order", "attempt_form_option_order", "attempt_form_item", "attempt_form_selection", "attempt_form", "attempt_policy_snapshot");
    DeliveryDataSource observer() { return (DeliveryDataSource) source; }
    TransactionTemplate tx;
    String username;
    UUID ownerId, quizId;


    @BeforeEach void setup() {
        tx = new TransactionTemplate(manager); observer().reset();
        username = "delivery-" + UUID.randomUUID().toString().substring(0, 12);
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
                    "saved_image_bindings", "saved_image_versions", "media_assets", "categories", "user_roles", "users")) sql.update("DELETE FROM " + table);
            sql.update("DELETE rp FROM role_permissions rp JOIN roles r ON r.role_id=rp.role_id WHERE r.role_name LIKE 'delivery-%'");
            sql.update("DELETE FROM roles WHERE role_name LIKE 'delivery-%'");
        });
    }



    @ParameterizedTest @EnumSource(AnswerOrder.class)
    void realStartResumeAndHttpReturnSavedStateOrderAndOnlyProvenActions(AnswerOrder order) throws Exception {
        var started = starts.start(command(prepared(), order, "view"));
        var before = targetSnapshot();
        var writes = new ArrayList<String>();
        observer().beforeSql = (statement, connection) -> {
            if (statement.stripLeading().matches("(?is)^(INSERT|UPDATE|DELETE).*")) writes.add(statement);
        };
        var first = view(started.attemptId());
        assertThat(first.items()).hasSize(5).extracting(ParticipantAttemptView.Item::type).containsExactlyElementsOf(FAMILIES);
        assertThat(first.formId()).isEqualTo(started.formId());
        assertThat(first.quizRevisionId()).isEqualTo(started.quizRevisionId());
        assertThat(first.execution()).isEqualTo(AttemptLifecycle.Execution.IN_PROGRESS);
        assertThat(first.stateVersion()).isEqualTo(1);
        assertThat(first.timing()).isEqualTo(ParticipantAttemptView.Timing.UNTIMED);
        assertThat(first.allowedActions()).extracting(Enum::name).containsExactly("VIEW_ATTEMPT");
        assertThat(recreated().read(started.attemptId())).isEqualTo(first);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("views", views);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewController(beans.getBean(ParticipantAttemptViewService.class)))
                .setControllerAdvice(new uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewErrorHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v2/attempts/{id}", started.attemptId()).param("includeCorrectAnswers", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body)).isEqualTo(json.readTree(json.writeValueAsString(first)));
        assertThat(body).doesNotContain("correctAnswer", "isCorrect", "explanation", "score", "sourceKey", "ownerId", "subject", "policySnapshotId");
        assertThat(writes).isEmpty(); observer().reset();
        assertThat(targetSnapshot()).isEqualTo(before);
        org.mockito.Mockito.verifyNoInteractions(legacy);
    }

    @Test void republishingNeverReplacesAnExistingView() {
        var started = starts.start(command(prepared(), AnswerOrder.SHUFFLED_PER_ATTEMPT, "view"));
        var original = view(started.attemptId());
        tx.executeWithoutResult(s -> questions.findAllByQuizId_IdOrderById(quizId).forEach(q -> q.setQuestionText("Changed author text")));
        var ids = tx.execute(s -> questions.findAllByQuizId_IdOrderById(quizId).stream().map(Question::getId).toList());
        var next = publish(1, ids); binding.bind(quizId, next);
        assertThat(next).isNotEqualTo(original.quizRevisionId());
        assertThat(recreated().read(started.attemptId())).isEqualTo(original);
    }

    @Test void accessLossForeignAndMissingTargetsAreNonEnumeratingWithoutLegacyFallback() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "view"));
        assertThat(view(started.attemptId()).items()).hasSize(5);
        revoke(ownerId, "ATTEMPT_READ"); reject(started.attemptId(), "NOT_FOUND"); reject(UUID.randomUUID(), "NOT_FOUND");
        grant(ownerId, "ATTEMPT_READ"); revoke(ownerId, "ATTEMPT_CREATE");
        assertThat(view(started.attemptId()).items()).hasSize(5);
        String owner = username;
        String other = "view-other-" + UUID.randomUUID(); UUID otherId = user(other, true);
        grant(otherId, "ATTEMPT_READ", "ATTEMPT_READ_ALL", "QUESTION_UPDATE", "QUESTION_ADMIN"); authenticate(other);
        reject(started.attemptId(), "NOT_FOUND"); reject(UUID.randomUUID(), "NOT_FOUND");
        authenticate(owner); assertThat(view(started.attemptId()).items()).hasSize(5);
        tx.executeWithoutResult(s -> sql.update("UPDATE users SET is_active=0 WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        reject(started.attemptId(), "NOT_FOUND");
        tx.executeWithoutResult(s -> sql.update("UPDATE users SET is_active=1 WHERE user_id=UUID_TO_BIN(?)", ownerId.toString()));
        SecurityContextHolder.clearContext(); reject(started.attemptId(), "UNAUTHENTICATED");
        org.mockito.Mockito.verifyNoInteractions(legacy);
    }

    @Test void editorLosingFullAuthorityCannotUsePublicVisibilityAsAReplacement() {
        var revision = prepared();
        tx.executeWithoutResult(s -> quizzes.findById(quizId).orElseThrow().setVisibility(Visibility.PUBLIC));
        String editor = "view-editor-" + UUID.randomUUID(); UUID editorId = user(editor, true);
        grant(editorId, "ATTEMPT_READ", "ATTEMPT_CREATE", "QUESTION_UPDATE", "QUESTION_ADMIN"); authenticate(editor);
        var started = starts.start(command(revision, AnswerOrder.AUTHORED, "editor"));
        assertThat(view(started.attemptId()).items()).hasSize(5);
        revoke(editorId, "QUESTION_UPDATE"); reject(started.attemptId(), "NOT_FOUND");
        grant(editorId, "QUESTION_UPDATE"); assertThat(view(started.attemptId()).items()).hasSize(5);
    }

    @Test void actualHttpReadOfForeignPrivateAndMissingAttemptsHasTheSameSafeFailure() throws Exception {
        var id = starts.start(command(prepared(), AnswerOrder.AUTHORED, "private")).attemptId();
        var before = targetSnapshot(); String other = "view-other-" + UUID.randomUUID();
        grant(user(other, true), "ATTEMPT_READ", "ATTEMPT_READ_ALL", "QUESTION_UPDATE", "QUESTION_ADMIN"); authenticate(other);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory(); beans.registerSingleton("views", views);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewController(beans.getBean(ParticipantAttemptViewService.class)))
                .setControllerAdvice(new uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewErrorHandler()).build();
        for (var candidate : List.of(id, UUID.randomUUID())) {
            String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/attempts/{id}", candidate))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(body).path("detail").asText()).isEqualTo("Attempt is unavailable");
            assertThat(body).doesNotContain("items", "Frozen", "correctAnswer", "explanation", "subject");
        }
        assertThat(targetSnapshot()).isEqualTo(before); org.mockito.Mockito.verifyNoInteractions(legacy);
    }

    @ParameterizedTest @ValueSource(strings = {"order", "item", "policy-json", "binding"})
    void corruptionNeverBecomesAPartialViewOrLegacyRead(String fault) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "view"));
        assertThat(view(started.attemptId()).items()).hasSize(5);
        tx.executeWithoutResult(s -> {
            switch (fault) {
                case "order" -> sql.update("DELETE FROM attempt_form_option LIMIT 1");
                case "item" -> sql.update("UPDATE attempt_form SET item_count=item_count+1");
                case "policy-json" -> sql.update("UPDATE attempt_policy_snapshot SET canonical_json='{}'");
                case "binding" -> sql.update("UPDATE question_revision_contract_bindings SET binding_hash=REPEAT('0',64)");
            }
        });
        var before = targetSnapshot(); reject(started.attemptId(), "CORRUPT");
        assertThat(targetSnapshot()).isEqualTo(before); org.mockito.Mockito.verifyNoInteractions(legacy);
    }

    @ParameterizedTest @ValueSource(strings = {"policy", "form", "lifecycle"})
    void unknownVersionsAreDeniedBeforeContentAndNeverTryLegacy(String root) {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "view"));
        assertThat(view(started.attemptId()).items()).hasSize(5);
        String table = switch (root) { case "policy" -> "attempt_policy_snapshot"; case "form" -> "attempt_form"; default -> "attempt_lifecycle_state"; };
        String column = root.equals("lifecycle") ? "lifecycle_version" : "schema_version";
        String check = switch (root) { case "policy" -> "chk_policy_snapshot_version"; case "form" -> "chk_form_version"; default -> "chk_attempt_lifecycle_version"; };
        sql.execute("ALTER TABLE " + table + " ALTER CHECK " + check + " NOT ENFORCED");
        try {
            tx.executeWithoutResult(s -> sql.update("UPDATE " + table + " SET " + column + "=2"));
            var before = targetSnapshot(); observer().reset(); reject(started.attemptId(), "NOT_FOUND");
            assertThat(observer().selects).noneMatch(q -> q.contains("question_revisions"));
            assertThat(targetSnapshot()).isEqualTo(before); org.mockito.Mockito.verifyNoInteractions(legacy);
        } finally {
            tx.executeWithoutResult(s -> sql.update("UPDATE " + table + " SET " + column + "=1"));
            sql.execute("ALTER TABLE " + table + " ALTER CHECK " + check + " ENFORCED");
        }
    }

    @ParameterizedTest @CsvSource({"2,false", "4,false", "2,true", "4,true"})
    void oldCallerSnapshotStillReadsARealNewlyCommittedStart(int isolation, boolean publishAfterSnapshot) {
        UUID revision = publishAfterSnapshot ? null : prepared();
        var old = new TransactionTemplate(manager); old.setIsolationLevel(isolation);
        var separate = new TransactionTemplate(manager); separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var started = new AtomicReference<AttemptStartService.Result>();
        var result = old.execute(s -> {
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class)).isZero();
            started.set(separate.execute(inner -> starts.start(command(publishAfterSnapshot ? prepared() : revision, AnswerOrder.AUTHORED, "new"))));
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_participant_subject", Integer.class)).isEqualTo(isolation == 4 ? 0 : 1);
            var read = view(started.get().attemptId()); assertThat(read.items()).hasSize(5);
            assertThat(s.isRollbackOnly()).isFalse(); return read;
        });
        assertThat(recreated().read(started.get().attemptId())).isEqualTo(result);
    }

    @ParameterizedTest @ValueSource(ints = {2, 4})
    void currentTerminalStateIsVisibleWithoutInventingGradingOrReviewRelease(int isolation) throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "view"));
        assertThat(view(started.attemptId()).items()).hasSize(5);
        var old = new TransactionTemplate(manager); old.setIsolationLevel(isolation);
        var separate = new TransactionTemplate(manager); separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var result = old.execute(s -> {
            sql.queryForObject("SELECT row_version FROM attempt_lifecycle_state", Long.class);
            separate.executeWithoutResult(inner -> submitStateFixture(started.attemptId()));
            var read = view(started.attemptId());
            assertThat(read.execution()).isEqualTo(AttemptLifecycle.Execution.SUBMITTED);
            assertThat(read.stateVersion()).isEqualTo(2); assertThat(read.submittedAt()).isNotNull();
            assertThat(read.contentVisibility()).isEqualTo(ParticipantViewDisclosureReader.ContentVisibility.WITHHELD);
            assertThat(read.items()).isNull(); assertThat(s.isRollbackOnly()).isFalse(); return read;
        });
        var serialized = json.readTree(json.writeValueAsString(result));
        assertThat(serialized.has("items")).isFalse(); assertThat(serialized.has("score")).isFalse();
        assertThat(serialized.has("gradingState")).isFalse();
    }

    @Test void reachedReadFailurePreservesStateAndMarksAnOuterWriteForRollback() {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "view"));
        var before = targetSnapshot(); String title = sql.queryForObject("SELECT title FROM quizzes", String.class);
        AtomicBoolean reached = new AtomicBoolean();
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("question_option_revisions")) { reached.set(true); throw new SQLException("private diagnostic", "08006"); }
        };
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            sql.update("UPDATE quizzes SET title='outer uncommitted'"); reject(started.attemptId(), "UNAVAILABLE");
            assertThat(s.isRollbackOnly()).isTrue();
        })).isInstanceOf(org.springframework.transaction.UnexpectedRollbackException.class);
        assertThat(reached).isTrue(); observer().reset();
        assertThat(sql.queryForObject("SELECT title FROM quizzes", String.class)).isEqualTo(title);
        assertThat(targetSnapshot()).isEqualTo(before); assertThat(view(started.attemptId()).items()).hasSize(5);
    }

    @Test void severalParentsAndDifferentFormSizesHaveBoundedQueriesThroughSerialization() throws Exception {
        List<Integer> totals = new ArrayList<>();
        for (int size : List.of(1, 20)) {
            List<UUID> attempts = new ArrayList<>(); List<String> names = new ArrayList<>();
            for (int parent = 0; parent < 3; parent++) {
                username = "view-parent-" + UUID.randomUUID(); ownerId = user(username, true);
                grant(ownerId, "ATTEMPT_READ", "ATTEMPT_CREATE"); quizId = newQuiz(); authenticate(username);
                var revision = publish(0, IntStream.range(0, size).mapToObj(i -> draft(QuestionType.MCQ_SINGLE)).toList());
                binding.bind(quizId, revision); attempts.add(starts.start(command(revision, AnswerOrder.SHUFFLED_PER_ATTEMPT, "count")).attemptId());
                names.add(username);
            }
            observer().reset();
            for (int i = 0; i < attempts.size(); i++) {
                authenticate(names.get(i)); assertThat(json.readTree(json.writeValueAsString(view(attempts.get(i)))).path("items")).hasSize(size);
            }
            totals.add(observer().selects.size());
        }
        assertThat(totals.get(1)).isEqualTo(totals.get(0)); assertThat(totals.get(0)).isLessThanOrEqualTo(90);
        System.out.println("Participant view SQL for 3 parents with 1/20 items: " + totals);
    }

    @Test void concurrentReadersBothReachProjectionAndReturnTheSameCommittedView() throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "view"));
        var expected = view(started.attemptId()); var before = targetSnapshot();
        var arrived = new CountDownLatch(2); var release = new CountDownLatch(1);
        var seen = ConcurrentHashMap.<Integer>newKeySet();
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("question_option_revisions") && seen.add(connection)) { arrived.countDown(); await(release); }
        };
        var pool = Executors.newFixedThreadPool(2);
        Callable<ParticipantAttemptView> read = () -> {
            authenticate(username); try { return view(started.attemptId()); } finally { SecurityContextHolder.clearContext(); }
        };
        var one = pool.submit(read); var two = pool.submit(read);
        try {
            assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue(); release.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS)).isEqualTo(expected); assertThat(two.get(10, TimeUnit.SECONDS)).isEqualTo(expected);
            assertThat(observer().sqlErrorCodes).isEmpty();
        } finally { release.countDown(); pool.shutdownNow(); observer().reset(); }
        assertThat(targetSnapshot()).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"2,false,false", "2,false,true", "2,true,false", "2,true,true",
            "4,false,false", "4,false,true", "4,true,false", "4,true,true"})
    void completedUntimedViewAndNewStartBothCommitWithoutTimingGapDeadlock(
            int isolation, boolean readOnly, boolean timedStart) throws Exception {
        UUID revision = prepared();
        var previous = starts.start(command(revision, AnswerOrder.AUTHORED, "previous"));
        var terminal = submissions.submit(new AttemptSubmissionService.Command(previous.attemptId(),
                previous.stateVersion(), true, uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.Key.from("finish-previous")));
        var expected = view(previous.attemptId());
        assertThat(expected.execution()).isEqualTo(AttemptLifecycle.Execution.SUBMITTED);
        assertThat(expected.stateVersion()).isEqualTo(terminal.stateVersion());
        assertThat(expected.timing()).isEqualTo(ParticipantAttemptView.Timing.UNTIMED);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_timing", Integer.class)).isZero();

        var base = command(revision, AnswerOrder.AUTHORED, "new-start");
        var request = timedStart ? timed(base) : base;
        var writerHeldQuestions = new CountDownLatch(1);
        var allowWriter = new CountDownLatch(1);
        var readerIssuedQuestions = new CountDownLatch(1);
        var writerThread = new AtomicReference<Thread>();
        var readerThread = new AtomicReference<Thread>();
        var writerConnection = new AtomicInteger();
        var readerConnection = new AtomicInteger();
        observer().reset();
        observer().beforeSql = (statement, connection) -> {
            if (Thread.currentThread() == writerThread.get()
                    && statement.stripLeading().startsWith(timedStart ? "INSERT INTO attempt_timing" : "INSERT INTO attempt_trace_event")) {
                // Hold the start at the timing boundary, after real form/lifecycle writes and question locks.
                writerConnection.set(connection);
                writerHeldQuestions.countDown();
                await(allowWriter);
            }
            if (Thread.currentThread() == readerThread.get()
                    && statement.contains("FROM question_revisions q")
                    && (statement.contains("FOR SHARE") || statement.contains("FOR UPDATE"))) {
                readerConnection.set(connection);
                readerIssuedQuestions.countDown();
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<AttemptStartService.Result> writing = pool.submit(() -> {
                writerThread.set(Thread.currentThread()); authenticate(username);
                try { return starts.start(request); }
                finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(writerHeldQuestions.await(10, TimeUnit.SECONDS)).as("start holds real question locks").isTrue();
            Future<ParticipantAttemptView> reading = pool.submit(() -> {
                readerThread.set(Thread.currentThread()); authenticate(username);
                var transaction = new TransactionTemplate(manager);
                transaction.setIsolationLevel(isolation); transaction.setReadOnly(readOnly);
                try {
                    return transaction.execute(status -> {
                        assertThat(sql.queryForObject("SELECT @@transaction_isolation", String.class))
                                .isEqualTo(isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ ? "REPEATABLE-READ" : "READ-COMMITTED");
                        assertThat(sql.queryForObject("SELECT @@transaction_read_only", Boolean.class)).isEqualTo(readOnly);
                        return view(previous.attemptId());
                    });
                } finally { SecurityContextHolder.clearContext(); }
            });
            assertThat(readerIssuedQuestions.await(10, TimeUnit.SECONDS)).as("view reaches real question-lock SQL").isTrue();
            awaitQuestionLockWait();
            assertThat(reading.isDone()).as("view really waits for the start, not merely for a Java barrier").isFalse();
            assertThat(readerConnection.get()).isNotEqualTo(writerConnection.get());
            allowWriter.countDown();

            var created = writing.get(20, TimeUnit.SECONDS);
            try { assertThat(reading.get(20, TimeUnit.SECONDS)).isEqualTo(expected); }
            catch (ExecutionException failure) {
                throw new AssertionError("View/start failed: isolation=" + isolation + ", readOnly=" + readOnly
                        + ", timedStart=" + timedStart + ", MySQL codes=" + observer().sqlErrorCodes, failure);
            }
            assertThat(created.attemptId()).isNotEqualTo(previous.attemptId());
            assertThat(created.replayed()).isFalse();
            assertThat(observer().sqlErrorCodes).isEmpty();
            observer().reset();

            // Fresh transactions prove both durable outcomes and exact replay, without retries hiding a deadlock.
            var active = view(created.attemptId());
            assertThat(active.execution()).isEqualTo(AttemptLifecycle.Execution.IN_PROGRESS);
            assertThat(active.timing()).isEqualTo(timedStart ? ParticipantAttemptView.Timing.TIMED : ParticipantAttemptView.Timing.UNTIMED);
            assertThat(view(previous.attemptId())).isEqualTo(expected);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_lifecycle_state", Integer.class)).isEqualTo(2);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_timing", Integer.class)).isEqualTo(timedStart ? 1 : 0);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM attempt_submission", Integer.class)).isEqualTo(1);
            var replay = starts.start(request);
            assertThat(replay.replayed()).isTrue(); assertThat(replay.attemptId()).isEqualTo(created.attemptId());
            System.out.println("Timing/read lock order: isolation=" + isolation + ", readOnly=" + readOnly
                    + ", timedStart=" + timedStart + "; real question wait, both operations committed, no SQL errors");
        } finally {
            allowWriter.countDown(); pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); observer().reset();
        }
    }

    private void awaitQuestionLockWait() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            if (admin.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='question_revisions'
                    """, Integer.class, SCHEMA) > 0) return;
            Thread.sleep(10);
        } while (System.nanoTime() < until);
        throw new AssertionError("View never entered the expected MySQL question-revision lock wait");
    }

    @Test void authorityRemainsLockedThroughActualContentProjection() throws Exception {
        var started = starts.start(command(prepared(), AnswerOrder.AUTHORED, "delivery"));
        var reading = new CountDownLatch(1); var release = new CountDownLatch(1); var revokeIssued = new CountDownLatch(1);
        observer().beforeSql = (statement, connection) -> {
            if (statement.contains("question_option_revisions")) { reading.countDown(); await(release); }
            if (statement.startsWith("DELETE rp FROM role_permissions")) revokeIssued.countDown();
        };
        var workers = Executors.newFixedThreadPool(2);
        Future<ParticipantAttemptView> reader = workers.submit(() -> {
            authenticate(username);
            try { return views.read(started.attemptId()); }
            finally { SecurityContextHolder.clearContext(); }
        });
        Future<?> revoker = null;
        try {
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            revoker = workers.submit(() -> revoke(ownerId, "ATTEMPT_READ"));
            assertThat(revokeIssued.await(5, TimeUnit.SECONDS)).isTrue();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
            while (System.nanoTime() < until) {
                waiting = admin.queryForObject("""
                        SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                        JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                        WHERE l.OBJECT_SCHEMA=?
                        """, Integer.class, SCHEMA) > 0;
                if (waiting) break;
                Thread.sleep(10);
            }
            assertThat(waiting).isTrue(); assertThat(revoker.isDone()).isFalse();
        } finally {
            release.countDown();
            try {
                assertThat(((ParticipantAttemptView) reader.get(10, TimeUnit.SECONDS)).items()).hasSize(5);
                if (revoker != null) revoker.get(10, TimeUnit.SECONDS);
            } finally { workers.shutdownNow(); observer().reset(); }
        }
        reject(started.attemptId(), "NOT_FOUND");
        grant(ownerId, "ATTEMPT_READ"); assertThat(view(started.attemptId()).items()).hasSize(5);
    }

    private ParticipantAttemptView view(UUID id) { return (ParticipantAttemptView) views.read(id); }
    @ParameterizedTest @EnumSource(ParticipantViewMediaReader.Availability.class)
    void reviewMediaProjectsCurrentAvailabilityAfterRestartWithoutChangingEvidence(ParticipantViewMediaReader.Availability availability) throws Exception {
        UUID id = mediaFixture();
        tx.executeWithoutResult(s -> submitStateFixture(id));
        var before = targetSnapshot();
        var state = new AtomicReference<>(ParticipantViewMediaReader.Availability.AVAILABLE);
        var calls = new AtomicInteger();
        ParticipantViewMediaReader images = (attempt, revision, refs) -> {
            assertThat(attempt).isEqualTo(id); assertThat(refs).hasSize(1); calls.incrementAndGet();
            var ref = refs.get(0);
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM quiz_revision_images WHERE quiz_revision_id=UUID_TO_BIN(?) AND question_revision_id=UUID_TO_BIN(?) AND asset_id=UUID_TO_BIN(?)",
                    Integer.class, revision.toString(), ref.questionRevisionId().toString(), ref.assetId().toString())).isEqualTo(1);
            return Map.of(ref.formItemId(), new ParticipantViewMediaReader.Image(state.get(),
                    state.get() == ParticipantViewMediaReader.Availability.AVAILABLE ? "https://images.example.invalid/original-version.png" : null));
        };
        ParticipantViewDisclosureReader released = facts -> ParticipantViewDisclosureReader.ContentVisibility.VISIBLE;
        assertThat(((ParticipantAttemptView) reader(released, images, metrics).read(id)).items().get(0).image().url()).isNotNull();
        state.set(availability);
        // A new service instance must re-read current decisions, not retain yesterday's display grant.
        var restarted = reader(released, images, metrics);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory(); beans.registerSingleton("views", restarted);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewController(beans.getBean(ParticipantAttemptViewService.class)))
                .setControllerAdvice(new uk.gegc.quizmaker.features.attempt.api.ParticipantAttemptViewErrorHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(json)).build();
        var body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v2/attempts/{id}", id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        var payload = json.readTree(body); var item = payload.path("items").get(0);
        assertThat(item.path("questionText").asText()).isEqualTo("Frozen TRUE_FALSE");
        assertThat(item.path("image").path("availability").asText()).isEqualTo(availability.name());
        assertThat(item.path("image").has("url")).isEqualTo(availability == ParticipantViewMediaReader.Availability.AVAILABLE);
        assertThat(payload.path("timing").asText()).isEqualTo("UNTIMED");
        assertThat(payload.path("allowedActions").get(0).asText()).isEqualTo("VIEW_ATTEMPT");
        assertThat(payload.path("stateVersion").asInt()).isEqualTo(2); assertThat(calls).hasValue(2);
        assertThat(body).doesNotContain("correctAnswer", "isCorrect", "explanation", "score", "objectKey", "source/");
        assertThat(targetSnapshot()).isEqualTo(before);
        tx.executeWithoutResult(s -> sql.update("UPDATE attempt_form SET item_count=item_count+1"));
        var corrupt = targetSnapshot();
        assertThatThrownBy(() -> reader(released, images, metrics).read(id)).isInstanceOfSatisfying(AttemptDeliveryException.class,
                failure -> assertThat(failure.reason().name()).isEqualTo("CORRUPT"));
        assertThat(calls).hasValue(2); assertThat(targetSnapshot()).isEqualTo(corrupt);
    }

    @Test void runtimeMediaRemainsGatedAndActiveFailureCannotMutateTimeOrLifecycle() {
        UUID id = mediaFixture(); var before = targetSnapshot(); reject(id, "UNSUPPORTED");
        for (var availability : List.of(ParticipantViewMediaReader.Availability.UNAVAILABLE, ParticipantViewMediaReader.Availability.RESTRICTED)) {
            var projected = reader(facts -> ParticipantViewDisclosureReader.ContentVisibility.VISIBLE,
                    (attempt, revision, refs) -> Map.of(refs.get(0).formItemId(), new ParticipantViewMediaReader.Image(availability, null)), metrics);
            assertThatThrownBy(() -> projected.read(id)).isInstanceOfSatisfying(AttemptDeliveryException.class,
                    failure -> assertThat(failure.reason().name()).isEqualTo("UNAVAILABLE"));
            assertThat(targetSnapshot()).isEqualTo(before);
        }
        tx.executeWithoutResult(s -> submitStateFixture(id));
        assertThat(view(id).items()).isNull(); // No release proof: not even the media reader is consulted.
    }

    @Test void brokenMetricsCannotChangeProjectionOrLeakDiagnosticCauses() {
        var id = starts.start(command(prepared(), AnswerOrder.AUTHORED, "view")).attemptId(); var expected = view(id);
        var broken = org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
        org.mockito.Mockito.when(broken.config()).thenThrow(new IllegalStateException("synthetic metrics outage"));
        assertThat(reader(context.getBean(ParticipantViewDisclosureReader.class), context.getBean(ParticipantViewMediaReader.class), broken).read(id)).isEqualTo(expected);
        assertThat(metrics.find("quizmaker.attempt.view").tag("outcome", "view").timer()).isNotNull();
        assertThat(metrics.getMeters().stream().filter(m -> m.getId().getName().equals("quizmaker.attempt.view")))
                .allSatisfy(m -> assertThat(m.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isIn("version", "outcome")));
        revoke(ownerId, "ATTEMPT_READ");
        assertThatThrownBy(() -> reader(context.getBean(ParticipantViewDisclosureReader.class), context.getBean(ParticipantViewMediaReader.class), broken).read(id))
                .isInstanceOfSatisfying(AttemptDeliveryException.class, failure -> {
                    assertThat(failure.reason().name()).isEqualTo("NOT_FOUND"); assertThat(failure).hasNoCause();
                });
    }

    private UUID mediaFixture() {
        // Explicit projection fixture: real publication/bindings/form/lifecycle/trace writers, not a claim
        // that #600 supports media start, #556 releases review, or #818/#831 implement display authority.
        var retained = context.getBean(uk.gegc.quizmaker.features.media.application.RetainedImageService.class);
        UUID asset = tx.execute(s -> {
            var value = new uk.gegc.quizmaker.features.media.domain.model.MediaAsset(); var id = UUID.randomUUID();
            value.setId(id); value.setKey("source/" + id);
            value.setType(uk.gegc.quizmaker.features.media.domain.model.MediaAssetType.IMAGE);
            value.setStatus(uk.gegc.quizmaker.features.media.domain.model.MediaAssetStatus.READY);
            value.setMimeType("image/png"); value.setSizeBytes(1L); value.setWidth(640); value.setHeight(480); value.setCreatedBy(username);
            return context.getBean(uk.gegc.quizmaker.features.media.domain.repository.MediaAssetRepository.class).saveAndFlush(value).getId();
        });
        retained.prepare(Set.of(asset), username); var question = draft(QuestionType.TRUE_FALSE);
        tx.executeWithoutResult(s -> {
            questions.findById(question).orElseThrow().setAttachmentAssetId(asset);
            retained.bind(question, Set.of(asset), Set.of(), username);
        });
        var revisionId = publish(0, List.of(question)); binding.bind(quizId, revisionId);
        return tx.execute(s -> {
            context.getBean(QuestionRevisionContractService.class).declareScope(ownerId, Set.of(revisionId));
            var revision = context.getBean(QuizRevisionRepository.class).findByIdForShare(ownerId, revisionId).orElseThrow();
            var resolution = context.getBean(AttemptPolicyResolver.class).resolve(new uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest(
                    1, 1, uk.gegc.quizmaker.features.attempt.domain.model.resolution.PolicyResolutionRequest.HASH_ALGORITHM,
                    BuiltInPracticePreset.REFERENCE, content(revisionId), List.of()));
            var snapshot = context.getBean(PolicySnapshotService.class).createOrReplay(ownerId,
                    new uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot(UUID.randomUUID(), resolution));
            var form = new FixedForm(UUID.randomUUID(), 1, revisionId,
                    List.of(new FixedForm.Item(UUID.randomUUID(), revision.items().get(0).id(), 0)));
            context.getBean(FormOptionOrderService.class).createOrReplay(ownerId, form, resolution.policy().answerOrdering());
            UUID id = UUID.randomUUID();
            context.getBean(AttemptSubjectService.class).bindOrReplay(ownerId, AttemptSubject.authenticated(id, ownerId));
            var initial = lifecycle.initialize(id);
            var started = lifecycle.save(id, 0, new AttemptLifecycle.State(1, AttemptLifecycle.Execution.IN_PROGRESS, AttemptLifecycle.AnswerCollection.NOT_STARTED));
            var scope = new ExecutionTrace.Scope(id, snapshot.id(), form.id()); var trace = context.getBean(ExecutionTraceService.class);
            trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.FORM_CREATED, ExecutionTrace.Actor.SYSTEM,
                    new ExecutionTrace.Data(UUID.randomUUID(), null, null, null, ExecutionTrace.ReasonCode.MATERIALIZED)));
            trace.append(scope, new ExecutionTrace.Event(1, UUID.randomUUID(), ExecutionTrace.Type.ATTEMPT_STARTED, ExecutionTrace.Actor.PARTICIPANT,
                    new ExecutionTrace.Data(UUID.randomUUID(), null, initial.state(), started.state(), ExecutionTrace.ReasonCode.ACCEPTED)));
            return id;
        });
    }
    private ParticipantAttemptViewService recreated() {
        return reader(context.getBean(ParticipantViewDisclosureReader.class), context.getBean(ParticipantViewMediaReader.class), metrics);
    }
    private ParticipantAttemptViewService reader(ParticipantViewDisclosureReader disclosure, ParticipantViewMediaReader media,
                                                 io.micrometer.core.instrument.MeterRegistry meter) {
        return new ParticipantAttemptViewServiceImpl(context.getBean(AuthenticatedSubjectResolver.class),
                context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AttemptViewRouteRepository.class),
                context.getBean(AttemptReadFactsService.class), disclosure, media, manager, meter, context.getBean(Clock.class));
    }
    private void submitStateFixture(UUID id) {
        // Real storage transition only; #529/#556 must separately prove terminal commands and release.
        lifecycle.save(id, 1, new AttemptLifecycle.State(1, AttemptLifecycle.Execution.SUBMITTED, AttemptLifecycle.AnswerCollection.COMPLETE));
    }
    private void reject(UUID id, String reason) {
        assertThatThrownBy(() -> views.read(id)).isInstanceOfSatisfying(AttemptDeliveryException.class, failure -> {
            assertThat(failure.reason().name()).isEqualTo(reason); assertThat(failure).hasNoCause();
            assertThat(failure.getSuppressed()).isEmpty(); assertThat(failure.getMessage()).isEqualTo("Attempt delivery rejected: " + reason);
        });
    }

    private void revoke(UUID userId, String permission) {
        tx.executeWithoutResult(status -> sql.update("""
                DELETE rp FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id
                JOIN permissions p ON p.permission_id=rp.permission_id
                WHERE ur.user_id=UUID_TO_BIN(?) AND p.permission_name=?
                """, userId.toString(), permission));
    }
    private Map<String, List<String>> targetSnapshot() {
        return tx.execute(status -> {
            Map<String, List<String>> result = new TreeMap<>();
            TARGET_TABLES.forEach(table -> result.put(table, sql.queryForList("SELECT * FROM " + table).stream().map(row -> {
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
            String role = "delivery-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name='QUIZ_UPDATE'", role);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", id.toString(), role);
        });
        return id;
    }

    private UUID newQuiz() {
        return tx.execute(s -> {
            var category = new Category(); category.setName("delivery-" + UUID.randomUUID()); categories.saveAndFlush(category);
            var quiz = new Quiz(); quiz.setCreator(users.findById(ownerId).orElseThrow()); quiz.setCategory(category);
            quiz.setTitle("Start fixture " + UUID.randomUUID()); quiz.setDescription("Synthetic source"); quiz.setDifficulty(Difficulty.EASY);
            quiz.setStatus(QuizStatus.DRAFT); quiz.setVisibility(Visibility.PRIVATE); quiz.setEstimatedTime(5);
            quiz.setIsTimerEnabled(false); quiz.setIsRepetitionEnabled(false);
            return quizzes.saveAndFlush(quiz).getId();
        });
    }
    @Test void timedParticipantViewUsesSavedFactsAcrossReaderRestartAndHidesPrivateTimingInputs() throws Exception {
        UUID revision=prepared(); var command=timed(command(revision,AnswerOrder.AUTHORED,"timed-view"));
        var result=starts.start(command);
        var view=(ParticipantAttemptView)views.read(result.attemptId());
        assertThat(view.timing()).isEqualTo(ParticipantAttemptView.Timing.TIMED);
        assertThat(view.timingDetails().effectiveDeadline()).isEqualTo(view.startedAt().plusSeconds(900));
        assertThat(view.timingDetails().graceSeconds()).isZero(); assertThat(view.timingDetails().expired()).isFalse();
        assertThat((ParticipantAttemptView)recreated().read(result.attemptId())).isEqualTo(view);
        var encoded=json.writeValueAsString(view);
        assertThat(encoded).doesNotContain("policySnapshotId","durationDeadline","overallDeadline","userId","ownerId");
        tx.executeWithoutResult(status -> sql.update("UPDATE quizzes SET timer_duration_min=99 WHERE quiz_id=UUID_TO_BIN(?)",quizId.toString()));
        assertThat(((ParticipantAttemptView)views.read(result.attemptId())).timingDetails()).isEqualTo(view.timingDetails());
        var later = new ParticipantAttemptViewServiceImpl(context.getBean(AuthenticatedSubjectResolver.class),
                context.getBean(uk.gegc.quizmaker.features.attempt.domain.repository.AttemptViewRouteRepository.class),
                context.getBean(AttemptReadFactsService.class),context.getBean(ParticipantViewDisclosureReader.class),context.getBean(ParticipantViewMediaReader.class),
                manager,metrics,Clock.fixed(view.timingDetails().effectiveDeadline(),ZoneOffset.UTC));
        var expired=(ParticipantAttemptView)later.read(result.attemptId());
        assertThat(expired.timingDetails().expired()).isTrue();
        assertThat(expired.allowedActions()).containsExactly(uk.gegc.quizmaker.features.attempt.domain.model.command.AttemptCommands.Type.VIEW_ATTEMPT);
        assertThat(expired.execution()).isEqualTo(uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.AttemptLifecycle.Execution.IN_PROGRESS);
        var expiredDelivery=new AttemptDeliveryServiceImpl(context.getBean(AttemptReadFactsService.class),manager,metrics,
                Clock.fixed(view.timingDetails().effectiveDeadline(),ZoneOffset.UTC));
        assertThatThrownBy(() -> expiredDelivery.read(result.attemptId())).isInstanceOfSatisfying(AttemptDeliveryException.class,e -> assertThat(e.reason().name()).isEqualTo("INACTIVE"));
    }
    @Test void unknownTimingVersionNeverFallsBackToLegacyOrCurrentSettings() {
        var result=starts.start(timed(command(prepared(),AnswerOrder.AUTHORED,"unknown-timing")));
        assertThat(((ParticipantAttemptView)views.read(result.attemptId())).timingDetails()).isNotNull();
        sql.execute("ALTER TABLE attempt_timing ALTER CHECK ck_attempt_timing NOT ENFORCED");
        try {
            tx.executeWithoutResult(status -> sql.update("UPDATE attempt_timing SET schema_version=2"));
            reject(result.attemptId(),"UNSUPPORTED"); org.mockito.Mockito.verifyNoInteractions(legacy);
        } finally {
            tx.executeWithoutResult(status -> sql.update("UPDATE attempt_timing SET schema_version=1"));
            sql.execute("ALTER TABLE attempt_timing ALTER CHECK ck_attempt_timing ENFORCED");
        }
    }
    @Test void missingTimingEvidenceFailsClosedAndWrongOwnerCannotInspectTheFailure() {
        UUID revision=prepared(); var result=starts.start(timed(command(revision,AnswerOrder.AUTHORED,"missing-timing")));
        assertThat(((ParticipantAttemptView)views.read(result.attemptId())).timingDetails()).isNotNull();
        tx.executeWithoutResult(status -> sql.update("DELETE FROM attempt_timing WHERE attempt_id=UUID_TO_BIN(?)",result.attemptId().toString()));
        reject(result.attemptId(),"CORRUPT");
        String foreign="foreign-timing-"+UUID.randomUUID().toString().substring(0,8); var id=user(foreign,true);grant(id,"ATTEMPT_READ","ATTEMPT_CREATE");
        authenticate(foreign); reject(result.attemptId(),"NOT_FOUND");
        SecurityContextHolder.clearContext(); reject(result.attemptId(),"UNAUTHENTICATED");
    }
    @Test void timingReadsStayBoundedAcrossMultipleParentQuizzesAndLargerForms(org.junit.jupiter.api.TestReporter report) throws Exception {
        List<UUID> small=new ArrayList<>(),large=new ArrayList<>();
        for(int n=0;n<3;n++) {
            quizId=newQuiz(); UUID revision=publish(0,List.of(draft(QuestionType.TRUE_FALSE)));binding.bind(quizId,revision);
            small.add(starts.start(timed(command(revision,AnswerOrder.AUTHORED,"small-timing-"+n))).attemptId());
            quizId=newQuiz(); var items=java.util.stream.IntStream.range(0,20).mapToObj(i -> draft(QuestionType.TRUE_FALSE)).toList();
            revision=publish(0,items);binding.bind(quizId,revision);
            large.add(starts.start(timed(command(revision,AnswerOrder.AUTHORED,"large-timing-"+n))).attemptId());
        }
        observer().selects.clear();
        for (var id:small) assertThat(json.readTree(json.writeValueAsString(views.read(id))).path("items")).hasSize(1);
        int few=observer().selects.size(); observer().selects.clear();
        for (var id:large) assertThat(json.readTree(json.writeValueAsString(views.read(id))).path("items")).hasSize(20);
        int many=observer().selects.size();
        assertThat(many).isEqualTo(few); assertThat(many).isLessThan(120);
        assertThat(observer().selects.stream().filter(q -> q.contains("FROM attempt_timing"))).hasSize(3);
        report.publishEntry("timingQueries","3 parents, 1 versus 20 items: "+few+" / "+many);
        System.out.println("Timed participant view SQL for 3 parents with 1/20 items: " + few + " / " + many);
    }
    private static AttemptStartService.Command timed(AttemptStartService.Command c) {
        Instant close=Instant.parse("2026-11-05T12:15:00Z");
        return new AttemptStartService.Command(c.version(),c.quizId(),c.content(),c.publicationVersion(),c.key(),c.preset(),c.context(),c.legacyMode(),
                c.answerOrder(),c.draftsEnabled(),c.navigation(),c.delivery(),c.selection(),c.attemptLimit(),new TimingPolicy(TimingMode.TOTAL_ATTEMPT,Duration.ofMinutes(30),close));
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
                BuiltInPracticePreset.REFERENCE, LegacyPracticeRequest.LaunchContext.ORDINARY, "ALL_AT_ONCE", order);
    }
    private static void authenticate(String name) {
        var principal = org.springframework.security.core.userdetails.User.withUsername(name).password("test-only").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
    private void grant(UUID userId, String... names) {
        tx.executeWithoutResult(s -> {
            String role = "delivery-" + UUID.randomUUID();
            sql.update("INSERT INTO roles(role_name,is_default) VALUES (?,FALSE)", role);
            for (String name : names) assertThat(sql.update("INSERT INTO role_permissions(role_id,permission_id) SELECT r.role_id,p.permission_id FROM roles r CROSS JOIN permissions p WHERE r.role_name=? AND p.permission_name=?", role, name)).isEqualTo(1);
            sql.update("INSERT INTO user_roles(user_id,role_id) SELECT UUID_TO_BIN(?),role_id FROM roles WHERE role_name=?", userId.toString(), role);
        });
    }

}
