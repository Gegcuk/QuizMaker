package uk.gegc.quizmaker.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.attempt.*;
import uk.gegc.quizmaker.model.attempt.AttemptMode;
import uk.gegc.quizmaker.service.attempt.AttemptService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/attempts")
@RequiredArgsConstructor
@Validated
public class AttemptController {

    private final AttemptService attemptService;

    @PostMapping("/quizzes/{quizId}")
    public ResponseEntity<Map<String, UUID>> startAttempt(
            @PathVariable UUID quizId,
            @RequestBody(required = false) @Valid StartAttemptRequest request
    ){
        AttemptMode mode = (request != null && request.mode() != null)
                ? request.mode()
                : AttemptMode.ALL_AT_ONCE;
        AttemptDto attemptDto = attemptService.startAttempt(quizId, mode);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("attemptId", attemptDto.attemptId()));
    }

    @GetMapping()
    public ResponseEntity<Page<AttemptDto>> listAttempts(
            @RequestParam(name="page", defaultValue="0")
            @Min(value = 0, message = "page must be ≥ 0") int page,

            @RequestParam(name="size", defaultValue="20")
            @Min(value = 1, message = "size must be ≥ 1") int size,

            @RequestParam(name = "quizId", required = false) UUID quizId,
            @RequestParam(name = "userId", required = false) UUID userId
    )  {
        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "startedAt")
        );

        Page<AttemptDto> result = attemptService.getAttempts(pageable, quizId, userId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{attemptId}")
    public ResponseEntity<AttemptDetailsDto> getAttempt(@PathVariable UUID attemptId){
        return ResponseEntity.ok(attemptService.getAttemptDetail(attemptId));
    }

    @PostMapping("/{attemptId}/answers")
    public ResponseEntity<AnswerSubmissionDto> submitAnswer(
            @PathVariable UUID attemptId,
            @RequestBody @Valid AnswerSubmissionRequest request
            ){
        return ResponseEntity.ok(attemptService.submitAnswer(attemptId, request));
    }

    @PostMapping("/{attemptId}/answers/batch")
    public ResponseEntity<List<AnswerSubmissionDto>> submitBatch(
            @PathVariable UUID attemptId,
            @RequestBody @Valid BatchAnswerSubmissionRequest request
    ){
        return ResponseEntity.ok(attemptService.submitBatch(attemptId, request));
    }

    @PostMapping("/{attemptId}/complete")
    public ResponseEntity<AttemptResultDto> completeAttempt(
            @PathVariable UUID attemptId
    ){
        return ResponseEntity.ok(attemptService.completeAttempt(attemptId));
    }

}
