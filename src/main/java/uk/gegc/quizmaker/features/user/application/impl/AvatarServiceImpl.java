package uk.gegc.quizmaker.features.user.application.impl;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.exception.DocumentStorageException;
import uk.gegc.quizmaker.exception.UnsupportedFileTypeException;
import uk.gegc.quizmaker.features.user.application.AvatarService;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class AvatarServiceImpl implements AvatarService {

    private static final Set<String> ALLOWED_MIME = Set.of("image/png", "image/jpeg");
    private static final int MAX_DIMENSION = 512;
    private static final int MAX_SOURCE_W = 10_000;
    private static final int MAX_SOURCE_H = 10_000;
    private static final long MAX_UPLOAD_BYTES = 10L * 1024L * 1024L; // 10MB

    private final UserRepository userRepository;
    private final Tika tika = new Tika();

    @Value("${app.files.avatars-dir:uploads/avatars}")
    private String avatarsDir;

    @Value("${app.files.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    @Value("${app.files.avatars-public-path:/avatars}")
    private String publicPath;

    public AvatarServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public String uploadAndAssignAvatar(String username, MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("Avatar file is required");
            }

            if (file.getSize() > MAX_UPLOAD_BYTES) {
                log.warn("Avatar upload rejected: file too large (userKey={} size={} bytes)", username, file.getSize());
                throw new IllegalArgumentException("File too large");
            }

            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException e) {
                throw new DocumentStorageException("Failed to read file: " + e.getMessage(), e);
            }

            String mime = tika.detect(bytes);
            if (!ALLOWED_MIME.contains(mime)) {
                log.warn("Avatar upload rejected: unsupported mime (userKey={} mime={})", username, mime);
                throw new UnsupportedFileTypeException("Unsupported image type. Allowed: PNG, JPEG");
            }

            // Pre-validate dimensions without fully decoding
            prevalidateDimensions(bytes);

            byte[] processed = resizeAndNormalize(bytes);

            // Persist to disk atomically, always PNG extension
            Path baseDir = Paths.get(avatarsDir).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);
            String filename = UUID.randomUUID() + ".png";
            Path tmp = baseDir.resolve(filename + ".tmp");
            Path output = baseDir.resolve(filename);
            try {
                Files.write(tmp, processed, java.nio.file.StandardOpenOption.CREATE_NEW);
                try {
                    Files.move(tmp, output, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                safeDelete(tmp);
                throw new DocumentStorageException("Failed to store avatar: " + e.getMessage(), e);
            }

            // Ensure rollback cleanup if anything fails after file persisted
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        safeDelete(output);
                    }
                }
            });

            // Update user avatar URL
            User user = userRepository.findByUsername(username)
                    .or(() -> userRepository.findByEmail(username))
                    .orElseThrow(() -> new uk.gegc.quizmaker.exception.ResourceNotFoundException("User " + username + " not found"));

            String oldUrl = user.getAvatarUrl();
            String publicUrl = buildPublicUrl(filename);
            user.setAvatarUrl(publicUrl);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);

            log.info("Avatar updated (userId={}, filename={})", user.getId(), filename);

            // After successful commit, attempt to delete the old local avatar file
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteOldAvatar(oldUrl, baseDir);
                }
            });

            return publicUrl;
        } catch (UnsupportedFileTypeException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to store avatar: " + e.getMessage(), e);
        }
    }

    private String buildPublicUrl(String filename) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", "");
        String path = (publicPath == null ? "/avatars": ("/" + publicPath.replaceAll("^/+", "").replaceAll("/+$", "")));
        return base + path + "/" + filename;
    }

    private byte[] resizeAndNormalize(byte[] input) throws IOException {
        ImageIO.setUseCache(false);
        BufferedImage src = readBufferedImage(input);

        // Apply EXIF orientation if present (e.g., JPEGs)
        try {
            src = applyExifOrientationIfPresent(src, input);
        } catch (Exception ex) {
            // Do not fail on EXIF read issues; proceed without orientation correction
            log.debug("EXIF orientation read failed: {}", ex.getMessage());
        }

        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) {
            throw new UnsupportedFileTypeException("Invalid image dimensions");
        }
        if (width > MAX_SOURCE_W || height > MAX_SOURCE_H || (long) width * (long) height > 40_000_000L) {
            throw new IllegalArgumentException("Image dimensions too large");
        }

        double scale = Math.min(1.0d, Math.min((double) MAX_DIMENSION / width, (double) MAX_DIMENSION / height));
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));

        BufferedImage dst = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = dst.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(src, 0, 0, targetW, targetH, null);
        g2d.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(dst, "png", baos);
            return baos.toByteArray();
        }
    }

    private BufferedImage applyExifOrientationIfPresent(BufferedImage src, byte[] inputBytes) throws Exception {
        Metadata metadata;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(inputBytes)) {
            metadata = ImageMetadataReader.readMetadata(bais);
        }
        ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        if (dir == null || !dir.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
            return src;
        }
        int orientation = dir.getInt(ExifIFD0Directory.TAG_ORIENTATION);

        int w = src.getWidth();
        int h = src.getHeight();
        AffineTransform tx = new AffineTransform();
        boolean swapWH = false;

        switch (orientation) {
            case 1: // Top-left (normal)
                return src;
            case 2: // Top-right (mirror horizontal)
                tx.scale(-1, 1);
                tx.translate(-w, 0);
                break;
            case 3: // Bottom-right (rotate 180)
                tx.translate(w, h);
                tx.rotate(Math.PI);
                break;
            case 4: // Bottom-left (mirror vertical)
                tx.scale(1, -1);
                tx.translate(0, -h);
                break;
            case 5: // Left-top (mirror horizontal and rotate 90 CW)
                tx.rotate(Math.PI / 2);
                tx.scale(1, -1);
                tx.translate(0, -h);
                swapWH = true;
                break;
            case 6: // Right-top (rotate 90 CW)
                tx.translate(h, 0);
                tx.rotate(Math.PI / 2);
                swapWH = true;
                break;
            case 7: // Right-bottom (mirror horizontal and rotate 90 CCW)
                tx.rotate(-Math.PI / 2);
                tx.scale(1, -1);
                tx.translate(0, -h);
                swapWH = true;
                break;
            case 8: // Left-bottom (rotate 90 CCW)
                tx.translate(0, w);
                tx.rotate(-Math.PI / 2);
                swapWH = true;
                break;
            default:
                return src;
        }

        int newW = swapWH ? h : w;
        int newH = swapWH ? w : h;
        BufferedImage oriented = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_BICUBIC);
        op.filter(src, oriented);
        return oriented;
    }

    private void prevalidateDimensions(byte[] input) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(input);
             ImageInputStream iis = ImageIO.createImageInputStream(bais)) {
            if (iis == null) {
                throw new UnsupportedFileTypeException("Unsupported or corrupt image");
            }
            java.util.Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new UnsupportedFileTypeException("Unsupported or corrupt image");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SOURCE_W || h > MAX_SOURCE_H || (long) w * h > 40_000_000L) {
                    throw new IllegalArgumentException("Image dimensions too large");
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private BufferedImage readBufferedImage(byte[] input) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(input)) {
            BufferedImage img = ImageIO.read(bais);
            if (img == null) {
                throw new UnsupportedFileTypeException("Unsupported or corrupt image");
            }
            return img;
        }
    }

    private void safeDelete(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private void deleteOldAvatar(String oldUrl, Path baseDir) {
        if (oldUrl == null || oldUrl.isBlank()) return;
        int idx = oldUrl.lastIndexOf('/');
        if (idx < 0 || idx == oldUrl.length() - 1) return;
        String name = oldUrl.substring(idx + 1);
        if (name.contains("..")) return;
        Path candidate = baseDir.resolve(name).normalize();
        if (!candidate.startsWith(baseDir)) return;
        safeDelete(candidate);
    }
}


