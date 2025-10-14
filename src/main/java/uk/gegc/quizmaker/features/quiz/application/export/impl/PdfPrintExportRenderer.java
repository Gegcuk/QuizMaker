package uk.gegc.quizmaker.features.quiz.application.export.impl;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Minimal PDF renderer using PDFBox as a placeholder implementation.
 * Renders a very simple text-only PDF; can be replaced by HTML->PDF in future.
 */
@Component
@RequiredArgsConstructor
public class PdfPrintExportRenderer implements ExportRenderer {

    @Override
    public boolean supports(ExportFormat format) {
        return format == ExportFormat.PDF_PRINT;
    }

    @Override
    public ExportFile render(ExportPayload payload) {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(PDType1Font.HELVETICA, 12);
                contentStream.newLineAtOffset(50, 730);
                contentStream.showText("Quizzes Export (PDF) generated at " + java.time.Instant.now());
                contentStream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            byte[] bytes = baos.toByteArray();

            String filename = payload.filenamePrefix() + ".pdf";
            return new ExportFile(
                    filename,
                    "application/pdf",
                    () -> new ByteArrayInputStream(bytes),
                    bytes.length
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to render PDF export", e);
        }
    }
}


