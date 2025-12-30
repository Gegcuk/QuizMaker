package uk.gegc.quizmaker.features.bugreport.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.bugreport.api.dto.CreateBugReportRequest;
import uk.gegc.quizmaker.features.bugreport.application.BugReportService;
import uk.gegc.quizmaker.features.bugreport.config.BugReportProperties;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bug-reports")
@RequiredArgsConstructor
@Tag(name = "Bug Reports", description = "Submit bugs or issues for the QuizMaker team")
public class BugReportController {

    private final BugReportService bugReportService;
    private final RateLimitService rateLimitService;
    private final TrustedProxyUtil trustedProxyUtil;
    private final BugReportProperties bugReportProperties;

    @Operation(
            summary = "Submit a bug report",
            description = "Allows users to quickly report a bug with only a message required. " +
                    "Optional fields help us reproduce and follow up."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Bug report created"),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping
    public ResponseEntity<Map<String, UUID>> submitBugReport(
            HttpServletRequest request,
            @Valid @RequestBody CreateBugReportRequest bugReportRequest
    ) {
        String clientIp = trustedProxyUtil.getClientIp(request);
        rateLimitService.checkRateLimit("bug-report-submit", clientIp, bugReportProperties.getRateLimitPerMinute());

        UUID id = bugReportService.createReport(bugReportRequest, clientIp).id();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("bugReportId", id));
    }
}
