package uk.gegc.quizmaker.shared.api.docs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.shared.api.dto.ApiSummary;

import java.time.Duration;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "API Discovery", description = "Lightweight endpoints for API documentation discovery")
@RequiredArgsConstructor
public class ApiDiscoveryController {

    private final ApiDocumentationService apiDocumentationService;

    @Operation(
            summary = "Get API group summary",
            description = "Returns a lightweight summary of available API documentation groups",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Summary returned",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = ApiSummary.class)
                            )
                    )
            }
    )
    @GetMapping("/api-summary")
    public ResponseEntity<ApiSummary> summary() {
        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(15)).cachePublic())
                .body(apiDocumentationService.buildSummary());
    }
}
