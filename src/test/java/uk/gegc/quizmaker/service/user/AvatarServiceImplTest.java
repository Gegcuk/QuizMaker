package uk.gegc.quizmaker.service.user;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.user.application.impl.AvatarServiceImpl;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.DocumentStorageException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.UnsupportedFileTypeException;
import uk.gegc.quizmaker.util.TestImageFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
            // verify dimensions <= 512
            java.awt.image.BufferedImage outImg = javax.imageio.ImageIO.read(stored.toFile());
            assertNotNull(outImg);
            assertTrue(Math.max(outImg.getWidth(), outImg.getHeight()) <= 512);
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

    @Test
    void jpegReencodedAndScaled() throws Exception {
        byte[] jpeg = TestImageFactory.makeJpeg(1200, 1200, 0.8f);
        MockMultipartFile file = new MockMultipartFile("file", "in.jpg", "image/jpeg", jpeg);
        User user = new User();
        when(userRepository.findByUsername("dave")).thenReturn(Optional.of(user));

        String url = avatarService.uploadAndAssignAvatar("dave", file);
        assertTrue(url.endsWith(".png"));

        String filename = url.substring(url.lastIndexOf('/') + 1);
        Path stored = tempDir.resolve(filename);
        java.awt.image.BufferedImage outImg = javax.imageio.ImageIO.read(stored.toFile());
        assertNotNull(outImg);
        assertEquals(512, Math.max(outImg.getWidth(), outImg.getHeight()));
    }

    @Test
    void streamReadFailureYieldsDocumentStorageException() {
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", new byte[]{1}) {
            @Override
            @org.springframework.lang.NonNull
            public byte[] getBytes() throws IOException {
                throw new IOException("boom");
            }
        };
        assertThrows(DocumentStorageException.class, () -> avatarService.uploadAndAssignAvatar("zoe", file));
    }

    @Test
    void nonWritablePathThrowsDocumentStorageException() throws Exception {
        Path filePath = Files.createTempFile("avatars-file-", ".tmp");
        try {
            var dir = AvatarServiceImpl.class.getDeclaredField("avatarsDir");
            dir.setAccessible(true);
            dir.set(avatarService, filePath.toString());

            byte[] png = TestImageFactory.makePng(100, 100, java.awt.Color.BLACK, null);
            MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", png);
            assertThrows(DocumentStorageException.class, () -> avatarService.uploadAndAssignAvatar("amy", file));
        } finally {
            Files.deleteIfExists(filePath);
            // restore dir for cleanup
            var dir = AvatarServiceImpl.class.getDeclaredField("avatarsDir");
            dir.setAccessible(true);
            dir.set(avatarService, tempDir.toString());
        }
    }

    @Test
    void deleteOldAvatar_pathTraversalIgnored() throws Exception {
        Path baseDir = tempDir;
        Path outside = Files.createTempFile("outside-", ".png");
        try {
            String badUrl = "http://test-host/avatars/../../" + outside.getFileName();
            var method = AvatarServiceImpl.class.getDeclaredMethod("deleteOldAvatar", String.class, Path.class);
            method.setAccessible(true);
            method.invoke(avatarService, badUrl, baseDir);
            assertTrue(Files.exists(outside));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void deleteOldAvatar_noFilenameIgnored() throws Exception {
        Path baseDir = tempDir;
        var method = AvatarServiceImpl.class.getDeclaredMethod("deleteOldAvatar", String.class, Path.class);
        method.setAccessible(true);
        // should be no-op and not throw
        method.invoke(avatarService, "http://test-host/avatars/", baseDir);
    }

    @Test
    void urlNormalizationVariants_singleSlashes() throws Exception {
        var base = AvatarServiceImpl.class.getDeclaredField("publicBaseUrl");
        base.setAccessible(true);
        base.set(avatarService, "http://test-host/");
        var path = AvatarServiceImpl.class.getDeclaredField("publicPath");
        path.setAccessible(true);
        path.set(avatarService, "avatars/");

        byte[] png = TestImageFactory.makePng(100, 50, java.awt.Color.BLUE, null);
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", png);
        when(userRepository.findByUsername("norm")).thenReturn(Optional.of(new User()));
        String url = avatarService.uploadAndAssignAvatar("norm", file);
        assertTrue(url.startsWith("http://test-host/avatars/"));
        assertFalse(url.contains("//avatars"));
    }

    @Test
    void defaultPublicPathFallbackToAvatars() throws Exception {
        var path = AvatarServiceImpl.class.getDeclaredField("publicPath");
        path.setAccessible(true);
        path.set(avatarService, null);

        when(userRepository.findByUsername("fp")).thenReturn(Optional.of(new User()));
        byte[] png = TestImageFactory.makePng(64, 64, java.awt.Color.YELLOW, null);
        MockMultipartFile file = new MockMultipartFile("file", "x.png", "image/png", png);
        String url = avatarService.uploadAndAssignAvatar("fp", file);
        assertTrue(url.startsWith("http://test-host/avatars/"));
    }

    @Test
    void pixelBombRejectedOver40MP() {
        // 10000 x 10000 = 100,000,000 > 40,000,000
        byte[] png = TestImageFactory.makePng(10_000, 10_000, java.awt.Color.BLACK, null);
        MockMultipartFile file = new MockMultipartFile("file", "bomb.png", "image/png", png);
        when(userRepository.findByUsername("bomb")).thenReturn(Optional.of(new User()));
        assertThrows(IllegalArgumentException.class, () -> avatarService.uploadAndAssignAvatar("bomb", file));
    }

    @Test
    void boundaryAllowedAt40MPAndScaled() throws Exception {
        // 10000 x 4000 = 40,000,000 -> allowed, then scaled to <= 512
        when(userRepository.findByUsername("edge")).thenReturn(Optional.of(new User()));
        byte[] png = TestImageFactory.makePng(10_000, 4_000, java.awt.Color.PINK, null);
        String url = avatarService.uploadAndAssignAvatar("edge", new MockMultipartFile("file", "edge.png", "image/png", png));
        Path f = tempDir.resolve(url.substring(url.lastIndexOf('/') + 1));
        java.awt.image.BufferedImage out = javax.imageio.ImageIO.read(f.toFile());
        assertTrue(Math.max(out.getWidth(), out.getHeight()) <= 512);
    }

    @Test
    void oldAvatarDeletedAfterCommit() throws Exception {
        // first upload creates initial avatar
        User user = new User();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user));
        byte[] first = TestImageFactory.makePng(200, 150, java.awt.Color.CYAN, null);
        String url1 = avatarService.uploadAndAssignAvatar("user", new MockMultipartFile("file", "a.png", "image/png", first));
        Path f1 = tempDir.resolve(url1.substring(url1.lastIndexOf('/') + 1));
        assertTrue(Files.exists(f1));

        // second upload should mark the first one for deletion on commit
        byte[] second = TestImageFactory.makePng(300, 300, java.awt.Color.MAGENTA, null);
        String url2 = avatarService.uploadAndAssignAvatar("user", new MockMultipartFile("file", "b.png", "image/png", second));
        Path f2 = tempDir.resolve(url2.substring(url2.lastIndexOf('/') + 1));
        assertTrue(Files.exists(f2));

        // simulate transaction commit callbacks
        for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        assertFalse(Files.exists(f1));
        assertTrue(Files.exists(f2));
    }

    @Test
    void rollbackDeletesNewFile_whenUserNotFound() throws Exception {
        byte[] png = TestImageFactory.makePng(100, 80, java.awt.Color.ORANGE, null);
        // repository returns empty so service throws after file persisted
        when(userRepository.findByUsername("missing")).thenReturn(Optional.empty());

        // capture the filename by intercepting URL after failure not possible; instead inspect dir pre/post rollback
        int before = (int) java.nio.file.Files.list(tempDir).count();
        try {
            avatarService.uploadAndAssignAvatar("missing", new MockMultipartFile("file", "x.png", "image/png", png));
            fail("Expected ResourceNotFoundException");
        } catch (ResourceNotFoundException ex) {
            // trigger rollback cleanup
            for (var sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            int after = (int) java.nio.file.Files.list(tempDir).count();
            assertEquals(before, after, "New file should be deleted on rollback");
        }
    }

    @Test
    void noTmpFilesLeftAfterSuccess() throws Exception {
        when(userRepository.findByUsername("tmp")).thenReturn(Optional.of(new User()));
        byte[] png = TestImageFactory.makePng(120, 90, java.awt.Color.BLUE, null);
        avatarService.uploadAndAssignAvatar("tmp", new MockMultipartFile("file", "x.png", "image/png", png));
        // ensure no .tmp files present
        boolean anyTmp = java.nio.file.Files.walk(tempDir)
                .anyMatch(p -> p.getFileName().toString().endsWith(".tmp"));
        assertFalse(anyTmp);
    }

    @Test
    void noUpscaleWhen512x512() throws Exception {
        when(userRepository.findByUsername("square")).thenReturn(Optional.of(new User()));
        byte[] exact = TestImageFactory.makePng(512, 512, java.awt.Color.GRAY, null);
        String url = avatarService.uploadAndAssignAvatar("square", new MockMultipartFile("file", "sq.png", "image/png", exact));
        Path f = tempDir.resolve(url.substring(url.lastIndexOf('/') + 1));
        java.awt.image.BufferedImage out = javax.imageio.ImageIO.read(f.toFile());
        assertEquals(512, out.getWidth());
        assertEquals(512, out.getHeight());
    }
}


