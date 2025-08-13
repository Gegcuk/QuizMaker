package uk.gegc.quizmaker.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UserAvatarIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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


