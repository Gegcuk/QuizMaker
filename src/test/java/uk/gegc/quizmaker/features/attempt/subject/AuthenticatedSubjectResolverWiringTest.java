package uk.gegc.quizmaker.features.attempt.subject;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.application.impl.AuthenticatedSubjectResolverImpl;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.features.user.application.impl.AuthenticatedUserLookupImpl;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.subject.AuthenticatedSubjectResolverTest.*;

class AuthenticatedSubjectResolverWiringTest {
    @Test
    void realComponentsWireThroughApplicationInterfacesWithoutTheFullApplicationOrWrites() {
        var users = mock(UserRepository.class);
        UUID alice = UUID.randomUUID();
        when(users.findActiveAuthenticatedUserId("alice")).thenReturn(Optional.of(alice));
        new ApplicationContextRunner().withUserConfiguration(Components.class)
                .withBean(UserRepository.class, () -> users).run(context -> {
                    assertThat(context).hasSingleBean(AuthenticatedSubjectResolver.class).hasSingleBean(AuthenticatedUserLookup.class);
                    var consumer = context.getBean(Consumer.class);
                    try {
                        authenticate(token(principal("alice")));
                        assertThat(consumer.resolver.resolve(UUID.randomUUID()).userId()).isEqualTo(alice);
                    } finally { SecurityContextHolder.clearContext(); }
                    verify(users).findActiveAuthenticatedUserId("alice");
                    verifyNoMoreInteractions(users);
                });
    }

    private record Consumer(AuthenticatedSubjectResolver resolver) { }

    @Configuration(proxyBeanMethods = false)
    @Import({AuthenticatedSubjectResolverImpl.class, AuthenticatedUserLookupImpl.class})
    static class Components {
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean Consumer consumer(AuthenticatedSubjectResolver resolver) { return new Consumer(resolver); }
    }
}
