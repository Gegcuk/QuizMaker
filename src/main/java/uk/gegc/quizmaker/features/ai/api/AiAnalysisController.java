package uk.gegc.quizmaker.features.ai.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.ai.infra.analysis.AiResponseAnalyzer;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for AI response analysis (temporary debugging tool)
 */
@Tag(name = "AI Analysis", description = "Temporary endpoints for analyzing AI responses")
@RestController
@RequestMapping("/api/v1/ai-analysis")
@RequiredArgsConstructor
@Slf4j
@SecurityRequirement(name = "Bearer Authentication")
public class AiAnalysisController {

    private final AiResponseAnalyzer aiResponseAnalyzer;

    @Operation(
            summary = "Analyze AI responses",
            description = "Analyze logged AI responses to identify patterns of non-compliance with instructions. Requires ADMIN role."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Analysis completed successfully",
                    content = @Content(schema = @Schema(implementation = Map.class))
            ),
            @ApiResponse(responseCode = "403", description = "Missing ADMIN role"),
            @ApiResponse(responseCode = "500", description = "Error during analysis")
    })
    @PostMapping("/analyze")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, String>> analyzeAiResponses() {
        log.info("Starting AI response analysis...");
        
        try {
            aiResponseAnalyzer.analyzeRecentResponses();
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "AI response analysis completed. Check application logs for details.");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error during AI response analysis", e);
            
            Map<String, String> response = new HashMap<>();
            response.put("status", "error");
            response.put("message", "Error during analysis: " + e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
} 