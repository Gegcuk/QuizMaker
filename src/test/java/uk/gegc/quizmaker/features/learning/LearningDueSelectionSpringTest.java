package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningDueSelectionServiceImpl;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningDueSelection.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningDueSelectionRepository;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static uk.gegc.quizmaker.features.learning.LearningScheduleMySqlIntegrationTest.rejected;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningScheduleException.Reason.*;

/** Wiring and request/access rules only. SQL, real schedule writers and actual RC use companions. */
class LearningDueSelectionSpringTest {
    static final Instant TIME = Instant.parse("2026-11-19T09:00:00.123456Z");
    static final UUID ANNA = UUID.randomUUID();
    static final LearningSession.Source SOURCE = new LearningSession.Source(1, UUID.randomUUID(), UUID.randomUUID(), 1);
    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class)
            .withPropertyValues("quizmaker.learning.due.max-batch-size=20");

    @Configuration(proxyBeanMethods = false) @EnableMethodSecurity
    @EnableConfigurationProperties(LearningDueSelectionConfig.class)
    static class Config {
        @Bean LearningDueSelectionRepository repository() { return mock(LearningDueSelectionRepository.class); }
        @Bean AuthenticatedUserLookup users() { return mock(AuthenticatedUserLookup.class); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean PlatformTransactionManager transactions() {
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus()); return manager;
        }
        @Bean LearningDueSelectionService service(LearningDueSelectionRepository repository, AuthenticatedUserLookup users,
                List<LearningSessionSourceVerifier> sources, LearningDueSelectionConfig config,
                SimpleMeterRegistry metrics, PlatformTransactionManager manager) {
            return new LearningDueSelectionServiceImpl(repository, users, sources, config,
                    Clock.fixed(TIME.plusNanos(789), ZoneOffset.UTC), metrics, manager);
        }
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); TransactionSynchronizationManager.clear(); }
    ApplicationContextRunner allowed() {
        return context.withBean(LearningSessionSourceVerifier.class,
                () -> (learner, source) -> learner.equals(ANNA) && source.equals(SOURCE));
    }
    static LearningDueSelectionService.Select request(int limit, Cursor after) {
        return new LearningDueSelectionService.Select(1, SOURCE, limit, after);
    }
    static Item item(String id) {
        return new Item(UUID.fromString(id), new LearningSchedule.Expected(UUID.randomUUID(), 1, 0), TIME);
    }

    @Test void methodSecurityRejectsAnonymousBeforeIdentityOrRepository() {
        context.run(c -> {
            assertThatThrownBy(() -> c.getBean(LearningDueSelectionService.class).select(request(2, null)))
                    .isInstanceOf(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class);
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningDueSelectionRepository.class));
        });
    }
    @Test void noConfiguredMaximumMeansUnavailableRatherThanAnInventedDefault() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            assertThat(c.getBean(LearningDueSelectionConfig.class).getMaxBatchSize()).isNull();
            rejected(() -> c.getBean(LearningDueSelectionService.class).select(request(1, null)), SELECTION_UNAVAILABLE);
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningDueSelectionRepository.class));
        });
    }
    @ParameterizedTest @ValueSource(ints = {-1, 0, Integer.MAX_VALUE})
    void invalidConfiguredMaximumCannotEnableSelection(int value) {
        context.withPropertyValues("quizmaker.learning.due.max-batch-size=" + value).run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            rejected(() -> c.getBean(LearningDueSelectionService.class).select(request(1, null)), SELECTION_UNAVAILABLE);
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningDueSelectionRepository.class));
        });
    }
    @Test void invalidRequestsAndCursorsFailBeforeIdentityAndPersistence() {
        allowed().run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            var service = c.getBean(LearningDueSelectionService.class);
            rejected(() -> service.select(null), INVALID_INPUT);
            rejected(() -> service.select(new LearningDueSelectionService.Select(1, null, 2, null)), INVALID_INPUT);
            rejected(() -> service.select(new LearningDueSelectionService.Select(2, SOURCE, 2, null)), UNSUPPORTED_VERSION);
            for (int limit : List.of(-1, 0, 21, Integer.MAX_VALUE)) rejected(() -> service.select(request(limit, null)), INVALID_INPUT);
            UUID question = UUID.randomUUID();
            var other = new LearningSession.Source(1, UUID.randomUUID(), UUID.randomUUID(), 1);
            for (var cursor : List.of(new Cursor(1, SOURCE, null, question), new Cursor(1, SOURCE, TIME, null),
                    new Cursor(1, other, TIME, question), new Cursor(1, SOURCE, TIME.plusSeconds(1), question),
                    new Cursor(1, SOURCE, TIME.minusNanos(1), question),
                    new Cursor(1, SOURCE, Instant.parse("0999-01-01T00:00:00Z"), question)))
                rejected(() -> service.select(request(2, cursor)), INVALID_INPUT);
            rejected(() -> service.select(request(2, new Cursor(2, SOURCE, TIME, question))), UNSUPPORTED_VERSION);
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningDueSelectionRepository.class));
        });
    }
    @ParameterizedTest @ValueSource(ints = {0, 2})
    void absentOrAmbiguousSourceAuthorityDenies(int adapters) {
        var runner = context;
        for (int i = 0; i < adapters; i++) runner = runner.withBean("source" + i, LearningSessionSourceVerifier.class, () -> (learner, source) -> true);
        runner.run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            when(c.getBean(AuthenticatedUserLookup.class).findActiveUserId("anna")).thenReturn(Optional.of(ANNA));
            rejected(() -> c.getBean(LearningDueSelectionService.class).select(request(2, null)), SOURCE_UNAVAILABLE);
            verifyNoInteractions(c.getBean(LearningDueSelectionRepository.class));
        });
    }
    @Test void selfIdentityAndExactCurrentSourceAreRequiredWithSuccessfulControl() {
        allowed().run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            var service = c.getBean(LearningDueSelectionService.class);
            var users = c.getBean(AuthenticatedUserLookup.class); var repository = c.getBean(LearningDueSelectionRepository.class);
            when(users.findActiveUserId("anna")).thenReturn(Optional.empty());
            rejected(() -> service.select(request(2, null)), UNAVAILABLE);
            when(users.findActiveUserId("anna")).thenReturn(Optional.of(UUID.randomUUID()));
            rejected(() -> service.select(request(2, null)), SOURCE_UNAVAILABLE);
            verifyNoInteractions(repository);
            when(users.findActiveUserId("anna")).thenReturn(Optional.of(ANNA));
            when(repository.sourceExists(SOURCE)).thenReturn(false);
            rejected(() -> service.select(request(2, null)), SOURCE_UNAVAILABLE);
            when(repository.sourceExists(SOURCE)).thenReturn(true);
            when(repository.find(ANNA, SOURCE, TIME, null, 3)).thenReturn(List.of());
            assertThat(service.select(request(2, null)).outcome()).isEqualTo(Outcome.EMPTY);
            verify(users, never()).lockActiveUserId(any());
        });
    }
    @Test void unsignedTiesLookaheadPartialAndEmptyHaveImmutableTypedResults() {
        allowed().run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            when(c.getBean(AuthenticatedUserLookup.class).findActiveUserId("anna")).thenReturn(Optional.of(ANNA));
            var repository = c.getBean(LearningDueSelectionRepository.class);
            when(repository.sourceExists(SOURCE)).thenReturn(true);
            var a = item("00000000-0000-0000-7fff-ffffffffffff");
            var b = item("00000000-0000-0000-8000-000000000000");
            var d = item("80000000-0000-0000-0000-000000000000");
            when(repository.find(ANNA, SOURCE, TIME, null, 3)).thenReturn(List.of(a, b, d));
            var service = c.getBean(LearningDueSelectionService.class);
            var first = service.select(request(2, null));
            assertThat(first.items()).containsExactly(a, b); assertThat(first.outcome()).isEqualTo(Outcome.READY);
            assertThat(first.next()).isEqualTo(new Cursor(1, SOURCE, TIME, b.questionRevisionId()));
            assertThat(first.selectedAt()).isEqualTo(TIME); assertThat(first.requestedSize()).isEqualTo(2);
            assertThatThrownBy(() -> first.items().clear()).isInstanceOf(UnsupportedOperationException.class);
            when(repository.find(ANNA, SOURCE, TIME, first.next(), 3)).thenReturn(List.of(d));
            var last = service.select(request(2, first.next()));
            assertThat(last.items()).containsExactly(d); assertThat(last.outcome()).isEqualTo(Outcome.PARTIAL); assertThat(last.next()).isNull();
            when(repository.find(ANNA, SOURCE, TIME, null, 3)).thenReturn(List.of());
            assertThat(service.select(request(2, null)).outcome()).isEqualTo(Outcome.EMPTY);
            assertThat(c.getBean(SimpleMeterRegistry.class).get("quizmaker.learning.due").tag("outcome", "partial").counter().count()).isOne();
        });
    }
    @Test void incompatibleCallerRejectsBeforeWorkAndTelemetryCannotBreakSuccessfulSelection() {
        allowed().run(c -> {
            LearningScheduleMySqlIntegrationTest.login("anna");
            var service = c.getBean(LearningDueSelectionService.class);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            for (Integer isolation : Arrays.asList(null, TransactionDefinition.ISOLATION_REPEATABLE_READ)) {
                TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
                rejected(() -> service.select(request(2, null)), UNSUPPORTED_TRANSACTION);
            }
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningDueSelectionRepository.class));
            TransactionSynchronizationManager.clear();
            var users = c.getBean(AuthenticatedUserLookup.class); var repository = c.getBean(LearningDueSelectionRepository.class);
            when(users.findActiveUserId("anna")).thenReturn(Optional.of(ANNA)); when(repository.sourceExists(SOURCE)).thenReturn(true);
            when(repository.find(ANNA, SOURCE, TIME, null, 3)).thenReturn(List.of());
            var metrics = mock(MeterRegistry.class);
            when(metrics.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("telemetry unavailable"));
            assertThat(new LearningDueSelectionServiceImpl(repository, users, List.of((learner, source) -> true),
                    c.getBean(LearningDueSelectionConfig.class), Clock.fixed(TIME, ZoneOffset.UTC), metrics,
                    c.getBean(PlatformTransactionManager.class)).select(request(2, null)).outcome()).isEqualTo(Outcome.EMPTY);
        });
    }
    @Test void disabledAndNonUserPrincipalsCannotBecomeLearnerIdentity() {
        allowed().run(c -> {
            for (boolean disabled : List.of(false, true)) {
                Object principal = disabled ? User.withUsername("anna").password("unused").authorities("ROLE_USER").disabled(true).build() : "anna";
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
                rejected(() -> c.getBean(LearningDueSelectionService.class).select(request(2, null)), UNAUTHENTICATED);
            }
            verifyNoInteractions(c.getBean(AuthenticatedUserLookup.class), c.getBean(LearningDueSelectionRepository.class));
        });
    }
}
