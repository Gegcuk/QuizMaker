package uk.gegc.quizmaker.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create"
})
class UserAvatarIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private String testUserId;

    @BeforeEach
    void setUp() {
        // Clean up any existing test user
        userRepository.findByUsername("it-user").ifPresent(userRepository::delete);
        
        // Create test user
        testUser = new User();
        testUser.setUsername("it-user");
        testUser.setEmail("it-user@test.com");
        testUser.setHashedPassword("password");
        testUser.setActive(true);
        testUser.setDeleted(false);
        testUser = userRepository.save(testUser);
        
        // Use the user's ID (UUID) as the authentication principal
        testUserId = testUser.getId().toString();
    }

    @Test
    @WithMockUser(username = "it-user")
    void uploadAvatar_badImage_returns400() throws Exception {
        MockMultipartFile bad = new MockMultipartFile("file", "x.png", "image/png", new byte[]{1,2,3});
        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(bad)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "it-user")
    void uploadAvatar_empty_returns400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "x.png", "image/png", new byte[]{});
        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(empty)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());
    }
}


