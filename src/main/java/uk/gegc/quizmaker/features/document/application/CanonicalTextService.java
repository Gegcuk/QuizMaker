package uk.gegc.quizmaker.features.document.application;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.features.document.domain.model.Document;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;
import uk.gegc.quizmaker.shared.exception.DocumentNotFoundException;
import uk.gegc.quizmaker.shared.exception.DocumentProcessingException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing canonical text representation of documents.
 * 
 * This service provides one canonical UTF-8 text string per document with offset indexing
 * for pages and paragraphs. The canonical text is stored in the filesystem alongside
 * the original upload, and a source version hash is maintained for determinism.
 * 
 * Implementation of Day 2 — Canonical Text Pipeline from the chunk processing improvement plan.
 */
@Service
@Slf4j
public class CanonicalTextService {

    private final DocumentRepository documentRepository;
    private final DocumentConversionService documentConversionService;
    private final Path baseDir;
    private final ObjectMapper objectMapper;

    public CanonicalTextService(
            DocumentRepository documentRepository,
            DocumentConversionService documentConversionService,
            @Value("${quizmaker.canonical.base-dir:uploads/documents/canonical}") String baseDirStr) {
        this.documentRepository = documentRepository;
        this.documentConversionService = documentConversionService;
        this.baseDir = Paths.get(baseDirStr);
        this.objectMapper = new ObjectMapper().disable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Load or build canonical text for a document.
     * 
     * @param documentId the document ID
     * @return canonicalized text with metadata and offsets
     */
    public CanonicalizedText loadOrBuild(UUID documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + documentId));

        // Check if canonical text already exists
        Path canonicalTextPath = getCanonicalTextPath(documentId);
        Path metadataPath = getCanonicalTextMetadataPath(documentId);
        log.debug("Checking for existing canonical text: {} and metadata: {}", 
                canonicalTextPath, metadataPath);
        log.debug("Files exist: canonicalText={}, metadata={}", 
                Files.exists(canonicalTextPath), Files.exists(metadataPath));
        
        if (Files.exists(canonicalTextPath) && Files.exists(metadataPath)) {
            try {
                log.debug("Loading existing canonical text for document: {}", documentId);
                return loadFromFile(canonicalTextPath, document);
            } catch (IOException e) {
                log.warn("Failed to load existing canonical text for document {}, rebuilding: {}", 
                        documentId, e.getMessage());
            }
        }

        // Build canonical text from original document
        return buildCanonicalText(document);
    }

    /**
     * Build canonical text from the original document file.
     */
    private CanonicalizedText buildCanonicalText(Document document) {
        try {
            log.info("Building canonical text for document: {}", document.getId());

            // Read the original file
            Path originalFilePath = Paths.get(document.getFilePath());
            byte[] fileContent = Files.readAllBytes(originalFilePath);

            // Convert document using existing converter system
            ConvertedDocument convertedDocument = documentConversionService.convertDocument(
                    fileContent, 
                    document.getOriginalFilename(), 
                    document.getContentType()
            );

            // Extract canonical text
            String canonicalText = extractCanonicalText(convertedDocument);
            
            // Generate source version hash
            String sourceVersionHash = calculateSourceVersionHash(canonicalText);
            
            // Build offset indexes
            List<OffsetRange> pageOffsets = buildPageOffsets(convertedDocument, canonicalText);
            List<OffsetRange> paragraphOffsets = buildParagraphOffsets(canonicalText);

            // Create canonicalized text object
            CanonicalizedText canonicalizedText = new CanonicalizedText(
                    canonicalText,
                    sourceVersionHash,
                    pageOffsets,
                    paragraphOffsets
            );

            // Save to filesystem
            saveToFile(canonicalizedText, document.getId());

            // Update document with source version hash
            updateDocumentSourceVersionHash(document, sourceVersionHash);

            log.info("Successfully built canonical text for document: {} ({} characters, {} pages, {} paragraphs)",
                    document.getId(), canonicalText.length(), pageOffsets.size(), paragraphOffsets.size());

            return canonicalizedText;

        } catch (Exception e) {
            String errorMessage = String.format("Failed to build canonical text for document %s: %s", 
                    document.getId(), e.getMessage());
            log.error(errorMessage, e);
            throw new DocumentProcessingException(errorMessage, e);
        }
    }

    /**
     * Extract canonical text from converted document.
     * Prioritizes fullContent, falls back to structured content if needed.
     */
    private String extractCanonicalText(ConvertedDocument cd) {
        // 1) Prefer fullContent
        String full = cd.getFullContent();
        if (full != null && !full.trim().isEmpty()) {
            return normalizeText(full);
        }

        // 2) Build from structured content — titles only if they precede real content
        StringBuilder out = new StringBuilder();
        boolean anyContent = false;

        if (cd.getChapters() != null) {
            for (ConvertedDocument.Chapter ch : cd.getChapters()) {
                StringBuilder chapterBuf = new StringBuilder();
                boolean titleAdded = false;

                // chapter content
                if (ch.getContent() != null && !ch.getContent().isBlank()) {
                    if (ch.getTitle() != null && !ch.getTitle().isBlank()) {
                        chapterBuf.append(ch.getTitle()).append("\n\n");
                        titleAdded = true;
                    }
                    chapterBuf.append(ch.getContent()).append("\n\n");
                    anyContent = true;
                }

                // section content
                if (ch.getSections() != null) {
                    for (ConvertedDocument.Section s : ch.getSections()) {
                        if (s.getContent() != null && !s.getContent().isBlank()) {
                            // add chapter title once before the first real content if not already
                            if (!titleAdded && ch.getTitle() != null && !ch.getTitle().isBlank()) {
                                chapterBuf.append(ch.getTitle()).append("\n\n");
                                titleAdded = true;
                            }
                            if (s.getTitle() != null && !s.getTitle().isBlank()) {
                                chapterBuf.append(s.getTitle()).append("\n\n");
                            }
                            chapterBuf.append(s.getContent()).append("\n\n");
                            anyContent = true;
                        }
                    }
                }

                if (!chapterBuf.isEmpty()) {
                    out.append(chapterBuf);
                }
            }
        }

        // 3) If truly no body content, return empty (ignore synthesized titles)
        if (!anyContent) {
            return "";
        }

        // 4) Optionally prepend top-level title
        if (cd.getTitle() != null && !cd.getTitle().isBlank()) {
            out.insert(0, cd.getTitle() + "\n\n");
        }

        return normalizeText(out.toString());
    }

    /**
     * Normalize text to canonical UTF-8 format.
     */
    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        
        // Normalize Unicode characters
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC);
        
        // Ensure consistent line endings
        normalized = normalized.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
        
        // Remove excessive whitespace while preserving structure
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        
        return normalized.trim();
    }

    /**
     * Calculate SHA-256 hash of the canonical text for version tracking.
     */
    private String calculateSourceVersionHash(String canonicalText) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalText.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new DocumentProcessingException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Build page offset ranges from converted document.
     */
    private List<OffsetRange> buildPageOffsets(ConvertedDocument convertedDocument, String canonicalText) {
        List<OffsetRange> pageOffsets = new ArrayList<>();
        
        // If we have page information from the converter, use it
        if (convertedDocument.getTotalPages() != null && convertedDocument.getTotalPages() > 0) {
            // For now, create placeholder page offsets
            // In a full implementation, this would use actual page boundaries from the converter
            int charsPerPage = canonicalText.length() / convertedDocument.getTotalPages();
            
            for (int i = 0; i < convertedDocument.getTotalPages(); i++) {
                int startOffset = i * charsPerPage;
                int endOffset = (i == convertedDocument.getTotalPages() - 1) 
                        ? canonicalText.length() 
                        : (i + 1) * charsPerPage;
                
                pageOffsets.add(new OffsetRange(startOffset, endOffset, "Page " + (i + 1) + " (approx)"));
            }
        } else {
            // Fallback: treat entire document as single page
            pageOffsets.add(new OffsetRange(0, canonicalText.length(), "Page 1"));
        }
        
        return pageOffsets;
    }

    /**
     * Build paragraph offset ranges from canonical text using index-based scanning.
     */
    private List<OffsetRange> buildParagraphOffsets(String text) {
        List<OffsetRange> out = new ArrayList<>();
        if (text.isEmpty()) return out;

        final String SEP = "\n\n";
        int start = 0;
        while (true) {
            int idx = text.indexOf(SEP, start);
            int end = (idx == -1) ? text.length() : idx;
            if (end > start) {
                String para = text.substring(start, end);
                String title = para.lines().findFirst().orElse("Paragraph " + (out.size() + 1));
                title = title.length() > 100 ? title.substring(0, 97) + "..." : title;
                out.add(new OffsetRange(start, end, title));
            }
            if (idx == -1) break;
            start = idx + SEP.length();
        }
        return out;
    }

    /**
     * Save canonicalized text to filesystem with atomic writes.
     */
    private void saveToFile(CanonicalizedText canonicalizedText, UUID documentId) throws IOException {
        Path canonicalTextPath = getCanonicalTextPath(documentId);
        Path metadataPath = getCanonicalTextMetadataPath(documentId);
        
        // Ensure directory exists
        Files.createDirectories(baseDir);
        
        // Atomic write for canonical text
        Path tmpTxt = Files.createTempFile(baseDir, documentId.toString(), ".txt.tmp");
        Files.writeString(tmpTxt, canonicalizedText.getText(), StandardCharsets.UTF_8);
        Files.move(tmpTxt, canonicalTextPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        
        // Build metadata JSON
        CanonicalMetaJson json = new CanonicalMetaJson(
                canonicalizedText.getSourceVersionHash(),
                canonicalizedText.getPageOffsets().stream()
                        .map(o -> new OffsetJson(o.getStartOffset(), o.getEndOffset(), o.getTitle()))
                        .toList(),
                canonicalizedText.getParagraphOffsets().stream()
                        .map(o -> new OffsetJson(o.getStartOffset(), o.getEndOffset(), o.getTitle()))
                        .toList()
        );
        
        // Atomic write for metadata
        Path tmpMeta = Files.createTempFile(baseDir, documentId.toString(), ".meta.tmp");
        Files.writeString(tmpMeta, objectMapper.writeValueAsString(json), StandardCharsets.UTF_8);
        Files.move(tmpMeta, metadataPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        
        log.debug("Saved canonical text to: {}", canonicalTextPath);
    }

    /**
     * Load canonicalized text from filesystem.
     */
    private CanonicalizedText loadFromFile(Path canonicalTextPath, Document document) throws IOException {
        log.debug("Loading canonical text from: {}", canonicalTextPath);
        
        // Load the canonical text
        String text = Files.readString(canonicalTextPath, StandardCharsets.UTF_8);
        log.debug("Loaded canonical text, length: {}", text.length());
        
        // Load and parse metadata
        Path metadataPath = getCanonicalTextMetadataPath(document.getId());
        log.debug("Loading metadata from: {}", metadataPath);
        
        try {
            String metadataContent = Files.readString(metadataPath, StandardCharsets.UTF_8);
            log.debug("Loaded metadata, length: {}", metadataContent.length());
            
            CanonicalMetaJson meta = objectMapper.readValue(metadataContent, CanonicalMetaJson.class);
            
            // Convert JSON objects back to domain objects
            List<OffsetRange> pageOffsets = meta.pageOffsets().stream()
                    .map(j -> new OffsetRange(j.start(), j.end(), j.title()))
                    .toList();
            List<OffsetRange> paragraphOffsets = meta.paragraphOffsets().stream()
                    .map(j -> new OffsetRange(j.start(), j.end(), j.title()))
                    .toList();
            
            log.debug("Extracted source version hash: {}", meta.sourceVersionHash());
            log.debug("Extracted {} page offsets", pageOffsets.size());
            log.debug("Extracted {} paragraph offsets", paragraphOffsets.size());
            
            return new CanonicalizedText(text, meta.sourceVersionHash(), pageOffsets, paragraphOffsets);
            
        } catch (Exception parse) {
            log.warn("Corrupt canonical metadata for {} — rebuilding. Reason: {}", 
                    document.getId(), parse.toString());
            throw new IOException("Corrupt metadata", parse); // triggers rebuild in caller
        }
    }

    /**
     * Get the filesystem path for canonical text storage.
     */
    private Path getCanonicalTextPath(UUID documentId) {
        return baseDir.resolve(documentId.toString() + ".txt");
    }

    /**
     * Get the filesystem path for canonical text metadata.
     */
    private Path getCanonicalTextMetadataPath(UUID documentId) {
        return baseDir.resolve(documentId.toString() + ".meta");
    }

    /**
     * Update document with source version hash.
     */
    private void updateDocumentSourceVersionHash(Document document, String sourceVersionHash) {
        // Note: This would require adding a sourceVersionHash field to the Document entity
        // For now, we'll log the hash for tracking
        log.info("Document {} source version hash: {}", document.getId(), sourceVersionHash);
    }

    /**
     * Convert byte array to hexadecimal string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    /**
     * Canonicalized text with metadata and offset indexes.
     */
    @Data
    public static class CanonicalizedText {
        private final String text;
        private final String sourceVersionHash;
        private final List<OffsetRange> pageOffsets;
        private final List<OffsetRange> paragraphOffsets;
    }

    /**
     * Represents a range of character offsets in the canonical text.
     */
    @Data
    public static class OffsetRange {
        private final int startOffset;
        private final int endOffset;
        private final String title;
    }

    /**
     * JSON record for metadata serialization.
     */
    public record CanonicalMetaJson(
            @JsonProperty("sourceVersionHash") String sourceVersionHash,
            @JsonProperty("pageOffsets") List<OffsetJson> pageOffsets,
            @JsonProperty("paragraphOffsets") List<OffsetJson> paragraphOffsets
    ) {}

    /**
     * JSON record for offset serialization.
     */
    public record OffsetJson(
            @JsonProperty("start") int start,
            @JsonProperty("end") int end,
            @JsonProperty("title") String title
    ) {}
}
