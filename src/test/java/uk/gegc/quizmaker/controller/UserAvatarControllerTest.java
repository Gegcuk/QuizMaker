package uk.gegc.quizmaker.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import uk.gegc.quizmaker.dto.user.AvatarUploadResponse;
import uk.gegc.quizmaker.exception.UnsupportedFileTypeException;
import uk.gegc.quizmaker.service.user.AvatarService;
import uk.gegc.quizmaker.service.user.UserProfileService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
class UserAvatarControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvatarService avatarService;

    @MockitoBean
    private UserProfileService userProfileService; // required dependency for controller slice

    @Test
    @WithMockUser(username = "alice")
    @DisplayName("Should upload avatar successfully")
    void uploadAvatar_success() throws Exception {
        byte[] image = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47}; // mock bytes
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", image);
        when(avatarService.uploadAndAssignAvatar(any(), any())).thenReturn("http://localhost/avatars/abc.png");

        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.avatarUrl").value("http://localhost/avatars/abc.png"))
                .andExpect(jsonPath("$.message").value("Avatar updated successfully"));
    }

    @Test
    @WithMockUser(username = "alice")
    @DisplayName("Should return 400 when file missing")
    void uploadAvatar_missingFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Should return 401 when unauthenticated")
    void uploadAvatar_unauthenticated() throws Exception {
        byte[] image = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47};
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", image);
        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice")
    @DisplayName("Should return 403 when CSRF token missing")
    void uploadAvatar_missingCsrf() throws Exception {
        byte[] image = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47};
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", image);
        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(file)
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice")
    @DisplayName("Should return 415 for wrong content type")
    void uploadAvatar_wrongContentType() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/users/me/avatar")
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @WithMockUser(username = "alice")
    @DisplayName("Should return 400 for unsupported MIME type")
    void uploadAvatar_unsupportedMime() throws Exception {
        byte[] image = "bad".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.gif", "image/gif", image);
        when(avatarService.uploadAndAssignAvatar(any(), any())).thenThrow(new UnsupportedFileTypeException("Unsupported image type. Allowed: PNG, JPEG, WEBP"));

        mockMvc.perform(multipart("/api/v1/users/me/avatar").file(file)
                        .with(SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest());
    }
}


