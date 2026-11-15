package uk.gegc.quizmaker.features.assignment.application.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.features.assignment.application.*;
import uk.gegc.quizmaker.features.assignment.application.AssignmentManagementAuthority.Action;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentContract.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublication.*;
import uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException;
import uk.gegc.quizmaker.features.assignment.domain.repository.AssignmentPublicationRepository;
import uk.gegc.quizmaker.features.attempt.infra.receipt.CanonicalCommandInput;
import uk.gegc.quizmaker.features.user.application.AuthenticatedUserLookup;
import uk.gegc.quizmaker.shared.security.MembershipResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.*;
import static uk.gegc.quizmaker.features.assignment.domain.model.AssignmentPublicationException.Reason.*;

@Service
@PreAuthorize("isAuthenticated()")
public class AssignmentPublicationServiceImpl implements AssignmentPublicationService {
    private final AssignmentPublicationRepository repository;
    private final AuthenticatedUserLookup users;
    private final List<AssignmentSourceVerifier> sources;
    private final List<AssignmentManagementAuthority> authorities;
    private final MembershipResolver memberships;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public AssignmentPublicationServiceImpl(AssignmentPublicationRepository repository, AuthenticatedUserLookup users,
            List<AssignmentSourceVerifier> sources, List<AssignmentManagementAuthority> authorities,
            MembershipResolver memberships, Clock clock, MeterRegistry metrics,
            ObjectMapper mapper, PlatformTransactionManager manager) {
        this.repository=repository; this.users=users; this.sources=List.copyOf(sources); this.memberships=memberships;
        this.authorities=List.copyOf(authorities);
        this.clock=clock; this.metrics=metrics; this.mapper=mapper;
        this.transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override public Result create(Save command) { return save(command,Operation.CREATE); }
    @Override public Result save(Save command) { return save(command,Operation.SAVE); }
    private Result save(Save c,Operation operation) {
        return run(operation.name().toLowerCase(Locale.ROOT),()-> {
            require(c!=null && c.assignmentId()!=null && c.commandId()!=null && c.scope()!=null && c.definition()!=null
                    && (operation==Operation.CREATE?c.expectedVersion()==0:c.expectedVersion()>0),INVALID_INPUT);
            var actor=actor();
            var hash=hash(operation,c);
            var now=now();
            var action=operation==Operation.CREATE ? Action.CREATE_DRAFT : Action.SAVE_DRAFT;
            Head head;
            if(operation==Operation.CREATE) {
                var existing=repository.head(c.assignmentId(),true);
                if(existing.isPresent()) head=owned(existing.get(),actor);
                else {
                    requireMembership(actor,c.scope());
                    requireAuthority(actor,action,c.assignmentId(),c.scope());
                    repository.create(c.assignmentId(),actor,c.scope(),now);
                    head=owned(c.assignmentId(),actor,true);
                }
            } else head=owned(c.assignmentId(),actor,true);
            // Recheck the persisted scope, including a concurrent create winner and every replay.
            requireAuthority(actor,action,head.assignmentId(),head.scope());
            var replay=replay(head,actor,c.commandId(),operation,hash);
            if(replay.isPresent()) return replay.get();
            require(head.version()==c.expectedVersion(),STALE);
            require(head.scope().equals(c.scope()),INVALID_REFERENCE);
            var designations=verify(actor,c.scope(),c.definition());
            int number=head.draftId()==null?1:Math.addExact(snapshot(head,head.draftId(),Status.DRAFT).snapshot().version(),1);
            var captured=new Captured(new Snapshot(UUID.randomUUID(),head.assignmentId(),number,1,Status.DRAFT,
                    actor,head.scope(),c.definition(),now),designations);
            repository.insert(captured);
            var next=new Head(head.assignmentId(),actor,head.scope(),Math.addExact(head.version(),1),captured.snapshot().id(),
                    head.publicationId(),head.publishedDraftId());
            repository.point(head,next);
            repository.record(head.assignmentId(),actor,c.commandId(),new Receipt(operation,hash,next.version(),captured.snapshot().id()),now);
            return new Result(next.version(),captured,false);
        });
    }
    @Override public Result publish(Publish c) {
        return run("publish",()-> {
            require(c!=null && c.assignmentId()!=null && c.commandId()!=null && c.expectedVersion()>0,INVALID_INPUT);
            var actor=actor();
            var head=owned(c.assignmentId(),actor,true);
            requireAuthority(actor,Action.PUBLISH,head.assignmentId(),head.scope());
            var hash=hash(Operation.PUBLISH,c);
            var replay=replay(head,actor,c.commandId(),Operation.PUBLISH,hash);
            if(replay.isPresent()) return replay.get();
            require(head.version()==c.expectedVersion(),STALE);
            require(head.draftId()!=null,CORRUPT);
            var draft=snapshot(head,head.draftId(),Status.DRAFT);
            // Revalidate current source rights and immutable audience provenance before a new publication.
            var labels=verify(actor,head.scope(),draft.snapshot().definition());
            require(labels.equals(draft.designations()),INVALID_REFERENCE);
            var now=now();
            Captured published;
            Head next;
            if(head.draftId().equals(head.publishedDraftId())) {
                published=snapshot(head,head.publicationId(),Status.PUBLISHED); next=head;
            } else {
                int number=head.publicationId()==null?1:Math.addExact(snapshot(head,head.publicationId(),Status.PUBLISHED).snapshot().version(),1);
                published=new Captured(new Snapshot(UUID.randomUUID(),head.assignmentId(),number,1,Status.PUBLISHED,
                        actor,head.scope(),draft.snapshot().definition(),now),labels);
                repository.insert(published);
                next=new Head(head.assignmentId(),actor,head.scope(),Math.addExact(head.version(),1),head.draftId(),published.snapshot().id(),head.draftId());
                repository.point(head,next);
            }
            repository.record(head.assignmentId(),actor,c.commandId(),new Receipt(Operation.PUBLISH,hash,next.version(),published.snapshot().id()),now);
            return new Result(next.version(),published,false);
        });
    }
    @Override public Result draft(UUID assignmentId) {
        return run("draft",()-> { var actor=actor(); var h=owned(assignmentId,actor,false);
            requireAuthority(actor,Action.READ_DRAFT,h.assignmentId(),h.scope());
            require(h.draftId()!=null,CORRUPT); return new Result(h.version(),snapshot(h,h.draftId(),Status.DRAFT),false); });
    }
    @Override public Optional<Result> current(UUID assignmentId) {
        return run("current",()-> { var actor=actor(); var h=owned(assignmentId,actor,false);
            requireAuthority(actor,Action.READ_CURRENT,h.assignmentId(),h.scope());
            return h.publicationId()==null?Optional.empty():Optional.of(new Result(h.version(),snapshot(h,h.publicationId(),Status.PUBLISHED),false)); });
    }
    @Override public Result read(UUID assignmentId,UUID publicationId) {
        return run("read",()-> { var actor=actor(); var h=owned(assignmentId,actor,false);
            requireAuthority(actor,Action.READ_PUBLICATION,h.assignmentId(),h.scope());
            require(publicationId!=null,INVALID_INPUT); return new Result(h.version(),snapshot(h,publicationId,Status.PUBLISHED),false); });
    }
    private UUID actor() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        require(authentication instanceof UsernamePasswordAuthenticationToken && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof UserDetails,UNAUTHENTICATED);
        var p=(UserDetails)authentication.getPrincipal();
        require(p.isEnabled() && p.isAccountNonLocked() && p.isAccountNonExpired() && p.isCredentialsNonExpired()
                && p.getUsername()!=null && p.getUsername().equals(authentication.getName()),UNAUTHENTICATED);
        return users.lockActiveUserId(p.getUsername()).orElseThrow(()->new AssignmentPublicationException(UNAVAILABLE));
    }
    private Head owned(UUID id,UUID actor,boolean lock) {
        require(id!=null,INVALID_INPUT);
        return owned(repository.head(id,lock).orElseThrow(()->new AssignmentPublicationException(UNAVAILABLE)),actor);
    }
    private Head owned(Head h,UUID actor) {
        require(h.ownerId().equals(actor),UNAVAILABLE);
        requireMembership(actor,h.scope());
        return h;
    }
    private void requireMembership(UUID actor,Scope scope) {
        require(scope.kind()==ScopeKind.PERSONAL || memberships.isMemberOfOrganization(actor,scope.organizationId()),UNAVAILABLE);
    }
    private void requireAuthority(UUID actor,Action action,UUID assignment,Scope scope) {
        require(authorities.size()==1,UNAVAILABLE);
        require(authorities.get(0).allows(new AssignmentManagementAuthority.Context(actor,action,assignment,scope)),UNAVAILABLE);
    }
    private Map<UUID,String> verify(UUID actor,Scope scope,Definition definition) {
        require(sources.size()==1,INVALID_REFERENCE);
        var labels=sources.get(0).verify(actor,scope,definition);
        require(repository.revisionExists(definition.quizRevisionId(),definition.contentSchemaVersion()),INVALID_REFERENCE);
        return labels;
    }
    private Captured snapshot(Head h,UUID id,Status status) {
        var value=repository.snapshot(h.assignmentId(),id,status).orElseThrow(()->new AssignmentPublicationException(UNAVAILABLE));
        require(value.snapshot().ownerId().equals(h.ownerId()) && value.snapshot().scope().equals(h.scope()),CORRUPT);
        return value;
    }
    private Optional<Result> replay(Head h,UUID actor,UUID command,Operation operation,String hash) {
        return repository.receipt(h.assignmentId(),actor,command).map(r->{
            require(r.operation()==operation && r.fingerprint().equals(hash),CONFLICT);
            require(r.version()>0 && r.version()<=h.version(),CORRUPT);
            return new Result(r.version(),snapshot(h,r.snapshotId(),operation==Operation.PUBLISH?Status.PUBLISHED:Status.DRAFT),true);
        });
    }
    private String hash(Operation operation,Object command) {
        var input=mapper.createObjectNode().put("version",1).put("operation",operation.name());
        input.set("command",mapper.valueToTree(command));
        return CanonicalCommandInput.fingerprint(1,input).digest();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private <T> T run(String operation,Supplier<T> work) {
        require(!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                && Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),TransactionDefinition.ISOLATION_READ_COMMITTED),
                UNSUPPORTED_TRANSACTION);
        String outcome="unavailable";
        try {
            T result=transaction.execute(status->{
                try { return work.get(); }
                catch(AssignmentPublicationException failure) { throw new AssignmentPublicationException(failure.reason()); }
                catch(RuntimeException failure) { throw new AssignmentPublicationException(UNAVAILABLE); }
            });
            outcome=result instanceof Result r && r.replayed()?"replayed":"success";
            return result;
        } catch(AssignmentPublicationException failure) { outcome=failure.reason().name().toLowerCase(Locale.ROOT); throw failure; }
        catch(RuntimeException failure) { throw new AssignmentPublicationException(UNAVAILABLE); }
        finally { try { metrics.counter("quizmaker.assignment.publication","operation",operation,"outcome",outcome).increment(); }
            catch(RuntimeException ignored) { /* Telemetry cannot change the transaction outcome. */ } }
    }
}
