package uk.gegc.quizmaker.features.question.application.impl;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    public QuestionRevisionContractServiceImpl(QuestionContractBindingRepository repository, QuestionContractRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    @Override
    @Transactional
    public void bind(UUID ownerId, UUID quizRevisionId, List<QuestionContractBinding> bindings) {
        try {
            var snapshot = snapshot(ownerId, quizRevisionId);
            if (bindings == null || bindings.stream().anyMatch(Objects::isNull)) {
                throw new ContractResolutionException(INVALID_BINDING);
            }
            var ids = new HashSet<UUID>();
            bindings.forEach(binding -> {
                if (!ids.add(binding.questionRevisionId())) throw new ContractResolutionException(INVALID_BINDING);
            });
            if (!ids.equals(snapshot.questionRevisionIds())) throw new ContractResolutionException(INVALID_BINDING);
            // Consistent lock order across quizzes sharing question revisions.
            for (var binding : bindings.stream().sorted(Comparator.comparing(QuestionContractBinding::questionRevisionId)).toList()) {
                registry.resolve(binding);
                repository.insert(ownerId, quizRevisionId, binding);
            }
        } catch (DataAccessException failure) {
            throw new ContractResolutionException(PERSISTENCE_UNAVAILABLE);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ResolvedQuestionContracts> resolve(UUID ownerId, UUID quizRevisionId) {
        try {
            var snapshot = snapshot(ownerId, quizRevisionId);
            if (!snapshot.bindings().keySet().equals(snapshot.questionRevisionIds())) {
                throw new ContractResolutionException(UNBOUND_REVISION);
            }
            var resolved = new HashMap<UUID, ResolvedQuestionContracts>();
            snapshot.bindings().forEach((id, binding) -> resolved.put(id, registry.resolve(binding)));
            return Map.copyOf(resolved);
        } catch (DataAccessException failure) {
            throw new ContractResolutionException(PERSISTENCE_UNAVAILABLE);
        }
    }

    private QuestionContractBindingRepository.Snapshot snapshot(UUID ownerId, UUID quizRevisionId) {
        return repository.findForRevision(ownerId, quizRevisionId)
                .orElseThrow(() -> new ContractResolutionException(REVISION_UNAVAILABLE));
    }
}
