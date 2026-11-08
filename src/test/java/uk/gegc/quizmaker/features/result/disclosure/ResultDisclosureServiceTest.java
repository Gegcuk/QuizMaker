package uk.gegc.quizmaker.features.result.disclosure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gegc.quizmaker.features.result.application.ResultDisclosureService;
import uk.gegc.quizmaker.features.result.application.impl.ResultDisclosureServiceImpl;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.result.disclosure.DisclosureFixtures.*;

class ResultDisclosureServiceTest {
    @Test void springExposesTheInterfaceAndEachCategoryHasABoundedOutcomeCounter() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(ResultDisclosureServiceImpl.class); context.refresh();
            var service = context.getBean(ResultDisclosureService.class);
            var result = service.evaluate(new Fixture().build());
            assertThat(result.requested(ALL)).containsExactlyInAnyOrderElementsOf(PRACTICE);
            var metrics = context.getBean(MeterRegistry.class);
            assertThat(metrics.getMeters()).hasSize(Category.values().length);
            metrics.getMeters().forEach(meter -> {
                assertThat(meter.getId().getName()).isEqualTo("quizmaker.result.disclosure");
                assertThat(meter.getId().getTags()).extracting("key").containsExactly("category", "outcome", "version");
                assertThat(meter.getId().toString()).doesNotContain(USER.toString(), RESULT.id().toString(), BINDING.policyHash());
                assertThat(metrics.get(meter.getId().getName()).tags(meter.getId().getTags()).counter().count()).isEqualTo(1);
            });
        }
    }

    @Test void arbitraryVersionsCannotBecomeUnboundedMetricLabels() {
        var metrics = new SimpleMeterRegistry(); var service = new ResultDisclosureServiceImpl(metrics);
        for (int version : new int[]{-1, 0, 2, 89, Integer.MAX_VALUE}) {
            var fixture = new Fixture(); fixture.version = version;
            assertThat(service.evaluate(fixture.build()).requested(ALL)).isEmpty();
        }
        assertThat(service.evaluate(null).requested(ALL)).isEmpty();
        assertThat(metrics.getMeters()).hasSize(Category.values().length);
        metrics.getMeters().forEach(meter -> {
            assertThat(meter.getId().getTag("version")).isEqualTo("unsupported");
            assertThat(meter.getId().getTag("outcome")).isEqualTo("deny");
            assertThat(metrics.get(meter.getId().getName()).tags(meter.getId().getTags()).counter().count()).isEqualTo(6);
        });
    }

    @Test void pendingAndDeniedRemainDifferentWithoutPublishingAnything() {
        var metrics = new SimpleMeterRegistry(); var service = new ResultDisclosureServiceImpl(metrics);
        var decisions = service.evaluate(new Fixture().pendingRelease().build());
        assertThat(decisions.requested(ALL)).isEmpty();
        assertThat(metrics.get("quizmaker.result.disclosure").tags("category", "total_score", "outcome", "pending").counter().count()).isEqualTo(1);
        assertThat(metrics.get("quizmaker.result.disclosure").tags("category", "feedback", "outcome", "deny").counter().count()).isEqualTo(1);
    }

    @Test void failedMetricsNeverGrantOrRemoveAuthorityOrExposeThePrivateFailure() {
        MeterRegistry metrics = mock(MeterRegistry.class);
        when(metrics.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("SECRET_PRIVATE_PAYLOAD"));
        var service = new ResultDisclosureServiceImpl(metrics);
        var fixture = new Fixture();
        assertThat(service.evaluate(fixture.build()).requested(ALL)).containsExactlyInAnyOrderElementsOf(PRACTICE);
        fixture.pendingRelease(); assertThat(service.evaluate(fixture.build()).requested(ALL)).isEmpty();
        fixture.audience = new Audience(Access.DENIED, RESULT.attemptId(), USER, USER);
        assertThat(service.evaluate(fixture.build()).requested(ALL)).isEmpty();
        verify(metrics, times(Category.values().length * 3)).counter(anyString(), any(String[].class));
    }
}
