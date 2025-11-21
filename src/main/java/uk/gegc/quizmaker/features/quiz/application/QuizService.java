package uk.gegc.quizmaker.features.quiz.application;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.*;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface QuizService {

    UUID createQuiz(String username, CreateQuizRequest request);

    Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria quizSearchCriteria, String scope, Authentication authentication);

    QuizDto getQuizById(UUID id, Authentication authentication);

    QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest updateQuizRequest);

    void deleteQuizById(String username, UUID quizId);

    void addQuestionToQuiz(String username, UUID quizId, UUID questionId);

    void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId);

    void addTagToQuiz(String username, UUID quizId, UUID tagId);

    void removeTagFromQuiz(String username, UUID quizId, UUID tagId);

    void changeCategory(String username, UUID quizId, UUID categoryId);

    QuizDto setVisibility(String name, UUID quizId, Visibility visibility);

    QuizDto setStatus(String name, UUID quizId, QuizStatus status);

    Page<QuizDto> getPublicQuizzes(Pageable pageable);

    void deleteQuizzesByIds(String name, List<UUID> quizIds);

    BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String name, BulkQuizUpdateRequest request);

    QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request);

    QuizGenerationResponse generateQuizFromUpload(String username, MultipartFile file, GenerateQuizFromUploadRequest request);

    QuizGenerationResponse generateQuizFromText(String username, GenerateQuizFromTextRequest request);

    QuizGenerationResponse startQuizGeneration(String username, GenerateQuizFromDocumentRequest request);

    QuizGenerationStatus getGenerationStatus(UUID jobId, String username);

    QuizDto getGeneratedQuiz(UUID jobId, String username);

    QuizGenerationStatus cancelGenerationJob(UUID jobId, String username);

    Page<QuizGenerationStatus> getGenerationJobs(String username, Pageable pageable);

    QuizGenerationJobService.JobStatistics getGenerationJobStatistics(String username);

    void createQuizCollectionFromGeneratedQuestions(
            UUID jobId,
            Map<Integer, List<Question>> chunkQuestions,
            GenerateQuizFromDocumentRequest originalRequest
    );

    /**
     * Archive a quiz by setting its status to ARCHIVED.
     * Delegates to QuizPublishingService.setStatus().
     */
    QuizDto archiveQuiz(String username, UUID quizId);

    /**
     * Unarchive a quiz by setting its status to DRAFT.
     * Delegates to QuizPublishingService.setStatus().
     */
    QuizDto unarchiveQuiz(String username, UUID quizId);
}
