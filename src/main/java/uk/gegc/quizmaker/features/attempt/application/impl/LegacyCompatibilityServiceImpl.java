package uk.gegc.quizmaker.features.attempt.application.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptPresetService;
import uk.gegc.quizmaker.features.attempt.application.LegacyCompatibilityService;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyCompatibility.*;
import uk.gegc.quizmaker.features.attempt.domain.model.compatibility.LegacyInventoryException;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.LegacyPracticeRequest;
import uk.gegc.quizmaker.features.attempt.domain.repository.LegacyInventoryRepository;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

@Service
public final class LegacyCompatibilityServiceImpl implements LegacyCompatibilityService {
    private final LegacyInventoryRepository repository;
    private final AttemptPresetService presets;
    private final PlatformTransactionManager transactions;
    private final MeterRegistry metrics;

    public LegacyCompatibilityServiceImpl(LegacyInventoryRepository repository, AttemptPresetService presets,
                                          PlatformTransactionManager transactions, MeterRegistry metrics) {
        this.repository = repository; this.presets = presets; this.transactions = transactions; this.metrics = metrics;
    }

    @Override
    public Finding classifyLaunch(LegacyPracticeRequest request) {
        return LegacyCompatibility.classifyMapping(presets.mapLegacy(request));
    }

    @Override
    public Report inventory(Limits limits) {
        Objects.requireNonNull(limits);
        var read = new TransactionTemplate(transactions);
        // One consistent view across families/pages, even when invoked inside a READ_COMMITTED writer.
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
        read.setTimeout(limits.timeoutSeconds());
        Report report;
        try {
            report = read.execute(status -> {
                try {
                    var families = new ArrayList<FamilyCount>();
                    for (Family family : Family.values()) families.add(scan(family, limits));
                    return new Report(LegacyCompatibility.VERSION, families);
                } catch (RuntimeException failure) {
                    // Sanitize before TransactionTemplate can log rollback diagnostics.
                    throw new LegacyInventoryException();
                }
            });
        } catch (RuntimeException failure) {
            metrics.counter("attempt.legacy.inventory", "outcome", "unavailable").increment();
            throw new LegacyInventoryException();
        }
        metrics.counter("attempt.legacy.inventory", "outcome", report.complete() ? "complete" : "truncated").increment();
        return report;
    }

    private FamilyCount scan(Family family, Limits limits) {
        var findings = new EnumMap<Finding, Long>(Finding.class);
        UUID cursor = null;
        int examined = 0;
        while (examined < limits.maxRowsPerFamily()) {
            int size = Math.min(limits.batchSize(), limits.maxRowsPerFamily() - examined);
            var rows = repository.read(family, cursor, size + 1);
            for (Observation row : rows.subList(0, Math.min(size, rows.size()))) {
                findings.merge(LegacyCompatibility.classify(family, row), 1L, Long::sum);
                cursor = row.cursor(); examined++;
            }
            if (rows.size() <= size) return new FamilyCount(family, examined, true, findings);
        }
        // A look-ahead row proved more data exists. Never present the budget as the database total.
        return new FamilyCount(family, examined, false, findings);
    }
}
