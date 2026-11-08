package uk.gegc.quizmaker.features.result.application;

import java.util.UUID;
import uk.gegc.quizmaker.features.result.domain.model.ResultRelease;

/** Trusted internal command after #603 commits; not an endpoint, scheduler or participant grant. */
public interface ImmediateResultReleaseService {
    Acknowledgement release(UUID submissionId);
    record Acknowledgement(ResultRelease release, boolean replayed) { }
}
