package uk.gegc.quizmaker.features.bugreport.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
import uk.gegc.quizmaker.features.bugreport.api.dto.BugReportSubmissionResponse;
import uk.gegc.quizmaker.features.bugreport.application.BugReportService;
import uk.gegc.quizmaker.features.bugreport.config.BugReportProperties;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.util.TrustedProxyUtil;

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
                    "Optional fields help us reproduce and follow up. This endpoint is anonymous and rate limited " +
                    "per client IP address; clients should honor the Retry-After header on a 429 response."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Bug report created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BugReportSubmissionResponse.class),
                            examples = @ExampleObject(
                                    name = "Submission accepted",
                                    value = "{\"bugReportId\":\"d2719d51-2c24-4c06-a3de-9db1f0b8e8b9\"}"
                            )
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Validation error",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(
                                    name = "Missing message",
                                    value = "{\"type\":\"https://quizzence.com/docs/errors/validation-failed\",\"title\":\"Validation Failed\",\"status\":400,\"detail\":\"Validation failed for one or more fields\",\"instance\":\"/api/v1/bug-reports\",\"fieldErrors\":{\"message\":\"Message is required\"}}"
                            )
                    )),
            @ApiResponse(responseCode = "429", description = "Submission rate limit exceeded",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(
                                    name = "Rate limit exceeded",
                                    value = "{\"type\":\"https://quizzence.com/docs/errors/rate-limit-exceeded\",\"title\":\"Rate Limit Exceeded\",\"status\":429,\"detail\":\"Too many requests for bug-report-submit\",\"instance\":\"/api/v1/bug-reports\",\"retryAfterSeconds\":30}"
                            )
                    ))
    })
    @PostMapping
    public ResponseEntity<BugReportSubmissionResponse> submitBugReport(
            HttpServletRequest request,
            @Valid @RequestBody CreateBugReportRequest bugReportRequest
    ) {
        String clientIp = trustedProxyUtil.getClientIp(request);
        rateLimitService.checkRateLimit("bug-report-submit", clientIp, bugReportProperties.getRateLimitPerMinute());

        UUID id = bugReportService.createReport(bugReportRequest, clientIp).id();
        return ResponseEntity.status(HttpStatus.CREATED).body(new BugReportSubmissionResponse(id));
    }
}
