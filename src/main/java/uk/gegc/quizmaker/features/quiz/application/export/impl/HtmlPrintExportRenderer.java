package uk.gegc.quizmaker.features.quiz.application.export.impl;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.features.quiz.application.export.AnswerKeyBuilder;
import uk.gegc.quizmaker.features.quiz.application.export.ExportRenderer;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuestionExportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportDto;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.export.AnswerKeyEntry;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportFile;
import uk.gegc.quizmaker.features.quiz.domain.model.export.ExportPayload;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

        String filename = payload.filenamePrefix() + ".html";

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
        sb.append("<style>");
        sb.append("body{font-family:sans-serif;margin:24px;line-height:1.6;} ");
        sb.append("h1{margin-bottom:0;} ");
        sb.append("h2{margin-top:32px;border-bottom:2px solid #333;padding-bottom:8px;} ");
        sb.append("h3{margin-top:24px;color:#555;} ");
        sb.append(".meta{color:#555;font-size:14px;} ");
        sb.append(".page-break{page-break-before:always;} ");
        sb.append(".question{margin:20px 0;padding:12px;background:#f9f9f9;border-left:3px solid #333;} ");
        sb.append(".question-header{font-weight:bold;font-size:16px;margin-bottom:8px;} ");
        sb.append(".question-content{margin:8px 0;} ");
        sb.append(".hint{margin-top:8px;padding:8px;background:#fff3cd;border-left:3px solid #ffc107;font-style:italic;font-size:14px;} ");
        sb.append(".explanation{margin-top:8px;padding:8px;background:#d1ecf1;border-left:3px solid #17a2b8;font-size:14px;} ");
        sb.append(".answer-key{margin-top:32px;} ");
        sb.append(".answer-key h2{margin-top:0;} ");
        sb.append(".answer-entry{margin:8px 0;padding:8px;background:#f9f9f9;} ");
        sb.append(".tag{display:inline-block;margin-right:6px;color:#666;font-size:12px;} ");
        sb.append(".category{color:#666;font-size:12px;} ");
        sb.append(".cover{margin-bottom:24px;border-bottom:1px solid #ddd;padding-bottom:12px;} ");
        sb.append(".type-section{margin-top:32px;} ");
        sb.append("</style>");
        sb.append("</head><body>");

        if (Boolean.TRUE.equals(payload.printOptions().includeCover())) {
            sb.append("<div class=\"cover\"><h1>Quizzes Export</h1>");
            sb.append("<div class=\"meta\">Generated: ").append(java.time.Instant.now()).append("</div></div>");
        }

        // Collect all questions from all quizzes
        List<QuestionExportDto> allQuestions = new ArrayList<>();
        for (QuizExportDto quiz : payload.quizzes()) {
            if (payload.quizzes().size() > 1 || Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
                renderQuizHeader(sb, quiz, payload);
            }
            allQuestions.addAll(quiz.questions());
        }

        // Render questions - grouped or sequential
        if (Boolean.TRUE.equals(payload.printOptions().groupQuestionsByType())) {
            renderQuestionsGroupedByType(sb, allQuestions, payload);
        } else {
            renderQuestionsSequential(sb, allQuestions, payload);
        }

        // Answer key
        List<AnswerKeyEntry> answerKey = answerKeyBuilder.build(allQuestions);

        if (Boolean.TRUE.equals(payload.printOptions().answersOnSeparatePages())) {
            sb.append("<div class=\"page-break\"></div>");
        }

        sb.append("<section class=\"answer-key\">");
        sb.append("<h2>Answer Key</h2>");
        int akIdx = 1;
        for (AnswerKeyEntry entry : answerKey) {
            sb.append("<div class=\"answer-entry\"><strong>").append(akIdx++).append(".</strong> [").append(entry.type()).append("] ")
              .append(escape(formatAnswerForDisplay(entry)))
              .append("</div>");
        }
        sb.append("</section>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private void renderQuizHeader(StringBuilder sb, QuizExportDto quiz, ExportPayload payload) {
        sb.append("<section class=\"quiz\">");
        sb.append("<h2>").append(escape(quiz.title())).append("</h2>");
        
        if (Boolean.TRUE.equals(payload.printOptions().includeMetadata())) {
            sb.append("<div class=\"meta\">");
            if (quiz.category() != null) {
                sb.append("<span class=\"category\">Category: ").append(escape(quiz.category())).append("</span> ");
            }
            sb.append("<span>Difficulty: ").append(quiz.difficulty()).append("</span> ");
            sb.append("<span>Time: ").append(quiz.estimatedTime()).append(" min</span> ");
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
        sb.append("</section>");
    }

    private void renderQuestionsSequential(StringBuilder sb, List<QuestionExportDto> questions, ExportPayload payload) {
        int questionNumber = 1;
        for (QuestionExportDto question : questions) {
            renderQuestion(sb, question, questionNumber++, payload);
        }
    }

    private void renderQuestionsGroupedByType(StringBuilder sb, List<QuestionExportDto> questions, ExportPayload payload) {
        Map<String, List<QuestionExportDto>> grouped = questions.stream()
                .collect(Collectors.groupingBy(
                    q -> q.type().name(),
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        int questionNumber = 1;
        for (Map.Entry<String, List<QuestionExportDto>> entry : grouped.entrySet()) {
            sb.append("<div class=\"type-section\">");
            sb.append("<h3>").append(formatQuestionType(entry.getKey())).append(" Questions</h3>");
            for (QuestionExportDto question : entry.getValue()) {
                renderQuestion(sb, question, questionNumber++, payload);
            }
            sb.append("</div>");
        }
    }

    private void renderQuestion(StringBuilder sb, QuestionExportDto question, int number, ExportPayload payload) {
        sb.append("<div class=\"question\">");
        sb.append("<div class=\"question-header\">").append(number).append(". ").append(escape(question.questionText())).append("</div>");
        
        // Question content (options, etc.)
        sb.append("<div class=\"question-content\">");
        renderQuestionContent(sb, question);
        sb.append("</div>");

        // Optional hint
        if (Boolean.TRUE.equals(payload.printOptions().includeHints()) && question.hint() != null && !question.hint().isBlank()) {
            sb.append("<div class=\"hint\">💡 <strong>Hint:</strong> ").append(escape(question.hint())).append("</div>");
        }

        // Optional explanation
        if (Boolean.TRUE.equals(payload.printOptions().includeExplanations()) && question.explanation() != null && !question.explanation().isBlank()) {
            sb.append("<div class=\"explanation\">ℹ️ <strong>Explanation:</strong> ").append(escape(question.explanation())).append("</div>");
        }

        sb.append("</div>");
    }

    private void renderQuestionContent(StringBuilder sb, QuestionExportDto question) {
        JsonNode content = question.content();
        if (content == null || content.isNull()) {
            return;
        }

        switch (question.type()) {
            case MCQ_SINGLE, MCQ_MULTI -> {
                if (content.has("options")) {
                    sb.append("<ul>");
                    for (JsonNode option : content.get("options")) {
                        String text = option.has("text") ? option.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case TRUE_FALSE -> {
                sb.append("<ul><li>True</li><li>False</li></ul>");
            }
            case FILL_GAP -> {
                sb.append("<p><em>Fill in the blanks:</em></p>");
                if (content.has("gaps")) {
                    sb.append("<ul>");
                    int gapNum = 1;
                    for (int i = 0; i < content.get("gaps").size(); i++) {
                        sb.append("<li>Gap ").append(gapNum++).append(": _________________</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case ORDERING -> {
                sb.append("<p><em>Order the following items:</em></p>");
                if (content.has("items")) {
                    sb.append("<ul>");
                    for (JsonNode item : content.get("items")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case MATCHING -> {
                sb.append("<p><em>Match the items:</em></p>");
                if (content.has("left") && content.has("right")) {
                    sb.append("<div><strong>Left Column:</strong><ul>");
                    for (JsonNode item : content.get("left")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        String id = item.has("id") ? item.get("id").asText() : "";
                        sb.append("<li>").append(escape(id)).append(". ").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul></div>");
                    sb.append("<div><strong>Right Column:</strong><ul>");
                    for (JsonNode item : content.get("right")) {
                        String text = item.has("text") ? item.get("text").asText() : "";
                        String id = item.has("id") ? item.get("id").asText() : "";
                        sb.append("<li>").append(escape(id)).append(". ").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul></div>");
                }
            }
            case HOTSPOT -> {
                sb.append("<p><em>Select the correct region on the image</em></p>");
                if (content.has("imageUrl")) {
                    sb.append("<p>Image: ").append(escape(content.get("imageUrl").asText())).append("</p>");
                }
            }
            case COMPLIANCE -> {
                sb.append("<p><em>Mark each statement as Compliant or Non-compliant:</em></p>");
                if (content.has("statements")) {
                    sb.append("<ul>");
                    for (JsonNode statement : content.get("statements")) {
                        String text = statement.has("text") ? statement.get("text").asText() : "";
                        sb.append("<li>").append(escape(text)).append("</li>");
                    }
                    sb.append("</ul>");
                }
            }
            case OPEN -> {
                sb.append("<p><em>Answer:</em></p><p>_____________________________________________________________________</p>");
            }
        }
    }

    private String formatAnswerForDisplay(AnswerKeyEntry answer) {
        JsonNode normalized = answer.normalizedAnswer();
        if (normalized == null || normalized.isNull()) {
            return "No answer";
        }
        
        return switch (answer.type()) {
            case MCQ_SINGLE -> normalized.has("correctOptionId") ? 
                    "Option: " + normalized.get("correctOptionId").asText() : "N/A";
            case MCQ_MULTI -> normalized.has("correctOptionIds") ?
                    "Options: " + normalized.get("correctOptionIds").toString() : "N/A";
            case TRUE_FALSE -> normalized.has("answer") ?
                    (normalized.get("answer").asBoolean() ? "True" : "False") : "N/A";
            case OPEN -> normalized.has("answer") && !normalized.get("answer").isNull() ?
                    normalized.get("answer").asText() : "Open answer (manual grading)";
            default -> normalized.toString();
        };
    }

    private String formatQuestionType(String type) {
        return switch (type) {
            case "MCQ_SINGLE" -> "Multiple Choice (Single Answer)";
            case "MCQ_MULTI" -> "Multiple Choice (Multiple Answers)";
            case "TRUE_FALSE" -> "True/False";
            case "FILL_GAP" -> "Fill in the Gap";
            case "ORDERING" -> "Ordering";
            case "MATCHING" -> "Matching";
            case "HOTSPOT" -> "Hotspot";
            case "COMPLIANCE" -> "Compliance";
            case "OPEN" -> "Open-Ended";
            default -> type;
        };
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
