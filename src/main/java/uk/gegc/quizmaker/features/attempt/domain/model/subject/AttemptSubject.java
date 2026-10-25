package uk.gegc.quizmaker.features.attempt.domain.model.subject;

import java.util.UUID;

import static uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectException.Reason.*;

/** Immutable target binding. This value is evidence, never proof of authentication or access. */
public record AttemptSubject(UUID attemptId, int schemaVersion, Type type, UUID userId) {
    public enum Type { AUTHENTICATED_USER }

    public AttemptSubject {
        if (attemptId == null || userId == null) throw new AttemptSubjectException(INVALID_SUBJECT);
        if (schemaVersion != 1) throw new AttemptSubjectException(UNSUPPORTED_VERSION);
        if (type == null) throw new AttemptSubjectException(UNSUPPORTED_TYPE);
    }

    public static AttemptSubject authenticated(UUID attemptId, UUID userId) {
        return new AttemptSubject(attemptId, 1, Type.AUTHENTICATED_USER, userId);
    }
}
