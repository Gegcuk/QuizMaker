package uk.gegc.quizmaker.shared.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Utility health endpoint compatibility")
class UtilityControllerTest {

    @Mock
    private ApplicationAvailability applicationAvailability;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UtilityController(applicationAvailability)).build();
    }

    @Test
    @DisplayName("GET /api/v1/health returns the legacy UP shape without diagnostic fields when live")
    void health_whenApplicationIsLive_returnsMinimalUpResponse() throws Exception {
        when(applicationAvailability.getLivenessState()).thenReturn(LivenessState.CORRECT);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("GET /actuator/health/liveness preserves the public status-only contract")
    void publicLiveness_whenApplicationIsLive_returnsMinimalUpResponse() throws Exception {
        when(applicationAvailability.getLivenessState()).thenReturn(LivenessState.CORRECT);

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/health returns minimal DOWN with 503 when liveness is broken")
    void health_whenApplicationLivenessIsBroken_returnsMinimalDownResponse() throws Exception {
        when(applicationAvailability.getLivenessState()).thenReturn(LivenessState.BROKEN);

        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("GET /actuator/health/liveness returns minimal DOWN with 503 when liveness is broken")
    void publicLiveness_whenApplicationLivenessIsBroken_returnsMinimalDownResponse() throws Exception {
        when(applicationAvailability.getLivenessState()).thenReturn(LivenessState.BROKEN);

        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }
}
