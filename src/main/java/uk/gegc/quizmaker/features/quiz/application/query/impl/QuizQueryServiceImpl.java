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
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.UUID;

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
                    return quizMapper.toDto(quiz);
                }
            }
        }

        // For anonymous users or non-owners: only allow access to public quizzes
        if (quiz.getVisibility() != Visibility.PUBLIC || quiz.getStatus() != QuizStatus.PUBLISHED) {
            throw new ForbiddenException("Access denied: quiz is not public");
        }

        return quizMapper.toDto(quiz);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getPublicQuizzes(Pageable pageable) {
        // Enforce visibility invariants: public catalog shows PUBLISHED && PUBLIC
        return quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable)
                .map(quizMapper::toDto);
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

        return quizRepository.findAll(spec, pageable).map(quizMapper::toDto);
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

        return quizMapper.toDto(quiz);
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

}
