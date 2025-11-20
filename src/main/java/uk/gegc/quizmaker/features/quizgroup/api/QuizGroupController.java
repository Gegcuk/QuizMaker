package uk.gegc.quizmaker.features.quizgroup.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSummaryDto;
import uk.gegc.quizmaker.features.quizgroup.api.dto.*;
import uk.gegc.quizmaker.features.quizgroup.application.QuizGroupService;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Quiz Groups", description = "Operations for creating, reading, updating, deleting and managing quiz groups")
@RestController
@RequestMapping("/api/v1/quiz-groups")
@RequiredArgsConstructor
@Validated
@Slf4j
@SecurityRequirement(name = "bearerAuth")
public class QuizGroupController {

    private final QuizGroupService quizGroupService;

    @Operation(
            summary = "Create a new quiz group",
            description = "Create a new quiz group. Requires QUIZ_GROUP_CREATE permission. Ownership is set to the current user."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Payload for creating a new quiz group",
            required = true,
            content = @Content(schema = @Schema(implementation = CreateQuizGroupRequest.class))
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Quiz group created successfully",
                    content = @Content(schema = @Schema(implementation = Map.class))),
            @ApiResponse(responseCode = "400", description = "Validation failure",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    @RequirePermission(PermissionName.QUIZ_GROUP_CREATE)
    public ResponseEntity<Map<String, UUID>> createQuizGroup(
            @RequestBody @Valid CreateQuizGroupRequest request,
            Authentication authentication
    ) {
        UUID groupId = quizGroupService.create(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("groupId", groupId));
    }

    @Operation(
            summary = "List quiz groups",
            description = "Returns a page of quiz groups owned by the authenticated user, sorted by creation date (newest first)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of quiz groups returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    @RequirePermission(PermissionName.QUIZ_GROUP_READ)
    public ResponseEntity<Page<QuizGroupSummaryDto>> getQuizGroups(
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            Authentication authentication
    ) {
        Page<QuizGroupSummaryDto> groups = quizGroupService.list(pageable, authentication);
        return ResponseEntity.ok(groups);
    }

    @Operation(
            summary = "Get a quiz group by ID",
            description = "Returns full QuizGroupDto for the specified group. Requires QUIZ_GROUP_READ permission and ownership or QUIZ_GROUP_ADMIN permission."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quiz group returned",
                    content = @Content(schema = @Schema(implementation = QuizGroupDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz group not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{groupId}")
    @RequirePermission(PermissionName.QUIZ_GROUP_READ)
    public ResponseEntity<QuizGroupDto> getQuizGroup(
            @Parameter(description = "UUID of the quiz group", required = true)
            @PathVariable UUID groupId,
            Authentication authentication
    ) {
        QuizGroupDto group = quizGroupService.get(groupId, authentication);
        return ResponseEntity.ok(group);
    }

    @Operation(
            summary = "Update a quiz group",
            description = "Update quiz group details. Requires QUIZ_GROUP_UPDATE permission and ownership or QUIZ_GROUP_ADMIN permission. Only provided fields will be updated."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Fields to update in the quiz group",
            required = true,
            content = @Content(schema = @Schema(implementation = UpdateQuizGroupRequest.class))
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Quiz group updated successfully",
                    content = @Content(schema = @Schema(implementation = QuizGroupDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation failure",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz group not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping("/{groupId}")
    @RequirePermission(PermissionName.QUIZ_GROUP_UPDATE)
    public ResponseEntity<QuizGroupDto> updateQuizGroup(
            @Parameter(description = "UUID of the quiz group", required = true)
            @PathVariable UUID groupId,
            @RequestBody @Valid UpdateQuizGroupRequest request,
            Authentication authentication
    ) {
        QuizGroupDto group = quizGroupService.update(authentication.getName(), groupId, request);
        return ResponseEntity.ok(group);
    }

    @Operation(
            summary = "Delete a quiz group",
            description = "Soft delete a quiz group. Requires QUIZ_GROUP_DELETE permission and ownership or QUIZ_GROUP_ADMIN permission."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Quiz group deleted successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz group not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionName.QUIZ_GROUP_DELETE)
    public void deleteQuizGroup(
            @Parameter(description = "UUID of the quiz group", required = true)
            @PathVariable UUID groupId,
            Authentication authentication
    ) {
        quizGroupService.delete(authentication.getName(), groupId);
    }

    @Operation(
            summary = "List quizzes in a group",
            description = "Returns a paginated list of quizzes in the specified group, ordered by membership position."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of quizzes returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz group not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/{groupId}/quizzes")
    @RequirePermission(PermissionName.QUIZ_GROUP_READ)
    public ResponseEntity<Page<QuizSummaryDto>> getQuizzesInGroup(
            @Parameter(description = "UUID of the quiz group", required = true)
            @PathVariable UUID groupId,
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            Pageable pageable,
            Authentication authentication
    ) {
        Page<QuizSummaryDto> quizzes = quizGroupService.getQuizzesInGroup(groupId, pageable, authentication);
        return ResponseEntity.ok(quizzes);
    }

    @Operation(
            summary = "Add quizzes to a group",
            description = "Add one or more quizzes to a quiz group. Requires QUIZ_GROUP_UPDATE permission and ownership. Idempotent - existing memberships are ignored."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Quizzes to add and optional position",
            required = true,
            content = @Content(schema = @Schema(implementation = AddQuizzesToGroupRequest.class))
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Quizzes added successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failure",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions or quiz not owned by user",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz group or quiz not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/{groupId}/quizzes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionName.QUIZ_GROUP_UPDATE)
    public void addQuizzesToGroup(
            @Parameter(description = "UUID of the quiz group", required = true)
            @PathVariable UUID groupId,
            @RequestBody @Valid AddQuizzesToGroupRequest request,
            Authentication authentication
    ) {
        quizGroupService.addQuizzes(authentication.getName(), groupId, request);
    }

    @Operation(
            summary = "Remove a quiz from a group",
            description = "Remove a quiz from a quiz group. Requires QUIZ_GROUP_UPDATE permission and ownership."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Quiz removed successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz group or quiz not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{groupId}/quizzes/{quizId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionName.QUIZ_GROUP_UPDATE)
    public void removeQuizFromGroup(
            @Parameter(description = "UUID of the quiz group", required = true)
            @PathVariable UUID groupId,
            @Parameter(description = "UUID of the quiz", required = true)
            @PathVariable UUID quizId,
            Authentication authentication
    ) {
        quizGroupService.removeQuiz(authentication.getName(), groupId, quizId);
    }

    @Operation(
            summary = "Reorder quizzes in a group",
            description = "Reorder quizzes in a quiz group according to the provided ordered list. Requires QUIZ_GROUP_UPDATE permission and ownership. Positions are renumbered to a dense 0..n-1 sequence."
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Ordered list of quiz IDs (new order)",
            required = true,
            content = @Content(schema = @Schema(implementation = ReorderGroupQuizzesRequest.class))
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Quizzes reordered successfully"),
            @ApiResponse(responseCode = "400", description = "Validation failure or invalid order",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Quiz group not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Conflict - group was modified concurrently",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping("/{groupId}/quizzes/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionName.QUIZ_GROUP_UPDATE)
    public void reorderQuizzesInGroup(
            @Parameter(description = "UUID of the quiz group", required = true)
            @PathVariable UUID groupId,
            @RequestBody @Valid ReorderGroupQuizzesRequest request,
            Authentication authentication
    ) {
        quizGroupService.reorder(authentication.getName(), groupId, request);
    }

    @Operation(
            summary = "Get archived quizzes (virtual group)",
            description = "Returns a paginated list of archived quizzes (status = ARCHIVED) owned by the authenticated user. This is a virtual group exposed for UX convenience."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of archived quizzes returned"),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping("/archived")
    @RequirePermission(PermissionName.QUIZ_GROUP_READ)
    public ResponseEntity<Page<QuizSummaryDto>> getArchivedQuizzes(
            @ParameterObject
            @PageableDefault(page = 0, size = 20)
            @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable,
            Authentication authentication
    ) {
        Page<QuizSummaryDto> archivedQuizzes = quizGroupService.getArchivedQuizzes(pageable, authentication);
        return ResponseEntity.ok(archivedQuizzes);
    }
}

