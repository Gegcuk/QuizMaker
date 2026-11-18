package uk.gegc.quizmaker.features.result.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.ExecutionTraceService;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.trace.ExecutionTrace;
import uk.gegc.quizmaker.features.result.application.*;
import uk.gegc.quizmaker.features.result.domain.model.*;
import uk.gegc.quizmaker.features.result.domain.model.release.*;
import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;
import uk.gegc.quizmaker.features.result.domain.repository.*;
import uk.gegc.quizmaker.features.result.domain.repository.ScheduledReleaseRepository.Source;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.*;
import static uk.gegc.quizmaker.features.result.domain.model.ResultReleaseException.Reason.*;

@Service
public class ScheduledResultReleaseServiceImpl implements ScheduledResultReleaseService {
    private final ScheduledReleaseRepository work;
    private final ReleaseConfigurationReader configurations;
    private final ResultReleaseRepository releases;
    private final ExecutionTraceService trace;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate write, read;

    public ScheduledResultReleaseServiceImpl(ScheduledReleaseRepository work, ReleaseConfigurationReader configurations,
            ResultReleaseRepository releases, ExecutionTraceService trace, Clock clock, MeterRegistry metrics,
            PlatformTransactionManager transactions) {
        this.work=work; this.configurations=configurations; this.releases=releases; this.trace=trace;
        this.clock=clock; this.metrics=metrics;
        write=AttemptTransactions.readCommitted(transactions);
        write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read=AttemptTransactions.readCommitted(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); read.setReadOnly(true);
    }
    @Override public ScheduledRelease register(UUID submission, UUID expectedResult, UUID configuration) {
        require(submission!=null && expectedResult!=null && configuration!=null,INVALID_INPUT);
        return transaction(write,()->{
            var source=work.lockSource(submission);
            require(source.resultId().equals(expectedResult),CORRUPT);
            var rule=rule(source,configuration);
            var existing=work.find(submission,true);
            if(existing.isPresent()) {
                var saved=existing.get();
                require(saved.resultId().equals(expectedResult) && saved.configurationId().equals(configuration)
                        && saved.configurationHash().equals(rule.hash()) && saved.dueAt().equals(rule.rule().schedule().instant()),CORRUPT);
                return saved;
            }
            require(releases.find(expectedResult).isEmpty(),UNSUPPORTED);
            var now=now(); require(!now.isBefore(source.createdAt()),CORRUPT);
            var saved=new ScheduledRelease(submission,expectedResult,configuration,rule.hash(),UUID.randomUUID(),
                    rule.rule().schedule().instant(),now,ScheduledRelease.State.PENDING,0,0,rule.rule().schedule().instant(),null);
            work.insert(saved);
            return saved;
        });
    }
    @Override public Result execute(UUID submission) {
        require(submission!=null,INVALID_INPUT);
        // A bounded hint only identifies the attempt being made. All authority is re-read under locks.
        var hint=transaction(read,()->work.find(submission,false).orElseThrow(()->new ResultReleaseException(NOT_FOUND)));
        var attempted=new AtomicReference<>(hint);
        Result result;
        try { result=transaction(write,()->executeLocked(submission,attempted)); }
        catch(ResultReleaseException failure) {
            // A failed commit is uncertain. Reconcile actual release + work before scheduling a retry.
            result=transaction(write,()->recover(attempted.get(),failure.reason()));
        }
        count(result.outcome().name().toLowerCase(Locale.ROOT));
        if(result.outcome()==Outcome.RELEASED) {
            try { metrics.summary("quizmaker.result.scheduled.release_lag_ms","version","1")
                    .record(Duration.between(hint.dueAt(),result.release().releasedAt()).toMillis()); }
            catch(RuntimeException ignored) { /* Only acknowledged committed transitions contribute lag. */ }
        }
        return result;
    }
    private Result executeLocked(UUID submission, AtomicReference<ScheduledRelease> attempted) {
        var source=work.lockSource(submission); // Same intent/lifecycle order as grading and immediate release.
        var saved=work.find(submission,true).orElseThrow(()->new ResultReleaseException(NOT_FOUND));
        // Another worker may have advanced the hint. Charge a reached retry to its actual locked version.
        attempted.set(saved);
        require(saved.resultId().equals(source.resultId()),CORRUPT);
        var publication=rule(source,saved.configurationId());
        require(saved.configurationHash().equals(publication.hash())
                && saved.dueAt().equals(publication.rule().schedule().instant()),CORRUPT);
        var existing=releases.find(saved.resultId());
        if(saved.state()==ScheduledRelease.State.RELEASED) return replay(saved,existing);
        require(existing.isEmpty(),CORRUPT);
        if(saved.state()==ScheduledRelease.State.REVIEW) return new Result(Outcome.REVIEW,null,saved.lastFailure());
        var at=now();
        if(at.isBefore(saved.dueAt()) || at.isBefore(saved.nextAt())) return new Result(Outcome.NOT_DUE,null,null);
        require(!at.isBefore(source.createdAt()),CORRUPT);
        var release=new ResultRelease(saved.eventId(),1,saved.resultId(),at);
        var b=source.binding();
        trace.append(new ExecutionTrace.Scope(b.attemptId(),b.policySnapshotId(),b.formId()),
                new ExecutionTrace.Event(1,release.id(),ExecutionTrace.Type.RESULT_RELEASED,ExecutionTrace.Actor.SYSTEM,
                        new ExecutionTrace.Data(saved.resultId(),null,null,null,ExecutionTrace.ReasonCode.RELEASED)));
        releases.insert(release);
        work.update(saved,saved.released());
        return new Result(Outcome.RELEASED,release,null);
    }
    private Result recover(ScheduledRelease attempted, ResultReleaseException.Reason reason) {
        // Failure bookkeeping never takes an intent/lifecycle lock or repeats the business command.
        var current=work.find(attempted.submissionId(),true).orElseThrow(()->new ResultReleaseException(NOT_FOUND));
        var existing=releases.find(current.resultId());
        if(current.state()==ScheduledRelease.State.RELEASED) {
            if(reason!=UNAVAILABLE && reason!=BUSY && reason!=RETRYABLE) return new Result(Outcome.REVIEW,null,reason);
            return replay(current,existing);
        }
        require(existing.isEmpty(),CORRUPT);
        if(current.state()==ScheduledRelease.State.REVIEW) return new Result(Outcome.REVIEW,null,current.lastFailure());
        if(current.rowVersion()!=attempted.rowVersion()) return new Result(Outcome.DEFERRED,null,current.lastFailure());
        var next=current.failed(reason,now());
        work.update(current,next);
        return new Result(next.state()==ScheduledRelease.State.REVIEW?Outcome.REVIEW:Outcome.DEFERRED,null,reason);
    }
    private static Result replay(ScheduledRelease saved, Optional<ResultRelease> found) {
        var release=found.orElseThrow(()->new ResultReleaseException(CORRUPT));
        require(release.id().equals(saved.eventId()) && release.resultId().equals(saved.resultId())
                && !release.releasedAt().isBefore(saved.dueAt()) && !release.releasedAt().isBefore(saved.registeredAt()),CORRUPT);
        return new Result(Outcome.REPLAYED,release,null);
    }
    private ReleaseConfigurationService.Publication rule(Source source, UUID configuration) {
        var captured=configurations.readBound(source.ownerId(),source.quizId(),source.binding().quizRevisionId(),configuration);
        var rule=captured.rule();
        // Manual/scheduled composition has its own real-writer conformance gate (#633).
        require(rule.family()==ResultDisclosure.Family.FORMAL_ASSESSMENT
                && rule.triggers().equals(Set.of(ReleaseConfiguration.Trigger.SCHEDULED)),UNSUPPORTED);
        return captured;
    }
    @Override public Batch runBatch(int limit, Cursor after) {
        require(limit>0 && limit<=100 && (after==null || after.nextAt()!=null && after.submissionId()!=null),INVALID_INPUT);
        var page=transaction(read,()->work.due(now(),limit,after));
        int released=0,deferred=0,review=0;
        for(var candidate:page) {
            try {
                var result=execute(candidate.submissionId());
                if(result.outcome()==Outcome.RELEASED || result.outcome()==Outcome.REPLAYED) released++;
                else if(result.outcome()==Outcome.REVIEW) review++;
                else deferred++;
            } catch(ResultReleaseException unavailable) { deferred++; count("unavailable"); }
        }
        var backlog=transaction(read,()->work.backlog(now()));
        try {
            metrics.summary("quizmaker.result.scheduled.due","version","1").record(backlog.count());
            metrics.summary("quizmaker.result.scheduled.oldest_due_ms","version","1")
                    .record(backlog.oldest()==null?0:Math.max(0,Duration.between(backlog.oldest(),now()).toMillis()));
        } catch(RuntimeException ignored) { /* Optional telemetry never changes authoritative work. */ }
        return new Batch(page.size(),released,deferred,review,page.size()==limit?page.get(page.size()-1):null);
    }
    private Instant now() { return CommandReceipt.time(clock.instant()); }
    private void count(String outcome) {
        try { metrics.counter("quizmaker.result.scheduled","version","1","outcome",outcome).increment(); }
        catch(RuntimeException ignored) { /* bounded labels; no IDs, payload or exception details */ }
    }
    private static <T> T transaction(TransactionTemplate transaction,Supplier<T> work) {
        try { return transaction.execute(status->{
            try { return work.get(); }
            catch(RuntimeException failure) { throw safe(failure); }
        }); } catch(RuntimeException failure) { throw safe(failure); }
    }
    private static ResultReleaseException safe(RuntimeException failure) {
        if(failure instanceof ReleaseConfigurationException configuration) return new ResultReleaseException(switch(configuration.reason()) {
            case UNAVAILABLE -> UNAVAILABLE; case NOT_FOUND -> NOT_FOUND; default -> UNSUPPORTED;
        });
        return ResultReleaseFailures.safe(failure);
    }
}
