package uk.gegc.quizmaker.features.attempt.domain.model.subject;

import java.util.Objects;

/** Absence proves no target binding; it proves neither that an attempt exists nor its identity. */
public sealed interface AttemptSubjectLookup {
    record Bound(AttemptSubject subject) implements AttemptSubjectLookup {
        public Bound { Objects.requireNonNull(subject); }
    }

    record LegacyOrUnbound() implements AttemptSubjectLookup { }
}
