package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.lifecycle.LifecycleState;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.TimingMode;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshot;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.AttemptTimingRepository;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import static uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTimingException.Reason.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AttemptTimingServiceImpl implements AttemptTimingService {
    private final AttemptTimingRepository repository;
    private final MeterRegistry metrics;
    public AttemptTimingServiceImpl(AttemptTimingRepository repository, MeterRegistry metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }
    @Override public void capture(PolicySnapshot snapshot, LifecycleState started) {
        execute("capture", () -> {
            require(snapshot != null && started != null && started.startedAt() != null && !started.state().terminal(), INVALID);
            var policy = snapshot.resolution().policy().timing();
            if (policy.mode() != TimingMode.NONE)
                repository.insert(AttemptTiming.capture(started.attemptId(), snapshot.id(), policy, started.startedAt()));
            return null;
        });
    }
    @Override public AttemptTiming read(AttemptAccessService.Access access, PolicySnapshot snapshot) {
        return execute("read", () -> {
            require(access != null && snapshot != null && snapshot.id().equals(access.policySnapshotId())
                    && access.lifecycle() != null && access.attemptId().equals(access.lifecycle().attemptId()), CORRUPT);
            var policy = snapshot.resolution().policy().timing();
            var saved = repository.read(access.attemptId());
            if (policy.mode() == TimingMode.NONE) {
                require(saved.isEmpty(), CORRUPT);
                return null;
            }
            var timing = saved.orElseThrow(() -> new AttemptTimingException(MISSING));
            require(timing.attemptId().equals(access.attemptId()) && timing.policySnapshotId().equals(snapshot.id())
                    && timing.policy().equals(policy) && timing.startedAt().equals(access.lifecycle().startedAt()), CORRUPT);
            return timing;
        });
    }
    private <T> T execute(String operation, Supplier<T> work) {
        String outcome = "returned";
        try { return work.get(); }
        catch (RuntimeException failure) {
            var conflict = DatabaseConflictException.from(failure);
            if (conflict.isPresent()) {
                outcome = conflict.get().outcome();
                throw conflict.get();
            }
            var reason = failure instanceof AttemptTimingException known ? known.reason() : UNAVAILABLE;
            outcome = reason.name().toLowerCase(Locale.ROOT);
            throw new AttemptTimingException(reason);
        } finally {
            try { metrics.counter("quizmaker.attempt.timing", "version","1","operation",operation,"outcome",outcome).increment(); }
            catch (RuntimeException ignored) { /* telemetry never changes timing authority */ }
        }
    }
}
