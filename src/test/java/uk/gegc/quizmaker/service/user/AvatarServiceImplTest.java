package uk.gegc.quizmaker.service.user;

import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import uk.gegc.quizmaker.exception.UnsupportedFileTypeException;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.impl.AvatarServiceImpl;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarServiceImplTest {

    private UserRepository userRepository;
    private AvatarServiceImpl avatarService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        avatarService = new AvatarServiceImpl(userRepository);
        // set fields via reflection for test
        try {
            var dir = AvatarServiceImpl.class.getDeclaredField("avatarsDir");
            dir.setAccessible(true);
            dir.set(avatarService, "target/test-avatars");
            var base = AvatarServiceImpl.class.getDeclaredField("publicBaseUrl");
            base.setAccessible(true);
            base.set(avatarService, "http://localhost:8080");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void rejectsUnsupportedMime() {
        MockMultipartFile txt = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        assertThrows(UnsupportedFileTypeException.class, () -> avatarService.uploadAndAssignAvatar("user", txt));
    }

    @Test
    void storesAndUpdatesUser_corruptImage() {
        // PNG header only will fail ImageIO decode -> UnsupportedFileTypeException
        MockMultipartFile img = new MockMultipartFile("file", "a.png", "image/png", new byte[]{(byte)0x89, 0x50, 0x4E, 0x47});
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        assertThrows(UnsupportedFileTypeException.class, () -> avatarService.uploadAndAssignAvatar("alice", img));
    }

    @Test
    void emptyFileRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[]{});
        assertThrows(IllegalArgumentException.class, () -> avatarService.uploadAndAssignAvatar("alice", empty));
    }
}


