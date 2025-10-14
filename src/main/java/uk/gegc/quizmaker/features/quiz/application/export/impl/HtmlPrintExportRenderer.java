package uk.gegc.quizmaker.features.quiz.application.export.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.export.AnswerKeyBuilder;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.AnswerKeyEntry;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
public class HtmlPrintExportRenderer implements ExportRenderer {

    private final AnswerKeyBuilder answerKeyBuilder;

    @Override
    public boolean supports(ExportFormat format) {
        return format == ExportFormat.HTML_PRINT;
    }

    @Override
    public ExportFile render(ExportPayload payload) {
        String html = buildHtml(payload);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
        String filename = "quizzes_export_" + timestamp + ".html";

        return new ExportFile(
                filename,
                "text/html; charset=utf-8",
                () -> new ByteArrayInputStream(bytes),
                bytes.length
        );
    }

    private String buildHtml(ExportPayload payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><title>Quizzes Export</title>");
        sb.append("<style>body{font-family:sans-serif;margin:24px;} h1{margin-bottom:0;} h2{margin-top:32px;} .meta{color:#555;} .page-break{page-break-before: always;} .question{margin:16px 0;} .answer-key h2{margin-top:0;} .tag{display:inline-block;margin-right:6px;color:#666;font-size:12px;} .category{color:#666;font-size:12px;} .cover{margin-bottom:24px;border-bottom:1px solid #ddd;padding-bottom:12px;}</style>");
        sb.append("</head><body>");

        if (Boolean.TRUE.equals(payload.printOptions().includeCover())) {
            sb.append("<div class=\"cover\"><h1>Quizzes Export</h1>");
            sb.append("<div class=\"meta\">Generated at: ").append(LocalDateTime.now()).append("</div></div>");
        }

        payload.quizzes().forEach(quiz -> {
            sb.append("<section class=\"quiz\">");
            sb.append("<h2>").append(escape(quiz.title())).append("</h2>");
            if (Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
                sb.append("<div class=\"meta\">");
                if (quiz.category() != null) {
                    sb.append("<span class=\"category\">Category: ").append(escape(quiz.category())).append("</span> ");
                }
                sb.append("<span>Difficulty: ").append(quiz.difficulty()).append("</span> ");
                if (quiz.tags() != null && !quiz.tags().isEmpty()) {
                    sb.append("<span>Tags: ");
                    quiz.tags().forEach(tag -> sb.append("<span class=\"tag\">#").append(escape(tag)).append("</span>"));
                    sb.append("</span>");
                }
                sb.append("</div>");
            }
            if (quiz.description() != null && !quiz.description().isBlank()) {
                sb.append("<p>").append(escape(quiz.description())).append("</p>");
            }

            int idx = 1;
            for (var q : quiz.questions()) {
                sb.append("<div class=\"question\"><strong>").append(idx++).append(".</strong> ")
                  .append(escape(q.questionText()))
                  .append("</div>");
            }
            sb.append("</section>");
        });

        List<AnswerKeyEntry> answerKey = payload.quizzes().stream()
                .flatMap(q -> answerKeyBuilder.build(q.questions()).stream())
                .toList();

        if (Boolean.TRUE.equals(payload.printOptions().answersOnSeparatePages())) {
            sb.append("<div class=\"page-break\"></div>");
        }

        sb.append("<section class=\"answer-key\">");
        sb.append("<h2>Answer Key</h2>");
        int akIdx = 1;
        for (AnswerKeyEntry entry : answerKey) {
            sb.append("<div><strong>").append(akIdx++).append(".</strong> [").append(entry.type()).append("] ")
              .append(escape(entry.normalizedAnswer().toString()))
              .append("</div>");
        }
        sb.append("</section>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}


