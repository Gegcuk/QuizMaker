package uk.gegc.quizmaker.features.billing.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TestBillingErrorController.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@Import(BillingErrorHandler.class)
@DisplayName("BillingErrorHandler ProblemDetail mapping tests")
class BillingErrorHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("InsufficientAvailableTokensException returns 400 with ProblemDetail")
    void insufficientAvailableTokens_returnsProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/billing/test/insufficient-available-tokens")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Insufficient Available Tokens"))
                .andExpect(jsonPath("$.type").value("https://api.quizmaker.com/problems/insufficient-available-tokens"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Insufficient available tokens")))
                .andExpect(jsonPath("$.requestedTokens").value(50))
                .andExpect(jsonPath("$.availableTokens").value(10))
                .andExpect(jsonPath("$.shortfall").value(40));
    }
}
