package uk.gegc.quizmaker.features.result.application.impl;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.result.application.ResultNotificationEventReader;
import uk.gegc.quizmaker.features.result.domain.model.notification.*;
import uk.gegc.quizmaker.features.result.domain.repository.ResultNotificationEventRepository;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.*;
import static uk.gegc.quizmaker.features.result.domain.model.notification.ResultEventException.Reason.*;

@Service
public class ResultNotificationEventReaderImpl implements ResultNotificationEventReader {
    private final ResultNotificationEventRepository repository;
    private final PlatformTransactionManager transactions;
    private final TransactionTemplate read;
    public ResultNotificationEventReaderImpl(ResultNotificationEventRepository repository, PlatformTransactionManager transactions) {
        this.repository = repository; this.transactions = transactions;
        read = AttemptTransactions.readCommitted(transactions); read.setReadOnly(true);
    }
    @Override public Optional<ResultNotificationEvent> find(UUID id) {
        return execute(() -> { require(id != null, INVALID_INPUT); return repository.find(id); });
    }
    @Override public Page scan(UUID after, int limit) {
        return execute(() -> {
            require(limit >= 1 && limit <= 500, INVALID_INPUT);
            var rows = repository.scan(after, limit + 1);
            boolean more = rows.size() > limit;
            var page = more ? rows.subList(0, limit) : rows;
            return new Page(page, more ? page.get(page.size() - 1).id() : null);
        });
    }
    private <T> T execute(Supplier<T> work) {
        AttemptTransactions.requireCompatible(transactions, false);
        try {
            return read.execute(status -> {
                try { return work.get(); }
                catch (RuntimeException failure) { throw safe(failure); }
            });
        } catch (RuntimeException failure) { throw safe(failure); }
    }
    private static ResultEventException safe(RuntimeException failure) {
        return new ResultEventException(failure instanceof ResultEventException known ? known.reason() : UNAVAILABLE);
    }
}
