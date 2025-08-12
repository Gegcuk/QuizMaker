package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.user.MeResponse;
import uk.gegc.quizmaker.service.user.MeService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean MeService meService;

    @Test
    @DisplayName("GET /api/v1/users/me returns 200 and profile")
    @WithMockUser(username = "alice")
    void getMe_ReturnsProfile() throws Exception {
        MeResponse resp = new MeResponse(UUID.randomUUID(), "alice", "alice@example.com", "Alice",
                null, null, Map.of(), LocalDateTime.now(), true, List.of("USER"));
        when(meService.getCurrentUserProfile(any())).thenReturn(resp);

        mockMvc.perform(get("/api/v1/users/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"));

        verify(meService).getCurrentUserProfile(any());
    }
}


