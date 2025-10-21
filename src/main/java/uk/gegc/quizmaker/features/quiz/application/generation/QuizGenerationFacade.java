package uk.gegc.quizmaker.features.quiz.application.generation;

import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationResponse;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizGenerationStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizGenerationJob;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface QuizGenerationFacade {

    QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request);

    QuizGenerationResponse generateQuizFromUpload(String username, MultipartFile file, GenerateQuizFromUploadRequest request);

    QuizGenerationResponse generateQuizFromText(String username, GenerateQuizFromTextRequest request);

    QuizGenerationResponse startQuizGeneration(String username, GenerateQuizFromDocumentRequest request);

    QuizGenerationStatus cancelGenerationJob(UUID jobId, String username);

    void createQuizCollectionFromGeneratedQuestions(
            UUID jobId,
            Map<Integer, List<Question>> chunkQuestions,
            GenerateQuizFromDocumentRequest originalRequest
    );

    DocumentDto processDocumentCompletely(String username, MultipartFile file, GenerateQuizFromUploadRequest request);

    void verifyDocumentChunks(UUID documentId, GenerateQuizFromUploadRequest request);

    void verifyDocumentChunks(UUID documentId, GenerateQuizFromTextRequest request);

    DocumentDto processTextAsDocument(String username, GenerateQuizFromTextRequest request);

    void commitTokensForSuccessfulGeneration(
            QuizGenerationJob job,
            List<Question> allQuestions,
            GenerateQuizFromDocumentRequest originalRequest
    );
}
