package uk.gegc.quizmaker.features.attempt.expiry;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptExpiryService;
import uk.gegc.quizmaker.features.attempt.infra.scheduling.AttemptExpiryScheduler;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptExpirySchedulerTest {
    @Configuration(proxyBeanMethods=false) @Import(AttemptExpiryScheduler.class) static class Config { }
    @Test void schedulerIsAbsentByDefaultAndCannotEnableItself() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(c->assertThat(c).doesNotHaveBean(AttemptExpiryScheduler.class));
    }
    @Test void activationRequiresAnExplicitBoundedBatchSize() {
        for(String value:new String[]{"0","101"}) new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withBean(AttemptExpiryService.class,()->mock(AttemptExpiryService.class))
                .withPropertyValues("quizmaker.attempt-expiry.enabled=true","quizmaker.attempt-expiry.batch-size="+value)
                .run(c->assertThat(c).hasFailed());
    }
    @Test void failedScanRetainsCursorAndNextSweepRevisitsPendingWork() {
        var service=mock(AttemptExpiryService.class);
        var next=new AttemptExpiryService.Cursor(Instant.parse("2026-11-10T10:30:00Z"),UUID.randomUUID());
        when(service.runBatch(2,null)).thenReturn(new AttemptExpiryService.Batch(2,1,1,next));
        when(service.runBatch(2,next)).thenThrow(new IllegalStateException("private-diagnostic"))
                .thenReturn(new AttemptExpiryService.Batch(1,1,0,null));
        var scheduler=new AttemptExpiryScheduler(service,2);
        scheduler.poll(); scheduler.poll(); scheduler.poll(); scheduler.poll();
        var order=inOrder(service);
        order.verify(service).runBatch(2,null); order.verify(service,times(2)).runBatch(2,next); order.verify(service).runBatch(2,null);
        verifyNoMoreInteractions(service);
    }
}
