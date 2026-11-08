package uk.gegc.quizmaker.features.result.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.result.application.ImmediateResultReleaseService;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.repository.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

@Service
public class ImmediateResultReleaseServiceImpl implements ImmediateResultReleaseService {
    private final SubmittedAttemptSource source;
    private final AutomaticResultRepository results;
    private final ResultReleaseRepository releases;
    private final ExecutionTraceService trace;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public ImmediateResultReleaseServiceImpl(SubmittedAttemptSource source, AutomaticResultRepository results,
            ResultReleaseRepository releases, ExecutionTraceService trace, Clock clock, MeterRegistry metrics,
            PlatformTransactionManager manager) {
        this.source = source; this.results = results; this.releases = releases; this.trace = trace;
        this.clock = clock; this.metrics = metrics;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public Acknowledgement release(UUID submissionId) {
        var timer = Timer.start(); String outcome = "unavailable";
        try {
            require(submissionId != null, INVALID_INPUT);
            var committed = transaction.execute(status -> {
                try { return execute(submissionId); }
                catch (RuntimeException failure) { throw ResultReleaseFailures.safe(failure); }
            });
            var ack = committed.acknowledgement();
            outcome = ack.replayed() ? "replayed" : "released";
            if (!ack.replayed()) {
                try { metrics.summary("quizmaker.result.release.lag", "version", "1").record(committed.lagMillis()); }
                catch (RuntimeException ignored) { /* Only committed releases contribute lag. */ }
            }
            return ack;
        } catch (RuntimeException failure) {
            var safe = ResultReleaseFailures.safe(failure);
            outcome = safe.reason().name().toLowerCase(Locale.ROOT); throw safe;
        } finally {
            try { timer.stop(Timer.builder("quizmaker.result.release").tags("version", "1", "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* Telemetry cannot replace a committed acknowledgement. */ }
        }
    }
    private record Completed(Acknowledgement acknowledgement, long lagMillis) { }
    private Completed execute(UUID submissionId) {
        // Same retained intent lock as the finalizer. No shared-to-exclusive immutable-row upgrade.
        var facts = source.lock(submissionId);
        require(facts.snapshot().resolution().preset().equals(
                uk.gegc.quizmaker.features.attempt.domain.model.preset.BuiltInPracticePreset.REFERENCE), UNSUPPORTED);
        var result = results.find(submissionId).orElseThrow(() -> new ResultReleaseException(NOT_READY));
        var s = facts.submission();
        require(result.attemptId().equals(s.attemptId()) && result.formId().equals(s.formId())
                && result.policySnapshotId().equals(s.policySnapshotId()) && result.quizRevisionId().equals(s.quizRevisionId())
                && result.policyHash().equals(facts.snapshot().resolution().hash())
                && result.items().stream().map(AutomaticResult.Item::formItemId).toList()
                .equals(facts.form().items().stream().map(i -> i.id()).toList()), CORRUPT);
        var policy = ResultDisclosure.Policy.practice(ResultDisclosure.Binding.of(result), facts.snapshot().resolution().policy());
        require(policy.family() == ResultDisclosure.Family.PERSONAL_PRACTICE
                && policy.permitted().contains(ResultDisclosure.Category.TOTAL_SCORE), UNSUPPORTED);
        var existing = releases.find(result.id());
        if (existing.isPresent()) {
            require(!existing.get().releasedAt().isBefore(result.createdAt()), CORRUPT);
            return new Completed(new Acknowledgement(existing.get(), true), 0);
        }
        var at = clock.instant();
        require(!at.isBefore(result.createdAt()), CORRUPT);
        var release = new ResultRelease(UUID.randomUUID(), 1, result.id(), at);
        trace.append(new ExecutionTrace.Scope(result.attemptId(), result.policySnapshotId(), result.formId()),
                new ExecutionTrace.Event(1, release.id(), ExecutionTrace.Type.RESULT_RELEASED, ExecutionTrace.Actor.SYSTEM,
                        new ExecutionTrace.Data(result.id(), null, null, null, ExecutionTrace.ReasonCode.RELEASED)));
        releases.insert(release);
        return new Completed(new Acknowledgement(release, false), Duration.between(result.createdAt(), release.releasedAt()).toMillis());
    }
}
