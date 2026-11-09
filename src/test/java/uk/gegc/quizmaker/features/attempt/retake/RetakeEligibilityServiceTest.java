package uk.gegc.quizmaker.features.attempt.retake;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import uk.gegc.quizmaker.features.attempt.application.RetakeEligibilityService;
import uk.gegc.quizmaker.features.attempt.application.impl.RetakeEligibilityServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.retake.RetakeFixtures.*;

class RetakeEligibilityServiceTest {
    @Test void springExposesTheInterfaceAndOnlyTheServerClockDeterminesEligibility() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(Clock.class, () -> Clock.fixed(NOW, ZoneOffset.UTC));
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.register(RetakeEligibilityServiceImpl.class); context.refresh();
            var service = context.getBean(RetakeEligibilityService.class);
            var fixture = new Fixture(); fixture.cooldown = Duration.ofHours(1);
            fixture.starts = List.of(terminal(Execution.COMPLETED, NOW.minusSeconds(60)));
            var decision = service.evaluate(fixture.build());
            assertThat(decision.reason()).isEqualTo(Reason.COOLDOWN);
            assertThat(decision.nextEligibleAt()).isEqualTo(NOW.plusSeconds(3540));
            var metrics = context.getBean(MeterRegistry.class);
            assertThat(metrics.getMeters()).hasSize(1);
            var meter = metrics.getMeters().get(0);
            assertThat(meter.getId().getTags()).extracting("key").containsExactly("outcome", "reason", "version");
            assertThat(meter.getId().toString()).doesNotContain(SCOPE.subjectId().toString(), SCOPE.contextId().toString());
        }
    }

    @Test void oldEligibilityIsNotACapabilityAndFreshClosedOrUnavailableFactsDeny() {
        var service = new RetakeEligibilityServiceImpl(Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
        var fixture = new Fixture(); assertThat(service.evaluate(fixture.build()).outcome()).isEqualTo(Outcome.ALLOW);
        fixture.entry = Entry.CLOSED;
        assertThat(service.evaluate(fixture.build()).reason()).isEqualTo(Reason.ENTRY_CLOSED);
        fixture.entry = Entry.OPEN; fixture.knowledge = Knowledge.UNAVAILABLE;
        assertThat(service.evaluate(fixture.build()).reason()).isEqualTo(Reason.FACTS_UNAVAILABLE);
        fixture.knowledge = Knowledge.COMPLETE; fixture.starts = List.of(active());
        assertThat(service.evaluate(fixture.build()).reason()).isEqualTo(Reason.ACTIVE_ATTEMPT);
    }

    @Test void clockIsSampledOnceAndClockOutageCannotUseAClientOrFallbackTime() {
        var clock = mock(Clock.class); when(clock.instant()).thenReturn(NOW);
        var service = new RetakeEligibilityServiceImpl(clock, new SimpleMeterRegistry());
        assertThat(service.evaluate(new Fixture().build()).outcome()).isEqualTo(Outcome.ALLOW);
        verify(clock, times(1)).instant();
        when(clock.instant()).thenThrow(new IllegalStateException("PRIVATE_CLOCK_DIAGNOSTIC"));
        var decision = service.evaluate(new Fixture().build());
        assertThat(decision.reason()).isEqualTo(Reason.CLOCK_UNAVAILABLE);
        assertThat(decision.toString()).doesNotContain("PRIVATE_CLOCK_DIAGNOSTIC");
        verify(clock, times(2)).instant();
        verifyNoMoreInteractions(clock);
    }

    @Test void arbitraryVersionsRemainOneBoundedMetricValue() {
        var metrics = new SimpleMeterRegistry();
        var service = new RetakeEligibilityServiceImpl(Clock.fixed(NOW, ZoneOffset.UTC), metrics);
        for (int version : new int[]{-1, 0, 2, 345, Integer.MAX_VALUE}) {
            var fixture = new Fixture(); fixture.version = version;
            assertThat(service.evaluate(fixture.build()).reason()).isEqualTo(Reason.UNSUPPORTED_VERSION);
        }
        assertThat(metrics.getMeters()).hasSize(1);
        assertThat(metrics.get("quizmaker.attempt.retake.eligibility").tags("version", "unsupported",
                "reason", "unsupported_version", "outcome", "deny").counter().count()).isEqualTo(5);
        assertThat(service.evaluate(null).reason()).isEqualTo(Reason.INVALID_INPUT);
    }

    @Test void failedTelemetryDoesNotChangeAllowOrDenyAndDoesNotExposeItsException() {
        var metrics = mock(MeterRegistry.class);
        when(metrics.counter(anyString(), any(String[].class))).thenThrow(new IllegalStateException("PRIVATE_METRIC_DIAGNOSTIC"));
        var service = new RetakeEligibilityServiceImpl(Clock.fixed(NOW, ZoneOffset.UTC), metrics);
        var fixture = new Fixture(); assertThat(service.evaluate(fixture.build()).outcome()).isEqualTo(Outcome.ALLOW);
        fixture.access = Access.DENIED;
        var denied = service.evaluate(fixture.build());
        assertThat(denied.reason()).isEqualTo(Reason.ACCESS_DENIED);
        assertThat(denied.toString()).doesNotContain("PRIVATE_METRIC_DIAGNOSTIC");
    }
}
