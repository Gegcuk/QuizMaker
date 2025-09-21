package uk.gegc.quizmaker.features.billing.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.billing.application.StripeWebhookService;
import uk.gegc.quizmaker.features.billing.domain.exception.StripeWebhookInvalidSignatureException;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import org.springframework.http.HttpStatus;

@Slf4j
@RestController
@RequestMapping("/api/v1/billing")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService webhookService;
    private final FeatureFlags featureFlags;

    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> handleStripeWebhook(
            @RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature", required = false) String sigHeader
    ) {
        return handleWebhook(payload, sigHeader);
    }

    @PostMapping("/webhooks")
    public ResponseEntity<String> handleWebhooks(
            @RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature", required = false) String sigHeader
    ) {
        return handleWebhook(payload, sigHeader);
    }

    private ResponseEntity<String> handleWebhook(
            String payload,
            String sigHeader
    ) {
        if (!featureFlags.isBilling()) {
            log.warn("Billing feature is disabled, rejecting webhook");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("");
        }
        
        try {
            var res = webhookService.process(payload, sigHeader);
            return ResponseEntity.ok(switch (res) {
                case OK, DUPLICATE, IGNORED -> "";
            });
        } catch (StripeWebhookInvalidSignatureException e) {
            log.warn("Invalid Stripe signature: {}", e.getMessage());
            return ResponseEntity.status(401).body("");
        } catch (Exception e) {
            log.error("Error processing Stripe webhook: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body("");
        }
    }
}
