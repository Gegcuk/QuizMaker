package uk.gegc.quizmaker.features.billing.api;

import com.stripe.exception.StripeException;
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
@Tag(name = "Stripe Webhooks", description = "Machine-to-machine Stripe endpoints. They are public only for Stripe and require a valid Stripe-Signature header; do not send a bearer token.")
public class StripeWebhookController {

    private final StripeWebhookService webhookService;
    private final FeatureFlags featureFlags;

    @Operation(
            summary = "Handle Stripe webhook",
            description = "Verifies the Stripe-Signature header before processing. For Checkout Sessions, the server retrieves the authoritative session, credits tokens only when payment_status is paid, and settles each Stripe Checkout Session at most once. Invalid, transient Stripe, and database failures return 500 so Stripe retries delivery."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook processed, ignored, or already received"),
            @ApiResponse(responseCode = "400", description = "Malformed webhook payload",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Stripe signature",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Retryable Stripe, database, or settlement processing failure",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> handleStripeWebhook(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Raw JSON event payload sent by Stripe. It must not be modified before signature verification.",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "string", format = "json"))
            ) @RequestBody String payload,
            @Parameter(required = true, description = "Stripe signature header used to authenticate the raw payload")
            @RequestHeader(name = "Stripe-Signature", required = false) String sigHeader
    ) throws StripeException {
        return handleWebhook(payload, sigHeader);
    }

    @Operation(
            summary = "Handle Stripe webhook (alternative endpoint)",
            description = "Alternative Stripe webhook endpoint with the same signature verification, authoritative Checkout Session lookup, and session-level idempotent settlement as /stripe/webhook."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Webhook processed, ignored, or already received"),
            @ApiResponse(responseCode = "400", description = "Malformed webhook payload",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid Stripe signature",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Billing feature disabled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Retryable Stripe, database, or settlement processing failure",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping("/webhooks")
    public ResponseEntity<String> handleWebhooks(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    description = "Raw JSON event payload sent by Stripe. It must not be modified before signature verification.",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "string", format = "json"))
            ) @RequestBody String payload,
            @Parameter(required = true, description = "Stripe signature header used to authenticate the raw payload")
            @RequestHeader(name = "Stripe-Signature", required = false) String sigHeader
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
