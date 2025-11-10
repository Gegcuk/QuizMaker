package uk.gegc.quizmaker.features.billing.api;

import com.stripe.exception.StripeException;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import org.springframework.http.HttpStatus;

@Slf4j
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
@Tag(name = "Stripe Webhooks", description = "Internal endpoints for Stripe webhook events (not for public use)")
public class StripeWebhookController {

    private final StripeWebhookService webhookService;
    private final FeatureFlags featureFlags;

    @Operation(
            summary = "Handle Stripe webhook",
            description = "Internal endpoint for Stripe to send payment events. Validates signature and processes events."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook processed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid signature",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @Hidden // Hide from public Swagger UI
    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> handleStripeWebhook(
            @Parameter(hidden = true) @RequestBody String payload,
            @Parameter(description = "Stripe signature header for verification") @RequestHeader(name = "Stripe-Signature", required = false) String sigHeader
    ) throws StripeException {
        return handleWebhook(payload, sigHeader);
    }

    @Operation(
            summary = "Handle Stripe webhook (alternative endpoint)",
            description = "Alternative webhook endpoint for Stripe events. Validates signature and processes events."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook processed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid signature",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @Hidden // Hide from public Swagger UI
    @PostMapping("/webhooks")
    public ResponseEntity<String> handleWebhooks(
            @Parameter(hidden = true) @RequestBody String payload,
            @Parameter(description = "Stripe signature header for verification") @RequestHeader(name = "Stripe-Signature", required = false) String sigHeader
    ) throws StripeException {
        return handleWebhook(payload, sigHeader);
    }

    private ResponseEntity<String> handleWebhook(
            String payload,
            String sigHeader
    ) throws StripeException {
        if (!featureFlags.isBilling()) {
            log.warn("Billing feature is disabled, rejecting webhook");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("");
        }
        
        var res = webhookService.process(payload, sigHeader);
        return ResponseEntity.ok(switch (res) {
            case OK, DUPLICATE, IGNORED -> "";
        });
    }
}
