package uk.gegc.quizmaker.service.user.impl;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.exception.DocumentStorageException;
import uk.gegc.quizmaker.exception.UnsupportedFileTypeException;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.user.UserRepository;
import uk.gegc.quizmaker.service.user.AvatarService;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AvatarServiceImpl implements AvatarService {

    private static final Set<String> ALLOWED_MIME = Set.of("image/png", "image/jpeg", "image/webp");
    private static final int MAX_PIXELS = 512;

    private final UserRepository userRepository;
    private final Tika tika = new Tika();

    @Value("${app.files.avatars-dir:uploads/avatars}")
    private String avatarsDir;

    @Value("${app.files.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

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

            // Detect mime from content, not filename
            String mime = tika.detect(file.getBytes());
            if (!ALLOWED_MIME.contains(mime)) {
                throw new UnsupportedFileTypeException("Unsupported image type. Allowed: PNG, JPEG, WEBP");
            }

            byte[] processed = resizeAndNormalize(file.getBytes(), mime);

            String extension = switch (mime) {
                case "image/png" -> "png";
                case "image/jpeg" -> "jpg";
                case "image/webp" -> "webp";
                default -> "bin";
            };

            // Persist to disk
            Files.createDirectories(Paths.get(avatarsDir));
            String filename = UUID.randomUUID() + "." + extension;
            Path output = Paths.get(avatarsDir).resolve(filename);
            Files.write(output, processed);

            // Update user avatar URL
            User user = userRepository.findByUsername(username)
                    .or(() -> userRepository.findByEmail(username))
                    .orElseThrow(() -> new uk.gegc.quizmaker.exception.ResourceNotFoundException("User " + username + " not found"));

            String publicUrl = buildPublicUrl(filename);
            user.setAvatarUrl(publicUrl);
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
            return publicUrl;
        } catch (UnsupportedFileTypeException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to store avatar: " + e.getMessage(), e);
        }
    }

    private String buildPublicUrl(String filename) {
        String base = publicBaseUrl != null ? publicBaseUrl.replaceAll("/$", "") : "";
        String rel = avatarsDir.replace("\\", "/");
        if (!rel.startsWith("/")) rel = "/" + rel;
        return base + rel + "/" + filename;
    }

    private byte[] resizeAndNormalize(byte[] input, String mime) throws IOException {
        // Convert to BufferedImage using ImageIO for PNG/JPEG; for WEBP we attempt via ImageIO if plugin available
        BufferedImage src;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(input)) {
            src = ImageIO.read(bais);
        }
        if (src == null) {
            throw new UnsupportedFileTypeException("Unsupported or corrupt image");
        }

        int width = src.getWidth();
        int height = src.getHeight();
        if (width <= 0 || height <= 0) {
            throw new UnsupportedFileTypeException("Invalid image dimensions");
        }

        // Compute target size with max dimension 512, preserve aspect ratio
        double scale = 1.0;
        if (width > MAX_PIXELS || height > MAX_PIXELS) {
            scale = Math.min((double) MAX_PIXELS / width, (double) MAX_PIXELS / height);
        }
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));

        BufferedImage dst = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = dst.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(src, 0, 0, targetW, targetH, null);
        g2d.dispose();

        // Re-encode. Prefer PNG for lossless and wide support
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(dst, "png", baos);
            return baos.toByteArray();
        }
    }
}


