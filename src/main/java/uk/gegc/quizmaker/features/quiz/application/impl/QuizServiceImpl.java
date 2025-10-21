package uk.gegc.quizmaker.features.quiz.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.*;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizCommandService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizPublishingService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizRelationService;
import uk.gegc.quizmaker.features.quiz.application.command.QuizVisibilityService;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationFacade;
import uk.gegc.quizmaker.features.quiz.application.query.QuizQueryService;
import uk.gegc.quizmaker.features.quiz.domain.events.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizServiceImpl implements QuizService {

    private final QuizQueryService quizQueryService;
    private final QuizCommandService quizCommandService;
    private final QuizRelationService quizRelationService;
    private final QuizPublishingService quizPublishingService;
    private final QuizVisibilityService quizVisibilityService;
    private final QuizGenerationFacade quizGenerationFacade;

    @Override
    @Transactional
    public UUID createQuiz(String username, CreateQuizRequest request) {
        return quizCommandService.createQuiz(username, request);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria criteria, String scope, Authentication authentication) {
        return quizQueryService.getQuizzes(pageable, criteria, scope, authentication);
    }

    @Override
    @Transactional(readOnly = true)
    public QuizDto getQuizById(UUID id, Authentication authentication) {
        return quizQueryService.getQuizById(id, authentication);
    }

    @Override
    @Transactional
    public QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest request) {
        return quizCommandService.updateQuiz(username, id, request);
    }

    @Override
    @Transactional
    public void deleteQuizById(String username, UUID id) {
        quizCommandService.deleteQuizById(username, id);
    }

    @Override
    @Transactional
    public void deleteQuizzesByIds(String username, List<UUID> quizIds) {
        quizCommandService.deleteQuizzesByIds(username, quizIds);
    }

    @Override
    @Transactional
    public BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String username, BulkQuizUpdateRequest request) {
        return quizCommandService.bulkUpdateQuiz(username, request);
    }

    @Override
    @Transactional
    public QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request) {
        return quizGenerationFacade.generateQuizFromDocument(username, request);
    }

    @Override
    public QuizGenerationResponse generateQuizFromUpload(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        return quizGenerationFacade.generateQuizFromUpload(username, file, request);
    }

    @Override
    public QuizGenerationResponse generateQuizFromText(String username, GenerateQuizFromTextRequest request) {
        return quizGenerationFacade.generateQuizFromText(username, request);
    }

    @Transactional
    public DocumentDto processDocumentCompletely(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        return quizGenerationFacade.processDocumentCompletely(username, file, request);
    }

    @Transactional(readOnly = true)
    public void verifyDocumentChunks(UUID documentId, GenerateQuizFromUploadRequest request) {
        quizGenerationFacade.verifyDocumentChunks(documentId, request);
    }

    @Transactional(readOnly = true)
    public void verifyDocumentChunks(UUID documentId, GenerateQuizFromTextRequest request) {
        quizGenerationFacade.verifyDocumentChunks(documentId, request);
    }

    @Transactional
    public DocumentDto processTextAsDocument(String username, GenerateQuizFromTextRequest request) {
        return quizGenerationFacade.processTextAsDocument(username, request);
    }

    @Override
    public QuizGenerationResponse startQuizGeneration(String username, GenerateQuizFromDocumentRequest request) {
        return quizGenerationFacade.startQuizGeneration(username, request);
    }

    @Override
    @Transactional
    public QuizGenerationStatus getGenerationStatus(UUID jobId, String username) {
        return quizQueryService.getGenerationStatus(jobId, username);
    }

    @Override
    @Transactional
    public QuizDto getGeneratedQuiz(UUID jobId, String username) {
        return quizQueryService.getGeneratedQuiz(jobId, username);
    }

    @Override
    @Transactional
    public QuizGenerationStatus cancelGenerationJob(UUID jobId, String username) {
        return quizGenerationFacade.cancelGenerationJob(jobId, username);
    }

    @Override
    @Transactional
    public Page<QuizGenerationStatus> getGenerationJobs(String username, Pageable pageable) {
        return quizQueryService.getGenerationJobs(username, pageable);
    }

    @Override
    @Transactional
    public QuizGenerationJobService.JobStatistics getGenerationJobStatistics(String username) {
        return quizQueryService.getGenerationJobStatistics(username);
    }

    @EventListener
    @Async("generalTaskExecutor")
    @Transactional
    public void handleQuizGenerationCompleted(QuizGenerationCompletedEvent event) {
        try {
            quizGenerationFacade.createQuizCollectionFromGeneratedQuestions(
                    event.getJobId(),
                    event.getChunkQuestions(),
                    event.getOriginalRequest()
            );
        } catch (Exception e) {
            log.error("Failed to create quiz collection for job {}", event.getJobId(), e);
        }
    }

    @Override
    @Transactional
    public void createQuizCollectionFromGeneratedQuestions(
            UUID jobId,
            Map<Integer, List<Question>> chunkQuestions,
            GenerateQuizFromDocumentRequest originalRequest
    ) {
        quizGenerationFacade.createQuizCollectionFromGeneratedQuestions(jobId, chunkQuestions, originalRequest);
    }

    void commitTokensForSuccessfulGeneration(QuizGenerationJob job, List<Question> allQuestions,
                                             GenerateQuizFromDocumentRequest originalRequest) {
        quizGenerationFacade.commitTokensForSuccessfulGeneration(job, allQuestions, originalRequest);
    }

    @Override
    @Transactional
    public void addQuestionToQuiz(String username, UUID quizId, UUID questionId) {
        quizRelationService.addQuestionToQuiz(username, quizId, questionId);
    }

    @Override
    @Transactional
    public void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId) {
        quizRelationService.removeQuestionFromQuiz(username, quizId, questionId);
    }

    @Override
    @Transactional
    public void addTagToQuiz(String username, UUID quizId, UUID tagId) {
        quizRelationService.addTagToQuiz(username, quizId, tagId);
    }

    @Override
    @Transactional
    public void removeTagFromQuiz(String username, UUID quizId, UUID tagId) {
        quizRelationService.removeTagFromQuiz(username, quizId, tagId);
    }

    @Override
    @Transactional
    public void changeCategory(String username, UUID quizId, UUID categoryId) {
        quizRelationService.changeCategory(username, quizId, categoryId);
    }

    @Override
    @Transactional
    public QuizDto setVisibility(String name, UUID quizId, Visibility visibility) {
        return quizVisibilityService.setVisibility(name, quizId, visibility);
    }

    @Override
    @Transactional
    public QuizDto setStatus(String username, UUID quizId, QuizStatus status) {
        return quizPublishingService.setStatus(username, quizId, status);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getPublicQuizzes(Pageable pageable) {
        return quizQueryService.getPublicQuizzes(pageable);
    }


}
