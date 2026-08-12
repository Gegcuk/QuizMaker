package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;
import uk.gegc.quizmaker.features.document.application.DocumentUploadStagingService;
import uk.gegc.quizmaker.features.document.application.StagedDocumentUpload;
import uk.gegc.quizmaker.shared.exception.DocumentResourceLimitException;
import uk.gegc.quizmaker.shared.exception.DocumentStorageException;
import uk.gegc.quizmaker.shared.exception.DocumentTypeMismatchException;
import uk.gegc.quizmaker.shared.exception.DocumentUploadLimitExceededException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Local staging adapter for legacy document storage. */
@Service
@RequiredArgsConstructor
@Slf4j
public class LocalDocumentUploadStagingService implements DocumentUploadStagingService {

    private static final int COPY_BUFFER_SIZE = 16 * 1024;
    private static final int DETECTION_BYTES = 16 * 1024;
    private static final Set<String> GENERIC_CONTENT_TYPES = Set.of(
            "application/octet-stream", "binary/octet-stream"
    );

    private final DocumentProcessingLimits limits;

    @Override
    public StagedDocumentUpload stage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        if (file.getSize() > limits.getMaxUploadBytes()) {
            throw new DocumentUploadLimitExceededException();
        }
        try {
            return stage(file.getInputStream(), file.getOriginalFilename(), file.getContentType(), file.getSize());
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to read document upload", e);
        }
    }

    @Override
    public StagedDocumentUpload stage(
            InputStream source,
            String originalFilename,
            String declaredContentType,
            long declaredSize
    ) {
        if (source == null) {
            throw new IllegalArgumentException("File content is required");
        }
        if (declaredSize > limits.getMaxUploadBytes()) {
            throw new DocumentUploadLimitExceededException();
        }

        String filename = sanitizeFilename(originalFilename);
        Path stagedFile = null;
        try (source) {
            Path stagingDirectory = stagingDirectory();
            Files.createDirectories(stagingDirectory);
            cleanupExpiredStagingFiles(stagingDirectory);
            stagedFile = Files.createTempFile(stagingDirectory, "document-", ".upload");
            long actualSize = copyWithLimit(source, stagedFile);
            String detectedContentType = detectContentType(stagedFile, filename, actualSize);
            validateDeclaredType(filename, declaredContentType, detectedContentType);
            return new StagedDocumentUpload(stagedFile, filename, detectedContentType, actualSize);
        } catch (DocumentUploadLimitExceededException | DocumentTypeMismatchException | DocumentResourceLimitException e) {
            discard(stagedFile);
            throw e;
        } catch (IOException e) {
            discard(stagedFile);
            throw new DocumentStorageException("Failed to stage document upload", e);
        }
    }

    @Override
    public Path promote(StagedDocumentUpload upload) {
        if (upload == null || upload.stagingPath() == null) {
            throw new IllegalArgumentException("Staged upload is required");
        }

        try {
            Path publishedDirectory = publishedDirectory();
            Files.createDirectories(publishedDirectory);
            Path target = publishedDirectory.resolve(UUID.randomUUID() + extensionFor(upload.detectedContentType()));
            try {
                return Files.move(upload.stagingPath(), target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                return Files.move(upload.stagingPath(), target);
            }
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to publish staged document", e);
        }
    }

    @Override
    public void discard(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove document storage file; cleanup will be retried where applicable");
        }
    }

    @Override
    public void visitExpiredPublishedFiles(Consumer<Path> visitor) {
        Objects.requireNonNull(visitor, "Published-file visitor is required");
        try {
            cleanupExpiredStagingFiles(stagingDirectory());
            Path publishedDirectory = publishedDirectory();
            if (!Files.exists(publishedDirectory)) {
                return;
            }
            try (var files = Files.list(publishedDirectory)) {
                files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .filter(this::isExpired)
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(visitor);
            }
        } catch (IOException e) {
            throw new DocumentStorageException("Failed to scan document storage for reconciliation", e);
        }
    }

    private long copyWithLimit(InputStream source, Path destination) throws IOException {
        long total = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (OutputStream output = Files.newOutputStream(destination)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > limits.getMaxUploadBytes()) {
                    throw new DocumentUploadLimitExceededException();
                }
                output.write(buffer, 0, read);
            }
        }
        if (total == 0) {
            throw new IllegalArgumentException("File is empty");
        }
        return total;
    }

    private String detectContentType(Path path, String filename, long actualSize) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(path)) {
            header = input.readNBytes(DETECTION_BYTES);
        }
        if (startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (isZip(header)) {
            return validateEpubArchive(path);
        }
        if (isTextFilename(filename) && isUtf8Text(header, actualSize == header.length)) {
            return "text/plain";
        }
        throw new DocumentTypeMismatchException("Uploaded content is not a supported PDF, EPUB, or UTF-8 text document");
    }

    private String validateEpubArchive(Path path) throws IOException {
        try (ZipFile zipFile = new ZipFile(path.toFile())) {
            long totalUncompressedSize = 0;
            int entryCount = 0;
            ZipEntry firstEntry = null;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (firstEntry == null) {
                    firstEntry = entry;
                }
                entryCount++;
                if (entryCount > limits.getMaxEpubEntries()) {
                    throw new DocumentResourceLimitException("EPUB archive contains too many entries");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                long uncompressedSize = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (uncompressedSize < 0 || compressedSize < 0) {
                    throw new DocumentTypeMismatchException("EPUB archive has incomplete entry metadata");
                }
                totalUncompressedSize = Math.addExact(totalUncompressedSize, uncompressedSize);
                if (totalUncompressedSize > limits.getMaxEpubUncompressedBytes()) {
                    throw new DocumentResourceLimitException("EPUB archive exceeds the uncompressed size limit");
                }
                if (uncompressedSize > 0 && compressedSize == 0) {
                    throw new DocumentResourceLimitException("EPUB archive exceeds the compression ratio limit");
                }
                if (compressedSize > 0 && uncompressedSize / compressedSize > limits.getMaxEpubCompressionRatio()) {
                    throw new DocumentResourceLimitException("EPUB archive exceeds the compression ratio limit");
                }
            }

            ZipEntry mimetype = zipFile.getEntry("mimetype");
            if (mimetype == null || firstEntry == null || !"mimetype".equals(firstEntry.getName())) {
                throw new DocumentTypeMismatchException("ZIP uploads must be valid EPUB archives");
            }
            try (InputStream input = zipFile.getInputStream(mimetype)) {
                String declared = new String(input.readNBytes(64), StandardCharsets.US_ASCII).trim();
                if (!"application/epub+zip".equals(declared)) {
                    throw new DocumentTypeMismatchException("ZIP uploads must be valid EPUB archives");
                }
            }
            return "application/epub+zip";
        } catch (ArithmeticException e) {
            throw new DocumentResourceLimitException("EPUB archive exceeds the uncompressed size limit");
        }
    }

    private void validateDeclaredType(String filename, String declaredContentType, String detectedContentType) {
        String expectedFromExtension = contentTypeForExtension(filename);
        if (expectedFromExtension == null || !expectedFromExtension.equals(detectedContentType)) {
            throw new DocumentTypeMismatchException("Filename extension does not match detected document type");
        }
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return;
        }
        String normalizedDeclaredType = normalizeDeclaredContentType(declaredContentType);
        if (!GENERIC_CONTENT_TYPES.contains(normalizedDeclaredType) && !normalizedDeclaredType.equals(detectedContentType)) {
            throw new DocumentTypeMismatchException("Declared content type does not match detected document type");
        }
    }

    private boolean isUtf8Text(byte[] bytes, boolean endOfInput) {
        CharBuffer decoded = CharBuffer.allocate(bytes.length);
        CoderResult result = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                // The fixed-size detection probe may end inside a valid multi-byte character.
                .decode(ByteBuffer.wrap(bytes), decoded, endOfInput);
        if (result.isError()) {
            return false;
        }
        decoded.flip();
        return decoded.chars().noneMatch(character -> character < 0x09 || (character > 0x0D && character < 0x20));
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean isZip(byte[] header) {
        return header.length >= 4
                && header[0] == 'P'
                && header[1] == 'K'
                && header[2] == 3
                && header[3] == 4;
    }

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank() || originalFilename.indexOf('\u0000') >= 0) {
            throw new DocumentTypeMismatchException("Document filename is required");
        }
        String filename = Paths.get(originalFilename).getFileName().toString().trim();
        if (filename.isBlank()) {
            throw new DocumentTypeMismatchException("Document filename is required");
        }
        return filename;
    }

    private String contentTypeForExtension(String filename) {
        String lowerCaseFilename = filename.toLowerCase(Locale.ROOT);
        if (lowerCaseFilename.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lowerCaseFilename.endsWith(".epub")) {
            return "application/epub+zip";
        }
        if (isTextFilename(filename)) {
            return "text/plain";
        }
        return null;
    }

    private boolean isTextFilename(String filename) {
        String lowerCaseFilename = filename.toLowerCase(Locale.ROOT);
        return lowerCaseFilename.endsWith(".txt") || lowerCaseFilename.endsWith(".text");
    }

    private String normalizeDeclaredContentType(String contentType) {
        return switch (contentType.toLowerCase(Locale.ROOT).trim()) {
            case "application/epub", "application/x-epub" -> "application/epub+zip";
            case "text/txt" -> "text/plain";
            default -> contentType.toLowerCase(Locale.ROOT).trim();
        };
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "application/epub+zip" -> ".epub";
            case "text/plain" -> ".txt";
            default -> ".bin";
        };
    }

    private Path stagingDirectory() {
        return storageRoot().resolve(".staging");
    }

    private Path publishedDirectory() {
        return storageRoot().resolve("published");
    }

    private Path storageRoot() {
        return Paths.get(limits.getStorageRoot()).toAbsolutePath().normalize();
    }

    private void cleanupExpiredStagingFiles(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(this::isExpired)
                    .forEach(this::discard);
        }
    }

    private boolean isExpired(Path path) {
        try {
            FileTime modifiedAt = Files.getLastModifiedTime(path);
            return modifiedAt.toInstant().isBefore(Instant.now().minus(limits.getStagingRetention()));
        } catch (IOException e) {
            return false;
        }
    }
}
