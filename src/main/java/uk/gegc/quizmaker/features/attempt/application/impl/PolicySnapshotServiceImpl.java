package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.PolicySnapshotService;
import uk.gegc.quizmaker.features.attempt.domain.model.snapshot.*;
import uk.gegc.quizmaker.features.attempt.domain.repository.PolicySnapshotRepository;
import uk.gegc.quizmaker.features.attempt.domain.repository.PolicySnapshotRepository.StoredSnapshot;
import uk.gegc.quizmaker.features.attempt.infra.policy.CanonicalPolicyFormat;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import static uk.gegc.quizmaker.features.attempt.domain.model.snapshot.PolicySnapshotException.Reason.*;

@Service
public class PolicySnapshotServiceImpl implements PolicySnapshotService {
    private final PolicySnapshotRepository repository;
    private final MeterRegistry metrics;
    private final CanonicalPolicyFormat format = new CanonicalPolicyFormat();
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public PolicySnapshotServiceImpl(PolicySnapshotRepository repository, PlatformTransactionManager transactions,
                                     MeterRegistry metrics) {
        this.repository = repository;
        this.metrics = metrics;
        this.write = new TransactionTemplate(transactions);
        this.read = new TransactionTemplate(transactions);
        this.read.setReadOnly(true);
    }

    @Override
    public PolicySnapshot createOrReplay(UUID revisionOwnerId, PolicySnapshot snapshot) {
        return execute("persist_or_replay", write, () -> {
            if (snapshot == null) throw new PolicySnapshotException(INVALID_INPUT);
            var verified = format.verify(snapshot.resolution());
            var row = new StoredSnapshot(snapshot.id(), PolicySnapshot.SCHEMA_VERSION, verified.policy().content().quizRevisionId(),
                    verified.resolverVersion(), verified.canonicalVersion(), verified.hashAlgorithm(), verified.canonicalJson(), verified.hash());
            var stored = verify(repository.createOrReplay(revisionOwnerId, row));
            if (!stored.equals(snapshot)) throw new PolicySnapshotException(SNAPSHOT_CONFLICT);
            return stored;
        });
    }

    @Override
    public PolicySnapshotRead read(UUID revisionOwnerId, PolicySnapshotReference reference) {
        if (reference == null) throw new PolicySnapshotException(INVALID_INPUT);
        if (reference == PolicySnapshotReference.Legacy.NO_SNAPSHOT) {
            count("read", "legacy_no_snapshot");
            return PolicySnapshotRead.Legacy.NO_SNAPSHOT;
        }
        return execute("read", read, () -> verify(repository.findById(revisionOwnerId,
                ((PolicySnapshotReference.Stored) reference).id()).orElseThrow(() -> new PolicySnapshotException(SNAPSHOT_UNAVAILABLE))));
    }

    private PolicySnapshot verify(StoredSnapshot row) {
        if (row.schemaVersion() != PolicySnapshot.SCHEMA_VERSION || row.resolverVersion() != 1 || row.canonicalVersion() != 1) {
            throw new PolicySnapshotException(UNSUPPORTED_VERSION);
        }
        if (!"SHA-256".equals(row.hashAlgorithm())) throw new PolicySnapshotException(UNSUPPORTED_HASH_ALGORITHM);
        var value = format.decode(row.canonicalJson(), row.hash());
        if (!value.policy().content().quizRevisionId().equals(row.quizRevisionId())) throw new PolicySnapshotException(CORRUPT_SNAPSHOT);
        return new PolicySnapshot(row.id(), value);
    }

    private <T> T execute(String operation, TransactionTemplate transaction, Supplier<T> action) {
        String outcome = "persistence_unavailable";
        try {
            T value = transaction.execute(status -> action.get());
            outcome = "verified";
            return value;
        } catch (PolicySnapshotException failure) {
            outcome = failure.reason().name().toLowerCase(Locale.ROOT);
            throw failure;
        } catch (DataAccessException | TransactionException failure) {
            throw new PolicySnapshotException(PERSISTENCE_UNAVAILABLE);
        } finally {
            count(operation, outcome);
        }
    }

    private void count(String operation, String outcome) {
        try {
            metrics.counter("attempt.policy.snapshots", "version", "1", "operation", operation, "outcome", outcome).increment();
        } catch (RuntimeException ignored) {
            // Telemetry must never change committed evidence or replace the original failure.
        }
    }
}
