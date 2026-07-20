package uk.gegc.quizmaker.features.bugreport.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportDto;
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportPageResponse;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.api.dto.UpdateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.application.BugReportService;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportSeverity;
import uk.gegc.quizmaker.features.bugreport.domain.model.BugReportStatus;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/bug-reports")
@RequiredArgsConstructor
@Tag(name = "Bug Reports Admin", description = "Administrative operations for bug reports")
@SecurityRequirement(name = "bearerAuth")
public class BugReportAdminController {

    private static final String BUG_REPORT_EXAMPLE = "{\"id\":\"d2719d51-2c24-4c06-a3de-9db1f0b8e8b9\",\"message\":\"Saving a quiz fails after adding an image\",\"reporterName\":\"Jane Doe\",\"reporterEmail\":\"jane@example.com\",\"pageUrl\":\"https://app.quizzence.com/quizzes/123/edit\",\"stepsToReproduce\":\"1. Add an image 2. Save the quiz\",\"clientVersion\":\"web 1.2.3 (Chrome 124)\",\"clientIp\":\"203.0.113.42\",\"severity\":\"HIGH\",\"status\":\"OPEN\",\"internalNote\":\"Reproduced in staging\",\"createdAt\":\"2026-07-20T10:15:30Z\",\"updatedAt\":\"2026-07-20T10:15:30Z\"}";
    private static final String PAGE_EXAMPLE = "{\"content\":[{\"id\":\"d2719d51-2c24-4c06-a3de-9db1f0b8e8b9\",\"message\":\"Saving a quiz fails after adding an image\",\"reporterName\":\"Jane Doe\",\"reporterEmail\":\"jane@example.com\",\"pageUrl\":\"https://app.quizzence.com/quizzes/123/edit\",\"stepsToReproduce\":\"1. Add an image 2. Save the quiz\",\"clientVersion\":\"web 1.2.3 (Chrome 124)\",\"clientIp\":\"203.0.113.42\",\"severity\":\"HIGH\",\"status\":\"OPEN\",\"internalNote\":\"Reproduced in staging\",\"createdAt\":\"2026-07-20T10:15:30Z\",\"updatedAt\":\"2026-07-20T10:15:30Z\"}],\"totalPages\":1,\"totalElements\":1,\"size\":20,\"number\":0,\"sort\":{\"sorted\":true,\"unsorted\":false,\"empty\":false},\"first\":true,\"last\":true,\"numberOfElements\":1,\"empty\":false,\"pageable\":{\"pageNumber\":0,\"pageSize\":20,\"offset\":0,\"sort\":{\"sorted\":true,\"unsorted\":false,\"empty\":false},\"paged\":true,\"unpaged\":false}}";
    private static final String NOT_FOUND_EXAMPLE = "{\"type\":\"https://quizzence.com/docs/errors/resource-not-found\",\"title\":\"Resource Not Found\",\"status\":404,\"detail\":\"Bug report d2719d51-2c24-4c06-a3de-9db1f0b8e8b9 not found\",\"instance\":\"/api/v1/admin/bug-reports/d2719d51-2c24-4c06-a3de-9db1f0b8e8b9\"}";

    private final BugReportService bugReportService;

    @Operation(
            summary = "Create a bug report (admin)",
            description = "Allows super admins to create bug reports on behalf of users."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Bug report created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BugReportDto.class),
                            examples = @ExampleObject(name = "Created report", value = BUG_REPORT_EXAMPLE)
                    )),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing SYSTEM_ADMIN permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<BugReportDto> createBugReport(
            @Valid @RequestBody CreateBugReportRequest request
    ) {
        BugReportDto created = bugReportService.createReport(request, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
            summary = "List bug reports",
            description = "Returns a paginated list of bug reports with optional status and severity filters. " +
                    "Only SYSTEM_ADMIN callers can access reporter contact details, captured client IP addresses, and internal notes. " +
                    "Pages are zero-based and default to 20 results ordered by createdAt descending."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of bug reports returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BugReportPageResponse.class),
                            examples = @ExampleObject(name = "Open reports", value = PAGE_EXAMPLE)
                    )),
            @ApiResponse(responseCode = "400", description = "Invalid status, severity, page, size, or sort parameter",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing SYSTEM_ADMIN permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @GetMapping
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<Page<BugReportDto>> listBugReports(
            @Parameter(name = "status", description = "Filter by status", in = ParameterIn.QUERY)
            @RequestParam(required = false) BugReportStatus status,
            @Parameter(name = "severity", description = "Filter by severity", in = ParameterIn.QUERY)
            @RequestParam(required = false) BugReportSeverity severity,
            @ParameterObject @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC, size = 20) Pageable pageable
    ) {
        Page<BugReportDto> result = bugReportService.listReports(status, severity, pageable);
        return ResponseEntity.ok(result);
    }

    @Operation(
            summary = "Get bug report by id",
            description = "Returns a single bug report."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bug report returned",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BugReportDto.class),
                            examples = @ExampleObject(name = "Bug report", value = BUG_REPORT_EXAMPLE)
                    )),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing SYSTEM_ADMIN permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Bug report not found",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Missing report", value = NOT_FOUND_EXAMPLE)
                    ))
    })
    @GetMapping("/{id}")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<BugReportDto> getBugReport(
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(bugReportService.getReport(id));
    }

    @Operation(
            summary = "Update a bug report",
            description = "Partially updates bug report fields, severity, or status."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bug report updated",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BugReportDto.class),
                            examples = @ExampleObject(name = "Updated report", value = BUG_REPORT_EXAMPLE)
                    )),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing SYSTEM_ADMIN permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Bug report not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PatchMapping("/{id}")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<BugReportDto> updateBugReport(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateBugReportRequest request
    ) {
        return ResponseEntity.ok(bugReportService.updateReport(id, request));
    }

    @Operation(
            summary = "Delete a bug report",
            description = "Removes a single bug report."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Bug report deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing SYSTEM_ADMIN permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Bug report not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public void deleteBugReport(
            @PathVariable UUID id
    ) {
        bugReportService.deleteReport(id);
    }

    @Operation(
            summary = "Delete multiple bug reports",
            description = "Bulk deletes the provided bug-report IDs. The request body is a JSON array of UUIDs; " +
                    "an empty array is accepted and returns 204. The endpoint does not return per-ID outcomes."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Bug reports deleted"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing SYSTEM_ADMIN permission",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/bulk-delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public void bulkDeleteBugReports(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "IDs of bug reports to delete",
                    required = true,
                    content = @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UUID.class)),
                            examples = @ExampleObject(
                                    name = "Report IDs",
                                    value = "[\"d2719d51-2c24-4c06-a3de-9db1f0b8e8b9\",\"642bd5cf-22d2-4d42-b70f-3cce734cdf25\"]"
                            )
                    )
            )
            @RequestBody List<UUID> ids
    ) {
        bugReportService.deleteReports(ids);
    }
}
