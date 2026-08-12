package uk.gegc.quizmaker.features.document.infra.isolation;

import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Prevents one-shot parser workers from scanning and caching every host font. */
final class BundledPdfFontMapper implements FontMapper, AutoCloseable {

    private static final String FALLBACK_FONT = "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private final TrueTypeFont fallbackFont;

    BundledPdfFontMapper() throws IOException {
        InputStream resource = FontMapper.class.getResourceAsStream(FALLBACK_FONT);
        if (resource == null) {
            throw new IOException("Bundled PDF fallback font is unavailable");
        }
        try {
            fallbackFont = new TTFParser().parse(new BufferedInputStream(resource));
        } catch (IOException failure) {
            resource.close();
            throw failure;
        }
    }

    @Override
    public FontMapping<TrueTypeFont> getTrueTypeFont(String baseFont, PDFontDescriptor fontDescriptor) {
        return new FontMapping<>(fallbackFont, true);
    }

    @Override
    public FontMapping<FontBoxFont> getFontBoxFont(String baseFont, PDFontDescriptor fontDescriptor) {
        return new FontMapping<>(fallbackFont, true);
    }

    @Override
    public CIDFontMapping getCIDFont(
            String baseFont,
            PDFontDescriptor fontDescriptor,
            PDCIDSystemInfo cidSystemInfo
    ) {
        return new CIDFontMapping(null, fallbackFont, true);
    }

    @Override
    public void close() throws IOException {
        fallbackFont.close();
    }
}
