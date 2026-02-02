package uk.gegc.quizmaker.features.repetition.api;

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
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.repetition.api.dto.ReminderToggleRequest;
import uk.gegc.quizmaker.features.repetition.api.dto.ReminderToggleResponse;
import uk.gegc.quizmaker.features.repetition.api.dto.RepetitionReviewRequest;
import uk.gegc.quizmaker.features.repetition.api.dto.ReviewResponseDto;
import uk.gegc.quizmaker.features.repetition.application.RepetitionQueryService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReminderService;
import uk.gegc.quizmaker.features.repetition.application.RepetitionReviewService;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionEntryDto;
import uk.gegc.quizmaker.features.repetition.application.dto.RepetitionHistoryDto;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.UnauthorizedException;

import java.util.Optional;
import java.util.UUID;

@Tag(name = "Repetition", description = "Spaced repetition review queue, history, and manual reviews")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/repetition")
@RequiredArgsConstructor
@Validated
public class RepetitionController {

    private final RepetitionQueryService repetitionQueryService;
    private final RepetitionReviewService repetitionReviewService;
    private final RepetitionReminderService repetitionReminderService;
    private final UserRepository userRepository;


    private UUID resolveAuthenticatedUserId(Authentication authentication) {
        String principal = authentication.getName();
        return safeParseUuid(principal)
                .orElseGet(() -> {
                    User user = userRepository.findByUsername(principal)
                            .or(() -> userRepository.findByEmail(principal))
                            .orElseThrow(() -> new UnauthorizedException("Unknown principal"));
                    return user.getId();
                });
    }

    @GetMapping("/due")
    @Operation(
            summary = "Get due repetition entries",
            description = "Returns reminder-enabled entries where nextReviewAt is due, ordered by nextReviewAt ASC."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of due repetition entries",
                    content = @Content(schema = @Schema(implementation = RepetitionEntryDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Page<RepetitionEntryDto>> getDue(
            Authentication authentication,
            @ParameterObject @PageableDefault(page = 0, size = 20) Pageable pageable
            ){
        UUID userId = resolveAuthenticatedUserId(authentication);
        return ResponseEntity.ok(repetitionQueryService.getDueEntries(userId, pageable));
    }

    @GetMapping("/priority")
    @Operation(
            summary = "Get priority queue",
            description = "Returns reminder-enabled entries ordered by nextReviewAt ASC with computed priorityScore (no in-memory reordering)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of priority queue entries",
                    content = @Content(schema = @Schema(implementation = RepetitionEntryDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Page<RepetitionEntryDto>> getPriorityDue(
            Authentication authentication,
            @ParameterObject @PageableDefault(page = 0, size = 20) Pageable pageable
    ){
        UUID userId = resolveAuthenticatedUserId(authentication);
        return ResponseEntity.ok(repetitionQueryService.getPriorityQueue(userId, pageable));
    }

    @GetMapping("/history")
    @Operation(
            summary = "Get repetition history",
            description = "Returns review history ordered by reviewedAt DESC."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of repetition history",
                    content = @Content(schema = @Schema(implementation = RepetitionHistoryDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<Page<RepetitionHistoryDto>> getRepetitionEntry(
            Authentication authentication,
            @ParameterObject @PageableDefault(page = 0, size = 20) Pageable pageable
    ){
        UUID userId = resolveAuthenticatedUserId(authentication);
        return ResponseEntity.ok(repetitionQueryService.getHistory(userId, pageable));
    }

    @PostMapping("/entries/{entryId}/review")
    @Operation(
            summary = "Submit manual review",
            description = "Applies SM-2 scheduling and writes a review log. Idempotency key prevents double-advancing."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Manual review processed",
                    content = @Content(schema = @Schema(implementation = ReviewResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Entry not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Idempotency conflict",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ReviewResponseDto> review(
            @Parameter(description = "Repetition entry ID", required = true)
            @PathVariable UUID entryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Manual review payload",
                    required = true,
                    content = @Content(schema = @Schema(implementation = RepetitionReviewRequest.class))
            )
            @RequestBody @Valid RepetitionReviewRequest request,
            Authentication authentication
    ){
        UUID userId = resolveAuthenticatedUserId(authentication);
        SpacedRepetitionEntry entry = repetitionReviewService.reviewEntry(
                entryId,
                userId,
                request.grade(),
                request.idempotencyKey()
        );

        ReviewResponseDto response = new ReviewResponseDto(
                entry.getId(),
                entry.getNextReviewAt(),
                entry.getIntervalDays(),
                entry.getRepetitionCount(),
                entry.getEaseFactor(),
                entry.getLastReviewedAt(),
                entry.getLastGrade()
        );

        return ResponseEntity.ok(response);
    }

    @PutMapping("/entries/{entryId}/reminder")
    @Operation(
            summary = "Enable/disable reminders for an entry",
            description = "Toggles reminderEnabled for the entry. Disabled entries are not shown in due/priority queues."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Reminder status updated",
                    content = @Content(schema = @Schema(implementation = ReminderToggleResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Entry not found",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<ReminderToggleResponse> setReminder(
            @Parameter(description = "Repetition entry ID", required = true)
            @PathVariable UUID entryId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Reminder toggle payload",
                    required = true,
                    content = @Content(schema = @Schema(implementation = ReminderToggleRequest.class))
            )
            @RequestBody @Valid ReminderToggleRequest request,
            Authentication authentication
    ) {
        UUID userId = resolveAuthenticatedUserId(authentication);
        SpacedRepetitionEntry entry = repetitionReminderService.setReminderEnabled(entryId, userId, request.enabled());
        return ResponseEntity.ok(new ReminderToggleResponse(entry.getId(), entry.getReminderEnabled()));
    }


    private Optional<UUID> safeParseUuid(String uuidString) {
        if (uuidString == null || uuidString.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(uuidString));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
