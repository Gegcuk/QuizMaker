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
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.dto.quiz.QuizSearchCriteria;
import uk.gegc.quizmaker.dto.quiz.UpdateQuizRequest;
import uk.gegc.quizmaker.service.quiz.QuizService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    @PostMapping
    public ResponseEntity<Map<String, UUID>> createQuiz(@RequestBody @Valid CreateQuizRequest request) {
        UUID quizId = quizService.createQuiz(request);
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
    public ResponseEntity<QuizDto> updateQuiz(
            @PathVariable UUID quizId,
            @RequestBody @Valid UpdateQuizRequest req
    ) {
        return ResponseEntity.ok(
                quizService.updateQuiz(quizId, req)
        );
    }

    @DeleteMapping("/{quizId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuiz(@PathVariable UUID quizId) {
        quizService.deleteQuizById(quizId);
    }

    @PostMapping("/{quizId}/questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addQuestion(
            @PathVariable UUID quizId,
            @PathVariable UUID questionId
    ) {
        quizService.addQuestionToQuiz(quizId, questionId);
    }

    @DeleteMapping("/{quizId}/questions/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeQuestion(
            @PathVariable UUID quizId,
            @PathVariable UUID questionId
    ) {
        quizService.removeQuestionFromQuiz(quizId, questionId);
    }

    @PostMapping("/{quizId}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addTag(
            @PathVariable UUID quizId,
            @PathVariable UUID tagId
    ) {
        quizService.addTagToQuiz(quizId, tagId);
    }

    @DeleteMapping("/{quizId}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTag(
            @PathVariable UUID quizId,
            @PathVariable UUID tagId
    ) {
        quizService.removeTagFromQuiz(quizId, tagId);
    }

    @PatchMapping("/{quizId}/category/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeCategory(
            @PathVariable UUID quizId,
            @PathVariable UUID categoryId
    ) {
        quizService.changeCategory(quizId, categoryId);
    }
}
