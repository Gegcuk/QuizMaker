package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.QuestionSelectionService;
import uk.gegc.quizmaker.features.attempt.application.SelectionSeedSource;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.RandomQuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;

import java.util.HashSet;

import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;

@Service
public final class QuestionSelectionServiceImpl implements QuestionSelectionService {
    private final SelectionSeedSource seeds;

    public QuestionSelectionServiceImpl(SelectionSeedSource seeds) {
        this.seeds = java.util.Objects.requireNonNull(seeds);
    }

    @Override
    public Result select(Input input) {
        if (input == null) throw new QuestionSelectionException(INVALID_INPUT);
        if (input.configuration().strategy().random()) {
            input.configuration().selectionSize(input.eligibleItems().size());
            SeedMaterial seed;
            try {
                seed = seeds.derive(new SeedRequest(input.version(), input.quizRevisionId(), input.revisionSchemaVersion(),
                        input.configuration(), input.context()));
            } catch (RuntimeException unavailable) {
                throw new QuestionSelectionException(SEED_UNAVAILABLE);
            }
            return RandomQuestionSelection.select(input, seed);
        }
        var selected = new HashSet<>(switch (input.configuration().strategy()) {
            case ALL -> input.eligibleItems().stream().map(Item::id).toList();
            case MANUAL_SET -> input.configuration().manualItemIds();
            default -> throw new QuestionSelectionException(UNKNOWN_STRATEGY);
        });
        for (AtomicGroup group : input.atomicGroups()) {
            long included = group.itemIds().stream().filter(selected::contains).count();
            if (included != 0 && included != group.itemIds().size()) {
                throw new QuestionSelectionException(CONSTRAINT_VIOLATION);
            }
        }
        var ids = input.eligibleItems().stream().map(Item::id).filter(selected::contains).toList();
        return new Result(QuestionSelection.VERSION,
                new Evidence(QuestionSelection.VERSION, input.version(), input.quizRevisionId(),
                        input.revisionSchemaVersion(), input.configuration()), ids);
    }
}
