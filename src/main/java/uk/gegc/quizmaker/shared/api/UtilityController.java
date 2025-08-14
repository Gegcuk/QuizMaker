package uk.gegc.quizmaker.shared.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1")
@Tag(name = "Utility", description = "Utility & administrative endpoints")
@RequiredArgsConstructor
public class UtilityController {

    private final HealthEndpoint healthEndpoint;

    @Operation(
            summary = "Health-check endpoint",
            description = "Returns 200 with `{ \"status\": \"UP\" }` if the service is alive"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Always returns service health",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = HealthComponent.class,
                                    example = "{\"status\":\"UP\"}"
                            )
                    )
            )
    })
    @GetMapping("/health")
    public ResponseEntity<HealthComponent> health() {
        return ResponseEntity.ok(healthEndpoint.health());
    }

}
