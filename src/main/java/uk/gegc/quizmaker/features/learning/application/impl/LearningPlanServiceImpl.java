package uk.gegc.quizmaker.features.learning.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.repository.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.*;
import uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.exception.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Service
@PreAuthorize("isAuthenticated()")
@ConditionalOnProperty(prefix = "quizmaker.learning", name = {"sessions-enabled", "custom-plans-enabled"}, havingValue = "true")
public class LearningPlanServiceImpl implements LearningPlanService {
    private final LearningPlanRepository plans;
    private final LearningScheduleRepository schedules;
    private final uk.gegc.quizmaker.features.question.application.PublishedQuestionContractService bindings;
    private final LearningStudyRepository preparation;
    private final boolean customReviewsEnabled;
    private final LearningScheduleService fsrs;
    private final LearningSchedulingStrategy calculation;
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> authorities;
    private final LearningCommandBudget budget;
    private final LearningDueSelectionConfig limits;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;
    private final LearningPlanFormat format = new LearningPlanFormat();
    public LearningPlanServiceImpl(LearningPlanRepository plans, LearningScheduleRepository schedules,
            LearningScheduleService fsrs, LearningSchedulingStrategy calculation, AuthenticatedUserLookup users,
            List<LearningSessionSourceVerifier> authorities, LearningCommandBudget budget, LearningDueSelectionConfig limits,
            Clock clock, MeterRegistry metrics, PlatformTransactionManager manager,
            uk.gegc.quizmaker.features.question.application.PublishedQuestionContractService bindings,LearningStudyRepository preparation,
            @org.springframework.beans.factory.annotation.Value("${quizmaker.learning.custom-reviews-enabled:false}") boolean customReviewsEnabled) {
        this.bindings=bindings; this.preparation=preparation; this.customReviewsEnabled=customReviewsEnabled;
        this.plans=plans; this.schedules=schedules; this.fsrs=fsrs; this.calculation=calculation; this.users=users;
        this.authorities=List.copyOf(authorities); this.budget=budget; this.limits=limits; this.clock=clock; this.metrics=metrics;
        transaction=new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public Acknowledgement create(Save c) {
        require(c != null && c.commandId() != null && c.expectedVersion() == 0 && c.definition() != null, INVALID_INPUT);
        return mutate("create",c.commandId(),c,null,null,null, learner -> {
            var now=now(); var view=new View(UUID.randomUUID(),1,1,c.definition(),false,now,now);
            plans.save(learner,view,true); return new Acknowledgement(c.commandId(),view,null,null);
        });
    }
    @Override public Acknowledgement edit(UUID id, Save c) {
        require(id != null && c != null && c.commandId() != null && c.expectedVersion()>0 && c.definition()!=null,INVALID_INPUT);
        return mutate("edit",c.commandId(),Arrays.asList(id,c),null,null,id, learner -> {
            var old=owned(learner,id,true); require(old.version()==c.expectedVersion(),CONFLICT);
            require(old.version()<Long.MAX_VALUE && old.planVersion()<Long.MAX_VALUE,CONFLICT);
            var next=new View(id,old.version()+1,old.planVersion()+1,c.definition(),old.paused(),old.createdAt(),now());
            plans.save(learner,next,true); return new Acknowledgement(c.commandId(),next,null,null);
        });
    }
    @Override public Acknowledgement pause(UUID id, Pause c) {
        require(id!=null && c!=null && c.commandId()!=null && c.expectedVersion()>0,INVALID_INPUT);
        return mutate("pause",c.commandId(),Arrays.asList(id,c),null,null,id, learner -> {
            var old=owned(learner,id,true); require(old.version()==c.expectedVersion() && old.version()<Long.MAX_VALUE,CONFLICT);
            var next=new View(id,old.version()+1,old.planVersion(),old.definition(),c.paused(),old.createdAt(),now());
            plans.save(learner,next,false); return new Acknowledgement(c.commandId(),next,null,null);
        });
    }
    @Override public View read(UUID id) { require(id!=null,INVALID_INPUT); return run("read",() -> owned(LearningActor.lock(users),id,false)); }
    @Override public PlanPage list(int limit,UUID after) {
        return run("list",() -> { bound(limit); var rows=plans.plans(LearningActor.lock(users),limit+1,after);
            var page=rows.subList(0,Math.min(rows.size(),limit)); return new PlanPage(page,rows.size()>limit?page.get(page.size()-1).planId():null); });
    }
    @Override public VersionPage history(UUID id,int limit,long after) {
        require(id!=null && after>=0,INVALID_INPUT);
        return run("history",() -> { bound(limit); UUID learner=LearningActor.lock(users); owned(learner,id,false);
            var rows=plans.versions(learner,id,limit+1,after); var page=rows.subList(0,Math.min(rows.size(),limit));
            return new VersionPage(page,rows.size()>limit?page.get(page.size()-1).version():null); });
    }
    @Override public Acknowledgement bind(Bind c) {
        require(c!=null && c.commandId()!=null && c.source()!=null && c.expectedVersion()>=0 && c.expectedVersion()<Long.MAX_VALUE,INVALID_INPUT);
        require(c.planId()==null ? c.planVersion()==null : c.questionRevisionId()==null ? c.planVersion()==null
                : c.planVersion()!=null && c.planVersion()>0,INVALID_INPUT);
        return mutate("bind",c.commandId(),c,c.source(),c.questionRevisionId(),c.planId(),learner -> {
            if (c.planId()!=null) { owned(learner,c.planId(),true);
                if(c.planVersion()!=null) require(plans.version(learner,c.planId(),c.planVersion()).isPresent(),UNAVAILABLE); }
            var old=plans.binding(learner,c.source(),c.questionRevisionId()); require(old.version()==c.expectedVersion(),CONFLICT);
            var next=new Binding(old.version()+1,c.planId(),c.planVersion()); plans.bind(learner,c.source(),c.questionRevisionId(),next);
            return new Acknowledgement(c.commandId(),null,next,null);
        });
    }
    @Override public Binding binding(Source source,UUID question) {
        return run("binding",() -> { UUID learner=authorize(source,question); return plans.binding(learner,source,question); });
    }
    @Override public Acknowledgement change(Change c) {
        require(c!=null && c.commandId()!=null && c.source()!=null && c.questionRevisionId()!=null
                && c.action()!=null && c.strategy()!=null,INVALID_INPUT);
        require(c.action()==Action.ENROLL ? c.expected()==null && !c.confirmed() : c.expected()!=null && c.confirmed(),INVALID_INPUT);
        require(c.strategy()==Strategy.FSRS || c.action()==Action.ENROLL
                ? c.targetPlanId()==null && c.targetPlanVersion()==null
                : c.targetPlanId()!=null && c.targetPlanVersion()!=null && c.targetPlanVersion()>0,INVALID_INPUT);
        return mutate("cycle",c.commandId(),c,c.source(),c.questionRevisionId(),null,learner -> {
            var key=new Key(learner,c.source(),c.questionRevisionId()); var active=schedules.lockCycle(key);
            Cycle previous=active.map(a -> current(key,a)).orElse(null);
            if(c.action()==Action.ENROLL && previous!=null) {
                require(previous.strategy()==c.strategy(),CONFLICT);
                if(c.strategy()==Strategy.CUSTOM) prepare(c.source());
                return new Acknowledgement(c.commandId(),null,null,previous);
            }
            if(c.action()!=Action.ENROLL) require(previous!=null && previous.expected().equals(c.expected()),CONFLICT);
            require(!schedules.hasCaptures(key),CONFLICT);
            long oldVersion=active.map(ActiveCycle::version).orElse(0L); require(oldVersion<Long.MAX_VALUE,CONFLICT);
            Cycle next;
            if(c.strategy()==Strategy.CUSTOM) {
                var binding=resolved(learner,c.source(),c.questionRevisionId());
                if(c.action()!=Action.ENROLL) require(binding.planId().equals(c.targetPlanId())
                        && binding.planVersion().equals(c.targetPlanVersion()),CONFLICT);
                var plan=owned(learner,binding.planId(),true);
                require(plans.version(learner,plan.planId(),binding.planVersion()).isPresent(),CORRUPT);
                if(c.action()==Action.SWITCH) require(previous.strategy()!=Strategy.CUSTOM
                        || !previous.planId().equals(binding.planId()) || !previous.planVersion().equals(binding.planVersion()),CONFLICT);
                if(c.action()==Action.RESTART) require(previous.strategy()==Strategy.CUSTOM && previous.completed(),CONFLICT);
                prepare(c.source());
                next=new Cycle(c.questionRevisionId(),UUID.randomUUID(),oldVersion+1,Strategy.CUSTOM,
                        plan.planId(),binding.planVersion(),0,0,now(),plan.paused(),false);
                schedules.replaceCycle(key,new ActiveCycle(next.cycleId(),next.cycleVersion(),"custom/v1"),oldVersion,c.commandId(),next.dueAt());
                plans.createCustom(key,next);
            } else {
                if(c.action()==Action.SWITCH) require(previous.strategy()!=Strategy.FSRS,CONFLICT);
                if(c.action()==Action.RESTART) require(previous.strategy()==Strategy.FSRS,CONFLICT);
                // The real FSRS reader verifies its original receipt, including after a custom -> FSRS switch.
                var reservation=UUID.randomUUID(); String input=format.encode(Arrays.asList("plan-fsrs",c));
                var receipt=schedules.reserve(key,c.commandId(),reservation,input);
                require(receipt.reservationId().equals(reservation) && receipt.result()==null && receipt.input().equals(input),CONFLICT);
                var identity=new LearningSchedule.Identity(learner,c.source().quizId(),c.source().quizRevisionId(),c.questionRevisionId(),UUID.randomUUID());
                var state=new LearningSchedule.Snapshot(oldVersion+1,calculation.initial(identity,FsrsManifest.approved(),now()));
                schedules.createCycle(key,state,oldVersion,c.commandId()); schedules.complete(key,c.commandId(),reservation,state);
                next=fsrsCycle(key,state);
            }
            return new Acknowledgement(c.commandId(),null,null,next);
        });
    }
    @Override public Cycle cycle(Source source,UUID question) {
        require(question!=null,INVALID_INPUT);
        return run("cycle_read",() -> { var key=new Key(authorize(source,question),source,question);
            // Keep strategy and state on one existing cycle until projection completes.
            var active=schedules.readCycle(key).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
            return current(key,active); });
    }
    @Override public CyclePage cycles(Source source,Window window,int limit,Cursor after) {
        require(window!=null && (after==null || after.dueAt()!=null && after.questionRevisionId()!=null
                && storable(after.dueAt())),INVALID_INPUT);
        return run("cycles",() -> { bound(limit); UUID learner=authorize(source,null);
            var rows=plans.cycles(learner,source,window,now(),limit+1,after); var page=rows.subList(0,Math.min(rows.size(),limit));
            var last=page.isEmpty()?null:page.get(page.size()-1);
            return new CyclePage(page,rows.size()>limit?new Cursor(last.dueAt(),last.questionRevisionId()):null); });
    }
    private void prepare(Source source) {
        if(customReviewsEnabled && !preparation.prepared(source.quizRevisionId())) {
            bindings.bind(source.quizId(),source.quizRevisionId());
            preparation.recordPreparation(source.quizRevisionId());
        }
    }
    private Cycle current(Key key,ActiveCycle active) {
        if("fsrs/v1".equals(active.strategyId())) return fsrsCycle(key,fsrs.read(key.source(),key.questionRevisionId()));
        require("custom/v1".equals(active.strategyId()),UNSUPPORTED_VERSION);
        var cycle=plans.custom(key).orElseThrow(() -> new LearningSessionException(CORRUPT));
        require(cycle.cycleId().equals(active.id()) && cycle.cycleVersion()==active.version(),CORRUPT); return cycle;
    }
    private static Cycle fsrsCycle(Key key,LearningSchedule.Snapshot s) {
        return new Cycle(key.questionRevisionId(),s.expected().cycleId(),s.cycleVersion(),Strategy.FSRS,null,null,
                s.state().sequence(),s.state().step()==null?0:s.state().step(),s.state().dueAt(),false,false);
    }
    private Binding resolved(UUID learner,Source source,UUID question) {
        var override=plans.binding(learner,source,question); if(override.planId()!=null) return override;
        var defaults=plans.binding(learner,source,null); require(defaults.planId()!=null,UNAVAILABLE);
        var head=owned(learner,defaults.planId(),true); return new Binding(defaults.version(),head.planId(),head.planVersion());
    }
    private Acknowledgement mutate(String op,UUID command,Object input,Source source,UUID question,UUID plan,
                                    Function<UUID,Acknowledgement> work) {
        String canonical=format.encode(Arrays.asList(op,input));
        return run(op,() -> {
            UUID learner=source==null?LearningActor.lock(users):authorize(source,question);
            if(plan!=null) owned(learner,plan,false);
            budget.chargeIfNew(learner,() -> plans.receipt(learner,command).isPresent());
            var old=plans.receipt(learner,command);
            if(old.isPresent()) {
                require(old.get().input().equals(canonical) && old.get().result().commandId().equals(command),CONFLICT);
                var ack=old.get().result(); if(ack.plan()!=null) owned(learner,ack.plan().planId(),false); return ack;
            }
            var ack=work.apply(learner); plans.record(learner,command,canonical,ack,now()); return ack;
        });
    }
    private UUID authorize(Source source,UUID question) {
        require(source!=null,INVALID_INPUT); UUID learner=LearningActor.lock(users);
        require(authorities.size()==1 && authorities.get(0).allows(learner,source),SOURCE_UNAVAILABLE);
        if(question!=null) require(schedules.sourceExists(new Key(learner,source,question)),SOURCE_UNAVAILABLE);
        return learner;
    }
    private View owned(UUID learner,UUID id,boolean lock) { return plans.plan(learner,id,lock).orElseThrow(() -> new LearningSessionException(UNAVAILABLE)); }
    private void bound(int limit) { Integer max=limits.getMaxBatchSize(); require(max!=null && max>0 && max<Integer.MAX_VALUE,PERSISTENCE_UNAVAILABLE); require(limit>0 && limit<=max,INVALID_INPUT); }
    private static boolean storable(Instant time) {
        return !time.isBefore(Instant.parse("1000-01-01T00:00:00Z")) && time.isBefore(Instant.parse("+10000-01-01T00:00:00Z"));
    }
    private Instant now() { var time=clock.instant().truncatedTo(ChronoUnit.MICROS); require(storable(time),INVALID_INPUT); return time; }
    private <T> T run(String op,Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED),UNSUPPORTED_TRANSACTION);
        String outcome="failed";
        var deferred=new AtomicBoolean();
        try { return transaction.execute(status -> { try {
            T result=work.get();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    record(op,switch(status) {
                        case STATUS_COMMITTED -> "success";
                        case STATUS_ROLLED_BACK -> "rolled_back";
                        default -> "unknown";
                    });
                }
            });
            deferred.set(true);
            return result;
        } catch(RuntimeException failure) {
            status.setRollbackOnly(); throw sanitized(failure); } }); }
        catch(RuntimeException failure) { outcome=failure instanceof LearningSessionException e?e.reason().name().toLowerCase(Locale.ROOT):"unavailable"; throw sanitized(failure); }
        finally { if(!deferred.get()) record(op,outcome); }
    }
    private void record(String operation,String outcome) {
        try { metrics.counter("quizmaker.learning.plans","operation",operation,"outcome",outcome,"version","1").increment(); }
        catch(RuntimeException ignored) { /* Telemetry cannot change transaction completion. */ }
    }
    private static RuntimeException sanitized(RuntimeException failure) {
        if(failure instanceof LearningSessionException known) return new LearningSessionException(known.reason());
        if(failure instanceof RateLimitExceededException rate) return rate;
        if(failure instanceof uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException known)
            return new LearningSessionException(switch(known.reason()) {
                case PERSISTENCE_UNAVAILABLE -> PERSISTENCE_UNAVAILABLE;
                case BINDING_CONFLICT -> CONFLICT;
                case INVALID_STORED_BINDING, TRANSACTION_REQUIRED, INVALID_SCOPE, SCOPE_REQUIRED, SCOPE_VIOLATION -> CORRUPT;
                case REVISION_UNAVAILABLE -> SOURCE_UNAVAILABLE;
                default -> UNSUPPORTED_VERSION;
            });
        if(failure instanceof LearningScheduleException known) return new LearningSessionException(switch(known.reason()) {
            case UNAUTHENTICATED -> UNAUTHENTICATED;
            case UNAVAILABLE, SOURCE_UNAVAILABLE, SESSION_UNAVAILABLE -> UNAVAILABLE;
            case CONFLICT, STALE_STATE, ACTIVE_SESSION, SESSION_INCOMPLETE -> CONFLICT;
            case UNSUPPORTED_VERSION, UNSUPPORTED_MANIFEST -> UNSUPPORTED_VERSION;
            case UNSUPPORTED_TRANSACTION -> UNSUPPORTED_TRANSACTION;
            case PERSISTENCE_UNAVAILABLE -> PERSISTENCE_UNAVAILABLE;
            default -> CORRUPT;
        });
        return DatabaseConflictException.from(failure).<RuntimeException>map(value -> value)
                .orElseGet(() -> new LearningSessionException(PERSISTENCE_UNAVAILABLE));
    }
}
