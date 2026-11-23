package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningSessionServiceImpl;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningSessionRepository;
import uk.gegc.quizmaker.features.learning.config.LearningCommandRateConfig;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.learning.LearningSessionTest.rejected;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;

/** Method-security and dependency wiring only. Real SQL/rollback lives in the MySQL companion. */
class LearningSessionSpringTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);
    private static final UUID LEARNER = UUID.randomUUID();
    private static final Instant TIME = Instant.parse("2026-11-19T09:00:00.123456789Z");
    private final LearningSessionService.Create command = new LearningSessionService.Create(1, UUID.randomUUID(),
            new LearningSession.Source(1, UUID.randomUUID(), UUID.randomUUID(), 1), FsrsManifest.approved());

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LearningCommandRateConfig.class)
    static class RateBinding {}

    @Test void deploymentCanLowerButCannotRaiseTheApprovedRateOrBurst() {
        var binding = new ApplicationContextRunner().withUserConfiguration(RateBinding.class);
        binding.withPropertyValues("quizmaker.learning.command-rate.per-minute=30",
                "quizmaker.learning.command-rate.burst=5").run(c -> {
            assertThat(c).hasNotFailed();
            var config = c.getBean(LearningCommandRateConfig.class);
            assertThat(config.getPerMinute()).isEqualTo(30);
            assertThat(config.getBurst()).isEqualTo(5);
        });
        for (var invalid : java.util.List.of("per-minute=61", "per-minute=0", "burst=21", "burst=0")) {
            binding.withPropertyValues("quizmaker.learning.command-rate." + invalid)
                    .run(c -> assertThat(c).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean LearningSessionRepository repository() { return mock(LearningSessionRepository.class); }
        @Bean AuthenticatedUserLookup users() { return mock(AuthenticatedUserLookup.class); }
        @Bean PlatformTransactionManager transactions() {
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
            return manager;
        }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean LearningSessionService sessions(LearningSessionRepository repository, AuthenticatedUserLookup users,
                java.util.List<LearningSessionSourceVerifier> sources, SimpleMeterRegistry metrics,
                PlatformTransactionManager manager) {
            return new LearningSessionServiceImpl(repository, org.mockito.Mockito.mock(uk.gegc.quizmaker.features.learning.domain.repository.LearningCommandBudget.class), users, sources, Clock.fixed(TIME, ZoneOffset.UTC), metrics, manager);
        }
    }

    @Test void methodSecurityDeniesUnauthenticatedCallsBeforePersistence() {
        context.run(c -> {
            var service = c.getBean(LearningSessionService.class);
            assertThat(AopUtils.isAopProxy(service)).isTrue();
            assertThatThrownBy(() -> service.create(command)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verifyNoInteractions(c.getBean(LearningSessionRepository.class), c.getBean(AuthenticatedUserLookup.class));
        });
    }

    @Test void missingOrAmbiguousSourceImplementationDeniesEvenAuthenticatedCreation() {
        context.run(c -> {
            login();
            when(c.getBean(AuthenticatedUserLookup.class).lockActiveUserId("anna")).thenReturn(Optional.of(LEARNER));
            rejected(() -> c.getBean(LearningSessionService.class).create(command), SOURCE_UNAVAILABLE);
            verifyNoInteractions(c.getBean(LearningSessionRepository.class));
        });
        context.withBean("sourceA", LearningSessionSourceVerifier.class, () -> (learner, source) -> true)
                .withBean("sourceB", LearningSessionSourceVerifier.class, () -> (learner, source) -> true).run(c -> {
                    login();
                    when(c.getBean(AuthenticatedUserLookup.class).lockActiveUserId("anna")).thenReturn(Optional.of(LEARNER));
                    rejected(() -> c.getBean(LearningSessionService.class).create(command), SOURCE_UNAVAILABLE);
                    verifyNoInteractions(c.getBean(LearningSessionRepository.class));
                });
    }

    @Test void validSourceUsesServerIdentityCapturedClockAndReceiptWithoutScheduleDependencies() {
        context.withBean(LearningSessionSourceVerifier.class, () -> (learner, source) -> learner.equals(LEARNER)).run(c -> {
            login();
            var repository = c.getBean(LearningSessionRepository.class);
            when(c.getBean(AuthenticatedUserLookup.class).lockActiveUserId("anna")).thenReturn(Optional.of(LEARNER));
            when(repository.sourceExists(command.source())).thenReturn(true);
            when(repository.insertOrRead(any())).thenAnswer(invocation -> invocation.getArgument(0));
            var result = c.getBean(LearningSessionService.class).create(command);
            assertThat(result.replayed()).isFalse();
            assertThat(result.session().learnerId()).isEqualTo(LEARNER);
            assertThat(result.session().createdAt()).isEqualTo(Instant.parse("2026-11-19T09:00:00.123456Z"));
            verify(repository).recordCreation(result.session());
            assertThat(c).doesNotHaveBean(LearningSchedulingStrategy.class);
        });
    }

    @Test void malformedCommandsAndUnavailableIdentityCannotCreateRows() {
        context.withBean(LearningSessionSourceVerifier.class, () -> (learner, source) -> true).run(c -> {
            login();
            var service = c.getBean(LearningSessionService.class);
            rejected(() -> service.create(null), INVALID_INPUT);
            rejected(() -> service.create(new LearningSessionService.Create(2, command.commandId(), command.source(),
                    command.configuration())), UNSUPPORTED_VERSION);
            rejected(() -> service.create(new LearningSessionService.Create(1, null, command.source(), command.configuration())), INVALID_INPUT);
            rejected(() -> service.create(new LearningSessionService.Create(1, command.commandId(), null, command.configuration())), INVALID_INPUT);
            rejected(() -> service.create(new LearningSessionService.Create(1, command.commandId(), command.source(), null)), INVALID_INPUT);
            rejected(() -> service.read(null), INVALID_INPUT);
            when(c.getBean(AuthenticatedUserLookup.class).lockActiveUserId("anna")).thenReturn(Optional.empty());
            rejected(() -> service.create(command), UNAVAILABLE);
            verifyNoInteractions(c.getBean(LearningSessionRepository.class));
        });
    }

    private static void login() {
        var user = User.withUsername("anna").password("unused").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
    }
}
