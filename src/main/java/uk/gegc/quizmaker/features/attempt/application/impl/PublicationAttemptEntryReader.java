package uk.gegc.quizmaker.features.attempt.application.impl;

import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.attempt.application.AttemptEntryReader;
import uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility;

/** #600 has locked current publication/access. Examination closure is integrated by #836. */
@Component
public final class PublicationAttemptEntryReader implements AttemptEntryReader {
    @Override public Entry read(RetakeEligibility.Scope scope) {
        return new Entry(1, scope != null && scope.kind() == RetakeEligibility.ContextKind.PUBLICATION
                ? RetakeEligibility.Entry.OPEN : RetakeEligibility.Entry.UNKNOWN);
    }
}
