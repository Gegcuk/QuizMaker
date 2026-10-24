package uk.gegc.quizmaker.features.question.application.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

@Service
public class QuestionRevisionContractServiceImpl implements QuestionRevisionContractService {
    private static final Object SCOPE_KEY = QuestionRevisionContractServiceImpl.class;
    private final QuestionContractBindingRepository repository;
    private final QuestionContractRegistry registry;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public QuestionRevisionContractServiceImpl(QuestionContractBindingRepository repository, QuestionContractRegistry registry,
                                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.registry = registry;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public void declareScope(UUID ownerId, Set<UUID> quizRevisionIds) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new ContractResolutionException(TRANSACTION_REQUIRED);
        }
        execute(readTransaction, () -> {
            declare(ownerId, quizRevisionIds);
            return null;
        });
    }

    private void declare(UUID ownerId, Set<UUID> quizRevisionIds) {
        if (ownerId == null || quizRevisionIds != null && quizRevisionIds.stream().anyMatch(Objects::isNull)) {
            throw new ContractResolutionException(REVISION_UNAVAILABLE);
        }
        if (quizRevisionIds == null) throw new ContractResolutionException(INVALID_SCOPE);
        var requested = Set.copyOf(quizRevisionIds);
        var existing = currentScope();
        if (existing != null) {
            if (!existing.ownerId().equals(ownerId)) throw new ContractResolutionException(REVISION_UNAVAILABLE);
            if (!existing.revisions().keySet().containsAll(requested)) throw new ContractResolutionException(SCOPE_VIOLATION);
            return;
        }
        var revisions = repository.findQuestionRevisionIds(ownerId, requested);
        if (!revisions.keySet().equals(requested)) throw new ContractResolutionException(REVISION_UNAVAILABLE);
        var questions = new HashSet<UUID>();
        revisions.values().forEach(questions::addAll);
        repository.lockQuestionRevisions(questions);
        var scope = new Scope(ownerId, revisions);
        TransactionSynchronizationManager.bindResource(SCOPE_KEY, scope);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                if (scope.violated) throw new ContractResolutionException(SCOPE_VIOLATION);
            }
            @Override public void suspend() { unbind(); }
            @Override public void resume() { TransactionSynchronizationManager.bindResource(SCOPE_KEY, scope); }
            @Override public void afterCompletion(int status) { unbind(); }
            private void unbind() {
                if (TransactionSynchronizationManager.getResource(SCOPE_KEY) == scope) {
                    TransactionSynchronizationManager.unbindResource(SCOPE_KEY);
                }
            }
        });
    }

    @Override
    public void bind(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings) {
        boolean standalone = !TransactionSynchronizationManager.isActualTransactionActive();
        execute(writeTransaction, () -> {
            if (standalone) declare(ownerId, Collections.singleton(quizRevisionId));
            var declared = requireRevision(ownerId, quizRevisionId);
            var actual = repository.findQuestionRevisionIds(ownerId, quizRevisionId)
                    .orElseThrow(() -> new ContractResolutionException(REVISION_UNAVAILABLE));
            requireMembership(declared, actual);
            if (bindings == null || bindings.stream().anyMatch(Objects::isNull)) {
                throw new ContractResolutionException(INVALID_BINDING);
            }
            var ids = new HashSet<UUID>();
            bindings.forEach(binding -> {
                if (!ids.add(binding.questionRevisionId())) throw new ContractResolutionException(INVALID_BINDING);
            });
            if (!ids.equals(actual)) throw new ContractResolutionException(INVALID_BINDING);
            var ordered = bindings.stream()
                    .sorted(Comparator.comparing(binding -> binding.questionRevisionId().toString())).toList();
            for (var binding : ordered) {
                registry.resolve(binding);
                repository.insert(ownerId, quizRevisionId, binding);
            }
            return null;
        });
    }

    @Override
    public Map<UUID, ResolvedQuestionContracts> resolve(UUID ownerId, UUID quizRevisionId) {
        boolean standalone = !TransactionSynchronizationManager.isActualTransactionActive();
        return execute(readTransaction, () -> {
            if (standalone) declare(ownerId, Collections.singleton(quizRevisionId));
            var declared = requireRevision(ownerId, quizRevisionId);
            var snapshot = repository.findForRevision(ownerId, quizRevisionId)
                    .orElseThrow(() -> new ContractResolutionException(REVISION_UNAVAILABLE));
            // A caller may have rolled its own newly inserted revision back to a savepoint.
            requireMembership(declared, snapshot.questionRevisionIds());
            if (!snapshot.bindings().keySet().equals(declared)) throw new ContractResolutionException(UNBOUND_REVISION);
            var resolved = new HashMap<UUID, ResolvedQuestionContracts>();
            snapshot.bindings().forEach((id, binding) -> resolved.put(id, registry.resolve(binding)));
            return Map.copyOf(resolved);
        });
    }

    private static Set<UUID> requireRevision(UUID ownerId, UUID quizRevisionId) {
        var scope = currentScope();
        if (scope == null) throw new ContractResolutionException(SCOPE_REQUIRED);
        if (!scope.ownerId().equals(ownerId) || quizRevisionId == null) throw new ContractResolutionException(REVISION_UNAVAILABLE);
        var ids = scope.revisions().get(quizRevisionId);
        if (ids == null) throw new ContractResolutionException(SCOPE_VIOLATION);
        return ids;
    }

    private static void requireMembership(Set<UUID> declared, Set<UUID> actual) {
        if (!declared.equals(actual)) throw new ContractResolutionException(SCOPE_VIOLATION);
    }

    private static Scope currentScope() {
        return (Scope) TransactionSynchronizationManager.getResource(SCOPE_KEY);
    }

    private <T> T execute(TransactionTemplate transaction, Supplier<T> action) {
        try {
            return transaction.execute(status -> {
                try {
                    return action.get();
                } catch (ContractResolutionException failure) {
                    if (failure.reason() == SCOPE_VIOLATION) {
                        var scope = currentScope();
                        if (scope != null) scope.violated = true;
                    }
                    throw failure;
                }
            });
        } catch (DataAccessException | TransactionException failure) {
            throw new ContractResolutionException(PERSISTENCE_UNAVAILABLE);
        }
    }

    private static final class Scope {
        private final UUID ownerId;
        private final Map<UUID, Set<UUID>> revisions;
        // Savepoints must not restore this terminal flag; only transaction completion discards it.
        private boolean violated;

        private Scope(UUID ownerId, Map<UUID, Set<UUID>> revisions) {
            var copy = new HashMap<UUID, Set<UUID>>();
            revisions.forEach((id, questions) -> copy.put(id, Set.copyOf(questions)));
            this.ownerId = ownerId;
            this.revisions = Map.copyOf(copy);
        }

        private UUID ownerId() { return ownerId; }
        private Map<UUID, Set<UUID>> revisions() { return revisions; }
    }
}
