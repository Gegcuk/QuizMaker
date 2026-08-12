package uk.gegc.quizmaker.features.document.infra.isolation;

import java.util.Locale;
import java.util.Set;

enum DocumentParserFormat {
    PDF(Set.of("application/pdf"), Set.of(".pdf"),
            "application/pdf", "PDF_DOCUMENT_CONVERTER"),
    EPUB(Set.of("application/epub+zip", "application/epub", "application/x-epub"), Set.of(".epub"),
            "application/epub+zip", "EPUB_DOCUMENT_CONVERTER"),
    TEXT(Set.of("text/plain", "text/txt"), Set.of(".txt", ".text"),
            "text/plain", "TEXT_DOCUMENT_CONVERTER");

    private final Set<String> contentTypes;
    private final Set<String> extensions;
    private final String convertedContentType;
    private final String converterType;

    DocumentParserFormat(
            Set<String> contentTypes,
            Set<String> extensions,
            String convertedContentType,
            String converterType
    ) {
        this.contentTypes = contentTypes;
        this.extensions = extensions;
        this.convertedContentType = convertedContentType;
        this.converterType = converterType;
    }

    static DocumentParserFormat resolve(String contentType, String filename) {
        String normalizedFilename = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        for (DocumentParserFormat format : values()) {
            if (format.contentTypes.contains(contentType)
                    || format.extensions.stream().anyMatch(normalizedFilename::endsWith)) {
                return format;
            }
        }
        return null;
    }

    String convertedContentType() {
        return convertedContentType;
    }

    String converterType() {
        return converterType;
    }
}
