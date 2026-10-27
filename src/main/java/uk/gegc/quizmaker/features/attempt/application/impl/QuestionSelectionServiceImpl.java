package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.attempt.application.QuestionSelectionService;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.*;
import uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException;

import java.util.HashSet;

import static uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelectionException.Reason.*;

@Service
public final class QuestionSelectionServiceImpl implements QuestionSelectionService {
    @Override
    public Result select(Input input) {
        if (input == null) throw new QuestionSelectionException(INVALID_INPUT);
        var selected = new HashSet<>(switch (input.configuration().strategy()) {
            case ALL -> input.eligibleItems().stream().map(Item::id).toList();
            case MANUAL_SET -> input.configuration().manualItemIds();
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
