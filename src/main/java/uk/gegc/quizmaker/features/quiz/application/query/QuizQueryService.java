package uk.gegc.quizmaker.features.quiz.application.query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationStatus;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;

import java.util.UUID;

public interface QuizQueryService {

    Page<QuizDto> getQuizzes(
            Pageable pageable,
            QuizSearchCriteria criteria,
            String scope,
            Authentication authentication
    );

    Page<QuizDto> getPublicQuizzes(Pageable pageable);

    QuizDto getQuizById(UUID quizId, Authentication authentication);

    @Transactional
    QuizGenerationStatus getGenerationStatus(UUID jobId, String username);

    @Transactional
    QuizDto getGeneratedQuiz(UUID jobId, String username);

    @Transactional
    Page<QuizGenerationStatus> getGenerationJobs(String username, Pageable pageable);

    @Transactional
    QuizGenerationJobService.JobStatistics getGenerationJobStatistics(String username);
}
