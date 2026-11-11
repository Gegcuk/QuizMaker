package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.api.dto.AnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.api.dto.BatchAnswerSubmissionRequest;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.CurrentAnswerRepository;
import uk.gegc.quizmaker.features.attempt.infra.repository.JsonCurrentAnswerRepository;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.application.QuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Complete, isolated Flyway schema; Hibernate cannot manufacture the projection guarantee. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.show-sql=false", "logging.level.org.hibernate.SQL=OFF"})
@Import(LegacyAnswerMembershipMySqlIntegrationTest.SqlConfig.class)
@TestExecutionListeners(listeners = LegacyAnswerMembershipMySqlIntegrationTest.Cleanup.class,
        mergeMode = TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
@ExtendWith(OutputCaptureExtension.class)
class LegacyAnswerMembershipMySqlIntegrationTest extends BaseIntegrationTest {
    private static final String CATALOG = "quizmaker_848_application_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PORT = System.getenv().getOrDefault("TEST_MYSQL_PORT", "3306");
    private static final String ADMIN_USER = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_USERNAME", "root");
    private static final String ADMIN_PASSWORD = System.getenv().getOrDefault("TEST_MYSQL_ADMIN_PASSWORD", "root");
    private static final String OPTIONS = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&createDatabaseIfNotExist=true";
    private static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();

    private static String url() {
        if (!PORT.matches("[0-9]{1,5}")) throw new IllegalArgumentException("Invalid test MySQL port");
        return "jdbc:mysql://127.0.0.1:" + PORT + "/" + CATALOG + OPTIONS;
    }

    @DynamicPropertySource
    static void ownCatalog(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", LegacyAnswerMembershipMySqlIntegrationTest::url);
        properties.add("spring.datasource.username", () -> ADMIN_USER);
        properties.add("spring.datasource.password", () -> ADMIN_PASSWORD);
    }

    @TestConfiguration
    static class SqlConfig {
        @Bean
        org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer sqlCapture() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector",
                    (org.hibernate.resource.jdbc.spi.StatementInspector) sql -> {
                        if (SQL.get() != null) SQL.get().add(sql);
                        return sql;
                    });
        }
    }

    public static class Cleanup extends AbstractTestExecutionListener {
        // afterTestClass runs in reverse order: clean up after DirtiesContext closes the app/executors.
        @Override public int getOrder() { return 1000; }
        @Override public void afterTestClass(TestContext ignored) {
            new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + PORT + "/" + OPTIONS,
                    ADMIN_USER, ADMIN_PASSWORD)).execute("DROP DATABASE IF EXISTS " + CATALOG);
        }
    }

    @Autowired AttemptService service;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ObjectMapper json;
    @MockitoSpyBean CurrentAnswerRepository currentAnswers;
    @MockitoSpyBean AnswerMapper answerMapper;

    @ParameterizedTest
    @CsvSource({"READ_COMMITTED,ALL_AT_ONCE", "READ_COMMITTED,ONE_BY_ONE", "READ_COMMITTED,TIMED",
            "REPEATABLE_READ,ALL_AT_ONCE", "REPEATABLE_READ,ONE_BY_ONE", "REPEATABLE_READ,TIMED"})
    void independentEmptyAttemptsBothAcceptTheirFirstHttpAnswer(String isolation, AttemptMode mode) throws Exception {
        jdbcTemplate.update("DELETE FROM answers");
        Fixture first = fixture(1, mode, QuestionType.TRUE_FALSE), second = fixture(1, mode, QuestionType.TRUE_FALSE);
        var reached = new CountDownLatch(2); var release = new CountDownLatch(1);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (Set.of(first.id(), second.id()).contains(call.getArgument(0))) {
                assertThat((List<?>) result).isEmpty(); reached.countDown(); await(release);
            }
            return result;
        }).when(currentAnswers).readCurrent(any(), anyString());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> httpAnswer(first, isolation));
            var two = executor.submit(() -> httpAnswer(second, isolation));
            assertThat(reached.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(answerLocks()).isEmpty();
            release.countDown();
            assertThat(List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS))).containsExactly(200, 200);
        } finally {
            release.countDown(); executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); reset(currentAnswers);
        }
        for (Fixture f : List.of(first, second)) {
            assertThat(service.completeAttempt(f.owner(), f.id()).totalScore()).isEqualTo(1.0);
            assertProjectionMatches(f.id());
        }
    }

    @ParameterizedTest @EnumSource(QuestionType.class)
    void allNineQuestionFamiliesKeepFeedbackDuplicateAndCompletionContracts(QuestionType type) throws Exception {
        Fixture f = fixture(1, AttemptMode.ALL_AT_ONCE, type);
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", f.id()).with(user(f.owner()))
                        .contentType("application/json").content(json.writeValueAsBytes(request(f, 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isCorrect").value(true))
                .andExpect(jsonPath("$.score").value(1.0)).andExpect(jsonPath("$.correctAnswer").exists());
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", f.id()).with(user(f.owner()))
                        .contentType("application/json").content(json.writeValueAsBytes(request(f, 0))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", f.id()).with(user(f.owner())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalScore").value(1.0))
                .andExpect(jsonPath("$.correctCount").value(1)).andExpect(jsonPath("$.answers.length()").value(1));
        assertProjectionMatches(f.id());
    }

    @Test void databaseOwnsProjectionEvenWhenCallerEntityIsCachedAndDirty() {
        Fixture f = fixture(2);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Attempt cached = entityManager.find(Attempt.class, f.id());
            service.submitAnswer(f.owner(), f.id(), request(f, 0)); entityManager.flush();
            cached.setAcceptedAnswerProjection("{\"version\":999}"); cached.setTotalScore(17.0); entityManager.flush();
            assertProjectionMatches(f.id());
            assertThat(service.completeAttempt(f.owner(), f.id()).totalScore()).isEqualTo(1.0);
            assertThat(entityManager.contains(cached)).isTrue();
        });
    }

    @ParameterizedTest @EnumSource(AttemptMode.class)
    void oldRepeatableReadUsesCommittedFactsAndPreservesManagedCallerChanges(AttemptMode mode) throws Exception {
        Fixture f = fixture(2, mode, QuestionType.TRUE_FALSE);
        var executor = Executors.newSingleThreadExecutor();
        String email = "caller-" + UUID.randomUUID() + "@example.invalid";
        try {
            rr().executeWithoutResult(status -> {
                Attempt cached = entityManager.find(Attempt.class, f.id());
                assertThat(cached.getAnswers()).isEmpty();
                try { executor.submit(() -> service.submitAnswer(f.owner(), f.id(), request(f, 0))).get(15, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                cached.getUser().setEmail(email);
                var result = service.completeAttempt(f.owner(), f.id());
                assertThat(result.totalScore()).isEqualTo(1.0); assertThat(result.answers()).hasSize(1);
                assertThat(entityManager.contains(cached.getUser())).isTrue();
            });
            assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner())).isEqualTo(email);
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void cachedAnswerScalarsDoNotOverrideNewerStoredScore() throws Exception {
        Fixture f = fixture(1);
        UUID id = service.submitAnswer(f.owner(), f.id(), request(f, 0)).answerId();
        var executor = Executors.newSingleThreadExecutor();
        try {
            rr().executeWithoutResult(status -> {
                Answer cached = entityManager.find(Answer.class, id); assertThat(cached.getScore()).isEqualTo(1.0);
                try { executor.submit(() -> jdbcTemplate.update("UPDATE answers SET score=0.25,is_correct=0 WHERE id=UUID_TO_BIN(?)", id.toString())).get(15, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                var result = service.completeAttempt(f.owner(), f.id());
                assertThat(result.totalScore()).isEqualTo(.25); assertThat(result.correctCount()).isZero();
                assertThat(result.answers()).singleElement().satisfies(answer -> assertThat(answer.score()).isEqualTo(.25));
            });
            assertProjectionMatches(f.id());
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void ownUncommittedAnswerIsIncludedAfterFlushInTheSameOldSnapshot() {
        Fixture f = fixture(2);
        rr().executeWithoutResult(status -> {
            Attempt caller = entityManager.find(Attempt.class, f.id()); assertThat(caller.getAnswers()).isEmpty();
            service.submitAnswer(f.owner(), f.id(), request(f, 0));
            assertThat(service.completeAttempt(f.owner(), f.id()).totalScore()).isEqualTo(1.0);
            assertThat(entityManager.contains(caller)).isTrue();
        });
        assertProjectionMatches(f.id());
    }

    @Test void cachedOrphanRemovingCollectionDoesNotDeleteAnAnswerReboundByAnotherTransaction() throws Exception {
        Fixture first = fixture(1), second = sibling(first);
        UUID answer = service.submitAnswer(first.owner(), first.id(), request(first, 0)).answerId();
        var executor = Executors.newSingleThreadExecutor();
        try {
            rr().executeWithoutResult(status -> {
                Attempt cached = entityManager.find(Attempt.class, first.id()); assertThat(cached.getAnswers()).hasSize(1);
                try { executor.submit(() -> jdbcTemplate.update("UPDATE answers SET attempt_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", second.id().toString(), answer.toString())).get(15, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                var result = service.completeAttempt(first.owner(), first.id());
                assertThat(result.totalScore()).isZero(); assertThat(result.answers()).isEmpty();
            });
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
        assertProjectionMatches(first.id()); assertProjectionMatches(second.id());
        assertThat(service.completeAttempt(second.owner(), second.id()).totalScore()).isEqualTo(1.0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE id=UUID_TO_BIN(?)", Integer.class, answer.toString())).isEqualTo(1);
    }

    @Test void failedBatchRollsBackRealInsertProjectionAndCallerChangesTogether() {
        Fixture f = fixture(3); service.submitAnswer(f.owner(), f.id(), request(f, 0));
        String original = jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner());
        var reached = new AtomicBoolean();
        doAnswer(call -> {
            Answer answer = call.getArgument(0);
            if (answer.getQuestion().getId().equals(f.questions().get(1))) {
                entityManager.flush();
                assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, f.id().toString())).isEqualTo(2);
                assertThat(projectionCount(f.id())).isEqualTo(2); reached.set(true);
            }
            return call.callRealMethod();
        }).when(answerMapper).toDto(any(Answer.class));
        try {
            assertThatThrownBy(() -> rr().executeWithoutResult(status -> {
                entityManager.find(Attempt.class, f.id()).getUser().setEmail("rollback@example.invalid");
                service.submitBatch(f.owner(), f.id(), new BatchAnswerSubmissionRequest(List.of(request(f, 1), request(f, 0))));
            })).isInstanceOf(IllegalStateException.class).hasMessageContaining("Already answered");
        } finally { reset(answerMapper); }
        assertThat(reached).isTrue(); assertThat(projectionCount(f.id())).isEqualTo(1); assertProjectionMatches(f.id());
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner())).isEqualTo(original);
    }

    @Test void callerFailureAfterSuccessfulCommandsRollsBackTheirProjection() {
        Fixture f = fixture(2); var reached = new AtomicBoolean();
        String original = jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner());
        assertThatThrownBy(() -> rr().executeWithoutResult(status -> {
            Attempt caller = entityManager.find(Attempt.class, f.id()); caller.getUser().setEmail("rollback@example.invalid");
            service.submitAnswer(f.owner(), f.id(), request(f, 0)); entityManager.flush();
            assertThat(projectionCount(f.id())).isEqualTo(1); reached.set(true);
            throw new IllegalStateException("caller fault");
        })).isInstanceOf(IllegalStateException.class).hasMessage("caller fault");
        assertThat(reached).isTrue(); assertThat(projectionCount(f.id())).isZero(); assertProjectionMatches(f.id());
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner())).isEqualTo(original);
    }

    @Test void triggerFailureRejectsInsertAndRollsBackCallerThenRetrySucceeds() {
        Fixture f = fixture(1);
        String original = jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner());
        jdbcTemplate.execute("CREATE TRIGGER projection_test_fault BEFORE UPDATE ON attempts FOR EACH ROW BEGIN IF NEW.id=UUID_TO_BIN('"+f.id()+"') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='projection-maintenance-fault'; END IF; END");
        try {
            assertThatThrownBy(() -> rr().executeWithoutResult(status -> {
                entityManager.find(Attempt.class, f.id()).getUser().setEmail("rollback@example.invalid");
                service.submitAnswer(f.owner(), f.id(), request(f, 0)); entityManager.flush();
            })).hasStackTraceContaining("projection-maintenance-fault");
        } finally { jdbcTemplate.execute("DROP TRIGGER projection_test_fault"); }
        assertThat(projectionCount(f.id())).isZero(); assertProjectionMatches(f.id());
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner())).isEqualTo(original);
        service.submitAnswer(f.owner(), f.id(), request(f, 0)); assertProjectionMatches(f.id());
    }

    @Test void copiedFieldUpdatesIdentityRebindDeleteAndRollbackKeepProjectionExact() {
        Fixture first = fixture(2), second = sibling(first);
        UUID id = service.submitAnswer(first.owner(), first.id(), request(first, 0)).answerId();
        jdbcTemplate.update("UPDATE answers SET score=.75,is_correct=0,question_id=UUID_TO_BIN(?),answered_at='2026-01-02 03:04:05' WHERE id=UUID_TO_BIN(?)", first.questions().get(1).toString(), id.toString());
        assertProjectionMatches(first.id());
        String before = projection(first.id());
        jdbcTemplate.update("UPDATE answers SET response='{\"answer\":false}' WHERE id=UUID_TO_BIN(?)", id.toString());
        assertThat(projection(first.id())).isEqualTo(before);
        rr().executeWithoutResult(status -> {
            jdbcTemplate.update("UPDATE answers SET attempt_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", second.id().toString(), id.toString());
            assertThat(projectionCount(first.id())).isZero(); assertThat(projectionCount(second.id())).isEqualTo(1); status.setRollbackOnly();
        });
        assertProjectionMatches(first.id()); assertProjectionMatches(second.id());
        UUID replacement = UUID.randomUUID();
        jdbcTemplate.update("UPDATE answers SET attempt_id=UUID_TO_BIN(?),id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", second.id().toString(), replacement.toString(), id.toString());
        assertProjectionMatches(first.id()); assertProjectionMatches(second.id());
        jdbcTemplate.update("DELETE FROM answers WHERE id=UUID_TO_BIN(?)", replacement.toString());
        assertProjectionMatches(second.id()); assertThat(projectionCount(second.id())).isZero();
    }

    @Test void mismatchedPreviousFactRejectsSourceMutationWithoutLeakingProjection() {
        Fixture f = fixture(1); UUID id = service.submitAnswer(f.owner(), f.id(), request(f, 0)).answerId();
        String marker = "projection-sensitive-" + UUID.randomUUID();
        String path = "$.answers.\"" + id + "\".score";
        jdbcTemplate.update("UPDATE attempts SET accepted_answer_projection=JSON_SET(accepted_answer_projection,?,?) WHERE id=UUID_TO_BIN(?)", path, marker, f.id().toString());
        Throwable failure = catchThrowable(() -> jdbcTemplate.update("UPDATE answers SET score=.5 WHERE id=UUID_TO_BIN(?)", id.toString()));
        assertThat(failure).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("Accepted answer projection is unavailable");
        var trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
        assertThat(trace.toString()).doesNotContain(marker);
        assertThat(jdbcTemplate.queryForObject("SELECT score FROM answers WHERE id=UUID_TO_BIN(?)", Double.class, id.toString())).isEqualTo(1.0);
        jdbcTemplate.update("UPDATE attempts SET accepted_answer_projection=JSON_SET(accepted_answer_projection,?,1.0) WHERE id=UUID_TO_BIN(?)", path, f.id().toString());
        jdbcTemplate.update("UPDATE answers SET score=.5 WHERE id=UUID_TO_BIN(?)", id.toString());
        assertProjectionMatches(f.id());
    }

    @Test void projectionTimeIsIndependentOfWriterSessionTimezone() {
        Fixture f = fixture(1); UUID answer = service.submitAnswer(f.owner(), f.id(), request(f, 0)).answerId();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET time_zone='+05:30'");
            try { jdbcTemplate.update("UPDATE answers SET answered_at=FROM_UNIXTIME(1767323045) WHERE id=UUID_TO_BIN(?)", answer.toString()); }
            finally { jdbcTemplate.execute("SET time_zone='+00:00'"); }
        });
        var facts = new JsonCurrentAnswerRepository(json).readCurrent(f.id(), projection(f.id()));
        assertThat(facts.get(0).answeredAt()).isEqualTo(Instant.ofEpochSecond(1767323045)); assertProjectionMatches(f.id());
    }

    @Test void invalidProjectionErrorsAreSafeAndOwnershipPrecedesDecoding(CapturedOutput output) throws Exception {
        Fixture f = fixture(1); String marker = "projection-sensitive-" + UUID.randomUUID();
        jdbcTemplate.update("UPDATE attempts SET accepted_answer_projection=JSON_OBJECT('version',1,'answers',?) WHERE id=UUID_TO_BIN(?)", marker, f.id().toString());
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", f.id())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", f.id()).with(user("another-owner"))).andExpect(status().isForbidden());
        var response = mockMvc.perform(post("/api/v1/attempts/{id}/complete", f.id()).with(user(f.owner())))
                .andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(marker); assertThat(output.getAll()).doesNotContain(marker);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM attempts WHERE id=UUID_TO_BIN(?)", String.class, f.id().toString())).isEqualTo("IN_PROGRESS");
    }

    @Test void attemptAndQuizCascadeDeletionRemoveProjectionWithParent() {
        Fixture direct = fixture(1), quiz = fixture(1);
        service.submitAnswer(direct.owner(), direct.id(), request(direct, 0)); service.submitAnswer(quiz.owner(), quiz.id(), request(quiz, 0));
        service.deleteAttempt(direct.owner(), direct.id());
        jdbcTemplate.update("DELETE FROM quizzes WHERE quiz_id=UUID_TO_BIN(?)", quiz.quiz().toString());
        for (Fixture f : List.of(direct, quiz)) {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempts WHERE id=UUID_TO_BIN(?)", Integer.class, f.id().toString())).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, f.id().toString())).isZero();
        }
    }

    @Test void deletionWaitsForParentBeforeTakingAnyChildLock() throws Exception {
        Fixture f = fixture(1); service.submitAnswer(f.owner(), f.id(), request(f, 0));
        var executor = Executors.newSingleThreadExecutor();
        var started = new CountDownLatch(1);
        var deletion = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
        try {
            rr().executeWithoutResult(status -> {
                assertThat(service.completeAttempt(f.owner(), f.id()).totalScore()).isEqualTo(1.0);
                deletion.set(executor.submit(() -> { started.countDown(); service.deleteAttempt(f.owner(), f.id()); }));
                try {
                    assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); boolean waiting = false;
                    var admin = new JdbcTemplate(new DriverManagerDataSource(url(), ADMIN_USER, ADMIN_PASSWORD));
                    while (System.nanoTime() < deadline) {
                        waiting = admin.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='attempts'", Integer.class, CATALOG) > 0;
                        if (waiting) break; Thread.sleep(20);
                    }
                    assertThat(waiting).isTrue(); assertThat(answerLocks()).isEmpty();
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                status.setRollbackOnly();
            });
            deletion.get().get(15, TimeUnit.SECONDS);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM attempts WHERE id=UUID_TO_BIN(?)", Integer.class, f.id().toString())).isZero();
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void deletionDoesNotFollowCachedCollectionToAnotherParticipantsReboundAnswer() throws Exception {
        Fixture first = fixture(1), second = sibling(first);
        UUID answer = service.submitAnswer(first.owner(), first.id(), request(first, 0)).answerId();
        var executor = Executors.newSingleThreadExecutor(); var childStayedManaged = new AtomicBoolean();
        String email = "after-delete-" + UUID.randomUUID() + "@example.invalid";
        try {
            rr().executeWithoutResult(status -> {
                Attempt cached = entityManager.find(Attempt.class, first.id());
                Answer cachedChild = cached.getAnswers().get(0); User caller = cached.getUser();
                try { executor.submit(() -> jdbcTemplate.update("UPDATE answers SET attempt_id=UUID_TO_BIN(?) WHERE id=UUID_TO_BIN(?)", second.id().toString(), answer.toString())).get(15, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new AssertionError(failure); }
                service.deleteAttempt(first.owner(), first.id());
                caller.setEmail(email); entityManager.flush();
                childStayedManaged.set(entityManager.contains(cachedChild));
            });
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue(); }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE id=UUID_TO_BIN(?)", Integer.class, answer.toString()))
                .as("deleting the former parent must preserve the other participant's answer").isEqualTo(1);
        assertThat(childStayedManaged).isTrue(); assertProjectionMatches(second.id());
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, first.owner())).isEqualTo(email);
    }

    @Test void deletionEvictsOnlyDeletedRowsAndPreservesLaterCallerChanges() {
        Fixture f = fixture(1);
        UUID answer = service.submitAnswer(f.owner(), f.id(), request(f, 0)).answerId();
        String email = "after-delete-" + UUID.randomUUID() + "@example.invalid";
        rr().executeWithoutResult(status -> {
            Attempt cached = entityManager.find(Attempt.class, f.id());
            Answer child = cached.getAnswers().get(0); User caller = cached.getUser();
            service.deleteAttempt(f.owner(), f.id());
            assertThat(entityManager.contains(cached)).isFalse();
            assertThat(entityManager.contains(child)).isFalse();
            assertThat(entityManager.contains(caller)).isTrue();
            assertThat(entityManager.find(Attempt.class, f.id())).isNull();
            assertThat(entityManager.find(Answer.class, answer)).isNull();
            caller.setEmail(email); entityManager.flush();
        });
        assertThat(jdbcTemplate.queryForObject("SELECT email FROM users WHERE username=?", String.class, f.owner())).isEqualTo(email);
    }

    @Test void original512RowBoundaryFixturePermitsAdjacentWritesAndHttpAnswerWhileCompletionIsOpen() throws Exception {
        Fixture source = fixture(512), adjacent = fixture(512), http = fixture(1);
        List<UUID> original;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/legacy-answer-848-pk-boundary-ids.txt"));
             var lines = new java.io.BufferedReader(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8))) {
            original = lines.lines().filter(line -> !line.startsWith("#") && !line.isBlank()).map(UUID::fromString).toList();
        }
        assertThat(original).hasSize(512);
        insertAdjacent(source, original); // Historical source rows and both target fixtures commit before the reader starts.
        var neighboring = adjacentIds(source.id()); var reached = new AtomicBoolean();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (source.id().equals(call.getArgument(0))) {
                reached.set(true); assertThat(answerLocks()).isEmpty();
                var executor = Executors.newSingleThreadExecutor();
                try {
                    executor.submit(() -> {
                        assertThat(httpAnswer(http, "REPEATABLE_READ")).isEqualTo(200);
                        insertAdjacent(adjacent, neighboring);
                    }).get(20, TimeUnit.SECONDS);
                } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); }
                assertThat(committedProjectionCount(adjacent.id())).isEqualTo(512); assertThat(committedProjectionCount(http.id())).isEqualTo(1);
            }
            return result;
        }).when(currentAnswers).readCurrent(any(), anyString());
        var sql = new ArrayList<String>(); SQL.set(sql);
        try {
            rr().executeWithoutResult(status -> {
                try {
                    mockMvc.perform(post("/api/v1/attempts/{id}/complete", source.id()).with(user(source.owner())))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.totalScore").value(512.0))
                            .andExpect(jsonPath("$.answers.length()").value(512));
                } catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(reached).isTrue(); assertThat(committedProjectionCount(adjacent.id())).isEqualTo(512);
            });
        } finally { SQL.remove(); reset(currentAnswers); }
        assertNoAnswerReads(sql); assertThat(selects(sql)).isBetween(1L, 10L);
        for (Fixture f : List.of(source, adjacent, http)) assertProjectionMatches(f.id());
    }

    @ParameterizedTest @ValueSource(ints = {1, 129, 512, 2048})
    void batchAndCompletionHaveBoundedReadsAndPermitAdjacentIndependentWrites(int size) throws Exception {
        Fixture first = fixture(size), second = fixture(size), http = fixture(1); // Commit all fixtures before the reader starts.
        long started = System.nanoTime(); var batchSql = new ArrayList<String>(); SQL.set(batchSql);
        try { service.submitBatch(first.owner(), first.id(), new BatchAnswerSubmissionRequest(
                java.util.stream.IntStream.range(0, size).mapToObj(index -> request(first, index)).toList())); }
        finally { SQL.remove(); }
        long submitted = System.nanoTime(); assertProjectionMatches(first.id());
        assertThat(selects(batchSql)).isBetween(1L, 12L + (size + 255) / 256);
        assertNoAnswerReads(batchSql);
        var neighboring = adjacentIds(first.id()); var reached = new AtomicBoolean();
        var peerNanos = new java.util.concurrent.atomic.AtomicLong();
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (first.id().equals(call.getArgument(0))) {
                reached.set(true); assertThat((List<?>) result).hasSize(size);
                assertThat(answerLocks()).isEmpty();
                var executor = Executors.newSingleThreadExecutor();
                try {
                    executor.submit(() -> {
                        long peerStarted = System.nanoTime();
                        assertThat(httpAnswer(http, "REPEATABLE_READ")).isEqualTo(200);
                        insertAdjacentBatch(second, neighboring);
                        peerNanos.set(System.nanoTime() - peerStarted);
                    }).get(20, TimeUnit.SECONDS);
                } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue(); }
                assertThat(committedProjectionCount(second.id())).isEqualTo(size);
            }
            return result;
        }).when(currentAnswers).readCurrent(any(), anyString());
        var completionSql = new ArrayList<String>(); SQL.set(completionSql);
        long completing = System.nanoTime();
        try {
            rr().executeWithoutResult(status -> {
                assertThat(service.completeAttempt(first.owner(), first.id()).totalScore()).isEqualTo((double) size);
                assertThat(reached).isTrue();
                assertThat(committedProjectionCount(second.id())).isEqualTo(size); // Fresh observer while reader remains open.
            });
        } finally { SQL.remove(); reset(currentAnswers); }
        long readerNanos = System.nanoTime() - completing - peerNanos.get();
        assertThat(selects(completionSql)).isBetween(1L, 10L); assertNoAnswerReads(completionSql);
        assertProjectionMatches(first.id()); assertProjectionMatches(second.id());
        int bytes = jdbcTemplate.queryForObject("SELECT JSON_STORAGE_SIZE(accepted_answer_projection) FROM attempts WHERE id=UUID_TO_BIN(?)", Integer.class, first.id().toString());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        System.out.printf("PROJECTION_COST answers=%d batchMs=%d peerWriteMs=%d readerMs=%d totalMs=%d batchSelects=%d completeSelects=%d jsonBytes=%d%n", size, (submitted-started)/1_000_000, peerNanos.get()/1_000_000, readerNanos/1_000_000, elapsed.toMillis(), selects(batchSql), selects(completionSql), bytes);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(60));
    }

    private void insertAdjacent(Fixture target, List<UUID> ids) {
        var source = new DriverManagerDataSource(url() + "&sessionVariables=innodb_lock_wait_timeout=3", ADMIN_USER, ADMIN_PASSWORD);
        var inserts = new JdbcTemplate(source);
        new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            for (int index = 0; index < ids.size(); index++)
                inserts.update("INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'{\"answer\":true}',1,1,CURRENT_TIMESTAMP)", ids.get(index).toString(), target.id().toString(), target.questions().get(index).toString());
        });
    }

    private void insertAdjacentBatch(Fixture target, List<UUID> ids) {
        long started = System.nanoTime();
        var source = new DriverManagerDataSource(url()
                + "&sessionVariables=innodb_lock_wait_timeout=3&rewriteBatchedStatements=true", ADMIN_USER, ADMIN_PASSWORD);
        var inserts = new JdbcTemplate(source);
        // Keep every adjacent key and every production trigger; batch only the peer's SQL transport.
        // The frozen boundary regression above still exercises individual INSERT statements.
        new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            var rows = new ArrayList<Object[]>(ids.size());
            for (int index = 0; index < ids.size(); index++)
                rows.add(new Object[]{ids.get(index).toString(), target.id().toString(), target.questions().get(index).toString()});
            inserts.batchUpdate("INSERT INTO answers(id,attempt_id,question_id,response,is_correct,score,answered_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),'{\"answer\":true}',1,1,CURRENT_TIMESTAMP)", rows);
        });
        System.out.printf("PROJECTION_PEER_COMMITTED answers=%d elapsedMs=%d%n", ids.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    private List<UUID> adjacentIds(UUID attempt) {
        var existing = jdbcTemplate.queryForList("SELECT BIN_TO_UUID(id) FROM answers ORDER BY id", String.class);
        var used = new HashSet<>(existing); var result = new ArrayList<UUID>();
        for (String id : jdbcTemplate.queryForList("SELECT BIN_TO_UUID(id) FROM answers WHERE attempt_id=UUID_TO_BIN(?) ORDER BY id", String.class, attempt.toString())) {
            BigInteger value = new BigInteger(id.replace("-", ""), 16); String encoded;
            do {
                value = value.add(BigInteger.ONE); String hex = String.format("%032x", value);
                encoded = hex.substring(0,8)+"-"+hex.substring(8,12)+"-"+hex.substring(12,16)+"-"+hex.substring(16,20)+"-"+hex.substring(20);
            } while (used.contains(encoded));
            used.add(encoded); result.add(UUID.fromString(encoded));
        }
        return result;
    }

    private static long selects(List<String> sql) { return sql.stream().filter(statement -> statement.toLowerCase(Locale.ROOT).startsWith("select")).count(); }
    private static void assertNoAnswerReads(List<String> sql) {
        assertThat(sql.stream().filter(statement -> statement.toLowerCase(Locale.ROOT).startsWith("select")
                && statement.toLowerCase(Locale.ROOT).matches("(?s).*\\b(from|join) answers\\b.*")).toList()).isEmpty();
    }
    private List<Map<String, Object>> answerLocks() {
        return new JdbcTemplate(new DriverManagerDataSource(url(), ADMIN_USER, ADMIN_PASSWORD)).queryForList(
                "SELECT ENGINE_TRANSACTION_ID,INDEX_NAME,LOCK_MODE,LOCK_DATA FROM performance_schema.data_locks WHERE OBJECT_SCHEMA=? AND OBJECT_NAME='answers'", CATALOG);
    }
    private String projection(UUID attempt) { return jdbcTemplate.queryForObject("SELECT accepted_answer_projection FROM attempts WHERE id=UUID_TO_BIN(?)", String.class, attempt.toString()); }
    private int projectionCount(UUID attempt) { return jdbcTemplate.queryForObject("SELECT JSON_LENGTH(accepted_answer_projection->'$.answers') FROM attempts WHERE id=UUID_TO_BIN(?)", Integer.class, attempt.toString()); }
    private int committedProjectionCount(UUID attempt) {
        // This observer must not join the reader's deliberately old RR snapshot.
        return new JdbcTemplate(new DriverManagerDataSource(url(), ADMIN_USER, ADMIN_PASSWORD)).queryForObject(
                "SELECT JSON_LENGTH(accepted_answer_projection->'$.answers') FROM attempts WHERE id=UUID_TO_BIN(?)", Integer.class, attempt.toString());
    }
    private void assertProjectionMatches(UUID attempt) {
        var projected = new JsonCurrentAnswerRepository(json).readCurrent(attempt, projection(attempt));
        var source = jdbcTemplate.query("SELECT BIN_TO_UUID(id),BIN_TO_UUID(question_id),is_correct,score,answered_at FROM answers WHERE attempt_id=UUID_TO_BIN(?)", (rows,index) -> {
            boolean correct = rows.getBoolean(3); Boolean correctness = rows.wasNull() ? null : correct;
            double score = rows.getDouble(4); Double storedScore = rows.wasNull() ? null : score;
            return new AcceptedAnswerFact(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)), correctness, storedScore, rows.getTimestamp(5).toInstant());
        }, attempt.toString());
        assertThat(projected).containsExactlyInAnyOrderElementsOf(source);
    }
    private TransactionTemplate rr() { var tx = new TransactionTemplate(transactions); tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); return tx; }
    private int httpAnswer(Fixture f, String isolation) {
        var tx = new TransactionTemplate(transactions); tx.setIsolationLevelName("ISOLATION_" + isolation);
        return tx.execute(status -> {
            try { return mockMvc.perform(post("/api/v1/attempts/{id}/answers", f.id()).with(user(f.owner()))
                    .contentType("application/json").content(json.writeValueAsBytes(request(f, 0)))).andReturn().getResponse().getStatus(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }
    private Fixture fixture(int count) { return fixture(count, AttemptMode.ALL_AT_ONCE, QuestionType.TRUE_FALSE); }
    private Fixture fixture(int count, AttemptMode mode, QuestionType type) {
        return new TransactionTemplate(transactions).execute(status -> {
            User owner = owner();
            var category = new Category(); category.setName("projection-" + UUID.randomUUID()); entityManager.persist(category);
            var quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category); quiz.setTitle("Projection fixture");
            quiz.setStatus(QuizStatus.PUBLISHED); quiz.setVisibility(Visibility.PUBLIC); quiz.setDifficulty(Difficulty.EASY);
            quiz.setEstimatedTime(1); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(mode == AttemptMode.TIMED);
            quiz.setTimerDuration(1); quiz.setIsDeleted(false); entityManager.persist(quiz);
            var questions = new ArrayList<UUID>();
            for (int index = 0; index < count; index++) {
                var question = new Question(); question.setType(type); question.setDifficulty(Difficulty.EASY);
                question.setQuestionText("Synthetic question " + index); question.setExplanation("Synthetic explanation");
                question.setContent(QuestionContracts.example(type, QuestionContracts.View.AUTHOR).toString());
                question.getQuizId().add(quiz); entityManager.persist(question); questions.add(question.getId());
            }
            if (mode == AttemptMode.ONE_BY_ONE) questions.sort(Comparator.comparing(UUID::toString));
            var attempt = new Attempt(); attempt.setUser(owner); attempt.setQuiz(quiz); attempt.setMode(mode);
            attempt.setStatus(AttemptStatus.IN_PROGRESS); entityManager.persist(attempt); entityManager.flush();
            return new Fixture(owner.getUsername(), attempt.getId(), quiz.getId(), questions, type);
        });
    }
    private User owner() {
        var owner = new User(); owner.setUsername("projection-" + UUID.randomUUID());
        owner.setEmail(owner.getUsername() + "@example.invalid"); owner.setHashedPassword("synthetic-unused");
        owner.setActive(true); entityManager.persist(owner); return owner;
    }
    private Fixture sibling(Fixture first) {
        return new TransactionTemplate(transactions).execute(status -> {
            User owner = owner(); var attempt = new Attempt(); attempt.setUser(owner);
            attempt.setQuiz(entityManager.getReference(Quiz.class, first.quiz())); attempt.setMode(AttemptMode.ALL_AT_ONCE);
            attempt.setStatus(AttemptStatus.IN_PROGRESS); entityManager.persist(attempt); entityManager.flush();
            return new Fixture(owner.getUsername(), attempt.getId(), first.quiz(), first.questions(), first.type());
        });
    }
    private AnswerSubmissionRequest request(Fixture f, int index) {
        return new AnswerSubmissionRequest(f.questions().get(index), QuestionContracts.example(f.type(), QuestionContracts.View.RESPONSE), true, true, true);
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for independent attempts"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private record Fixture(String owner, UUID id, UUID quiz, List<UUID> questions, QuestionType type) { }
}
