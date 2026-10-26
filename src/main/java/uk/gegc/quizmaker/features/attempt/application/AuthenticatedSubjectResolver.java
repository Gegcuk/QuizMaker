package uk.gegc.quizmaker.features.attempt.application;

import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;

import java.util.UUID;

/** Internal authenticated identity boundary; resolution neither persists nor authorizes an attempt. */
public interface AuthenticatedSubjectResolver {
    /**
     * Resolves the current server-authenticated user for a server-allocated target attempt ID.
     * No request user/subject ID, capability, or legacy sentinel can establish identity here.
     * #600 owns atomic start/binding; #537 owns resource access and #625 guest resolution.
     */
    AttemptSubject resolve(UUID serverAttemptId);
}
