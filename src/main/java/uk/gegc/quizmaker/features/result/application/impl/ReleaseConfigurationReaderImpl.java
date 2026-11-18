package uk.gegc.quizmaker.features.result.application.impl;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.result.application.*;
import uk.gegc.quizmaker.features.result.domain.repository.ReleaseConfigurationRepository;
import uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.*;
import static uk.gegc.quizmaker.features.result.domain.model.release.ReleaseConfigurationException.Reason.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ReleaseConfigurationReaderImpl implements ReleaseConfigurationReader {
    private final ReleaseConfigurationRepository repository;
    private final PlatformTransactionManager transactions;
    public ReleaseConfigurationReaderImpl(ReleaseConfigurationRepository repository, PlatformTransactionManager transactions) {
        this.repository = repository; this.transactions = transactions;
    }
    @Override public ReleaseConfigurationService.Publication readBound(UUID owner, UUID quiz, UUID revision, UUID id) {
        AttemptTransactions.requireCompatible(transactions, false);
        require(owner != null && quiz != null && revision != null && id != null, INVALID_INPUT);
        return repository.readBound(owner, quiz, revision, id).orElseThrow(() -> new ReleaseConfigurationException(NOT_FOUND));
    }
}
