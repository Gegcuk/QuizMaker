package uk.gegc.quizmaker.features.document.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import uk.gegc.quizmaker.features.document.api.dto.DocumentStructureJobDto;
import uk.gegc.quizmaker.features.document.application.DocumentStructureJobService;
import uk.gegc.quizmaker.features.document.domain.model.DocumentStructureJob;
import uk.gegc.quizmaker.features.document.infra.mapping.DocumentStructureJobMapper;

import java.util.List;
import java.util.UUID;

/**
 * Controller for document structure extraction job management.
 * <p>
 * Provides endpoints for polling job status and managing document structure
 * extraction jobs following the Long Running Operation (LRO) pattern.
 * <p>
 * Implementation of Day 7 — REST API + Jobs from the chunk processing improvement plan.
 */
@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
@Tag(name = "Document Jobs", description = "Document structure extraction job management endpoints")
public class DocumentJobController {

    private final DocumentStructureJobService jobService;
    private final DocumentStructureJobMapper jobMapper;

    @GetMapping("/{jobId}")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Get job status", description = "Retrieves the current status and progress of a document structure extraction job")
    public ResponseEntity<DocumentStructureJobDto> getJobStatus(
            @Parameter(description = "Job ID") @PathVariable UUID jobId,
            Authentication authentication) {

        String username = authentication.getName();
        DocumentStructureJob job = jobService.getJobByIdAndUsername(jobId, username);
        
        DocumentStructureJobDto jobDto = jobMapper.toDto(job);
        
        // Add cache control headers for LRO pattern
        ResponseEntity.BodyBuilder responseBuilder = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        
        // Add Retry-After header for pending and processing jobs
        if (job.getStatus() == DocumentStructureJob.StructureExtractionStatus.PENDING || 
            job.getStatus() == DocumentStructureJob.StructureExtractionStatus.PROCESSING) {
            responseBuilder.header(HttpHeaders.RETRY_AFTER, "5"); // 5 seconds
        }
        
        return responseBuilder.body(jobDto);
    }

    @PostMapping("/{jobId}/cancel")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Cancel job", description = "Cancels a running document structure extraction job")
    public ResponseEntity<DocumentStructureJobDto> cancelJob(
            @Parameter(description = "Job ID") @PathVariable UUID jobId,
            Authentication authentication) {

        String username = authentication.getName();
        DocumentStructureJob cancelledJob = jobService.cancelJob(jobId, username);
        
        DocumentStructureJobDto jobDto = jobMapper.toDto(cancelledJob);
        return ResponseEntity.ok(jobDto);
    }

    @GetMapping("/my-jobs")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Get my jobs", description = "Retrieves all document structure extraction jobs for the current user")
    public ResponseEntity<Page<DocumentStructureJobDto>> getMyJobs(
            Authentication authentication,
            Pageable pageable) {

        String username = authentication.getName();
        Page<DocumentStructureJob> jobs = jobService.getJobsByUser(username, pageable);
        
        Page<DocumentStructureJobDto> jobDtos = jobs.map(jobMapper::toDto);
        return ResponseEntity.ok(jobDtos);
    }

    @GetMapping("/my-jobs/statistics")
    @PreAuthorize("hasRole('USER')")
    @Operation(summary = "Get job statistics", description = "Retrieves job statistics for the current user")
    public ResponseEntity<DocumentStructureJobService.JobStatistics> getJobStatistics(
            Authentication authentication) {

        String username = authentication.getName();
        DocumentStructureJobService.JobStatistics statistics = jobService.getJobStatistics(username);
        
        return ResponseEntity.ok(statistics);
    }

    @GetMapping("/documents/{documentId}")
    @PreAuthorize("hasRole('USER') and @documentSecurityService.canAccessDocument(#documentId, authentication.name)")
    @Operation(summary = "Get jobs for document", description = "Retrieves all structure extraction jobs for a specific document")
    public ResponseEntity<List<DocumentStructureJobDto>> getJobsForDocument(
            @Parameter(description = "Document ID") @PathVariable UUID documentId) {

        List<DocumentStructureJob> jobs = jobService.getJobsByDocument(documentId);
        
        List<DocumentStructureJobDto> jobDtos = jobs.stream()
                .map(jobMapper::toDto)
                .toList();
        
        return ResponseEntity.ok(jobDtos);
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get active jobs", description = "Retrieves all currently active structure extraction jobs (admin only)")
    public ResponseEntity<List<DocumentStructureJobDto>> getActiveJobs() {

        List<DocumentStructureJob> activeJobs = jobService.getActiveJobs();
        
        List<DocumentStructureJobDto> jobDtos = activeJobs.stream()
                .map(jobMapper::toDto)
                .toList();
        
        return ResponseEntity.ok(jobDtos);
    }

    @GetMapping("/stuck")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get stuck jobs", description = "Retrieves jobs that have been running too long (admin only)")
    public ResponseEntity<List<DocumentStructureJobDto>> getStuckJobs(
            @Parameter(description = "Maximum duration in hours") @RequestParam(defaultValue = "24") int maxDurationHours) {

        List<DocumentStructureJob> stuckJobs = jobService.getStuckJobs(maxDurationHours);
        
        List<DocumentStructureJobDto> jobDtos = stuckJobs.stream()
                .map(jobMapper::toDto)
                .toList();
        
        return ResponseEntity.ok(jobDtos);
    }

    @PostMapping("/cleanup/old")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cleanup old jobs", description = "Removes old completed jobs (admin only)")
    public ResponseEntity<Integer> cleanupOldJobs(
            @Parameter(description = "Days to keep completed jobs") @RequestParam(defaultValue = "30") int daysToKeep) {

        int deletedCount = jobService.cleanupOldJobs(daysToKeep);
        
        return ResponseEntity.ok(deletedCount);
    }

    @PostMapping("/cleanup/stale")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Cleanup stale jobs", description = "Marks stale pending jobs as failed (admin only)")
    public ResponseEntity<Integer> cleanupStaleJobs() {

        int cleanedCount = jobService.cleanupStalePendingJobs();
        
        return ResponseEntity.ok(cleanedCount);
    }
}
