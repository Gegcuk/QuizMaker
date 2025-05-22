package uk.gegc.quizmaker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.dto.attempt.*;
import uk.gegc.quizmaker.model.attempt.AttemptMode;
import uk.gegc.quizmaker.service.attempt.AttemptService;

import java.util.List;
import java.util.Map;
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
            @ApiResponse(responseCode = "201", description = "Attempt started successfully",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(
                                    example = "{\"attemptId\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}"
                            )
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Quiz not found")
    })
    @PostMapping("/quizzes/{quizId}")
    public ResponseEntity<Map<String, UUID>> startAttempt(
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
        AttemptDto dto = attemptService.startAttempt(username, quizId, mode);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("attemptId", dto.attemptId()));
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

    @Operation(summary = "Get attempt details", description = "Retrieve detailed information for a specific attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Attempt details returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AttemptDetailsDto.class)
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Attempt not found")
    })
    @GetMapping("/{attemptId}")
    public ResponseEntity<AttemptDetailsDto> getAttempt(
            @Parameter(description = "UUID of the attempt to retrieve", required = true)
            @PathVariable UUID attemptId,

            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptDetailsDto details = attemptService.getAttemptDetail(username, attemptId);
        return ResponseEntity.ok(details);
    }

    @Operation(summary = "Submit a single answer", description = "Submit an answer to a specific question within an attempt.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer submission result",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AnswerSubmissionDto.class)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "404", description = "Attempt or question not found")
    })
    @PostMapping("/{attemptId}/answers")
    public ResponseEntity<AnswerSubmissionDto> submitAnswer(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Answer submission payload",
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

    @Operation(summary = "Submit batch of answers", description = "Submit multiple answers at once (only for ALL_AT_ONCE mode).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch answers submitted",
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = AnswerSubmissionDto.class))
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "404", description = "Attempt not found"),
            @ApiResponse(responseCode = "409", description = "Invalid attempt mode or duplicate answers")
    })
    @PostMapping("/{attemptId}/answers/batch")
    public ResponseEntity<List<AnswerSubmissionDto>> submitBatch(
            @Parameter(description = "UUID of the attempt", required = true)
            @PathVariable UUID attemptId,

            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Batch answer submission payload",
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
            @ApiResponse(responseCode = "404", description = "Attempt not found"),
            @ApiResponse(responseCode = "409", description = "Attempt in invalid state")
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

}