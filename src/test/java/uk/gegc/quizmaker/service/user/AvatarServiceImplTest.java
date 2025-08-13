package uk.gegc.quizmaker.service.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.exception.UnsupportedFileTypeException;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.impl.AvatarServiceImpl;
import uk.gegc.quizmaker.util.TestImageFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AvatarServiceImplTest {

    private UserRepository userRepository;
    private AvatarServiceImpl avatarService;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        userRepository = mock(UserRepository.class);
        avatarService = new AvatarServiceImpl(userRepository);
        tempDir = Files.createTempDirectory("avatars-test-");
        TransactionSynchronizationManager.initSynchronization();
        try {
            var dir = AvatarServiceImpl.class.getDeclaredField("avatarsDir");
            dir.setAccessible(true);
            dir.set(avatarService, tempDir.toString());
            var base = AvatarServiceImpl.class.getDeclaredField("publicBaseUrl");
            base.setAccessible(true);
            base.set(avatarService, "http://test-host");
            var path = AvatarServiceImpl.class.getDeclaredField("publicPath");
            path.setAccessible(true);
            path.set(avatarService, "/avatars");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rejectsUnsupportedMime() {
        MockMultipartFile txt = new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8));
        assertThrows(UnsupportedFileTypeException.class, () -> avatarService.uploadAndAssignAvatar("user", txt));
    }

    @Test
    void emptyFileRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "a.png", "image/png", new byte[]{});
        assertThrows(IllegalArgumentException.class, () -> avatarService.uploadAndAssignAvatar("alice", empty));
    }

    @Test
    void pngStoredScaledAndUrlBuilt() {
        byte[] png = TestImageFactory.makePng(800, 600, java.awt.Color.BLUE, java.awt.Color.RED);
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", png);
        User user = new User();
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));

        String url = avatarService.uploadAndAssignAvatar("alice", file);

        assertNotNull(url);
        assertTrue(url.startsWith("http://test-host/avatars/"));
        assertTrue(url.endsWith(".png"));
        assertEquals(url, user.getAvatarUrl());

        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path stored = tempDir.resolve(filename);
        assertTrue(Files.exists(stored));
        try {
            byte[] out = Files.readAllBytes(stored);
            assertTrue(TestImageFactory.isPng(out));
        } catch (IOException e) {
            fail(e);
        }
    }

    @Test
    void rejectHugeDimensions() {
        byte[] png = TestImageFactory.makePng(2000, 12000, java.awt.Color.GRAY, null);
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", png);
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(new User()));
        assertThrows(IllegalArgumentException.class, () -> avatarService.uploadAndAssignAvatar("bob", file));
    }

    @Test
    void fileTooLargeBySize() {
        byte[] huge = new byte[10 * 1024 * 1024 + 1];
        MockMultipartFile file = new MockMultipartFile("file", "huge.jpg", "image/jpeg", huge);
        assertThrows(IllegalArgumentException.class, () -> avatarService.uploadAndAssignAvatar("alice", file));
    }

    @Test
    void extensionMismatchAcceptedByContent() {
        byte[] png = TestImageFactory.makePng(300, 300, java.awt.Color.GREEN, null);
        // misleading name and declared content type, detection uses content
        MockMultipartFile file = new MockMultipartFile("file", "pic.jpg", "image/jpeg", png);
        when(userRepository.findByUsername("carol")).thenReturn(Optional.of(new User()));
        String url = avatarService.uploadAndAssignAvatar("carol", file);
        assertTrue(url.endsWith(".png"));
    }
}


