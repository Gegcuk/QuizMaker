package uk.gegc.quizmaker.service.attempt;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uk.gegc.quizmaker.dto.attempt.*;
import uk.gegc.quizmaker.dto.result.QuizResultSummaryDto;
import uk.gegc.quizmaker.model.attempt.AttemptMode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttemptService {
    AttemptDto startAttempt(UUID quizId, AttemptMode mode);
    Page<AttemptDto> getAttempts(Pageable pageable, UUID quizId, UUID userId);
    AttemptDetailsDto getAttemptDetail(UUID attemptId);
    AnswerSubmissionDto submitAnswer(UUID attemptId, AnswerSubmissionRequest request);
    List<AnswerSubmissionDto> submitBatch(UUID attemptId, BatchAnswerSubmissionRequest request);
    AttemptResultDto completeAttempt(UUID attemptId);
    QuizResultSummaryDto getQuizResultSummary(UUID quizId);

}
