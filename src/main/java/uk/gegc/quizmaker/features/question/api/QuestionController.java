package uk.gegc.quizmaker.features.question.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionDto;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;
import uk.gegc.quizmaker.features.question.application.QuestionService;

import java.util.Map;
import java.util.UUID;

@Tag(
        name = "Questions",
        description = "Operations for managing quiz questions"
)
@RestController
@RequestMapping("/api/v1/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @Operation(
            summary = "Create a question",
            description = "Add a new question (ADMIN only)",
            tags = {"Questions"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Question created"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @SecurityRequirement(name = "bearerAuth")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Question to create",
            required = true,
            content = @Content(schema = @Schema(implementation = CreateQuestionRequest.class))
    )
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, UUID>> createQuestion(
            Authentication authentication,
            @RequestBody @Valid CreateQuestionRequest request
    ) {
        UUID id = questionService.createQuestion(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("questionId", id));
    }

    @Operation(
            summary = "List questions",
            description = "Get a page of questions, optionally filtered by quizId",
            tags = {"Questions"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of questions returned")
    })
    @GetMapping
    public ResponseEntity<Page<QuestionDto>> getQuestions(
            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Filter by quiz UUID",
                    required = false
            )
            @RequestParam(required = false) UUID quizId,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Page number (0-based)",
                    example = "0"
            )
            @RequestParam(defaultValue = "0") int pageNumber,

            @Parameter(
                    in = ParameterIn.QUERY,
                    description = "Page size",
                    example = "20"
            )
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable page = PageRequest.of(pageNumber, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(questionService.listQuestions(quizId, page));
    }

    @Operation(
            summary = "Get question by ID",
            description = "Retrieve a single question by its UUID",
            tags = {"Questions"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Question returned"),
            @ApiResponse(responseCode = "404", description = "Question not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<QuestionDto> getQuestion(
            @Parameter(description = "UUID of the question", required = true)
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(questionService.getQuestion(id));
    }

    @Operation(
            summary = "Update a question",
            description = "Modify an existing question (ADMIN only)",
            tags = {"Questions"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Question updated"),
            @ApiResponse(responseCode = "400", description = "Validation error"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Question not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Updated question data",
            required = true,
            content = @Content(schema = @Schema(implementation = UpdateQuestionRequest.class))
    )
    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuestionDto> updateQuestion(
            @Parameter(description = "UUID of the question", required = true)
            @PathVariable UUID id,
            Authentication authentication,
            @RequestBody @Valid UpdateQuestionRequest request
    ) {
        return ResponseEntity.ok(
                questionService.updateQuestion(authentication.getName(), id, request)
        );
    }

    @Operation(
            summary = "Delete a question",
            description = "Remove a question by its UUID (ADMIN only)",
            tags = {"Questions"}
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Question deleted"),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "403", description = "Forbidden"),
            @ApiResponse(responseCode = "404", description = "Question not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteQuestion(
            @Parameter(description = "UUID of the question", required = true)
            @PathVariable UUID id,
            Authentication authentication
    ) {
        questionService.deleteQuestion(authentication.getName(), id);
    }
}