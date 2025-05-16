package uk.gegc.quizmaker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.dto.quiz.QuizSearchCriteria;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;
import uk.gegc.quizmaker.dto.result.QuizResultSummaryDto;
import uk.gegc.quizmaker.service.attempt.AttemptService;
import uk.gegc.quizmaker.service.quiz.QuizService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;
    private final AttemptService attemptService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, UUID>> createQuiz(@RequestBody @Valid CreateQuizRequest request,
                                                        Authentication authentication) {
        UUID quizId = quizService.createQuiz(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("quizId", quizId));
    }

    @GetMapping
    public ResponseEntity<Page<QuizDto>> getQuizzes(
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            @ModelAttribute QuizSearchCriteria quizSearchCriteria
    ) {

        Page<QuizDto> questionDtoPage = quizService.getQuizzes(pageable, quizSearchCriteria);
        return ResponseEntity.ok((questionDtoPage));
    }

    @GetMapping("/{quizId}")
    public ResponseEntity<QuizDto> getQuiz(@PathVariable UUID quizId) {
        return ResponseEntity.ok(quizService.getQuizById(quizId));
    }

    @PatchMapping("/{quizId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizDto> updateQuiz(
            @PathVariable UUID quizId,
            @RequestBody @Valid UpdateQuizRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                quizService.updateQuiz(authentication.getName(), quizId, request)
        );
    }

    @DeleteMapping("/{quizId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteQuiz(@PathVariable UUID quizId,
                           Authentication authentication) {
        quizService.deleteQuizById(authentication.getName(), quizId);
    }

    @PostMapping("/{quizId}/questions/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addQuestion(
            @PathVariable UUID quizId,
            @PathVariable UUID questionId,
            Authentication authentication
    ) {
        quizService.addQuestionToQuiz(authentication.getName(), quizId, questionId);
    }

    @DeleteMapping("/{quizId}/questions/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeQuestion(
            @PathVariable UUID quizId,
            @PathVariable UUID questionId,
            Authentication authentication
    ) {
        quizService.removeQuestionFromQuiz(authentication.getName(), quizId, questionId);
    }

    @PostMapping("/{quizId}/tags/{tagId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addTag(
            @PathVariable UUID quizId,
            @PathVariable UUID tagId,
            Authentication authentication
    ) {
        quizService.addTagToQuiz(authentication.getName(), quizId, tagId);
    }

    @DeleteMapping("/{quizId}/tags/{tagId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTag(
            @PathVariable UUID quizId,
            @PathVariable UUID tagId,
            Authentication authentication
    ) {
        quizService.removeTagFromQuiz(authentication.getName(), quizId, tagId);
    }

    @PatchMapping("/{quizId}/category/{categoryId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeCategory(
            @PathVariable UUID quizId,
            @PathVariable UUID categoryId,
            Authentication authentication
    ) {
        quizService.changeCategory(authentication.getName(), quizId, categoryId);
    }

    @GetMapping("/{quizId}/results")
    public ResponseEntity<QuizResultSummaryDto> getQuizResults(
            @PathVariable UUID quizId
    ) {
        return ResponseEntity.ok(attemptService.getQuizResultSummary(quizId));
    }
}
