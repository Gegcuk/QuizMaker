package uk.gegc.quizmaker.features.question.application.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.application.contract.ResolvedQuestionContracts;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionContractBindingRepository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.*;

@Service
public class QuestionRevisionContractServiceImpl implements QuestionRevisionContractService {
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
    public void bind(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings) {
        try {
            writeTransaction.executeWithoutResult(status -> bindInTransaction(ownerId, quizRevisionId, bindings));
        } catch (DataAccessException | TransactionException failure) {
            throw new ContractResolutionException(PERSISTENCE_UNAVAILABLE);
        }
    }

    private void bindInTransaction(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings) {
        var expectedIds = repository.findQuestionRevisionIds(ownerId, quizRevisionId)
                .orElseThrow(() -> new ContractResolutionException(REVISION_UNAVAILABLE));
        if (bindings == null || bindings.stream().anyMatch(Objects::isNull)) {
            throw new ContractResolutionException(INVALID_BINDING);
        }
        var ids = new HashSet<UUID>();
        bindings.forEach(binding -> {
            if (!ids.add(binding.questionRevisionId())) throw new ContractResolutionException(INVALID_BINDING);
        });
        if (!ids.equals(expectedIds)) throw new ContractResolutionException(INVALID_BINDING);
        // Canonical UUID order matches the BINARY(16) primary-key order used by current reads.
        var ordered = bindings.stream()
                .sorted(Comparator.comparing(binding -> binding.questionRevisionId().toString())).toList();
        for (var binding : ordered) {
            registry.resolve(binding);
            repository.insert(ownerId, quizRevisionId, binding);
        }
    }

    @Override
    public Map<UUID, ResolvedQuestionContracts> resolve(UUID ownerId, UUID quizRevisionId) {
        try {
            // Keep the handler outside execution so transaction begin/commit/rollback failures
            // share the same boundary as SQL failures. REQUIRED still joins an outer transaction.
            return readTransaction.execute(status -> resolveInTransaction(ownerId, quizRevisionId));
        } catch (DataAccessException | TransactionException failure) {
            throw new ContractResolutionException(PERSISTENCE_UNAVAILABLE);
        }
    }

    private Map<UUID, ResolvedQuestionContracts> resolveInTransaction(UUID ownerId, UUID quizRevisionId) {
        var snapshot = snapshot(ownerId, quizRevisionId);
        if (!snapshot.bindings().keySet().equals(snapshot.questionRevisionIds())) {
            throw new ContractResolutionException(UNBOUND_REVISION);
        }
        var resolved = new HashMap<UUID, ResolvedQuestionContracts>();
        snapshot.bindings().forEach((id, binding) -> resolved.put(id, registry.resolve(binding)));
        return Map.copyOf(resolved);
    }

    private QuestionContractBindingRepository.Snapshot snapshot(UUID ownerId, UUID quizRevisionId) {
        return repository.findForRevision(ownerId, quizRevisionId)
                .orElseThrow(() -> new ContractResolutionException(REVISION_UNAVAILABLE));
    }
}
