package uk.gegc.quizmaker.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.question.CreateQuestionRequest;
import uk.gegc.quizmaker.dto.question.QuestionDto;
import uk.gegc.quizmaker.dto.question.UpdateQuestionRequest;
import uk.gegc.quizmaker.service.question.QuestionService;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/questions")
@RequiredArgsConstructor
public class QuestionController {
    private final QuestionService questionService;

    @PostMapping
    public ResponseEntity<Map<String, UUID>> createQuestion(@RequestBody @Valid CreateQuestionRequest request){
        UUID id = questionService.createQuestion(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("questionId", id));
    }

    @GetMapping
    public ResponseEntity<Page<QuestionDto>> getQuestions(
            @RequestParam(required = false) UUID quizId,
            @RequestParam(defaultValue = "0") int pageNumber,
            @RequestParam(defaultValue = "20") int size){

        Pageable page = PageRequest.of(pageNumber, size, Sort.by("createdAt").descending());
        Page<QuestionDto> questionDtoPage = questionService.listQuestions(quizId, page);
        return ResponseEntity.ok(questionDtoPage);
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuestionDto> getQuestion(@PathVariable UUID id){
        return ResponseEntity.ok(questionService.getQuestion(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<QuestionDto> updateQuestion(
            @PathVariable UUID id,
            @RequestBody @Valid UpdateQuestionRequest request
            ){

        return ResponseEntity.ok(questionService.updateQuestion(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuestion(@PathVariable UUID id){
        questionService.deleteQuestion(id);
    }
}
