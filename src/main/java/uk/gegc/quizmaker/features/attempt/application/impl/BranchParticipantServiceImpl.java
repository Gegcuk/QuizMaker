package uk.gegc.quizmaker.features.attempt.application.impl;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.attempt.application.AttemptSubmissionService;
import uk.gegc.quizmaker.features.attempt.application.AuthenticatedSubjectResolver;
import uk.gegc.quizmaker.features.attempt.application.BranchParticipantService;
import uk.gegc.quizmaker.features.attempt.application.ResponseAcceptanceService;
import uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactions;
import uk.gegc.quizmaker.features.attempt.domain.model.response.ResponseAcceptanceException;
import uk.gegc.quizmaker.features.attempt.domain.model.submission.AttemptSubmissionException;
import uk.gegc.quizmaker.features.attempt.domain.repository.BranchingRepository;

@Service
@ConditionalOnProperty(name = "quizmaker.attempts.branch-participant-enabled", havingValue = "true")
public class BranchParticipantServiceImpl implements BranchParticipantService {
    private final AuthenticatedSubjectResolver subjects;
    private final BranchingRepository paths;
    private final ResponseAcceptanceService.Scenario responses;
    private final AttemptSubmissionService.Scenario submissions;
    private final TransactionTemplate routing;

    public BranchParticipantServiceImpl(AuthenticatedSubjectResolver subjects, BranchingRepository paths,
            ResponseAcceptanceService.Scenario responses, AttemptSubmissionService.Scenario submissions,
            PlatformTransactionManager manager) {
        this.subjects = subjects;
        this.paths = paths;
        this.responses = responses;
        this.submissions = submissions;
        routing = AttemptTransactions.readCommitted(manager);
        routing.setReadOnly(true);
    }

    @Override
    public Optional<ResponseAcceptanceService.Result> accept(ResponseAcceptanceService.Command command) {
        return isScenario(command.attemptId()) ? Optional.of(responses.acceptBranch(command)) : Optional.empty();
    }

    @Override
    public Optional<AttemptSubmissionService.Result> submit(AttemptSubmissionService.Command command) {
        final boolean scenario;
        try {
            scenario = isScenario(command.attemptId());
        } catch (ResponseAcceptanceException failure) {
            throw new AttemptSubmissionException(AttemptSubmissionException.Reason.valueOf(failure.reason().name()));
        }
        return scenario ? Optional.of(submissions.submitBranch(command)) : Optional.empty();
    }

    private boolean isScenario(UUID attemptId) {
        AttemptTransactions.requireCompatible(routing.getTransactionManager(), true);
        try {
            return Boolean.TRUE.equals(routing.execute(status -> {
                try {
                    return paths.isOwnedPath(attemptId, subjects.resolve(attemptId).userId());
                } catch (RuntimeException failure) {
                    throw AttemptCommandFailures.translate(failure);
                }
            }));
        } catch (RuntimeException failure) {
            throw AttemptCommandFailures.translate(failure);
        }
    }
}
