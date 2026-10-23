package uk.gegc.quizmaker.features.attempt.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.BaseIntegrationTest;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.event.AttemptCompletedEvent;
import uk.gegc.quizmaker.features.attempt.domain.model.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptRepository;
import uk.gegc.quizmaker.features.attempt.infra.mapping.AttemptMapper;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.*;
import uk.gegc.quizmaker.features.question.infra.mapping.AnswerMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.application.ShareLinkService;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateShareLinkRequest;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.time.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Commands run without an outer test transaction; every assertion reads newly committed state. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(LegacyAttemptTimeoutMySqlIntegrationTest.Config.class)
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class LegacyAttemptTimeoutMySqlIntegrationTest extends BaseIntegrationTest {
    private static final Instant START = Instant.parse("2026-01-02T12:00:00Z");
    private static final Instant DEADLINE = START.plusSeconds(60);
    @Autowired AttemptService service;
    @Autowired AttemptRepository attempts;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired CompletionEvents completions;
    @Autowired SqlGate sqlGate;
    @Autowired DataSource dataSource;
    @Autowired ShareLinkService links;
    @MockitoBean(name = "clock") Clock clock;
    @MockitoSpyBean AnswerMapper answerMapper;
    @MockitoSpyBean AttemptMapper attemptMapper;
    private final AtomicReference<Instant> now = new AtomicReference<>();

    @BeforeEach
    void controlTime() {
        now.set(START.plusSeconds(10));
        when(clock.instant()).thenAnswer(invocation -> now.get());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        completions.ids.clear();
        sqlGate.secondQuery.set(null);
    }

    @Test
    void lateAnswerCommitsAbandonedBeforeReturning422AndCannotBeCompletedLater() throws Exception {
        Fixture f = fixture(AttemptMode.TIMED, true, 2);
        now.set(DEADLINE.plusNanos(1000));
        mockMvc.perform(post("/api/v1/attempts/{id}/answers", f.attemptId())
                        .with(user(f.owner())).contentType("application/json").content(json.writeValueAsBytes(request(f, 0))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Illegal State"))
                .andExpect(jsonPath("$.detail").value("Attempt has timed out"))
                .andExpect(jsonPath("$.instance").value("/api/v1/attempts/" + f.attemptId() + "/answers"));
        State expired = state(f);
        assertThat(expired.status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(expired.completedAt()).isEqualTo(now.get());
        assertThat(expired.score()).isNull();
        assertThat(expired.answers()).isZero();
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", f.attemptId()).with(user(f.owner())))
                .andExpect(status().isUnprocessableEntity());
        assertThat(state(f)).isEqualTo(expired);
        assertThat(completions.ids).isEmpty();
    }

    @Test
    void directLateCompletionAndNewServiceInstanceKeepTheSameTerminalFacts() throws Exception {
        Fixture f = fixture(AttemptMode.TIMED, true, 1);
        now.set(DEADLINE.plusSeconds(1));
        mockMvc.perform(post("/api/v1/attempts/{id}/complete", f.attemptId()).with(user(f.owner())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Attempt has timed out"));
        State expired = state(f);
        assertThat(expired.status()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(expired.completedAt()).isEqualTo(now.get());
        assertThat(expired.score()).isNull();
        now.set(DEADLINE.plusSeconds(600));
        assertTerminalAfterRestart(f, expired);
        assertThat(state(f)).isEqualTo(expired);
        assertThat(completions.ids).isEmpty();
    }

    @Test
    void sharedAttemptPreservesImmediateFeedbackButCannotCompleteAfterExpiry() throws Exception {
        Fixture source = fixture(AttemptMode.TIMED, true, 2);
        UUID[] owners = tx(() -> {
            Attempt a = attempts.findById(source.attemptId()).orElseThrow();
            return new UUID[]{a.getQuiz().getId(), a.getUser().getId()};
        });
        var link = links.createShareLink(owners[0], owners[1],
                new CreateShareLinkRequest(ShareLinkScope.QUIZ_ATTEMPT_START, null, false));
        UUID id = service.startAnonymousAttempt(owners[0], link.link().id(), AttemptMode.TIMED).attemptId();
        tx(() -> jdbcTemplate.update("UPDATE attempts SET started_at=? WHERE id=UUID_TO_BIN(?)",
                java.sql.Timestamp.from(START), id.toString()));
        Fixture f = new Fixture("anonymous", id, source.questions());
        var cookie = new jakarta.servlet.http.Cookie("share_token", link.token());
        String path = "/api/v1/quizzes/shared/attempts/" + id;
        mockMvc.perform(post(path + "/answers").cookie(cookie).contentType("application/json")
                        .content(json.writeValueAsBytes(request(f, 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isCorrect").value(true))
                .andExpect(jsonPath("$.explanation").value("Accepted-answer feedback"));
        now.set(DEADLINE.plusSeconds(1));
        mockMvc.perform(post(path + "/answers").cookie(cookie).contentType("application/json")
                        .content(json.writeValueAsBytes(request(f, 1))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Attempt has timed out"));
        State expired = state(f);
        assertThat(expired).isEqualTo(new State(AttemptStatus.ABANDONED, now.get(), null, 1));
        mockMvc.perform(post(path + "/complete").cookie(cookie)).andExpect(status().isUnprocessableEntity());
        assertThat(state(f)).isEqualTo(expired);
        assertThat(completions.ids).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"-1000,false", "0,false", "1000,true"})
    void answerAndCompletePreserveTheStrictAfterBoundary(long nanoseconds, boolean expired) {
        now.set(DEADLINE.plusNanos(nanoseconds));
        Fixture answer = fixture(AttemptMode.TIMED, true, 1);
        Fixture complete = fixture(AttemptMode.TIMED, true, 1);
        if (expired) {
            assertTimeout(() -> service.submitAnswer(answer.owner(), answer.attemptId(), request(answer, 0)));
            assertTimeout(() -> service.completeAttempt(complete.owner(), complete.attemptId()));
            assertThat(state(answer).status()).isEqualTo(AttemptStatus.ABANDONED);
            assertThat(state(complete).status()).isEqualTo(AttemptStatus.ABANDONED);
            assertThat(completions.ids).isEmpty();
        } else {
            assertThat(service.submitAnswer(answer.owner(), answer.attemptId(), request(answer, 0)).isCorrect()).isTrue();
            assertThat(service.completeAttempt(complete.owner(), complete.attemptId()).totalScore()).isZero();
            assertThat(state(answer).status()).isEqualTo(AttemptStatus.IN_PROGRESS);
            assertThat(state(complete).status()).isEqualTo(AttemptStatus.COMPLETED);
            assertThat(completions.ids).containsExactly(complete.attemptId());
        }
    }

    @ParameterizedTest
    @EnumSource(AttemptMode.class)
    void disabledTimerNeverExpiresAndRetainsImmediateFeedback(AttemptMode mode) {
        Fixture f = fixture(mode, false, 1); // Stored duration remains one minute while disabled.
        now.set(DEADLINE.plusSeconds(3600));
        var accepted = service.submitAnswer(f.owner(), f.attemptId(), request(f, 0));
        assertThat(accepted.isCorrect()).isTrue();
        assertThat(accepted.score()).isEqualTo(1.0);
        assertThat(accepted.correctAnswer()).isEqualTo(json.createObjectNode().put("answer", true));
        assertThat(accepted.explanation()).isEqualTo("Accepted-answer feedback");
        assertThat(service.completeAttempt(f.owner(), f.attemptId()).totalScore()).isEqualTo(1.0);
        assertThat(state(f).status()).isEqualTo(AttemptStatus.COMPLETED);
        assertThat(completions.ids).containsExactly(f.attemptId());
    }

    @ParameterizedTest
    @EnumSource(value = AttemptMode.class, names = {"ONE_BY_ONE", "ALL_AT_ONCE"})
    void enabledQuizTimerDoesNotMakeOtherModesTimed(AttemptMode mode) {
        Fixture f = fixture(mode, true, 1);
        now.set(DEADLINE.plusSeconds(3600));
        assertThat(service.submitAnswer(f.owner(), f.attemptId(), request(f, 0)).isCorrect()).isTrue();
        assertThat(service.completeAttempt(f.owner(), f.attemptId()).totalScore()).isEqualTo(1.0);
    }

    @Test
    void wrongOwnerAndUnauthenticatedRequestsCannotExpireTheAttempt() throws Exception {
        Fixture f = fixture(AttemptMode.TIMED, true, 1);
        now.set(DEADLINE.plusSeconds(1));
        for (String suffix : List.of("/answers", "/complete")) {
            var request = post("/api/v1/attempts/" + f.attemptId() + suffix)
                    .contentType("application/json").content(json.writeValueAsBytes(request(f, 0)));
            mockMvc.perform(request).andExpect(status().isUnauthorized());
            mockMvc.perform(request.with(user("another-owner"))).andExpect(status().isForbidden());
        }
        assertThatThrownBy(() -> service.completeAttempt("another-owner", f.attemptId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(state(f)).isEqualTo(new State(AttemptStatus.IN_PROGRESS, null, null, 0));
        assertThat(completions.ids).isEmpty();
    }

    @Test
    void unrelatedFailureAfterRealAnswerInsertStillRollsBack() {
        Fixture f = fixture(AttemptMode.TIMED, true, 1);
        AtomicBoolean reached = new AtomicBoolean();
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(answerCount(f)).isEqualTo(1);
            reached.set(true);
            throw new IllegalStateException("Injected failure after answer insert");
        }).when(answerMapper).toDto(any(Answer.class));
        assertThatThrownBy(() -> service.submitAnswer(f.owner(), f.attemptId(), request(f, 0)))
                .hasMessage("Injected failure after answer insert");
        assertThat(reached).isTrue();
        assertThat(state(f)).isEqualTo(new State(AttemptStatus.IN_PROGRESS, null, null, 0));
    }

    @Test
    void unrelatedFailureAfterCompletionFlushRollsBackStateScoreAndCompletionEvent() {
        Fixture f = fixture(AttemptMode.TIMED, true, 1);
        service.submitAnswer(f.owner(), f.attemptId(), request(f, 0));
        AtomicBoolean reached = new AtomicBoolean();
        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM attempts WHERE id=UUID_TO_BIN(?)",
                    String.class, f.attemptId().toString())).isEqualTo("COMPLETED");
            reached.set(true);
            throw new IllegalStateException("Injected failure after completion flush");
        }).when(attemptMapper).toResultDto(any(), anyLong(), anyInt());
        assertThatThrownBy(() -> service.completeAttempt(f.owner(), f.attemptId()))
                .hasMessage("Injected failure after completion flush");
        assertThat(reached).isTrue();
        assertThat(state(f)).isEqualTo(new State(AttemptStatus.IN_PROGRESS, null, null, 1));
        assertThat(completions.ids).isEmpty();
    }

    @Test
    void batchRemainsAtomicAndOneByOneKeepsItsOrdering() {
        Fixture batch = fixture(AttemptMode.ALL_AT_ONCE, true, 2);
        var bad = new AnswerSubmissionRequest(batch.questions().get(1), json.createObjectNode(), true, true, true);
        assertThatThrownBy(() -> service.submitBatch(batch.owner(), batch.attemptId(),
                new BatchAnswerSubmissionRequest(List.of(request(batch, 0), bad)))).isInstanceOf(RuntimeException.class);
        assertThat(state(batch).answers()).isZero();
        var accepted = service.submitBatch(batch.owner(), batch.attemptId(),
                new BatchAnswerSubmissionRequest(List.of(request(batch, 0), request(batch, 1))));
        assertThat(accepted).hasSize(2);
        assertThat(service.completeAttempt(batch.owner(), batch.attemptId()).totalScore()).isEqualTo(2.0);
        Fixture sequential = fixture(AttemptMode.ONE_BY_ONE, false, 2);
        assertThatThrownBy(() -> service.submitAnswer(sequential.owner(), sequential.attemptId(), request(sequential, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Expected question");
        assertThat(state(sequential).answers()).isZero();
        assertThat(service.submitAnswer(sequential.owner(), sequential.attemptId(), request(sequential, 0)).nextQuestion()).isNotNull();
        service.submitAnswer(sequential.owner(), sequential.attemptId(), request(sequential, 1));
        assertThat(service.completeAttempt(sequential.owner(), sequential.attemptId()).totalScore()).isEqualTo(2.0);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void answerAndCompletionRaceHasOneTerminalOutcome(boolean answerFirst, boolean firstExpired) throws Exception {
        Fixture f = fixture(AttemptMode.TIMED, true, 1);
        now.set(firstExpired ? DEADLINE.plusNanos(1000) : DEADLINE);
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReachedLockQuery = new CountDownLatch(1);
        sqlGate.secondQuery.set(secondReachedLockQuery);
        AtomicBoolean gated = new AtomicBoolean();
        when(clock.instant()).thenAnswer(invocation -> {
            Instant sampled = now.get();
            if (Thread.currentThread().getName().equals("legacy-time-first") && gated.compareAndSet(false, true)) {
                firstHasLock.countDown();
                await(releaseFirst);
            }
            return sampled;
        });
        ExecutorService first = Executors.newSingleThreadExecutor(r -> new Thread(r, "legacy-time-first"));
        ExecutorService second = Executors.newSingleThreadExecutor(r -> new Thread(r, "legacy-time-second"));
        try {
            Future<Outcome> one = first.submit(() -> command(f, answerFirst));
            assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).as("first command checked time under lock").isTrue();
            Future<Outcome> two = second.submit(() -> command(f, !answerFirst));
            assertThat(secondReachedLockQuery.await(10, TimeUnit.SECONDS)).as("second reached real locking SQL").isTrue();
            now.set(DEADLINE.plusSeconds(1));
            releaseFirst.countDown();
            Outcome a = one.get(10, TimeUnit.SECONDS);
            Outcome b = two.get(10, TimeUnit.SECONDS);
            State result = state(f);
            if (firstExpired) {
                assertThat(a.success()).isFalse();
                assertThat(a.error()).isEqualTo("Attempt has timed out");
                assertThat(b.success()).isFalse();
                assertThat(result).isEqualTo(new State(AttemptStatus.ABANDONED, DEADLINE.plusNanos(1000), null, 0));
                assertThat(completions.ids).isEmpty();
            } else {
                assertThat(a.success()).as("permitted on-time command succeeds").isTrue();
                assertThat(b.success()).isFalse();
                if (answerFirst) {
                    assertThat(result.status()).isEqualTo(AttemptStatus.ABANDONED);
                    assertThat(result.answers()).isEqualTo(1);
                    assertThat(result.score()).isNull();
                    assertThat(completions.ids).isEmpty();
                } else {
                    assertThat(result.status()).isEqualTo(AttemptStatus.COMPLETED);
                    assertThat(result.answers()).isZero();
                    assertThat(result.score()).isZero();
                    assertThat(completions.ids).containsExactly(f.attemptId());
                }
            }
        } finally {
            releaseFirst.countDown();
            first.shutdownNow(); second.shutdownNow();
            assertThat(first.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            sqlGate.secondQuery.set(null);
        }
    }

    @Test
    void concurrentPauseWaitsForExpiryAndCannotRestoreAnActiveState() throws Exception {
        Fixture f = fixture(AttemptMode.TIMED, true, 1);
        now.set(DEADLINE.plusSeconds(1));
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondReachedLockQuery = new CountDownLatch(1);
        sqlGate.secondQuery.set(secondReachedLockQuery);
        when(clock.instant()).thenAnswer(invocation -> {
            firstHasLock.countDown(); await(releaseFirst); return now.get();
        });
        ExecutorService first = Executors.newSingleThreadExecutor(r -> new Thread(r, "legacy-time-first"));
        ExecutorService second = Executors.newSingleThreadExecutor(r -> new Thread(r, "legacy-time-second"));
        try {
            Future<Outcome> expiry = first.submit(() -> command(f, true));
            assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> pause = second.submit(() -> assertThatThrownBy(() -> service.pauseAttempt(f.owner(), f.attemptId()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Can only pause attempts that are in progress"));
            assertThat(secondReachedLockQuery.await(10, TimeUnit.SECONDS)).isTrue();
            releaseFirst.countDown();
            assertThat(expiry.get(10, TimeUnit.SECONDS).error()).isEqualTo("Attempt has timed out");
            pause.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> service.resumeAttempt(f.owner(), f.attemptId()))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Can only resume paused attempts");
            assertThat(state(f)).isEqualTo(new State(AttemptStatus.ABANDONED, now.get(), null, 0));
            assertThat(completions.ids).isEmpty();
        } finally {
            releaseFirst.countDown(); first.shutdownNow(); second.shutdownNow();
            assertThat(first.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            sqlGate.secondQuery.set(null);
        }
    }

    @Test
    void commandRelationshipLoadingIsBoundedAcrossMultipleAttemptsAndAnswers() {
        List<Fixture> small = new ArrayList<>();
        List<Fixture> large = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            small.add(answeredFixture(1));
            large.add(answeredFixture(8));
        }
        // Count this command's SQL, excluding the independent async analytics consumer.
        int smallQueries = countCommandSql(() -> small.forEach(f -> service.completeAttempt(f.owner(), f.attemptId())));
        int largeQueries = countCommandSql(() -> large.forEach(f -> service.completeAttempt(f.owner(), f.attemptId())));
        assertThat(largeQueries).as("three parents with eight answers each vs one answer each")
                .isLessThanOrEqualTo(smallQueries);
        assertThat(largeQueries).isLessThan(100);
        assertThat(large).allSatisfy(f -> assertThat(state(f).score()).isEqualTo(8.0));
    }

    @Test
    void answerSubmissionLoadingIsBoundedWithMultipleParentsAndExistingAnswers() {
        List<Fixture> small = new ArrayList<>();
        List<Fixture> large = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Fixture one = fixture(AttemptMode.TIMED, true, 2);
            Fixture eight = fixture(AttemptMode.TIMED, true, 9);
            service.submitAnswer(one.owner(), one.attemptId(), request(one, 0));
            for (int index = 0; index < 8; index++) service.submitAnswer(eight.owner(), eight.attemptId(), request(eight, index));
            small.add(one); large.add(eight);
        }
        int smallQueries = countCommandSql(() -> small.forEach(f -> service.submitAnswer(f.owner(), f.attemptId(), request(f, 1))));
        int largeQueries = countCommandSql(() -> large.forEach(f -> service.submitAnswer(f.owner(), f.attemptId(), request(f, 8))));
        assertThat(largeQueries).isLessThanOrEqualTo(smallQueries).isLessThan(100);
        assertThat(large).allSatisfy(f -> assertThat(state(f).answers()).isEqualTo(9));
    }

    private int countCommandSql(Runnable command) {
        sqlGate.statementCount.set(0);
        sqlGate.countingThread.set(Thread.currentThread());
        try { command.run(); return sqlGate.statementCount.get(); }
        finally { sqlGate.countingThread.set(null); }
    }

    private void assertTerminalAfterRestart(Fixture f, State expired) throws Exception {
        // Reopen the persistence unit and reconstruct the command service/repository/transaction manager.
        // No first-level cache or service object from the original command supplies terminal facts.
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("uk.gegc.quizmaker.features");
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none", "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
        try {
            var manager = new JpaTransactionManager(factory.getObject());
            manager.setDataSource(dataSource);
            var em = SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
            AttemptRepository reopened = new JpaRepositoryFactory(em).getRepository(AttemptRepository.class);
            var constructor = AttemptServiceImpl.class.getConstructors()[0];
            Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(type -> {
                if (type == AttemptRepository.class) return reopened;
                if (type == ApplicationEventPublisher.class) return context;
                return context.getBean(type);
            }).toArray();
            var proxy = new ProxyFactory(constructor.newInstance(dependencies));
            proxy.setInterfaces(AttemptService.class);
            proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            AttemptService restarted = (AttemptService) proxy.getProxy();
            assertThatThrownBy(() -> restarted.completeAttempt(f.owner(), f.attemptId()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ABANDONED");
            assertThatThrownBy(() -> restarted.submitAnswer(f.owner(), f.attemptId(), request(f, 0)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ABANDONED");
            State stored = new TransactionTemplate(manager).execute(status -> {
                Attempt a = reopened.findById(f.attemptId()).orElseThrow();
                return new State(a.getStatus(), a.getCompletedAt(), a.getTotalScore(), answerCount(f));
            });
            assertThat(stored).isEqualTo(expired);
        } finally { factory.destroy(); }
    }

    private Fixture answeredFixture(int questions) {
        Fixture f = fixture(AttemptMode.TIMED, true, questions);
        for (int i = 0; i < questions; i++) service.submitAnswer(f.owner(), f.attemptId(), request(f, i));
        return f;
    }

    private Fixture fixture(AttemptMode mode, boolean timer, int count) {
        return tx(() -> {
            var owner = new User(); owner.setUsername("timeout-" + UUID.randomUUID());
            owner.setEmail(owner.getUsername() + "@example.invalid"); owner.setHashedPassword("fixture-only");
            owner.setActive(true); entityManager.persist(owner);
            var category = new Category(); category.setName("timeout-" + UUID.randomUUID()); entityManager.persist(category);
            var quiz = new Quiz(); quiz.setCreator(owner); quiz.setCategory(category); quiz.setTitle("Timeout compatibility");
            quiz.setStatus(QuizStatus.PUBLISHED); quiz.setVisibility(Visibility.PUBLIC); quiz.setDifficulty(Difficulty.EASY);
            quiz.setEstimatedTime(1); quiz.setIsRepetitionEnabled(false); quiz.setIsTimerEnabled(timer); quiz.setTimerDuration(1);
            quiz.setIsDeleted(false); entityManager.persist(quiz);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var question = new Question(); question.setType(QuestionType.TRUE_FALSE); question.setDifficulty(Difficulty.EASY);
                question.setQuestionText("Synthetic question " + i); question.setContent("{\"answer\":true}");
                question.setExplanation("Accepted-answer feedback"); question.getQuizId().add(quiz); entityManager.persist(question); ids.add(question.getId());
            }
            var attempt = new Attempt(); attempt.setUser(owner); attempt.setQuiz(quiz); attempt.setMode(mode); attempt.setStatus(AttemptStatus.IN_PROGRESS);
            entityManager.persist(attempt); entityManager.flush();
            jdbcTemplate.update("UPDATE attempts SET started_at=? WHERE id=UUID_TO_BIN(?)", java.sql.Timestamp.from(START), attempt.getId().toString());
            // Match the real UUID ordering query rather than insertion order.
            entityManager.clear();
            ids = entityManager.createQuery("select q.id from Question q join q.quizId quiz where quiz.id=:id order by q.id", UUID.class)
                    .setParameter("id", quiz.getId()).getResultList();
            return new Fixture(owner.getUsername(), attempt.getId(), List.copyOf(ids));
        });
    }

    private AnswerSubmissionRequest request(Fixture f, int index) {
        return new AnswerSubmissionRequest(f.questions().get(index), json.createObjectNode().put("answer", true), true, true, true);
    }
    private State state(Fixture f) {
        return tx(() -> {
            var a = attempts.findById(f.attemptId()).orElseThrow();
            return new State(a.getStatus(), a.getCompletedAt(), a.getTotalScore(), answerCount(f));
        });
    }
    private int answerCount(Fixture f) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM answers WHERE attempt_id=UUID_TO_BIN(?)", Integer.class, f.attemptId().toString());
    }
    private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactionManager).execute(status -> action.get()); }
    private void assertTimeout(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(IllegalStateException.class).hasMessage("Attempt has timed out"); }
    private Outcome command(Fixture f, boolean answer) {
        try {
            if (answer) service.submitAnswer(f.owner(), f.attemptId(), request(f, 0));
            else service.completeAttempt(f.owner(), f.attemptId());
            return new Outcome(true, null);
        } catch (IllegalStateException failure) { return new Outcome(false, failure.getMessage()); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Timed out waiting for race gate"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    record Fixture(String owner, UUID attemptId, List<UUID> questions) { }
    record State(AttemptStatus status, Instant completedAt, Double score, int answers) { }
    record Outcome(boolean success, String error) { }
    static class CompletionEvents {
        final List<UUID> ids = new CopyOnWriteArrayList<>();
        @TransactionalEventListener public void completed(AttemptCompletedEvent event) { ids.add(event.getAttemptId()); }
    }
    static class SqlGate implements StatementInspector {
        final AtomicReference<CountDownLatch> secondQuery = new AtomicReference<>();
        final AtomicReference<Thread> countingThread = new AtomicReference<>();
        final AtomicInteger statementCount = new AtomicInteger();
        @Override public String inspect(String sql) {
            if (Thread.currentThread() == countingThread.get()) statementCount.incrementAndGet();
            CountDownLatch latch = secondQuery.get();
            if (latch != null && Thread.currentThread().getName().equals("legacy-time-second")
                    && sql.toLowerCase(Locale.ROOT).contains("for update") && sql.toLowerCase(Locale.ROOT).contains("attempts")) latch.countDown();
            return sql;
        }
    }
    @TestConfiguration
    static class Config {
        @Bean CompletionEvents completionEvents() { return new CompletionEvents(); }
        @Bean SqlGate sqlGate() { return new SqlGate(); }
        @Bean HibernatePropertiesCustomizer legacyTimeoutInspector(SqlGate gate) {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", gate);
        }
    }
}
