package uk.gegc.quizmaker.features.question.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.question.application.PublishedQuestionContractService;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.application.QuestionContractRegistry;
import uk.gegc.quizmaker.features.question.application.contract.v1.*;
import uk.gegc.quizmaker.features.question.domain.model.contract.QuestionContractBinding;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractScopeIntent;
import uk.gegc.quizmaker.features.quiz.application.QuizRevisionPublicationService;

import java.util.*;

@Service
public class PublishedQuestionContractServiceImpl implements PublishedQuestionContractService {
    private final QuizRevisionPublicationService publications;
    private final QuestionRevisionContractService contracts;
    private final QuestionContractRegistry registry;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;

    public PublishedQuestionContractServiceImpl(QuizRevisionPublicationService publications,
                                                QuestionRevisionContractService contracts, QuestionContractRegistry registry,
                                                PlatformTransactionManager manager, MeterRegistry metrics) {
        this.publications = publications;
        this.contracts = contracts;
        this.registry = registry;
        this.transaction = new TransactionTemplate(manager);
        this.metrics = metrics;
    }

    @Override
    public List<QuestionContractBinding> bind(UUID quizId, UUID revisionId) {
        Timer.Sample timer = Timer.start();
        String outcome = "rejected";
        try {
            var result = transaction.execute(status -> bindPublished(quizId, revisionId));
            outcome = "bound";
            return result;
        } catch (DataAccessException | TransactionException failure) {
            outcome = "persistence_unavailable";
            throw new ContractResolutionException(ContractResolutionException.Reason.PERSISTENCE_UNAVAILABLE);
        } catch (ContractResolutionException failure) {
            if (failure.reason() == ContractResolutionException.Reason.PERSISTENCE_UNAVAILABLE)
                outcome = "persistence_unavailable";
            throw failure;
        } finally {
            try {
                timer.stop(Timer.builder("question.contract.publication.binding")
                        .tags("version", "1", "outcome", outcome).register(metrics));
            } catch (RuntimeException ignored) { /* Telemetry cannot change the binding outcome. */ }
        }
    }

    private List<QuestionContractBinding> bindPublished(UUID quizId, UUID revisionId) {
        var revision = publications.read(quizId, revisionId).content();
        var bindings = new LinkedHashMap<UUID, QuestionContractBinding>();
        for (var item : revision.items()) {
            var question = item.question();
            var binding = BaselineQuestionContracts.binding(question);
            // Resolve every family before any binding write. The authenticated read supplies owner identity.
            registry.resolve(binding).response().optionLayout(question);
            bindings.putIfAbsent(question.id(), binding);
        }
        contracts.declareScope(revision.ownerId(), Set.of(revision.id()), ContractScopeIntent.WRITE);
        var result = List.copyOf(bindings.values());
        contracts.bind(revision.ownerId(), revision.id(), result);
        return result;
    }
}
