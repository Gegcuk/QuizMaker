package uk.gegc.quizmaker.features.attempt.application.impl;

import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.domain.model.timing.AttemptTiming;
import uk.gegc.quizmaker.features.attempt.domain.repository.*;
import static uk.gegc.quizmaker.features.attempt.application.AttemptExpiryService.Failure.Reason.*;

@Service
public class AttemptExpiryServiceImpl implements AttemptExpiryService {
    private static final Logger log=LoggerFactory.getLogger(AttemptExpiryServiceImpl.class);
    private final AttemptExpiryRepository repository;
    private final AttemptAccessRepository bindings;
    private final AttemptLifecycleService lifecycle;
    private final AttemptExpiryFactsService facts;
    private final SubmissionResponseFinalizer finalizer;
    private final AttemptTerminalSubmission terminal;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate write,read;
    public AttemptExpiryServiceImpl(AttemptExpiryRepository repository, AttemptAccessRepository bindings, AttemptLifecycleService lifecycle,
            AttemptExpiryFactsService facts, SubmissionResponseFinalizer finalizer, AttemptTerminalSubmission terminal,
            Clock clock, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.repository=repository; this.bindings=bindings; this.lifecycle=lifecycle; this.facts=facts;
        this.finalizer=finalizer; this.terminal=terminal; this.clock=clock; this.metrics=metrics;
        write=AttemptTransactions.readCommitted(manager); write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read=AttemptTransactions.readCommitted(manager); read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); read.setReadOnly(true);
    }
    @Override public Result expire(UUID attemptId) {
        if(attemptId==null) throw new Failure(INVALID);
        long start=System.nanoTime(); String outcome="unavailable";
        try {
            Result result=execute(write,()->expireLocked(attemptId));
            outcome=result.outcome().name().toLowerCase(Locale.ROOT);
            return result;
        } catch(Failure failure) {
            outcome=failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } finally {
            try {
                metrics.timer("attempt.expiry.command","version","1","outcome",outcome)
                        .record(Duration.ofNanos(System.nanoTime()-start));
            } catch(RuntimeException ignored) { /* telemetry is not transaction authority */ }
        }
    }
    private Result expireLocked(UUID attempt) {
        var user=repository.lockSubject(attempt).orElseThrow(()->new Failure(CORRUPT));
        if(!repository.claim(attempt)) return new Result(Outcome.BUSY,null);
        var state=lifecycle.lock(attempt);
        var previous=repository.receipt(attempt);
        if(previous.isPresent()) {
            var receipt=previous.get();
            check(receipt.version()==1 && state.state().terminal() && receipt.acknowledgement().stateVersion()==state.rowVersion()
                    && receipt.deadline().equals(state.submittedAt()),CORRUPT);
            repository.removeWork(attempt);
            return new Result(Outcome.REPLAYED,receipt.acknowledgement());
        }
        if(state.state().terminal()) {
            check(repository.voluntaryTerminal(attempt,state.rowVersion(),state.submittedAt()),CORRUPT);
            repository.removeWork(attempt);
            return new Result(Outcome.ALREADY_TERMINAL,null);
        }
        var bound=bindings.lockEvidence(attempt,user).orElseThrow(()->new Failure(CORRUPT));
        check(bound.lifecycle().equals(state),CORRUPT);
        var captured=new AttemptAccessService.Access(attempt,AttemptAccessService.Operation.ANSWER,bound.quizId(),bound.revisionId(),
                bound.formId(),bound.policySnapshotId(),state.rowVersion(),bound.contentOwnerId(),state);
        var saved=facts.read(captured);
        check(saved!=null && saved.timing()!=null,UNSUPPORTED);
        Instant now=uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt.time(clock.instant());
        if(saved.timing().decide(now)!=AttemptTiming.Decision.EXPIRED) return new Result(Outcome.NOT_DUE,null);
        var completion=finalizer.prepare(saved,false);
        var command=UUID.randomUUID();
        var ack=terminal.expire(saved,state.rowVersion(),command,completion,saved.timing().effectiveDeadline());
        check(ack!=null && ack.attemptId().equals(attempt),CORRUPT);
        repository.record(attempt,saved.timing().effectiveDeadline(),now,command,ack);
        repository.removeWork(attempt);
        try { metrics.timer("attempt.expiry.lag","version","1").record(Duration.between(saved.timing().effectiveDeadline(),now)); }
        catch(RuntimeException ignored) { /* optional signal */ }
        return new Result(Outcome.EXPIRED,ack);
    }
    @Override public Batch runBatch(int limit, Cursor after) {
        check(limit>0 && limit<=100,INVALID);
        var page=execute(read,()->repository.due(clock.instant(),limit,after));
        int expired=0,failed=0;
        for(var candidate:page) {
            try { if(expire(candidate.attemptId()).outcome()==Outcome.EXPIRED) expired++; }
            catch(Failure failure) {
                failed++;
                log.warn("Attempt expiry deferred: version=1 outcome={}",failure.reason().name().toLowerCase(Locale.ROOT));
            }
        }
        // A failing early row cannot starve later pages. The next sweep revisits unresolved work.
        return new Batch(page.size(),expired,failed,page.size()==limit ? page.get(page.size()-1) : null);
    }
    private static <T> T execute(TransactionTemplate tx,Supplier<T> work) {
        try {
            return tx.execute(status->{
                try { return work.get(); }
                catch(RuntimeException failure) { throw safe(failure); }
            });
        } catch(RuntimeException failure) { throw safe(failure); }
    }
    private static Failure safe(RuntimeException failure) { return new Failure(failure instanceof Failure known ? known.reason() : UNAVAILABLE); }
    private static void check(boolean ok, Failure.Reason reason) { if(!ok) throw new Failure(reason); }
}
