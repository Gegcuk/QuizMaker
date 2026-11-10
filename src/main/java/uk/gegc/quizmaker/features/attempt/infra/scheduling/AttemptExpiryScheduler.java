package uk.gegc.quizmaker.features.attempt.infra.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.application.AttemptExpiryService;

/** Dark by default. Enabling requires explicit technical polling/batch configuration. */
@Component
@ConditionalOnProperty(name="quizmaker.attempt-expiry.enabled",havingValue="true")
public class AttemptExpiryScheduler {
    private static final Logger log=LoggerFactory.getLogger(AttemptExpiryScheduler.class);
    private final AttemptExpiryService service;
    private final int batchSize;
    private AttemptExpiryService.Cursor cursor;
    public AttemptExpiryScheduler(AttemptExpiryService service,@Value("${quizmaker.attempt-expiry.batch-size}") int batchSize) {
        if(batchSize<1 || batchSize>100) throw new IllegalArgumentException("Invalid expiry batch size");
        this.service=service; this.batchSize=batchSize;
    }
    @Scheduled(fixedDelayString="${quizmaker.attempt-expiry.poll-delay}")
    public synchronized void poll() {
        try { cursor=service.runBatch(batchSize,cursor).next(); }
        catch(RuntimeException failure) { log.warn("Attempt expiry scan unavailable: version=1"); }
    }
}
