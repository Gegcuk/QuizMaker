package uk.gegc.quizmaker.features.question.application.impl;

import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.application.QuestionSourceConfirmationService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuestionSourceScopeRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.time.Clock;
import java.util.*;

@Service
public class QuestionSourceConfirmationServiceImpl implements QuestionSourceConfirmationService {
    private final QuestionSourceScopeRepository sources;
    private final Clock clock;

    public QuestionSourceConfirmationServiceImpl(QuestionSourceScopeRepository sources, Clock clock) {
        this.sources = sources;
        this.clock = clock;
    }

    @Override @Transactional
    public void confirm(Set<UUID> questionIds, UUID verifiedOwnerId, String evidence) {
        if (questionIds == null || questionIds.isEmpty() || questionIds.size() > 100
                || questionIds.stream().anyMatch(Objects::isNull) || verifiedOwnerId == null
                || evidence == null || evidence.isBlank() || evidence.length() > 500)
            throw new ValidationException("Invalid question source confirmation");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || new AuthenticationTrustResolverImpl().isAnonymous(authentication)) throw unavailable();
        var actor = sources.lockActor(authentication.getName()).orElseThrow(QuestionSourceConfirmationServiceImpl::unavailable);
        if (Collections.disjoint(sources.lockPermissionNames(actor), Set.of("QUIZ_MODERATE", "QUIZ_ADMIN", "QUESTION_ADMIN"))
                || !sources.lockActiveOwner(verifiedOwnerId)) throw unavailable();
        var locked = sources.lockForConfirmation(questionIds);
        if (locked.size() != questionIds.size() || locked.stream().anyMatch(source ->
                source.ownerId() != null && !source.ownedBy(verifiedOwnerId))) throw unavailable();
        var now = clock.instant();
        for (var source : locked) {
            if (source.ownerId() == null) sources.confirmLegacy(source.questionId(), verifiedOwnerId, actor, now, evidence.strip());
        }
    }

    private static ForbiddenException unavailable() { return new ForbiddenException("Question source confirmation is unavailable"); }
}
