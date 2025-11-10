package uk.gegc.quizmaker.features.quiz.application.query.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationStatus;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of read-only query service for quizzes.
 * <p>
 * TODO: Methods will be moved here from QuizServiceImpl using IntelliJ's Move Members (F6).
 * This is a placeholder implementation to be populated during refactoring.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuizQueryServiceImpl implements QuizQueryService {

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final QuizMapper quizMapper;
    private final UserRepository userRepository;
    private final AppPermissionEvaluator appPermissionEvaluator;
    private final QuizGenerationJobService jobService;
    private final FeatureFlags featureFlags;

    @Transactional(readOnly = true)
    public QuizDto getQuizById(UUID id, Authentication authentication) {
        Quiz quiz = quizRepository.findByIdWithTags(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + id + " not found"));

        // Get question count
        int questionCount = (int) questionRepository.countByQuizId_Id(id);

        // If user is authenticated, check if they're the owner
        if (authentication != null && authentication.isAuthenticated()) {
            User user = userRepository.findByUsername(authentication.getName())
                    .or(() -> userRepository.findByEmail(authentication.getName()))
                    .orElse(null);

            if (user != null) {
                boolean isOwner = quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId());
                boolean hasModerationPermissions = appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                        || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN);

                // Allow access if user is owner or has moderation permissions
                if (isOwner || hasModerationPermissions) {
                    return quizMapper.toDto(quiz, questionCount);
                }
            }
        }

        // For anonymous users or non-owners: only allow access to public quizzes
        if (quiz.getVisibility() != Visibility.PUBLIC || quiz.getStatus() != QuizStatus.PUBLISHED) {
            throw new ForbiddenException("Access denied: quiz is not public");
        }

        return quizMapper.toDto(quiz, questionCount);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getPublicQuizzes(Pageable pageable) {
        // Enforce visibility invariants: public catalog shows PUBLISHED && PUBLIC
        Page<Quiz> quizPage = quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable);
        
        // Batch fetch question counts to avoid N+1 queries
        Map<UUID, Long> questionCounts = batchFetchQuestionCounts(quizPage.getContent());
        
        return quizPage.map(quiz -> {
            int count = questionCounts.getOrDefault(quiz.getId(), 0L).intValue();
            return quizMapper.toDto(quiz, count);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getQuizzes(
            Pageable pageable,
            QuizSearchCriteria criteria,
            String scope,
            Authentication authentication) {
        final User user;
        if (authentication != null && authentication.isAuthenticated()) {
            user = userRepository.findByUsername(authentication.getName())
                    .or(() -> userRepository.findByEmail(authentication.getName()))
                    .orElse(null);
        } else {
            user = null;
        }

        org.springframework.data.jpa.domain.Specification<Quiz> spec = uk.gegc.quizmaker.features.quiz.domain.repository.QuizSpecifications.build(criteria);
        
        // Apply scoping based on the scope parameter
        switch (scope.toLowerCase()) {
            case "me":
                if (user == null) {
                    throw new ForbiddenException("Authentication required for scope=me");
                }
                // Only return quizzes owned by the user
                spec = spec.and((root, query, cb) -> 
                    cb.equal(root.get("creator").get("id"), user.getId()));
                break;
                
            case "all":
                if (user == null || !(appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE) 
                        || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN))) {
                    throw new ForbiddenException("Moderator/Admin permissions required for scope=all");
                }
                // Return all quizzes (no additional filtering)
                break;
                
            case "public":
            default:
                // Only return public, published quizzes
                spec = spec.and((root, query, cb) -> 
                    cb.and(
                        cb.equal(root.get("visibility"), Visibility.PUBLIC),
                        cb.equal(root.get("status"), QuizStatus.PUBLISHED)
                    ));
                break;
        }

        Page<Quiz> quizPage = quizRepository.findAll(spec, pageable);
        
        // Batch fetch question counts to avoid N+1 queries
        Map<UUID, Long> questionCounts = batchFetchQuestionCounts(quizPage.getContent());
        
        return quizPage.map(quiz -> {
            int count = questionCounts.getOrDefault(quiz.getId(), 0L).intValue();
            return quizMapper.toDto(quiz, count);
        });
    }

    @Transactional
    @Override
    public QuizGenerationStatus getGenerationStatus(UUID jobId, String username) {
        QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);
        return QuizGenerationStatus.fromEntity(job, featureFlags.isBilling());
    }

    @Transactional
    @Override
    public QuizDto getGeneratedQuiz(UUID jobId, String username) {
        QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);

        if (job.getStatus() != GenerationStatus.COMPLETED) {
            throw new ValidationException("Generation job is not yet completed. Current status: " + job.getStatus());
        }

        if (job.getGeneratedQuizId() == null) {
            throw new ResourceNotFoundException("Generated quiz not found for job: " + jobId);
        }

        Quiz quiz = quizRepository.findByIdWithTags(job.getGeneratedQuizId())
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + job.getGeneratedQuizId() + " not found"));

        if (quiz.getCreator() == null || !quiz.getCreator().getId().equals(job.getUser().getId())) {
            throw new ForbiddenException("Access denied");
        }

        int questionCount = (int) questionRepository.countByQuizId_Id(quiz.getId());
        return quizMapper.toDto(quiz, questionCount);
    }

    @Transactional
    @Override
    public Page<QuizGenerationStatus> getGenerationJobs(String username, Pageable pageable) {
        Page<QuizGenerationJob> jobs = jobService.getJobsByUser(username, pageable);
        return jobs.map(job -> QuizGenerationStatus.fromEntity(job, featureFlags.isBilling()));
    }

    @Transactional
    @Override
    public QuizGenerationJobService.JobStatistics getGenerationJobStatistics(String username) {
        return jobService.getJobStatistics(username);
    }

    /**
     * Helper method to batch fetch question counts for multiple quizzes.
     * Prevents N+1 queries when mapping Page<Quiz> to Page<QuizDto>.
     *
     * @param quizzes List of quizzes to get question counts for
     * @return Map of quiz ID to question count
     */
    private Map<UUID, Long> batchFetchQuestionCounts(List<Quiz> quizzes) {
        if (quizzes.isEmpty()) {
            return Map.of();
        }
        
        List<UUID> quizIds = quizzes.stream()
                .map(Quiz::getId)
                .collect(Collectors.toList());
        
        return questionRepository.countQuestionsForQuizzes(quizIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],  // quizId
                        row -> (Long) row[1]   // count
                ));
    }

}
