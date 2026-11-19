package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.*;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.learning.application.LearningScheduleService.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;
import static uk.gegc.quizmaker.features.learning.LearningScheduleMySqlIntegrationTest.rejected;

/** Wiring/security/input failures only; real persistence and concurrency use the MySQL companion. */
class LearningScheduleSpringTest {
    static final UUID LEARNER = UUID.randomUUID(), QUESTION = UUID.randomUUID();
    static final Instant TIME = Instant.parse("2026-11-19T09:00:00Z");
    static final LearningSession.Source SOURCE = new LearningSession.Source(1, UUID.randomUUID(), UUID.randomUUID(), 1);
    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);
    final Enroll enrollment = new Enroll(1, UUID.randomUUID(), SOURCE, QUESTION, FsrsManifest.approved());

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity
    static class Config {
        @Bean LearningScheduleRepository schedules() { return mock(LearningScheduleRepository.class); }
        @Bean AuthenticatedUserLookup users() { return mock(AuthenticatedUserLookup.class); }
        @Bean LearningSessionService sessions() { return mock(LearningSessionService.class); }
        @Bean LearningSchedulingStrategy strategy() { return new FsrsSchedulingStrategyImpl(); }
        @Bean PlatformTransactionManager transactions() {
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus()); return manager;
        }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean LearningScheduleService service(LearningScheduleRepository repository, AuthenticatedUserLookup users,
                List<LearningSessionSourceVerifier> sources, List<LearningSessionCompletionVerifier> completions,
                LearningSessionService sessions, LearningSchedulingStrategy strategy, SimpleMeterRegistry metrics,
                PlatformTransactionManager manager) {
            return new LearningScheduleServiceImpl(repository, users, sources, completions, sessions, strategy,
                    Clock.fixed(TIME, ZoneOffset.UTC), metrics, manager);
        }
    }

    @Test void methodSecurityDeniesBeforeIdentityOrRepository() {
        context.run(c -> {
            var service = c.getBean(LearningScheduleService.class);
            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertThatThrownBy(() -> service.execute(enrollment)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            assertThatThrownBy(() -> service.read(SOURCE, QUESTION)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningScheduleRepository.class));
        });
    }

    @Test void missingOrAmbiguousAuthorityDenies() {
        for (int adapters : List.of(0, 2)) {
            var runner = context;
            for (int i = 0; i < adapters; i++) runner = runner.withBean("source" + i, LearningSessionSourceVerifier.class, () -> (learner, source) -> true);
            runner.run(c -> {
                LearningScheduleMySqlIntegrationTest.login("anna");
                when(c.getBean(AuthenticatedUserLookup.class).lockActiveUserId("anna")).thenReturn(Optional.of(LEARNER));
                rejected(() -> c.getBean(LearningScheduleService.class).execute(enrollment), SOURCE_UNAVAILABLE);
                verifyNoInteractions(c.getBean(LearningScheduleRepository.class));
            });
        }
    }

    @Test void malformedCommandsRejectBeforeIdentityAndPersistence() {
        context.run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            var service = c.getBean(LearningScheduleService.class);
            rejected(() -> service.execute(null), INVALID_INPUT);
            rejected(() -> service.execute(new Enroll(2, enrollment.id(), SOURCE, QUESTION, enrollment.manifest())), UNSUPPORTED_VERSION);
            for (var input : List.of(new Enroll(1, null, SOURCE, QUESTION, enrollment.manifest()),
                    new Enroll(1, enrollment.id(), null, QUESTION, enrollment.manifest()),
                    new Enroll(1, enrollment.id(), SOURCE, null, enrollment.manifest()),
                    new Enroll(1, enrollment.id(), SOURCE, QUESTION, null))) rejected(() -> service.execute(input), INVALID_INPUT);
            var valid = new Expected(UUID.randomUUID(), 1, 0);
            var invalid = new Expected(valid.cycleId(), 0, 0);
            for (var input : List.of(new Rate(1, UUID.randomUUID(), SOURCE, QUESTION, null, LearningSchedule.Rating.GOOD),
                    new Rate(1, UUID.randomUUID(), SOURCE, QUESTION, invalid, LearningSchedule.Rating.GOOD),
                    new Rate(1, UUID.randomUUID(), SOURCE, QUESTION, valid, null),
                    new Restart(1, UUID.randomUUID(), SOURCE, QUESTION, valid, null),
                    new Capture(1, UUID.randomUUID(), SOURCE, QUESTION, valid, null),
                    new Release(1, UUID.randomUUID(), SOURCE, QUESTION, valid, null))) rejected(() -> service.execute(input), INVALID_INPUT);
            rejected(() -> service.read(null, QUESTION), INVALID_INPUT);
            rejected(() -> service.read(SOURCE, null), INVALID_INPUT);
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningScheduleRepository.class));
        });
    }

    @Test void authenticatedServerIdentityAndSupportedReadHaveSuccessfulControl() {
        context.withBean(LearningSessionSourceVerifier.class, () -> (learner, source) -> learner.equals(LEARNER) && source.equals(SOURCE)).run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            var users = c.getBean(AuthenticatedUserLookup.class); var repository = c.getBean(LearningScheduleRepository.class);
            var service = c.getBean(LearningScheduleService.class);
            when(users.lockActiveUserId("anna")).thenReturn(Optional.empty());
            rejected(() -> service.read(SOURCE, QUESTION), UNAVAILABLE); verifyNoInteractions(repository);
            when(users.lockActiveUserId("anna")).thenReturn(Optional.of(LEARNER));
            when(repository.sourceExists(any())).thenReturn(true);
            var identity = new LearningSchedule.Identity(LEARNER, SOURCE.quizId(), SOURCE.quizRevisionId(), QUESTION, UUID.randomUUID());
            var saved = new Snapshot(1, new FsrsSchedulingStrategyImpl().initial(identity, FsrsManifest.approved(), TIME));
            when(repository.find(any())).thenReturn(Optional.of(saved));
            assertThat(service.read(SOURCE, QUESTION)).isEqualTo(saved);
            verify(repository).find(new LearningScheduleRepository.Key(LEARNER, SOURCE, QUESTION));
            assertThat(c.getBean(SimpleMeterRegistry.class).get("quizmaker.learning.schedule").tag("outcome", "success").counter().count()).isEqualTo(1);
        });
    }

    @Test void commandsAndResultsDoNotPrintIdentityOrConfigurationPayloads() {
        var expected = new Expected(UUID.randomUUID(), 1, 0);
        for (Object value : List.of(enrollment, new Rate(1, UUID.randomUUID(), SOURCE, QUESTION, expected, LearningSchedule.Rating.GOOD),
                new Restart(1, UUID.randomUUID(), SOURCE, QUESTION, expected, FsrsManifest.approved()),
                new Capture(1, UUID.randomUUID(), SOURCE, QUESTION, expected, UUID.randomUUID()),
                new Release(1, UUID.randomUUID(), SOURCE, QUESTION, expected, UUID.randomUUID()), expected))
            assertThat(value.toString()).doesNotContain(SOURCE.quizId().toString(), QUESTION.toString(), "parameters", "desiredRetention");
    }
}
