package uk.gegc.quizmaker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class UserControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    UserRepository userRepository;

    @BeforeEach
    void setUpUser() {
        if (userRepository.findByEmail("user@example.com").isEmpty()) {
            User u = new User();
            u.setUsername("user");
            u.setEmail("user@example.com");
            u.setHashedPassword("{noop}password");
            u.setActive(true);
            userRepository.save(u);
        }
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns 200 for authenticated user")
    @WithMockUser(username = "user@example.com")
    void getMe_Authenticated_Returns200() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").exists())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    @DisplayName("GET /api/v1/users/me returns 403 for anonymous")
    void getMe_Anonymous_Returns403() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}


