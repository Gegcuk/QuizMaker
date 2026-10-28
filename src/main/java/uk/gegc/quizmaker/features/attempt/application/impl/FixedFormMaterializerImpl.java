package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.FixedFormMaterializer;
import uk.gegc.quizmaker.features.attempt.application.FormOptionOrderService;
import uk.gegc.quizmaker.features.attempt.application.ParticipantFormProjector;
import uk.gegc.quizmaker.features.attempt.domain.model.form.*;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.AuthoredQuestionOrder;
import uk.gegc.quizmaker.features.attempt.domain.model.ordering.OptionOrderException;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException;
import uk.gegc.quizmaker.features.quiz.domain.model.revision.QuizRevision;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRevisionRepository;

import java.util.Set;
import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException.Reason.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.form.FormMaterializationException.require;

@Service
public class FixedFormMaterializerImpl implements FixedFormMaterializer {
    private final QuizRevisionRepository revisions;
    private final QuestionRevisionContractService contracts;
    private final FormOptionOrderService orders;
    private final MeterRegistry metrics;
    private final TransactionTemplate transaction;

    public FixedFormMaterializerImpl(QuizRevisionRepository revisions, QuestionRevisionContractService contracts,
                                    FormOptionOrderService orders, PlatformTransactionManager manager, MeterRegistry metrics) {
        this.revisions = revisions;
        this.contracts = contracts;
        this.orders = orders;
        this.metrics = metrics;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public ParticipantForm materialize(UUID ownerId, Command command) {
        boolean standalone = !TransactionSynchronizationManager.isActualTransactionActive();
        Timer.Sample sample = null;
        try { sample = Timer.start(metrics); }
        catch (RuntimeException ignored) { /* telemetry is not command authority */ }
        String outcome = "rejected";
        try {
            var result = transaction.execute(status -> {
                require(ownerId != null, REVISION_UNAVAILABLE);
                require(command != null && command.form() != null && command.selection() != null
                        && command.questionOrder() != null && command.answerOrdering() != null, INVALID_INPUT);
                var revision = revisions.findById(ownerId, command.form().quizRevisionId())
                        .orElseThrow(() -> new FormMaterializationException(REVISION_UNAVAILABLE));
                validate(command, revision);
                if (standalone) contracts.declareScope(ownerId, Set.of(revision.id()));
                var resolved = contracts.resolve(ownerId, revision.id());
                var order = orders.createOrReplay(ownerId, command.form(), command.answerOrdering());
                return ParticipantFormProjector.project(command.form(), revision, order, resolved);
            });
            outcome = "success";
            return result;
        } catch (FormMaterializationException failure) {
            if (failure.reason() == INVALID_MATERIAL) outcome = "redaction_failure";
            if (failure.reason() == PERSISTENCE_UNAVAILABLE) outcome = "persistence_unavailable";
            throw failure;
        } catch (FixedFormException failure) {
            if (failure.reason() == FixedFormException.Reason.PERSISTENCE_UNAVAILABLE) outcome = "persistence_unavailable";
            throw failure;
        } catch (OptionOrderException failure) {
            if (failure.reason() == OptionOrderException.Reason.PERSISTENCE_UNAVAILABLE) outcome = "persistence_unavailable";
            throw failure;
        } catch (ContractResolutionException failure) {
            if (failure.reason() == ContractResolutionException.Reason.PERSISTENCE_UNAVAILABLE) outcome = "persistence_unavailable";
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            outcome = "persistence_unavailable";
            throw new FormMaterializationException(PERSISTENCE_UNAVAILABLE);
        } finally {
            try {
                if (sample != null) sample.stop(Timer.builder("quizmaker.attempt.form.materialization")
                        .tags("format", "1", "outcome", outcome).register(metrics));
            } catch (RuntimeException ignored) { /* best-effort telemetry must not change the result */ }
        }
    }

    private static void validate(Command command, QuizRevision revision) {
        var selection = command.selection();
        var evidence = selection.evidence();
        var supplied = command.questionOrder();
        require(evidence.quizRevisionId().equals(revision.id())
                && evidence.revisionSchemaVersion() == revision.schemaVersion(), INPUT_MISMATCH);
        var expected = AuthoredQuestionOrder.order(new AuthoredQuestionOrder.Input(AuthoredQuestionOrder.VERSION,
                revision.id(), revision.schemaVersion(), supplied.evidence().policy(),
                revision.items().stream().map(item -> new AuthoredQuestionOrder.Item(item.id(), item.authoredPosition())).toList(),
                selection.itemIds()));
        require(expected.equals(supplied) && command.form().items().stream().map(FixedForm.Item::sourceItemId).toList()
                .equals(expected.itemIds()), INPUT_MISMATCH);
    }
}
