package uk.gegc.quizmaker.features.learning.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.CustomSchedule.State;
import uk.gegc.quizmaker.features.learning.domain.model.LearningPlan.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Expected;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSession.Source;
import uk.gegc.quizmaker.features.learning.domain.repository.*;
import uk.gegc.quizmaker.features.learning.domain.repository.LearningScheduleRepository.*;
import uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Service
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Transactional(isolation=Isolation.READ_COMMITTED)
@ConditionalOnProperty(prefix="quizmaker.learning",name={"sessions-enabled","custom-plans-enabled","custom-reviews-enabled"},havingValue="true")
public class LearningCycleServiceImpl implements LearningCycleService {
    private final LearningScheduleRepository slots;
    private final LearningPlanRepository plans;
    private final CustomScheduleRepository store;
    private final LearningDueSelectionRepository selection;
    private final LearningScheduleService fsrs;
    private final LearningSessionService sessions;
    private final List<LearningSessionCompletionVerifier> completions;
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> authorities;
    private final LearningCommandBudget budget;
    private final LearningDueSelectionConfig limits;
    private final CustomSchedulingStrategy calculation;
    private final Clock clock;
    private final LearningPlanFormat format = new LearningPlanFormat();

    @Override public Snapshot read(Source source,UUID question) { return snapshot(key(source,question),false); }
    @Override public Snapshot readyForCapture(Source source,UUID question) { return snapshot(key(source,question),true); }
    @Override public List<LearningDueSelection.Item> due(Source source,int take) {
        UUID learner=authorize(source); Integer max=limits.getMaxBatchSize();
        require(max!=null && max>0 && max<Integer.MAX_VALUE,PERSISTENCE_UNAVAILABLE); require(take>0 && take<=max,INVALID_INPUT);
        return selection.findMixed(learner,source,now(),take);
    }
    @Override public void capture(Source source,UUID question,Expected expected,UUID sessionId,boolean practice) {
        var key=key(source,question); var current=snapshot(key,true);
        require(current.expected().equals(expected) && (practice || !current.completed() && !current.paused()),STALE_REVIEW);
        var session=sessions.read(sessionId);
        require(session.learnerId().equals(key.learnerId()) && session.source().equals(source)
                && slots.sessionContains(key,sessionId),UNAVAILABLE);
        slots.captureCycle(key,expected,sessionId);
    }
    @Override public Instant rate(Source source,UUID question,Expected expected,Rating rating,UUID command,boolean requireDue) {
        require(expected!=null && command!=null && rating!=null,INVALID_INPUT);
        var key=key(source,question); var current=snapshot(key,true);
        require(current.expected().equals(expected) && !current.completed()
                && (!requireDue || !current.dueAt().isAfter(now())),STALE_REVIEW);
        var header=slots.lockCycle(key).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
        if("fsrs/v1".equals(header.strategyId())) return fsrs.execute(new LearningScheduleService.Rate(
                1,command,source,question,expected,rating)).snapshot().state().dueAt();
        var cycle=custom(key); var definition=definition(key,cycle); var before=store.state(key,cycle,definition);
        var at=now(); var transition=calculation.review(definition,before,rating,at,
                stage -> store.stage(cycle.cycleId(),stage));
        store.change(key,cycle,before,transition.state(),command,rating.name(),transition.generated(),at);
        return transition.state().completed()?null:transition.state().dueAt();
    }
    @Override public void release(Source source,UUID question,Expected expected,UUID sessionId) {
        var key=key(source,question); var current=snapshot(key,true);
        require(current.expected().equals(expected),STALE_REVIEW);
        var session=sessions.read(sessionId);
        require(session.learnerId().equals(key.learnerId()) && session.source().equals(source)
                && slots.sessionContains(key,sessionId) && completions.size()==1
                && completions.get(0).completed(session),CONFLICT);
        slots.releaseCycle(key,expected,sessionId);
    }
    @Override public Preview preview(Source source,UUID question,String zone) {
        var key=key(source,question); snapshot(key,false); return preview(key,zone);
    }
    @Override public Acknowledgement confirm(UUID command,Preview preview,boolean confirmed) {
        require(command!=null && preview!=null && confirmed,INVALID_INPUT);
        var key=key(preview.source(),preview.questionRevisionId());
        String input=format.encode(Arrays.asList("timezone",preview));
        budget.chargeIfNew(key.learnerId(),() -> {
            if(plans.receipt(key.learnerId(),command).isPresent()) return true;
            require(!store.timezoneCommandConflicts(key.learnerId(),command),CONFLICT);
            return false;
        });
        var saved=plans.receipt(key.learnerId(),command);
        if(saved.isPresent()) {
            require(input.equals(saved.get().input()),CONFLICT);
            var result=saved.get().result();
            require(result.commandId().equals(command) && result.cycle()!=null
                    && result.cycle().questionRevisionId().equals(preview.questionRevisionId())
                    && result.cycle().expected().equals(preview.expected())
                    && result.cycle().dueAt().equals(preview.nextDueAt()),CORRUPT);
            return result;
        }
        var current=snapshot(key,true);
        require(current.expected().equals(preview.expected()),CONFLICT);
        require(preview.equals(preview(key,preview.timeZone())) && !slots.hasCaptures(key),CONFLICT);
        var cycle=custom(key); var before=store.state(key,cycle,definition(key,cycle));
        require(before.version()<Long.MAX_VALUE,CONFLICT);
        var after=new State(before.version()+1,before.sequence(),before.stage(),before.reviewedAt(),
                preview.nextDueAt(),preview.timeZone(),before.completed());
        store.change(key,cycle,before,after,command,"TIMEZONE",null,now());
        var result=new Acknowledgement(command,null,null,custom(key));
        plans.record(key.learnerId(),command,input,result,now()); return result;
    }
    private Preview preview(Key key,String zone) {
        require(zone!=null,INVALID_INPUT);
        try { zone=ZoneId.of(zone).getId(); } catch(DateTimeException failure) { throw new LearningSessionException(INVALID_INPUT); }
        var cycle=custom(key); var definition=definition(key,cycle); var state=store.state(key,cycle,definition);
        require(!state.completed(),CONFLICT);
        var interval=state.stage()<definition.intervals().size()?definition.intervals().get(state.stage()):store.stage(cycle.cycleId(),state.stage());
        Instant due=state.reviewedAt()==null?state.dueAt():calculation.due(interval,state.reviewedAt(),zone,definition.localTime());
        return new Preview(key.source(),key.questionRevisionId(),cycle.expected(),state.version(),zone,state.dueAt(),due);
    }
    private Snapshot snapshot(Key key,boolean lock) {
        var header=(lock?slots.lockCycle(key):slots.readCycle(key)).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
        if("fsrs/v1".equals(header.strategyId())) {
            var value=fsrs.read(key.source(),key.questionRevisionId()); return new Snapshot(value.expected(),value.state().dueAt(),false,false);
        }
        require("custom/v1".equals(header.strategyId()),UNSUPPORTED_VERSION);
        var cycle=custom(key); require(cycle.cycleId().equals(header.id()) && cycle.cycleVersion()==header.version(),CORRUPT);
        var definition=definition(key,cycle);
        store.state(key,cycle,definition);
        for (int stage : new int[]{cycle.stage(),Math.max(0,cycle.stage()-2)})
            if (stage>=definition.intervals().size()) store.stage(cycle.cycleId(),stage);
        return new Snapshot(cycle.expected(),cycle.dueAt(),cycle.completed(),cycle.paused());
    }
    private Cycle custom(Key key) { return plans.custom(key).orElseThrow(() -> new LearningSessionException(UNAVAILABLE)); }
    private Definition definition(Key key,Cycle cycle) { return plans.version(key.learnerId(),cycle.planId(),cycle.planVersion())
            .orElseThrow(() -> new LearningSessionException(CORRUPT)).definition(); }
    private Key key(Source source,UUID question) {
        require(question!=null,INVALID_INPUT); var key=new Key(authorize(source),source,question);
        require(slots.sourceExists(key),SOURCE_UNAVAILABLE); return key;
    }
    private UUID authorize(Source source) {
        require(TransactionSynchronizationManager.isActualTransactionActive() && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED),UNSUPPORTED_TRANSACTION);
        require(source!=null,INVALID_INPUT); UUID learner=LearningActor.lock(users);
        require(authorities.size()==1 && authorities.get(0).allows(learner,source),SOURCE_UNAVAILABLE); return learner;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
