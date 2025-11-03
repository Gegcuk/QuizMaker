package uk.gegc.quizmaker.shared.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Schema(description = "Lightweight API overview for discovery")
@Builder
public record ApiSummary(
    @Schema(description = "API version", example = "v1")
    String version,
    
    @Schema(description = "Base URL for API endpoints", example = "/api/v1")
    String baseUrl,
    
    @Schema(description = "List of available API groups/modules")
    List<ApiGroupSummary> groups,
    
    @Schema(description = "URL to full OpenAPI specification", example = "/v3/api-docs")
    String fullSpecUrl,
    
    @Schema(description = "URL to complete Swagger UI", example = "/swagger-ui/index.html")
    String fullDocsUrl
) {
}

