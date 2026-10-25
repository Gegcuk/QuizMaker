package uk.gegc.quizmaker.features.attempt.domain.repository;

import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubject;
import uk.gegc.quizmaker.features.attempt.domain.model.subject.AttemptSubjectLookup;

import java.util.UUID;

public interface AttemptSubjectRepository {
    AttemptSubject bindOrReplay(UUID trustedUserId, AttemptSubject subject);
    AttemptSubjectLookup read(UUID attemptId);
}
