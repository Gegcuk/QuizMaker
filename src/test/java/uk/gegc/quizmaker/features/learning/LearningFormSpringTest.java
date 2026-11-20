package uk.gegc.quizmaker.features.learning;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.application.impl.LearningFormServiceImpl;
import uk.gegc.quizmaker.features.learning.domain.model.LearningForm;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningFormRepository;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static uk.gegc.quizmaker.features.learning.LearningFormTest.rejected;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningFormException.Reason.SOURCE_UNAVAILABLE;

class LearningFormSpringTest {
    final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(Config.class);
    final uk.gegc.quizmaker.features.learning.domain.model.LearningSession session = LearningFormTest.session();
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean LearningSessionService sessions() { return mock(LearningSessionService.class); }
        @Bean LearningFormRepository repository() {
            var repository = mock(LearningFormRepository.class);
            when(repository.matchesSourceItems(any(), eq(List.of()))).thenReturn(true);
            return repository;
        }
        @Bean PlatformTransactionManager transactions() {
            var manager = mock(PlatformTransactionManager.class);
            when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus()); return manager;
        }
        @Bean Clock clock() { return Clock.fixed(LearningFormTest.TIME, ZoneOffset.UTC); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean LearningFormService forms(LearningSessionService sessions, LearningFormRepository repository,
                List<LearningFormContent> content, Clock clock, SimpleMeterRegistry metrics, PlatformTransactionManager manager) {
            return new LearningFormServiceImpl(sessions, repository, content, clock, metrics, manager);
        }
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void anonymousCallsAreDeniedBeforeSessionAndPersistence() {
        context.run(c -> {
            var forms = c.getBean(LearningFormService.class);
            assertThat(AopUtils.isAopProxy(forms)).isTrue();
            assertThatThrownBy(() -> forms.materialize(new LearningFormService.Materialize(1, session.id(), List.of())))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            assertThatThrownBy(() -> forms.read(session.id())).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verifyNoInteractions(c.getBean(LearningSessionService.class), c.getBean(LearningFormRepository.class));
        });
    }
    @Test void missingAndAmbiguousProvidersDenyWithSuccessfulSamePathControl() {
        var single = context.withBean("contentA", LearningFormContent.class, () -> (s, i) -> List.of());
        for (var runner : List.of(context, single.withBean("contentB", LearningFormContent.class, () -> (s, i) -> List.of()))) {
            runner.run(c -> {
                login(); when(c.getBean(LearningSessionService.class).read(session.id())).thenReturn(session);
                rejected(() -> c.getBean(LearningFormService.class).materialize(new LearningFormService.Materialize(1, session.id(), List.of())), SOURCE_UNAVAILABLE);
                verify(c.getBean(LearningFormRepository.class), never()).insert(any());
            });
        }
        single.run(c -> {
            login(); when(c.getBean(LearningSessionService.class).read(session.id())).thenReturn(session);
            assertThat(c.getBean(LearningFormService.class).materialize(new LearningFormService.Materialize(1, session.id(), List.of()))
                    .view().form().outcome()).isEqualTo(LearningForm.Outcome.EMPTY_COMPLETE);
            assertThat(c).doesNotHaveBean(LearningSchedulingStrategy.class);
        });
    }
    static void login() {
        var p = User.withUsername("anna").password("unused").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(p, null, p.getAuthorities()));
    }
}
