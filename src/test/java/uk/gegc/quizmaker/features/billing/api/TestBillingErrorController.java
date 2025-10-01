package uk.gegc.quizmaker.features.billing.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.billing.domain.exception.InsufficientAvailableTokensException;

@RestController
@RequestMapping(path = "/api/v1/billing/test", produces = MediaType.APPLICATION_JSON_VALUE)
public class TestBillingErrorController {

    @GetMapping("/insufficient-available-tokens")
    public void triggerInsufficientAvailableTokens() {
        // Simulate a deduction attempt where available balance is insufficient
        throw new InsufficientAvailableTokensException(
                "Insufficient available tokens for operation",
                50L, // requested
                10L, // available
                40L  // shortfall
        );
    }
}

