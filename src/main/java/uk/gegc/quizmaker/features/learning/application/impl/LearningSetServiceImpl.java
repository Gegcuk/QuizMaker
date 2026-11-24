package uk.gegc.quizmaker.features.learning.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.learning.application.*;
import uk.gegc.quizmaker.features.learning.config.LearningDueSelectionConfig;
import uk.gegc.quizmaker.features.learning.domain.model.*;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSet.*;
import uk.gegc.quizmaker.features.learning.domain.repository.*;
import uk.gegc.quizmaker.features.learning.infra.scheduling.LearningPlanFormat;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.exception.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.Reason.*;
import static uk.gegc.quizmaker.features.learning.domain.model.LearningSessionException.require;

@Service
@PreAuthorize("isAuthenticated()")
public class LearningSetServiceImpl implements LearningSetService {
    private final LearningSetRepository repository;
    private final AuthenticatedUserLookup users;
    private final List<LearningSessionSourceVerifier> sources;
    private final LearningCommandBudget budget;
    private final LearningDueSelectionConfig limits;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;
    private final LearningPlanFormat format=new LearningPlanFormat();
    private final boolean custom;

    public LearningSetServiceImpl(LearningSetRepository repository, AuthenticatedUserLookup users,
            List<LearningSessionSourceVerifier> sources, LearningCommandBudget budget,
            LearningDueSelectionConfig limits, Clock clock, MeterRegistry metrics, PlatformTransactionManager manager,
            @Value("${quizmaker.learning.custom-plans-enabled:false}") boolean customPlans,
            @Value("${quizmaker.learning.custom-reviews-enabled:false}") boolean customReviews) {
        this.repository=repository; this.users=users; this.sources=List.copyOf(sources); this.budget=budget;
        this.limits=limits; this.clock=clock; this.metrics=metrics; custom=customPlans && customReviews;
        transaction=new TransactionTemplate(manager); transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public Acknowledgement create(Create request) {
        require(request!=null && request.name()!=null && !request.name().isBlank() && request.name().length()<=255
                && request.name().equals(request.name().strip()) && request.name().chars().noneMatch(Character::isISOControl),INVALID_INPUT);
        return mutate("create",null,request.commandId(),request,null,learner -> repository.create(learner,request.name(),now()));
    }
    @Override public Acknowledgement change(UUID setId,Change request) {
        require(request!=null && request.member()!=null && request.expectedVersion()>0,INVALID_INPUT);
        Member member=request.member();
        return mutate("member",setId,request.commandId(),request,request.present()?member:null,learner -> {
            View set=owned(learner,setId,false); require(set.version()==request.expectedVersion(),CONFLICT);
            if(request.present() && member.kind()==Kind.SET) {
                owned(learner,member.childSetId(),false);
                require(!repository.reaches(learner,member.childSetId(),setId),CONFLICT);
            }
            return repository.change(learner,set,member,request.present(),now());
        });
    }
    @Override public Acknowledgement delete(UUID setId,Delete request) {
        require(request!=null && request.expectedVersion()>0,INVALID_INPUT);
        return mutate("delete",setId,request.commandId(),request,null,learner -> {
            View set=owned(learner,setId,false); require(set.version()==request.expectedVersion(),CONFLICT);
            return repository.delete(learner,set,now());
        });
    }
    @Override public View read(UUID setId) {
        require(setId!=null,INVALID_INPUT);
        return run("read",false,() -> { UUID learner=LearningActor.lock(users); repository.lockGraph(learner,false); return owned(learner,setId,false); });
    }
    @Override public SetPage list(int limit,UUID after) {
        bound(limit);
        return run("list",false,() -> {
            UUID learner=LearningActor.lock(users); repository.lockGraph(learner,false);
            var found=repository.list(learner,limit+1,after); var page=page(found,limit);
            return new SetPage(page,found.size()>limit?page.get(page.size()-1).setId():null);
        });
    }
    @Override public MemberPage members(UUID setId,int limit,String after) {
        bound(limit); require(setId!=null,INVALID_INPUT); Scope scope=new Scope(setId,null);
        Cursor cursor=cursor(after,scope,null);
        return run("members",false,() -> {
            UUID learner=LearningActor.lock(users); long graph=repository.lockGraph(learner,false);
            owned(learner,setId,false); checkGraph(cursor,graph);
            var found=repository.members(learner,setId,limit+1,cursor==null?null:cursor.memberKey()); var page=page(found,limit);
            String next=found.size()>limit?token(new Cursor(1,scope,null,graph,null,null,null,null,page.get(page.size()-1).key())):null;
            return new MemberPage(page,next);
        });
    }
    @Override public ItemPage items(Scope scope,Window window,int limit,String after) {
        bound(limit); require(scope!=null && window!=null,INVALID_INPUT); Cursor cursor=cursor(after,scope,window);
        return run("items",false,() -> {
            UUID learner=LearningActor.lock(users); long graph=repository.lockGraph(learner,false);
            if(scope.setId()!=null) owned(learner,scope.setId(),false);
            else authorize(learner,new Member(Kind.QUIZ,null,scope.source(),null));
            checkGraph(cursor,graph); repository.lockSources(learner,scope);
            Instant now=now(); var found=repository.items(learner,scope,window,now,limit+1,cursor,custom);
            var page=page(found,limit); Item last=page.isEmpty()?null:page.get(page.size()-1);
            String next=found.size()>limit?token(new Cursor(1,scope,window,graph,last.dueAt(),last.source().quizId(),
                    last.source().quizRevisionId(),last.questionRevisionId(),null)):null;
            return new ItemPage(window,now,page,next);
        });
    }
    private Acknowledgement mutate(String op,UUID setId,UUID command,Object input,Member added,Function<UUID,View> work) {
        require(command!=null && (op.equals("create") || setId!=null),INVALID_INPUT);
        String canonical=format.encode(Arrays.asList(op,setId,input));
        return run(op,true,() -> {
            UUID learner=LearningActor.lock(users);
            if(added!=null && added.source()!=null) authorize(learner,added);
            budget.chargeIfNew(learner,() -> repository.receipt(learner,command).isPresent());
            long graph=repository.lockGraph(learner,true);
            if(setId!=null) owned(learner,setId,true);
            var old=repository.receipt(learner,command);
            if(old.isPresent()) {
                require(old.get().input().equals(canonical) && old.get().result().commandId().equals(command),CONFLICT);
                return old.get().result();
            }
            require(graph<Long.MAX_VALUE,CONFLICT);
            var ack=new Acknowledgement(command,work.apply(learner),repository.advanceGraph(learner));
            repository.record(learner,command,canonical,ack,now()); return ack;
        });
    }
    private void authorize(UUID learner,Member member) {
        require(sources.size()==1 && sources.get(0).allows(learner,member.source()),SOURCE_UNAVAILABLE);
        if(member.questionRevisionId()!=null) require(repository.containsQuestion(member),SOURCE_UNAVAILABLE);
    }
    private View owned(UUID learner,UUID set,boolean deleted) {
        View view=repository.find(learner,set).orElseThrow(() -> new LearningSessionException(UNAVAILABLE));
        require(deleted || !view.deleted(),UNAVAILABLE); return view;
    }
    private void bound(int limit) {
        Integer max=limits.getMaxBatchSize(); require(max!=null && max>0 && max<Integer.MAX_VALUE,PERSISTENCE_UNAVAILABLE);
        require(limit>0 && limit<=max,INVALID_INPUT);
    }
    private Cursor cursor(String token,Scope scope,Window window) {
        if(token==null) return null;
        require(token.length()<=LearningPlanFormat.MAX_BYTES,INVALID_INPUT);
        try {
            Cursor c=format.decode(new String(Base64.getUrlDecoder().decode(token),StandardCharsets.UTF_8),Cursor.class);
            require(c.version()==1 && c.scope().equals(scope) && c.window()==window && c.graphVersion()>=0,INVALID_INPUT);
            require(window==null ? c.memberKey()!=null && c.memberKey().length()<=160 && c.dueAt()==null
                    : c.memberKey()==null && c.dueAt()!=null && storable(c.dueAt()) && c.quizId()!=null && c.revisionId()!=null && c.questionId()!=null,INVALID_INPUT);
            return c;
        } catch(RuntimeException failure) { throw new LearningSessionException(INVALID_INPUT); }
    }
    private String token(Cursor cursor) { return Base64.getUrlEncoder().withoutPadding().encodeToString(format.encode(cursor).getBytes(StandardCharsets.UTF_8)); }
    private static void checkGraph(Cursor cursor,long graph) { require(cursor==null || cursor.graphVersion()==graph,CONFLICT); }
    private static <T> List<T> page(List<T> rows,int limit) { return List.copyOf(rows.subList(0,Math.min(rows.size(),limit))); }
    private Instant now() {
        Instant at=clock.instant().truncatedTo(ChronoUnit.MICROS);
        require(storable(at),INVALID_INPUT); return at;
    }
    private static boolean storable(Instant at) { return !at.isBefore(Instant.parse("1000-01-01T00:00:00Z")) && at.isBefore(Instant.parse("+10000-01-01T00:00:00Z")); }
    private <T> T run(String operation,boolean write,Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
                || (!write || !TransactionSynchronizationManager.isCurrentTransactionReadOnly())
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED),UNSUPPORTED_TRANSACTION);
        var deferred=new AtomicBoolean();
        try { return transaction.execute(status -> {
            try {
                T result=work.get();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        count(operation,switch(status) {
                            case STATUS_COMMITTED -> "success";
                            case STATUS_ROLLED_BACK -> "rolled_back";
                            default -> "unknown";
                        });
                    }
                });
                deferred.set(true);
                return result;
            } catch(RuntimeException failure) { status.setRollbackOnly(); throw safe(failure); }
        }); } catch(RuntimeException failure) { if(!deferred.get()) count(operation,"failed"); throw safe(failure); }
    }
    private void count(String operation,String outcome) {
        try { metrics.counter("quizmaker.learning.sets","operation",operation,"outcome",outcome,"version","1").increment(); }
        catch(RuntimeException ignored) { /* Optional telemetry cannot change the result. */ }
    }
    private static RuntimeException safe(RuntimeException failure) {
        if(failure instanceof LearningSessionException known) return new LearningSessionException(known.reason());
        if(failure instanceof RateLimitExceededException) return failure;
        if(failure instanceof LearningScheduleException known) return new LearningSessionException(switch(known.reason()) {
            case UNSUPPORTED_MANIFEST,UNSUPPORTED_VERSION -> UNSUPPORTED_VERSION;
            case PERSISTENCE_UNAVAILABLE -> PERSISTENCE_UNAVAILABLE;
            default -> CORRUPT;
        });
        return DatabaseConflictException.from(failure).<RuntimeException>map(value -> value)
                .orElseGet(() -> new LearningSessionException(PERSISTENCE_UNAVAILABLE));
    }
}
