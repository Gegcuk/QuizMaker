package uk.gegc.quizmaker.shared.api;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.shared.api.dto.HealthStatusResponse;


@RestController
@Tag(name = "Utility", description = "Utility & administrative endpoints")
@RequiredArgsConstructor
public class UtilityController {

    private final ApplicationAvailability applicationAvailability;

    @Operation(
            summary = "Health-check endpoint",
            description = "Backward-compatible liveness alias. Returns only the application liveness status; " +
                    "dependency and operator diagnostics are never exposed by this endpoint."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Application is live",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = HealthStatusResponse.class,
                                    example = "{\"status\":\"UP\"}"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Application liveness is broken",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    implementation = HealthStatusResponse.class,
                                    example = "{\"status\":\"DOWN\"}"
                            )
                    )
            )
    })
    @GetMapping("/api/v1/health")
    public ResponseEntity<HealthStatusResponse> health() {
        return livenessResponse();
    }

    @Hidden
    @GetMapping("/actuator/health/liveness")
    public ResponseEntity<HealthStatusResponse> publicLiveness() {
        return livenessResponse();
    }

    private ResponseEntity<HealthStatusResponse> livenessResponse() {
        boolean live = applicationAvailability.getLivenessState() == LivenessState.CORRECT;
        HealthStatusResponse response = new HealthStatusResponse(live ? "UP" : "DOWN");
        return ResponseEntity.status(live ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

}
