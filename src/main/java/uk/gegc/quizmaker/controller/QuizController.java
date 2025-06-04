package uk.gegc.quizmaker.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.SortDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.controller.advice.GlobalExceptionHandler;
import uk.gegc.quizmaker.dto.quiz.*;
import uk.gegc.quizmaker.dto.result.QuizResultSummaryDto;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.service.attempt.AttemptService;
import uk.gegc.quizmaker.service.quiz.QuizService;

import java.util.Map;
import java.util.UUID;


@Tag(name = "Quizzes", description = "Operations for creating, reading, updating, deleting and associating quizzes.")
@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
@Validated
public class QuizController {

    private final QuizService quizService;
    private final AttemptService attemptService;

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
            QuizSearchCriteria quizSearchCriteria
    ) {
        Page<QuizDto> quizPage = quizService.getQuizzes(pageable, quizSearchCriteria);
        return ResponseEntity.ok(quizPage);
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
            summary     = "Toggle quiz visibility",
            description = "ADMIN only – switch a quiz between PUBLIC and PRIVATE.",
            security    = @SecurityRequirement(name = "bearerAuth"),
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content  = @Content(schema = @Schema(implementation = VisibilityUpdateRequest.class))
            ),
            responses = {
           @ApiResponse(
              responseCode = "200",
              description  = "Quiz successfully updated",
             content      = @Content(
                   mediaType = "application/json",
                    schema    = @Schema(implementation = QuizDto.class)
         )
        ),
        @ApiResponse(
            responseCode = "400",
          description  = "Validation failure or malformed JSON",
            content      = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
      ),
        @ApiResponse(
          responseCode = "401",
          description  = "Unauthenticated – JWT missing/expired",
         content      = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
      ),
       @ApiResponse(
           responseCode = "403",
             description  = "Authenticated but not an ADMIN",
            content      = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
    ),
      @ApiResponse(
             responseCode = "404",
             description  = "Quiz not found",
             content      = @Content(schema = @Schema(implementation = GlobalExceptionHandler.ErrorResponse.class))
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
    ){
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
    ){
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
            Pageable pageable
    ) {
        return ResponseEntity.ok(quizService.getPublicQuizzes(pageable));
    }
}
