package uk.gegc.quizmaker.features.attempt.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.attempt.api.dto.*;
import uk.gegc.quizmaker.features.attempt.application.AttemptService;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptMode;
import uk.gegc.quizmaker.features.attempt.domain.model.AttemptStatus;

import java.util.List;
import java.util.UUID;

@Tag(name = "Attempts", description = "Endpoints for managing quiz attempts")
@RestController
@RequestMapping("/api/v1/attempts")
@RequiredArgsConstructor
@Validated
public class AttemptController {

    private final AttemptService attemptService;

    @Operation(
            summary = "Start an attempt for a quiz",
            description = "Creates a new attempt for the given quiz and returns its ID."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Attempt started",
                    content = @Content(schema = @Schema(implementation = StartAttemptResponse.class),
                            examples = @ExampleObject(name = "success", value = """
                                    {
                                      "attemptId":"3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                      "firstQuestion":null
                                    }
                                    """))),
            @ApiResponse(responseCode = "404", description = "Quiz not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/quizzes/{quizId}")
    public ResponseEntity<StartAttemptResponse> startAttempt(
            @Parameter(description = "Quiz UUID to start an attempt for", required = true)
            @PathVariable UUID quizId,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Optional request body specifying the attempt mode",
                    required = false,
                    content = @Content(
                            schema = @Schema(implementation = StartAttemptRequest.class)
                    )
            )
            @RequestBody(required = false) @Valid StartAttemptRequest request,

            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptMode mode = (request != null && request.mode() != null)
                ? request.mode()
                : AttemptMode.ALL_AT_ONCE;
        StartAttemptResponse dto = attemptService.startAttempt(username, quizId, mode);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @Operation(
            summary = "List attempts",
            description = "Retrieves a paginated list of attempts for the authenticated user, optionally filtered by quizId or userId."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of attempts returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptDto.class)
                    )
            )
    })
    @GetMapping
    public ResponseEntity<Page<AttemptDto>> listAttempts(
            @Parameter(in = ParameterIn.QUERY, description = "Page number (0-based)", example = "0")
            @Min(0) @RequestParam(name = "page", defaultValue = "0") int page,

            @Parameter(in = ParameterIn.QUERY, description = "Page size", example = "20")
            @Min(1) @RequestParam(name = "size", defaultValue = "20") int size,

            @Parameter(in = ParameterIn.QUERY, description = "Filter by quiz UUID")
            @RequestParam(name = "quizId", required = false) UUID quizId,

            @Parameter(in = ParameterIn.QUERY, description = "Filter by user UUID")
            @RequestParam(name = "userId", required = false) UUID userId,

            Authentication authentication
    ) {
        String username = authentication.getName();
        Page<AttemptDto> result = attemptService.getAttempts(
                username,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt")),
                quizId,
                userId
        );
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "List attempts with embedded quiz and stats",
            description = """
                    Retrieves a paginated list of attempts with embedded quiz summary and statistics.
                    This endpoint reduces N+1 queries by returning all necessary display data in a single API call.
                    
                    Benefits:
                    - Single API call instead of 1 + (N × 2) calls
                    - Includes quiz title and question count without additional requests
                    - Includes computed stats (accuracy, completion, timing) for completed attempts
                    - Optimized with JOIN FETCH to avoid N+1 database queries
                    
                    Use this endpoint for:
                    - User attempt history pages
                    - Admin dashboards showing attempts
                    - Any list view that needs to display quiz titles and scores
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of enriched attempts returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptSummaryDto.class)
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - Cannot access other users' attempts",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/summary")
    public ResponseEntity<Page<AttemptSummaryDto>> listAttemptsSummary(
            @Parameter(in = ParameterIn.QUERY, description = "Page number (0-based)", example = "0")
            @Min(0) @RequestParam(name = "page", defaultValue = "0") int page,

            @Parameter(in = ParameterIn.QUERY, description = "Page size", example = "20")
            @Min(1) @Max(1000) @RequestParam(name = "size", defaultValue = "20") int size,

            @Parameter(in = ParameterIn.QUERY, description = "Filter by quiz UUID")
            @RequestParam(name = "quizId", required = false) UUID quizId,

            @Parameter(in = ParameterIn.QUERY, description = "Filter by user UUID (defaults to current user; admins can specify others)")
            @RequestParam(name = "userId", required = false) UUID userId,

            @Parameter(in = ParameterIn.QUERY, description = "Filter by attempt status (COMPLETED, IN_PROGRESS, PAUSED, ABANDONED)", example = "COMPLETED")
            @RequestParam(name = "status", required = false) String statusParam,

            Authentication authentication
    ) {
        String username = authentication.getName();
        
        // Parse and validate status parameter
        AttemptStatus status = null;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                status = AttemptStatus.valueOf(statusParam.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid status value: " + statusParam + ". Valid values are: COMPLETED, IN_PROGRESS, PAUSED, ABANDONED");
            }
        }
        
        Page<AttemptSummaryDto> result = attemptService.getAttemptsSummary(
                username,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt")),
                quizId,
                userId,
                status
        );
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get attempt details", description = "Retrieve detailed information for a specific attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt details returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptDetailsDto.class)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{attemptId}")
    public ResponseEntity<AttemptDetailsDto> getAttempt(
            @Parameter(description = "UUID of the attempt to retrieve", required = true)
            @PathVariable UUID attemptId,

            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptDetailsDto attempt = attemptService.getAttemptDetail(username, attemptId);
        return ResponseEntity.ok(attempt);
    }

    @Operation(
            summary = "Get current question for an attempt",
            description = "Retrieve the current question for an in-progress attempt. This is useful when resuming an attempt after closing the browser."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Current question returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CurrentQuestionDto.class),
                            examples = @ExampleObject(name = "success", value = """
                                    {
                                      "question": {
                                        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                                        "type": "MULTIPLE_CHOICE",
                                        "content": "What is the capital of France?",
                                        "options": ["London", "Berlin", "Paris", "Madrid"]
                                      },
                                      "questionNumber": 3,
                                      "totalQuestions": 10,
                                      "attemptStatus": "IN_PROGRESS"
                                    }
                                    """))
            ),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Attempt is not in progress or all questions answered",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{attemptId}/current-question")
    public ResponseEntity<CurrentQuestionDto> getCurrentQuestion(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,

            Authentication authentication
    ) {
        String username = authentication.getName();
        CurrentQuestionDto currentQuestion = attemptService.getCurrentQuestion(username, attemptId);
        return ResponseEntity.ok(currentQuestion);
    }

    @Operation(
            summary = "Submit a single answer", 
            description = """
                    Submit an answer to a specific question within an attempt. 
                    The request body includes optional flags:
                    - includeCorrectness: include whether the answer is correct (isCorrect field)
                    - includeCorrectAnswer: include the correct answer information (correctAnswer field)
                    By default, both are false and excluded from the response for security reasons.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer submission result",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AnswerSubmissionDto.class)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Attempt or question not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{attemptId}/answers")
    public ResponseEntity<AnswerSubmissionDto> submitAnswer(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Answer submission payload with optional flags for response content",
                    required = true,
                    content = @Content(schema = @Schema(implementation = AnswerSubmissionRequest.class))
            )
            @RequestBody @Valid AnswerSubmissionRequest request,

            Authentication authentication
    ) {
        String username = authentication.getName();
        AnswerSubmissionDto answer = attemptService.submitAnswer(username, attemptId, request);
        return ResponseEntity.ok(answer);
    }

    @Operation(
            summary = "Submit batch of answers", 
            description = """
                    Submit multiple answers at once (only for ALL_AT_ONCE mode).
                    Each answer in the batch can have its own includeCorrectness and includeCorrectAnswer flags.
                    By default, both are false and excluded from the response for security reasons.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch answers submitted",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = AnswerSubmissionDto.class))
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Invalid attempt mode or duplicate answers",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{attemptId}/answers/batch")
    public ResponseEntity<List<AnswerSubmissionDto>> submitBatch(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Batch answer submission payload. Each answer can have its own includeCorrectness and includeCorrectAnswer flags.",
                    required = true,
                    content = @Content(schema = @Schema(implementation = BatchAnswerSubmissionRequest.class))
            )
            @RequestBody @Valid BatchAnswerSubmissionRequest request,

            Authentication authentication
    ) {
        String username = authentication.getName();
        List<AnswerSubmissionDto> answers =
                attemptService.submitBatch(username, attemptId, request);
        return ResponseEntity.ok(answers);
    }

    @Operation(summary = "Complete an attempt", description = "Marks the attempt as completed and returns the results.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt completed successfully",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptResultDto.class)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Attempt in invalid state",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{attemptId}/complete")
    public ResponseEntity<AttemptResultDto> completeAttempt(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,

            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptResultDto result = attemptService.completeAttempt(username, attemptId);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Get attempt statistics", description = "Retrieve detailed statistics for a specific attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt statistics returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptStatsDto.class)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{attemptId}/stats")
    public ResponseEntity<AttemptStatsDto> getAttemptStats(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptStatsDto stats = attemptService.getAttemptStats(attemptId, username);
        return ResponseEntity.ok(stats);
    }

    @Operation(summary = "Pause an attempt", description = "Pause an in-progress attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt paused successfully"),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Attempt cannot be paused",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{attemptId}/pause")
    public ResponseEntity<AttemptDto> pauseAttempt(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptDto result = attemptService.pauseAttempt(username, attemptId);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Resume an attempt", description = "Resume a paused attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt resumed successfully"),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Attempt cannot be resumed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{attemptId}/resume")
    public ResponseEntity<AttemptDto> resumeAttempt(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptDto result = attemptService.resumeAttempt(username, attemptId);
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Delete an attempt", description = "Delete an attempt and all its associated answers. Users can only delete their own attempts.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Attempt deleted successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not own the attempt",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{attemptId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAttempt(
            @Parameter(description = "UUID of the attempt to delete", required = true)
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        attemptService.deleteAttempt(username, attemptId);
    }

    @Operation(summary = "Get shuffled questions", description = "Get questions for a quiz in randomized order (safe, without answers).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Shuffled questions returned")
    })
    @GetMapping("/quizzes/{quizId}/questions/shuffled")
    public ResponseEntity<List<QuestionForAttemptDto>> getShuffledQuestions(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            Authentication authentication
    ) {
        List<QuestionForAttemptDto> questions = attemptService.getShuffledQuestions(quizId, authentication);
        return ResponseEntity.ok(questions);
    }

    @Operation(
            summary = "Get attempt review",
            description = """
                    Retrieve a comprehensive review of a completed attempt with user answers and correct answers.
                    Only available to the attempt owner and only for completed attempts.
                    Query parameters allow customizing what information to include in the response.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt review returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptReviewDto.class)
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not own the attempt",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Attempt is not completed yet",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{attemptId}/review")
    public ResponseEntity<AttemptReviewDto> getAttemptReview(
            @Parameter(description = "UUID of the attempt to review", required = true)
            @PathVariable UUID attemptId,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Include user's submitted answers in the response",
                    example = "true"
            )
            @RequestParam(name = "includeUserAnswers", defaultValue = "true") boolean includeUserAnswers,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Include correct answers and explanations in the response",
                    example = "true"
            )
            @RequestParam(name = "includeCorrectAnswers", defaultValue = "true") boolean includeCorrectAnswers,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Include question context (text, hint, attachments, safe content) for rendering",
                    example = "true"
            )
            @RequestParam(name = "includeQuestionContext", defaultValue = "true") boolean includeQuestionContext,

            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptReviewDto review = attemptService.getAttemptReview(
                username,
                attemptId,
                includeUserAnswers,
                includeCorrectAnswers,
                includeQuestionContext
        );
        return ResponseEntity.ok(review);
    }

    @Operation(
            summary = "Get attempt answer key",
            description = """
                    Retrieve an answer key for a completed attempt (correct answers and explanations, no user responses).
                    This is a convenience endpoint that returns correct answers with question context and explanations.
                    Only available to the attempt owner and only for completed attempts.
                    """
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer key returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptReviewDto.class)
                    )
            ),
            @ApiResponse(responseCode = "403", description = "Forbidden - User does not own the attempt",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Attempt not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - Attempt is not completed yet",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{attemptId}/answer-key")
    public ResponseEntity<AttemptReviewDto> getAttemptAnswerKey(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,

            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptReviewDto answerKey = attemptService.getAttemptAnswerKey(username, attemptId);
        return ResponseEntity.ok(answerKey);
    }

}