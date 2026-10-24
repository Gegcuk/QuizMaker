package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.FixedFormService;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedForm;
import uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException;
import uk.gegc.quizmaker.features.attempt.domain.repository.FixedFormRepository;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.attempt.domain.model.form.FixedFormException.Reason.*;

@Service
public class FixedFormServiceImpl implements FixedFormService {
    private final FixedFormRepository repository;
    private final MeterRegistry metrics;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public FixedFormServiceImpl(FixedFormRepository repository, PlatformTransactionManager transactions,
                                MeterRegistry metrics) {
        this.repository = repository;
        this.metrics = metrics;
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    @Override
    public FixedForm createOrReplay(UUID ownerId, FixedForm form) {
        return execute("create_or_replay", write, () -> {
            if (form == null) throw new FixedFormException(INVALID_FORM);
            repository.createOrReplay(ownerId, form);
            return form;
        });
    }

    @Override
    public List<FixedForm> findByIds(UUID ownerId, Collection<UUID> formIds) {
        return execute("read", read, () -> repository.findByIds(ownerId, formIds));
    }

    private <T> T execute(String operation, TransactionTemplate transaction, Supplier<T> action) {
        var sample = Timer.start(metrics);
        String outcome = "persistence_unavailable";
        try {
            T result = transaction.execute(status -> action.get());
            outcome = "success";
            return result;
        } catch (FixedFormException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DataIntegrityViolationException failure) {
            outcome = "form_conflict";
            throw new FixedFormException(FORM_CONFLICT);
        } catch (DataAccessException | TransactionException failure) {
            throw new FixedFormException(PERSISTENCE_UNAVAILABLE);
        } finally {
            sample.stop(Timer.builder("quizmaker.attempt.form.operations")
                    .tags("operation", operation, "outcome", outcome).register(metrics));
        }
    }
}
