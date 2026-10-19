package uk.gegc.quizmaker.features.quiz.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSeenAcknowledgement;
import uk.gegc.quizmaker.features.quiz.application.QuizFreshnessService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizOwnerFreshnessRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class QuizFreshnessServiceImpl implements QuizFreshnessService {
    private final QuizOwnerFreshnessRepository freshnessRepository;
    private final QuizRepository quizRepository;
    private final AppPermissionEvaluator permissionEvaluator;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerGeneratedQuiz(UUID quizId) {
        freshnessRepository.registerGeneratedQuiz(Objects.requireNonNull(quizId, "quizId"));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> findNewQuizIds(UUID ownerId, Collection<UUID> quizIds) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(quizIds, "quizIds");
        return quizIds.isEmpty() ? Set.of() : freshnessRepository.findNewQuizIds(ownerId, quizIds);
    }

    @Override
    @PreAuthorize("isAuthenticated() and hasAuthority('QUIZ_READ')")
    @Transactional
    public QuizSeenAcknowledgement acknowledgeSeen(UUID quizId) {
        Objects.requireNonNull(quizId, "quizId");
        User user = permissionEvaluator.getCurrentUser();
        if (user == null) {
            throw new ResourceNotFoundException("Quiz not found");
        }
        // Serialize with quiz deletion without granting moderator ownership bypass.
        quizRepository.findOwnedByIdForUpdate(quizId, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Quiz not found"));
        freshnessRepository.acknowledge(quizId);
        return new QuizSeenAcknowledgement(quizId, false);
    }
}
