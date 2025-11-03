package uk.gegc.quizmaker.shared.api.docs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.ApplicationContext;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.shared.api.dto.ApiSummary;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "API Discovery", description = "Lightweight endpoints for API documentation discovery")
@RequiredArgsConstructor
public class ApiDiscoveryController {

    private final ApiDocumentationService apiDocumentationService;
    private final ApplicationContext applicationContext;

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

    @Operation(
            summary = "Diagnostic: List registered SpringDoc groups",
            description = """
                    Developer diagnostic endpoint to verify which API groups are registered with SpringDoc.
                    This helps debug issues where /v3/api-docs?group=X returns the full spec instead of filtered content.
                    
                    Expected behavior:
                    - Should show 8 groups: auth, quizzes, questions, attempts, documents, billing, ai, admin
                    - Each group should have its pathsToMatch configured
                    
                    If this returns empty or missing groups, check:
                    1. OpenApiGroupConfig.java is deployed
                    2. @Configuration annotation is present
                    3. Component scanning includes uk.gegc.quizmaker.shared.config package
                    """,
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Registered groups returned"
                    )
            }
    )
    @GetMapping("/diagnostic/springdoc-groups")
    public ResponseEntity<Map<String, Object>> diagnosticGroups() {
        // Get all GroupedOpenApi beans registered in Spring context
        Map<String, GroupedOpenApi> groupBeans = applicationContext.getBeansOfType(GroupedOpenApi.class);
        
        List<Map<String, Object>> groups = groupBeans.values().stream()
                .map(group -> Map.<String, Object>of(
                        "group", group.getGroup(),
                        "displayName", group.getDisplayName() != null ? group.getDisplayName() : "N/A",
                        "pathsToMatch", group.getPathsToMatch() != null ? group.getPathsToMatch() : List.of(),
                        "pathsToExclude", group.getPathsToExclude() != null ? group.getPathsToExclude() : List.of()
                ))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(Map.of(
                "totalGroups", groups.size(),
                "groups", groups,
                "configClass", "uk.gegc.quizmaker.shared.config.OpenApiGroupConfig",
                "expectedGroups", 8,
                "status", groups.size() == 8 ? "✅ OK" : "⚠️ MISSING GROUPS"
        ));
    }
}
