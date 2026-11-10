package uk.gegc.quizmaker.shared.transaction;

import jakarta.persistence.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import uk.gegc.quizmaker.shared.exception.DatabaseConflictException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Framework category vectors; real persistence/response-loss proof is in the MySQL expiry tests. */
@Isolated("Temporarily captures transaction-manager diagnostic levels")
class SafeJpaTransactionManagerTest {
    enum FailureKind { RESOURCE, DEADLOCK, TIMEOUT, OBJECT_OPTIMISTIC, JPA_OPTIMISTIC, JPA_ROLLBACK, TRANSACTION, TRANSACTION_TIMEOUT, UNEXPECTED, HEURISTIC, RUNTIME }
    static Stream<Arguments> commitCases() {
        return Arrays.stream(FailureKind.values()).flatMap(kind->Stream.of(false,true)
                .flatMap(rollbackOnFailure->(kind==FailureKind.RESOURCE?Stream.of(false,true):Stream.of(false))
                        .map(committed->Arguments.of(kind,rollbackOnFailure,committed))));
    }
    static RuntimeException failure(FailureKind kind) {
        var sql=new SQLException("private-completion-driver",kind==FailureKind.DEADLOCK?"40001":"HY000",
                kind==FailureKind.DEADLOCK?1213:kind==FailureKind.TIMEOUT?1205:0);
        sql.initCause(new IllegalStateException("private-completion-cause"));
        sql.addSuppressed(new IllegalStateException("private-completion-suppressed"));
        RuntimeException value=switch(kind) {
            case RESOURCE, DEADLOCK, TIMEOUT -> new DataAccessResourceFailureException("private-completion-message",sql);
            case OBJECT_OPTIMISTIC -> new ObjectOptimisticLockingFailureException("private-completion-message",sql);
            case JPA_OPTIMISTIC -> new RollbackException("private-completion-message",new OptimisticLockException("private-completion-entity",sql));
            case JPA_ROLLBACK -> new RollbackException("private-completion-message");
            case TRANSACTION -> new TransactionException("private-completion-message",sql) { };
            case TRANSACTION_TIMEOUT -> new TransactionTimedOutException("private-completion-message",sql);
            case UNEXPECTED -> new UnexpectedRollbackException("private-completion-message",sql);
            case HEURISTIC -> new HeuristicCompletionException(HeuristicCompletionException.STATE_MIXED,sql);
            case RUNTIME -> new UnsupportedOperationException("private-completion-message",sql);
        };
        value.addSuppressed(new IllegalStateException("private-completion-root-suppressed"));
        return value;
    }
    static String category(Throwable failure) {
        if(failure==null) return "success";
        if(failure instanceof UnexpectedRollbackException) return "unexpected-rollback";
        if(failure instanceof HeuristicCompletionException h) return "heuristic-"+h.getOutcomeState();
        if(failure instanceof TransactionSystemException) return "transaction-system";
        if(failure instanceof TransactionTimedOutException) return "transaction-timeout";
        if(failure instanceof TransactionException) return "transaction";
        if(failure instanceof ObjectOptimisticLockingFailureException) return "object-optimistic";
        if(failure instanceof DataAccessException) return "data-access";
        return "runtime";
    }
    record Observation(List<String> calls,List<Integer> completions,String category,String conflict) { }
    static final class Capture implements AutoCloseable {
        final ch.qos.logback.classic.Logger root=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender=new ch.qos.logback.core.read.ListAppender<>();
        final Map<ch.qos.logback.classic.Logger,ch.qos.logback.classic.Level> levels=new HashMap<>();
        Capture(boolean safe) {
            for(String name:List.of(JpaTransactionManager.class.getName(),SafeJpaTransactionManager.class.getName(),TransactionTemplate.class.getName())) {
                var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(name);
                levels.put(logger,logger.getLevel());
                logger.setLevel(safe?ch.qos.logback.classic.Level.TRACE:ch.qos.logback.classic.Level.OFF);
            }
            appender.start(); root.addAppender(appender);
        }
        String rendered() { return appender.list.stream().map(e->e.getFormattedMessage()+"\n"+(e.getThrowableProxy()==null?"":
                ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(e.getThrowableProxy()))).reduce("",(a,b)->a+"\n"+b); }
        @Override public void close() { levels.forEach(ch.qos.logback.classic.Logger::setLevel);root.detachAppender(appender);appender.stop(); }
    }
    static final class Provider {
        final EntityManagerFactory factory=mock(EntityManagerFactory.class);
        final List<String> calls=new ArrayList<>();
        final List<Integer> completions=new ArrayList<>();
        RuntimeException commitFailure,rollbackFailure;
        boolean commitApplied;
        Provider() {
            when(factory.getProperties()).thenReturn(Map.of());
            when(factory.createEntityManager()).thenAnswer(invocation->{
                int id=(int)calls.stream().filter(s->s.startsWith("create-")).count(); calls.add("create-"+id);
                var em=mock(EntityManager.class);var tx=mock(EntityTransaction.class);
                var active=new AtomicBoolean();var rollbackOnly=new AtomicBoolean();var open=new AtomicBoolean(true);
                when(em.getTransaction()).thenReturn(tx);when(em.isOpen()).thenAnswer(i->open.get());
                when(tx.isActive()).thenAnswer(i->active.get());when(tx.getRollbackOnly()).thenAnswer(i->rollbackOnly.get());
                doAnswer(i->{calls.add("begin-"+id);active.set(true);return null;}).when(tx).begin();
                doAnswer(i->{calls.add("rollback-only-"+id);rollbackOnly.set(true);return null;}).when(tx).setRollbackOnly();
                doAnswer(i->{
                    calls.add("commit-"+id);
                    if(rollbackOnly.get()) { calls.add("provider-rollback-"+id);active.set(false);throw new RollbackException("marked rollback-only"); }
                    if(commitFailure instanceof RollbackException || commitFailure instanceof UnexpectedRollbackException) {
                        calls.add("provider-rollback-"+id);active.set(false);
                    } else if(commitApplied || commitFailure==null) { calls.add("applied-"+id);active.set(false); }
                    if(commitFailure!=null) throw commitFailure;
                    return null;
                }).when(tx).commit();
                doAnswer(i->{calls.add("rollback-"+id);if(rollbackFailure!=null) throw rollbackFailure;active.set(false);return null;}).when(tx).rollback();
                doAnswer(i->{calls.add("close-"+id);open.set(false);return null;}).when(em).close();
                return em;
            });
        }
        JpaTransactionManager manager(boolean safe) {
            JpaTransactionManager result=safe?new SafeJpaTransactionManager():new JpaTransactionManager();
            result.setEntityManagerFactory(factory);result.afterPropertiesSet();return result;
        }
        void completion() { TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { completions.add(status); }
        }); }
        Observation observe(RuntimeException error) {
            assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String conflict=DatabaseConflictException.from(error).map(e->e.kind().name()).orElse("none");
            return new Observation(List.copyOf(calls),List.copyOf(completions),category(error),conflict);
        }
    }
    static void privateGraphAbsent(Throwable value) {
        if(value==null) return;
        assertThat(value.getMessage()).doesNotContain("private-completion");
        for(var suppressed:value.getSuppressed()) privateGraphAbsent(suppressed);
        privateGraphAbsent(value.getCause());
    }
    private Observation completion(boolean safe,FailureKind kind,boolean rollbackOnFailure,boolean applied,boolean explicitRollback,boolean rollbackFails) {
        var provider=new Provider(); provider.commitFailure=explicitRollback?null:failure(kind);provider.commitApplied=applied;
        if(rollbackFails) provider.rollbackFailure=failure(FailureKind.RESOURCE);
        var manager=provider.manager(safe);manager.setRollbackOnCommitFailure(rollbackOnFailure);
        RuntimeException error=null;
        try(var capture=new Capture(safe)) {
            var status=manager.getTransaction(new DefaultTransactionDefinition());provider.completion();
            try { if(explicitRollback) manager.rollback(status); else manager.commit(status); }
            catch(RuntimeException failure) { error=failure; }
            assertThat(error).isNotNull();
            if(safe) { privateGraphAbsent(error);assertThat(capture.rendered()).doesNotContain("private-completion"); }
        }
        return provider.observe(error);
    }
    @ParameterizedTest @MethodSource("commitCases")
    void completionCategoriesKeepOrdinaryManagerDecisions(FailureKind kind,boolean rollbackOnFailure,boolean applied) {
        var ordinary=completion(false,kind,rollbackOnFailure,applied,false,false);
        var safe=completion(true,kind,rollbackOnFailure,applied,false,false);
        assertThat(safe).isEqualTo(ordinary);
        if(kind==FailureKind.DEADLOCK) assertThat(safe.conflict()).isEqualTo("DEADLOCK");
        if(kind==FailureKind.TIMEOUT) assertThat(safe.conflict()).isEqualTo("LOCK_TIMEOUT");
        if(applied) assertThat(safe.calls()).contains("applied-0").doesNotContain("rollback-0");
        if(kind==FailureKind.TRANSACTION || kind==FailureKind.TRANSACTION_TIMEOUT || kind==FailureKind.JPA_ROLLBACK || kind==FailureKind.HEURISTIC) {
            if(!rollbackOnFailure) assertThat(safe.completions()).containsExactly(TransactionSynchronization.STATUS_UNKNOWN);
        }
        if(kind==FailureKind.RESOURCE && !applied) assertThat(safe.calls()).contains("rollback-0");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void rollbackErrorsAndCommitFollowedByRollbackErrorKeepTheOriginalUnknownOutcome(boolean explicitRollback) {
        var ordinary=completion(false,FailureKind.RESOURCE,false,false,explicitRollback,true);
        var safe=completion(true,FailureKind.RESOURCE,false,false,explicitRollback,true);
        assertThat(safe).isEqualTo(ordinary);
        assertThat(safe.calls().stream().filter(c->c.equals("rollback-0"))).hasSize(1);
        assertThat(safe.completions()).containsExactly(TransactionSynchronization.STATUS_UNKNOWN);
    }
    private Observation nested(boolean safe,int propagation,boolean failInner) {
        var provider=new Provider();var manager=provider.manager(safe);RuntimeException error=null;
        var outer=new TransactionTemplate(manager);var inner=new TransactionTemplate(manager);inner.setPropagationBehavior(propagation);
        try(var logs=new Capture(safe)) {
            try {
                outer.executeWithoutResult(status->{
                    provider.completion();
                    try { inner.executeWithoutResult(nested->{if(failInner) throw new DataAccessResourceFailureException("controlled denial");}); }
                    catch(DataAccessResourceFailureException expected) { /* owning caller catches; rollback-only remains */ }
                });
            } catch(RuntimeException failure) { error=failure; }
            if(safe) {privateGraphAbsent(error);assertThat(logs.rendered()).doesNotContain("private-completion");}
        }
        return provider.observe(error);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void requiredAndRequiresNewRetainJoiningSuspensionAndCaughtRollbackOnly(boolean failInner) {
        for(int propagation:List.of(TransactionDefinition.PROPAGATION_REQUIRED,TransactionDefinition.PROPAGATION_REQUIRES_NEW)) {
            var ordinary=nested(false,propagation,failInner);var safe=nested(true,propagation,failInner);
            assertThat(safe).isEqualTo(ordinary);
            if(propagation==TransactionDefinition.PROPAGATION_REQUIRED && failInner)
                assertThat(safe.calls()).contains("rollback-only-0","provider-rollback-0").doesNotContain("applied-0");
            if(propagation==TransactionDefinition.PROPAGATION_REQUIRES_NEW && failInner)
                assertThat(safe.calls()).contains("rollback-1","applied-0");
            if(!failInner) assertThat(safe.category()).isEqualTo("success");
        }
    }
    @Test void successfulCommitAndExplicitRollbackHaveTheSameCallbacksAndNoExtraAttempt() {
        for(boolean rollback:List.of(false,true)) {
            List<Observation> results=new ArrayList<>();
            for(boolean safe:List.of(false,true)) {
                var provider=new Provider();var manager=provider.manager(safe);
                var status=manager.getTransaction(new DefaultTransactionDefinition());provider.completion();
                if(rollback) manager.rollback(status);else manager.commit(status);
                results.add(provider.observe(null));
            }
            assertThat(results.get(0)).isEqualTo(results.get(1));
            assertThat(results.get(1).completions()).containsExactly(rollback?TransactionSynchronization.STATUS_ROLLED_BACK:TransactionSynchronization.STATUS_COMMITTED);
        }
    }
}
