package uk.gegc.quizmaker.shared.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "Summary of an API group/module")
@Builder
public record ApiGroupSummary(
    @Schema(description = "Group identifier", example = "auth")
    String group,
    
    @Schema(description = "Human-readable display name", example = "Authentication & Users")
    String displayName,
    
    @Schema(description = "Brief description of the group's functionality")
    String description,
    
    @Schema(description = "Icon/emoji for UI representation", example = "🔐")
    String icon,
    
    @Schema(description = "URL to OpenAPI spec for this group", example = "/v3/api-docs?group=auth")
    String specUrl,
    
    @Schema(description = "URL to Swagger UI for this group", example = "/swagger-ui/index.html?urls.primaryName=auth")
    String docsUrl,
    
    @Schema(description = "Estimated spec size in KB", example = "30")
    Integer estimatedSizeKB
) {
    /**
     * Factory method to create ApiGroupSummary with automatic URL generation
     */
    public static ApiGroupSummary of(String group, String displayName, String description, 
                                      String icon, Integer estimatedSizeKB) {
        return ApiGroupSummary.builder()
                .group(group)
                .displayName(displayName)
                .description(description)
                .icon(icon)
                .specUrl("/v3/api-docs?group=" + group)
                .docsUrl("/swagger-ui/index.html?urls.primaryName=" + group)
                .estimatedSizeKB(estimatedSizeKB)
                .build();
    }
}

