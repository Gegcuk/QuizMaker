package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.FormOptionOrderService;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.*;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.AnswerOrderingPolicy;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.FormOptionOrderRepository;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.*;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException.require;

@Service
public class FormOptionOrderServiceImpl implements FormOptionOrderService {
    private static final Set<QuestionType> FAMILIES = Set.of(QuestionType.MCQ_SINGLE, QuestionType.MCQ_MULTI,
            QuestionType.TRUE_FALSE, QuestionType.FILL_GAP, QuestionType.ORDERING);
    private final QuizRevisionRepository revisions;
    private final QuestionRevisionContractService contracts;
    private final FixedFormRepository forms;
    private final FormOptionOrderRepository orders;
    private final MeterRegistry metrics;
    private final TransactionTemplate write, read;

    public FormOptionOrderServiceImpl(QuizRevisionRepository revisions, QuestionRevisionContractService contracts,
            FixedFormRepository forms, FormOptionOrderRepository orders, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.revisions = revisions; this.contracts = contracts; this.forms = forms; this.orders = orders; this.metrics = metrics;
        write = new TransactionTemplate(manager); read = new TransactionTemplate(manager); read.setReadOnly(true);
    }

    @Override
    public FormOptionOrder createOrReplay(UUID ownerId, FixedForm form, AnswerOrderingPolicy policy) {
        boolean standalone = !TransactionSynchronizationManager.isActualTransactionActive();
        return execute("create_or_replay", write, () -> {
            if (ownerId == null) throw new FixedFormException(FixedFormException.Reason.REVISION_UNAVAILABLE);
            require(form != null && policy != null, INVALID_ORDER);
            var revision = revisions.findById(ownerId, form.quizRevisionId()).orElseThrow(() ->
                    new FixedFormException(FixedFormException.Reason.REVISION_UNAVAILABLE));
            if (standalone) contracts.declareScope(ownerId, Set.of(revision.id()));
            var resolved = contracts.resolve(ownerId, revision.id());
            var sources = new HashMap<UUID, uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision.Item>();
            revision.items().forEach(item -> sources.put(item.id(), item));
            var items = new ArrayList<FormOptionOrder.Item>();
            for (var item : form.items()) {
                var source = sources.get(item.sourceItemId());
                require(source != null, INVALID_ORDER);
                var question = source.question();
                require(FAMILIES.contains(question.type()), UNSUPPORTED_FAMILY);
                var pair = resolved.get(question.id());
                if (pair == null) throw new ContractResolutionException(ContractResolutionException.Reason.UNBOUND_REVISION);
                var ids = AnswerOptionOrder.order(form.id(), item.id(), policy, question, pair.response().optionLayout(question));
                items.add(new FormOptionOrder.Item(item.id(), item.sourceItemId(), pair.binding(), ids));
            }
            var result = new FormOptionOrder(form.id(), 1, policy, items);
            forms.createOrReplay(ownerId, form);
            orders.createOrReplay(ownerId, form, result);
            return result;
        });
    }

    @Override
    public List<FormOptionOrder> findByIds(UUID ownerId, Collection<UUID> formIds) {
        return execute("read", read, () -> orders.findByIds(ownerId, formIds));
    }

    private <T> T execute(String operation, TransactionTemplate transaction, Supplier<T> action) {
        Timer.Sample sample = null;
        try { sample = Timer.start(metrics); }
        catch (RuntimeException ignored) { /* telemetry availability is not command authority */ }
        String outcome = "persistence_unavailable";
        try {
            T result = transaction.execute(status -> action.get()); outcome = "success"; return result;
        } catch (FixedFormException | OptionOrderException | ContractResolutionException
                 | uk.gegc.quizmaker.features.quiz.domain.model.revision.RevisionIntegrityException failure) {
            outcome = failure instanceof ContractResolutionException contractFailure
                    && contractFailure.reason() == ContractResolutionException.Reason.PERSISTENCE_UNAVAILABLE
                    ? "persistence_unavailable" : "rejected";
            throw failure;
        } catch (DataIntegrityViolationException failure) {
            outcome = "rejected";
            throw new OptionOrderException(ORDER_CONFLICT);
        }
        catch (DataAccessException | TransactionException failure) { throw new OptionOrderException(PERSISTENCE_UNAVAILABLE); }
        finally {
            // Observability cannot change commit/rollback or replace an existing failure.
            try { if (sample != null) sample.stop(Timer.builder("quizmaker.attempt.option.order.operations")
                    .tags("operation", operation, "outcome", outcome).register(metrics)); }
            catch (RuntimeException ignored) { /* best-effort bounded telemetry */ }
        }
    }
}
