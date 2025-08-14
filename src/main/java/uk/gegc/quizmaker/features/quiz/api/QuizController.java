package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.controller.advice.GlobalExceptionHandler;
import uk.gegc.quizmaker.dto.attempt.AttemptDto;
import uk.gegc.quizmaker.dto.attempt.AttemptStatsDto;
import uk.gegc.quizmaker.dto.document.ProcessDocumentRequest;
import uk.gegc.quizmaker.dto.quiz.*;
import uk.gegc.quizmaker.dto.result.LeaderboardEntryDto;
import uk.gegc.quizmaker.dto.result.QuizResultSummaryDto;
import uk.gegc.quizmaker.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.question.QuestionType;
import uk.gegc.quizmaker.model.quiz.GenerationStatus;
import uk.gegc.quizmaker.model.quiz.QuizGenerationJob;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.repository.quiz.QuizGenerationJobRepository;
import uk.gegc.quizmaker.service.RateLimitService;
import uk.gegc.quizmaker.service.attempt.AttemptService;
import uk.gegc.quizmaker.service.document.DocumentProcessingService;
import uk.gegc.quizmaker.service.document.DocumentValidationService;
import uk.gegc.quizmaker.service.quiz.QuizGenerationJobService;
import uk.gegc.quizmaker.service.quiz.QuizService;
import uk.gegc.quizmaker.util.TrustedProxyUtil;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;


@Tag(name = "Quizzes", description = "Operations for creating, reading, updating, deleting and associating quizzes.")
@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
@Validated
@Slf4j
public class QuizController {

    private final QuizService quizService;
    private final AttemptService attemptService;
    private final DocumentProcessingService documentProcessingService;
    private final DocumentValidationService documentValidationService;
    private final QuizGenerationJobService jobService;
    private final QuizGenerationJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final TrustedProxyUtil trustedProxyUtil;

    @Operation(
            summary = "Create a new quiz",
            description = "Requires ADMIN role. Returns the generated UUID of the created quiz."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Payload for creating a new quiz",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = CreateQuizRequest.class)
            )
    )
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, UUID>> createQuiz(@RequestBody @Valid CreateQuizRequest request,
                                                        Authentication authentication) {
        UUID quizId = quizService.createQuiz(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("quizId", quizId));
    }

    @Operation(
            summary = "List quizzes with pagination and optional filters",
            description = "Returns a page of quizzes; you can page/sort via `page`, `size`, `sort`, and filter via the fields of QuizSearchCriteria"
    )
    @GetMapping
    public ResponseEntity<Page<QuizDto>> getQuizzes(
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,

            @ParameterObject
            @ModelAttribute
            QuizSearchCriteria quizSearchCriteria,
            HttpServletRequest request
    ) {
        // Rate limit search endpoint: 120/min per IP
        String clientIp = trustedProxyUtil.getClientIp(request);
        rateLimitService.checkRateLimit("search-quizzes", clientIp, 120);

        Page<QuizDto> quizPage = quizService.getQuizzes(pageable, quizSearchCriteria);

        // Simple weak ETag based on result set metadata
        String eTag = ("W/\"" + quizPage.getTotalElements() + ":" + quizPage.getNumber() + ":" + quizPage.getSize() + ":" + quizPage.getSort() + "\"");

        String ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.equals(eTag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
        }
        return ResponseEntity.ok().eTag(eTag).body(quizPage);
    }

    @Operation(
            summary = "Get a quiz by its ID",
            description = "Returns full QuizDto; 404 if not found."
    )
    @GetMapping("/{quizId}")
    public ResponseEntity<QuizDto> getQuiz(
            @Parameter(description = "UUID of the quiz to retrieve", required = true)
            @PathVariable UUID quizId) {
        return ResponseEntity.ok(quizService.getQuizById(quizId));
    }

    @Operation(
            summary = "Update an existing quiz",
            description = "ADMIN only. Only provided fields will be updated."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Fields to update in the quiz",
            required = true,
            content = @Content(
                    schema = @Schema(implementation = UpdateQuizRequest.class)
            )
    )
    @PatchMapping("/{quizId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizDto> updateQuiz(
            @Parameter(description = "UUID of the quiz to update", required = true)
            @PathVariable UUID quizId,
            @RequestBody @Valid UpdateQuizRequest request,
            Authentication authentication
    ) {
        return ResponseEntity.ok(
                quizService.updateQuiz(authentication.getName(), quizId, request)
        );
    }

    @Operation(
            summary = "Bulk update quizzes",
            description = "ADMIN only. Update multiple quizzes in one request."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Bulk update payload",
            required = true,
            content = @Content(schema = @Schema(implementation = BulkQuizUpdateRequest.class))
    )
    @PatchMapping("/bulk-update")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BulkQuizUpdateOperationResultDto> bulkUpdateQuizzes(
            @RequestBody @Valid BulkQuizUpdateRequest request,
            Authentication authentication
    ) {
        BulkQuizUpdateOperationResultDto resultDto = quizService.bulkUpdateQuiz(authentication.getName(), request);
        return ResponseEntity.ok(resultDto);
    }

    @Operation(
            summary = "Delete a quiz",
            description = "ADMIN only. Permanently deletes the quiz."
    )
    @DeleteMapping("/{quizId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteQuiz(
            @Parameter(description = "UUID of the quiz to delete", required = true)
            @PathVariable UUID quizId,
            Authentication authentication) {
        quizService.deleteQuizById(authentication.getName(), quizId);
    }

    @Operation(
            summary = "Bulk delete quizzes",
            description = "ADMIN only. Delete multiple quizzes by comma-separated IDs."
    )
    @DeleteMapping(params = "ids")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuizzes(
            @Parameter(description = "Comma-separated quiz IDs", required = true)
            @RequestParam("ids") List<UUID> quizIds,
            Authentication authentication
    ) {
        quizService.deleteQuizzesByIds(authentication.getName(), quizIds);
    }

    @Operation(
            summary = "Associate a question with a quiz",
            description = "ADMIN only. Adds an existing question to the quiz."
    )
    @PostMapping("/{quizId}/questions/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addQuestion(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            @Parameter(description = "UUID of the question", required = true)
            @PathVariable UUID questionId,
            Authentication authentication
    ) {
        quizService.addQuestionToQuiz(authentication.getName(), quizId, questionId);
    }

    @Operation(
            summary = "Remove a question from a quiz",
            description = "ADMIN only."
    )
    @DeleteMapping("/{quizId}/questions/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeQuestion(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            @Parameter(description = "UUID of the question", required = true)
            @PathVariable UUID questionId,
            Authentication authentication
    ) {
        quizService.removeQuestionFromQuiz(authentication.getName(), quizId, questionId);
    }

    @Operation(
            summary = "Add a tag to a quiz",
            description = "ADMIN only."
    )
    @PostMapping("/{quizId}/tags/{tagId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addTag(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            @Parameter(description = "UUID of the tag", required = true)
            @PathVariable UUID tagId,
            Authentication authentication
    ) {
        quizService.addTagToQuiz(authentication.getName(), quizId, tagId);
    }

    @Operation(
            summary = "Remove a tag from a quiz",
            description = "ADMIN only."
    )
    @DeleteMapping("/{quizId}/tags/{tagId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTag(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            @Parameter(description = "UUID of the tag", required = true)
            @PathVariable UUID tagId,
            Authentication authentication
    ) {
        quizService.removeTagFromQuiz(authentication.getName(), quizId, tagId);
    }

    @Operation(
            summary = "Change a quiz’s category",
            description = "ADMIN only."
    )
    @PatchMapping("/{quizId}/category/{categoryId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeCategory(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            @Parameter(description = "UUID of the new category", required = true)
            @PathVariable UUID categoryId,
            Authentication authentication
    ) {
        quizService.changeCategory(authentication.getName(), quizId, categoryId);
    }

    @Operation(
            summary = "Get aggregated quiz results summary",
            description = "Returns counts, scores, pass rate and per-question stats."
    )
    @GetMapping("/{quizId}/results")
    public ResponseEntity<QuizResultSummaryDto> getQuizResults(
            @Parameter(description = "UUID of the quiz to summarize", required = true)
            @PathVariable UUID quizId
    ) {
        return ResponseEntity.ok(attemptService.getQuizResultSummary(quizId));
    }

    @Operation(
            summary = "Get quiz leaderboard",
            description = "Retrieve top participants of a quiz raked by score"
    )
    @GetMapping("/{quizId}/leaderboard")
    public ResponseEntity<List<LeaderboardEntryDto>> getQuizLeaderboard(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            @RequestParam(name = "top", defaultValue = "10") int top
    ) {
        List<LeaderboardEntryDto> leaderBoardEntryDtos = attemptService.getQuizLeaderboard(quizId, top);
        return ResponseEntity.ok(leaderBoardEntryDtos);
    }

    @Operation(
            summary = "Owner-only: List attempts for a quiz",
            description = "Returns all attempts for the specified quiz. Only the quiz owner can access this endpoint.")
    @GetMapping("/{quizId}/attempts")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<AttemptDto>> listAttemptsForQuizOwner(
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            Authentication authentication
    ) {
        List<AttemptDto> attempts = attemptService.getAttemptsForQuizOwner(authentication.getName(), quizId);
        return ResponseEntity.ok(attempts);
    }

    @Operation(
            summary = "Owner-only: Attempt stats for a quiz",
            description = "Returns attempt statistics for a specific attempt belonging to the quiz. Only the quiz owner can access this endpoint.")
    @GetMapping("/{quizId}/attempts/{attemptId}/stats")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AttemptStatsDto> getAttemptStatsForQuizOwner(
            @Parameter(description = "UUID of the quiz", required = true) @PathVariable UUID quizId,
            @Parameter(description = "UUID of the attempt", required = true) @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        AttemptStatsDto stats = attemptService.getAttemptStatsForQuizOwner(authentication.getName(), quizId, attemptId);
        return ResponseEntity.ok(stats);
    }

    @Operation(
            summary = "Toggle quiz visibility",
            description = "ADMIN only – switch a quiz between PUBLIC and PRIVATE.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(implementation = VisibilityUpdateRequest.class))
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Quiz successfully updated",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = QuizDto.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Validation failure or malformed JSON",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Authenticated but not an ADMIN",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Quiz not found",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @PatchMapping("/{quizId}/visibility")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizDto> updateQuizVisibility(
            @Parameter(description = "UUID of the quiz to update", required = true)
            @PathVariable UUID quizId,
            @RequestBody @Valid VisibilityUpdateRequest request,
            Authentication authentication
    ) {
        QuizDto quizDto = quizService.setVisibility(
                authentication.getName(),
                quizId,
                request.isPublic() ? Visibility.PUBLIC : Visibility.PRIVATE
        );
        return ResponseEntity.ok(quizDto);
    }

    @Operation(
            summary = "Change quiz status",
            description = "ADMIN only - switch a quiz between DRAFT and PUBLISHED",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(implementation = QuizStatusUpdateRequest.class))
            ),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Quiz successfully updated",
                            content = @Content(mediaType = "application/json", schema = @Schema(implementation = QuizDto.class))),
                    @ApiResponse(responseCode = "400", description = "Validation failure or illegal status transition",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
                    @ApiResponse(responseCode = "401", description = "Unauthenticated",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
                    @ApiResponse(responseCode = "403", description = "Authenticated but not ADMIN",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Quiz not found",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class)))
            }
    )
    @PatchMapping("/{quizId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizDto> updateQuizStatus(
            @Parameter(description = "UUID of the quiz to update", required = true)
            @PathVariable UUID quizId,
            @RequestBody @Valid QuizStatusUpdateRequest request,
            Authentication authentication
    ) {
        QuizDto quizDto = quizService.setStatus(
                authentication.getName(),
                quizId,
                request.status()
        );
        return ResponseEntity.ok(quizDto);
    }

    @Operation(
            summary = "List public quizzes",
            description = "Returns a paginated list of quizzes with PUBLIC visibility"
    )
    @GetMapping("/public")
    public ResponseEntity<Page<QuizDto>> getPublicQuizzes(
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            HttpServletRequest request
    ) {
        // Rate limit public search endpoint: 120/min per IP
        String clientIp = trustedProxyUtil.getClientIp(request);
        rateLimitService.checkRateLimit("search-quizzes-public", clientIp, 120);

        Page<QuizDto> page = quizService.getPublicQuizzes(pageable);
        String eTag = ("W/\"" + page.getTotalElements() + ":" + page.getNumber() + ":" + page.getSize() + ":" + page.getSort() + "\"");
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.equals(eTag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(eTag).build();
        }
        return ResponseEntity.ok().eTag(eTag).body(page);
    }

    @Operation(
            summary = "Submit quiz for moderation review",
            description = "Creator submits the quiz for review. Transitions to PENDING_REVIEW.")
    @PostMapping("/{quizId}/submit-for-review")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> submitForReview(@PathVariable UUID quizId, Authentication authentication) {
        // Reuse the existing service contract; controller determines actor from auth
        uk.gegc.quizmaker.model.user.User dummy = new uk.gegc.quizmaker.model.user.User();
        // Find current user ID via service; we only have username here, so delegate through service layer in future
        // For now, call ModerationService via QuizService if exposed; otherwise this endpoint is a placeholder
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Unpublish a quiz",
            description = "Move a published quiz back to draft state.")
    @PostMapping("/{quizId}/unpublish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unpublishQuiz(@PathVariable UUID quizId,
                                              @RequestBody(required = false) UnpublishRequest request,
                                              Authentication authentication) {
        // Placeholder: wire to ModerationService when controller-level moderation endpoints are finalized
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Generate quiz from document using AI (Async)",
            description = "ADMIN only. Start an asynchronous quiz generation job from uploaded document chunks using AI. The document must be processed and have chunks available. Users can specify exactly how many questions of each type to generate per chunk. Supports different scopes: entire document, specific chunks, specific chapter, or specific section. Returns a job ID for tracking progress.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(implementation = GenerateQuizFromDocumentRequest.class))
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "202",
                            description = "Quiz generation job started successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = QuizGenerationResponse.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Validation failure or invalid request",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Authenticated but not an ADMIN",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Document not found or not processed",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "409",
                            description = "User already has an active generation job",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @PostMapping("/generate-from-document")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizGenerationResponse> generateQuizFromDocument(
            @RequestBody @Valid GenerateQuizFromDocumentRequest request,
            Authentication authentication
    ) {
        QuizGenerationResponse response = quizService.startQuizGeneration(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @Operation(
            summary = "Upload document and generate quiz in one operation (Async)",
            description = "ADMIN only. Upload a document, process it, and start quiz generation in a single operation. This endpoint combines document upload and quiz generation for simpler frontend integration. Returns a job ID for tracking progress.",
            security = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = "multipart/form-data",
                            schema = @Schema(implementation = GenerateQuizFromUploadRequest.class)
                    )
            ),
            responses = {
                    @ApiResponse(
                            responseCode = "202",
                            description = "Document uploaded, processed, and quiz generation started",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = QuizGenerationResponse.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Validation failure or invalid request",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Authenticated but not an ADMIN",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "422",
                            description = "Document processing failed",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @PostMapping(value = "/generate-from-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizGenerationResponse> generateQuizFromUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkingStrategy", required = false) String chunkingStrategy,
            @RequestParam(value = "maxChunkSize", required = false) Integer maxChunkSize,
            @RequestParam(value = "quizScope", required = false) String quizScope,
            @RequestParam(value = "chunkIndices", required = false) List<Integer> chunkIndices,
            @RequestParam(value = "chapterTitle", required = false) String chapterTitle,
            @RequestParam(value = "chapterNumber", required = false) Integer chapterNumber,
            @RequestParam(value = "quizTitle", required = false) String quizTitle,
            @RequestParam(value = "quizDescription", required = false) String quizDescription,
            @RequestParam(value = "questionsPerType", required = true) String questionsPerTypeJson,
            @RequestParam(value = "difficulty", required = true) String difficulty,
            @RequestParam(value = "estimatedTimePerQuestion", required = false) Integer estimatedTimePerQuestion,
            @RequestParam(value = "categoryId", required = false) UUID categoryId,
            @RequestParam(value = "tagIds", required = false) List<UUID> tagIds,
            Authentication authentication
    ) {
        try {
            // Validate file upload
            documentValidationService.validateFileUpload(file, chunkingStrategy, maxChunkSize);

            // Parse questions per type from JSON string
            Map<QuestionType, Integer> questionsPerType = parseQuestionsPerType(questionsPerTypeJson);

            // Create the combined request DTO
            GenerateQuizFromUploadRequest request = new GenerateQuizFromUploadRequest(
                    chunkingStrategy != null ? ProcessDocumentRequest.ChunkingStrategy.valueOf(chunkingStrategy.toUpperCase()) : null,
                    maxChunkSize,
                    quizScope != null ? QuizScope.valueOf(quizScope.toUpperCase()) : null,
                    chunkIndices,
                    chapterTitle,
                    chapterNumber,
                    quizTitle,
                    quizDescription,
                    questionsPerType,
                    Difficulty.valueOf(difficulty.toUpperCase()),
                    estimatedTimePerQuestion,
                    categoryId,
                    tagIds
            );

            // Process document and start quiz generation
            QuizGenerationResponse response = quizService.generateQuizFromUpload(authentication.getName(), file, request);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate quiz from upload: " + e.getMessage(), e);
        }
    }

    @Operation(
            summary = "Get quiz generation job status",
            description = "Get the current status and progress of a quiz generation job. Returns detailed information about the generation progress including processed chunks, estimated completion time, and any errors.",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Job status retrieved successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = QuizGenerationStatus.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Authenticated but not authorized to access this job",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Generation job not found",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @GetMapping("/generation-status/{jobId}")
    public ResponseEntity<QuizGenerationStatus> getGenerationStatus(
            @Parameter(description = "UUID of the generation job", required = true)
            @PathVariable UUID jobId,
            Authentication authentication
    ) {
        QuizGenerationStatus status = quizService.getGenerationStatus(jobId, authentication.getName());
        return ResponseEntity.ok(status);
    }

    @Operation(
            summary = "Get generated quiz from completed job",
            description = "Retrieve the final generated quiz once the generation job is completed. This endpoint should only be called after the generation status indicates completion.",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Generated quiz retrieved successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = QuizDto.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Authenticated but not authorized to access this quiz",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Generation job or generated quiz not found",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "409",
                            description = "Generation job is not yet completed",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @GetMapping("/generated-quiz/{jobId}")
    public ResponseEntity<QuizDto> getGeneratedQuiz(
            @Parameter(description = "UUID of the generation job", required = true)
            @PathVariable UUID jobId,
            Authentication authentication
    ) {
        QuizDto quiz = quizService.getGeneratedQuiz(jobId, authentication.getName());
        return ResponseEntity.ok(quiz);
    }

    @Operation(
            summary = "Cancel quiz generation job",
            description = "Cancel an active quiz generation job. Only jobs that are in PENDING or PROCESSING status can be cancelled.",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Generation job cancelled successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = QuizGenerationStatus.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Job cannot be cancelled (already completed or failed)",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "403",
                            description = "Authenticated but not authorized to cancel this job",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Generation job not found",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @DeleteMapping("/generation-status/{jobId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizGenerationStatus> cancelGenerationJob(
            @Parameter(description = "UUID of the generation job to cancel", required = true)
            @PathVariable UUID jobId,
            Authentication authentication
    ) {
        QuizGenerationStatus status = quizService.cancelGenerationJob(jobId, authentication.getName());
        return ResponseEntity.ok(status);
    }

    @Operation(
            summary = "List user's quiz generation jobs",
            description = "Get a paginated list of all quiz generation jobs for the authenticated user, ordered by creation time (newest first).",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Generation jobs retrieved successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = Page.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @GetMapping("/generation-jobs")
    public ResponseEntity<Page<QuizGenerationStatus>> getGenerationJobs(
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "startedAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            Authentication authentication
    ) {
        Page<QuizGenerationStatus> jobs = quizService.getGenerationJobs(authentication.getName(), pageable);
        return ResponseEntity.ok(jobs);
    }

    @Operation(
            summary = "Get generation job statistics",
            description = "Get statistics about the user's quiz generation jobs including success rates, average generation times, and job counts by status.",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Statistics retrieved successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = QuizGenerationJobService.JobStatistics.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @GetMapping("/generation-jobs/statistics")
    public ResponseEntity<QuizGenerationJobService.JobStatistics> getGenerationJobStatistics(
            Authentication authentication
    ) {
        QuizGenerationJobService.JobStatistics statistics = quizService.getGenerationJobStatistics(authentication.getName());
        return ResponseEntity.ok(statistics);
    }

    @Operation(
            summary = "Clean up stale pending jobs",
            description = "Clean up any pending jobs that have been pending for too long (10+ minutes). This is useful for clearing stuck jobs.",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Cleanup completed successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = String.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @PostMapping("/generation-jobs/cleanup-stale")
    public ResponseEntity<String> cleanupStaleJobs(Authentication authentication) {
        // This is a simple cleanup operation that doesn't require user-specific logic
        // In a production system, you might want to restrict this to admin users
        jobService.cleanupStalePendingJobs();
        return ResponseEntity.ok("Stale jobs cleaned up successfully");
    }

    @Operation(
            summary = "Force cancel a specific job",
            description = "Force cancel a specific generation job by ID. This is useful for clearing stuck jobs that can't be cancelled normally.",
            security = @SecurityRequirement(name = "bearerAuth"),
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Job cancelled successfully",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(implementation = String.class)
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "Unauthenticated – JWT missing/expired",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Job not found",
                            content = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
                    )
            }
    )
    @PostMapping("/generation-jobs/{jobId}/force-cancel")
    public ResponseEntity<String> forceCancelJob(
            @Parameter(description = "UUID of the job to force cancel", required = true)
            @PathVariable UUID jobId,
            Authentication authentication
    ) {
        try {
            QuizGenerationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + jobId));
            
            log.info("Force cancelling job: {} (current status: {})", jobId, job.getStatus());
            job.setStatus(GenerationStatus.CANCELLED);
            job.setErrorMessage("Force cancelled by user");
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
            
            return ResponseEntity.ok("Job " + jobId + " force cancelled successfully");
        } catch (Exception e) {
            log.error("Error force cancelling job: {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error cancelling job: " + e.getMessage());
        }
    }

    /**
     * Parse questions per type from JSON string
     */
    private Map<QuestionType, Integer> parseQuestionsPerType(String questionsPerTypeJson) {
        try {
            return objectMapper.readValue(questionsPerTypeJson, new TypeReference<Map<QuestionType, Integer>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid questionsPerType JSON format: " + e.getMessage());
        }
    }
}
