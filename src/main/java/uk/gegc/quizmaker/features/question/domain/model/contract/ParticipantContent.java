package uk.gegc.quizmaker.features.question.domain.model.contract;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Closed presentation vocabulary. No raw JSON, answer, source key or grading fields. */
public sealed interface ParticipantContent {
    record Option(UUID id, String text) {
        public Option {
            require(id != null && text != null && text.codePointCount(0, text.length()) <= 10_000);
        }
    }

    record Gap(int id) { }

    record Choices(List<Option> options) implements ParticipantContent {
        public Choices { options = copyOptions(options); }
    }

    record TrueFalse() implements ParticipantContent { }

    record Open() implements ParticipantContent { }

    record Compliance(List<ComplianceStatement> statements) implements ParticipantContent {
        public Compliance {
            require(statements != null && statements.size() >= 2 && statements.size() <= 6);
            var ids = new HashSet<UUID>();
            for (var statement : statements) require(statement != null && ids.add(statement.id()));
            statements = List.copyOf(statements);
        }
    }

    record ComplianceStatement(UUID id, String text, ComplianceImage image) {
        public ComplianceStatement {
            require(id != null && (text == null || text.codePointCount(0, text.length()) <= 10_000));
            require(text != null && !text.isBlank() || image != null);
        }
        @Override public String toString() { return "ComplianceStatement[redacted]"; }
    }

    /** Exact retained reference only; a separate current-authority reader supplies public URLs. */
    record ComplianceImage(@com.fasterxml.jackson.annotation.JsonIgnore UUID assetId, int width, int height, String mimeType) {
        public ComplianceImage {
            require(assetId != null && width > 0 && height > 0
                    && mimeType != null && mimeType.startsWith("image/") && mimeType.length() <= 100);
        }
        @Override public String toString() { return "ComplianceImage[redacted]"; }
    }

    record Matching(List<MatchingOption> left, List<MatchingOption> right) implements ParticipantContent {
        public Matching {
            left = copyMatching(left); right = copyMatching(right);
            var ids = new HashSet<UUID>();
            for (var option : left) require(ids.add(option.id()));
            for (var option : right) require(ids.add(option.id()));
        }
    }

    record MatchingOption(UUID id, String text, MatchingImage image) {
        public MatchingOption {
            require(id != null && (text == null || text.codePointCount(0, text.length()) <= 10_000));
            require(text != null && !text.isBlank() || image != null);
        }
        @Override public String toString() { return "MatchingOption[redacted]"; }
    }

    /** Internal retained-image reference only. Current display authority must supply every public URL. */
    record MatchingImage(@com.fasterxml.jackson.annotation.JsonIgnore UUID assetId, int width, int height, String mimeType) {
        public MatchingImage {
            require(assetId != null && width > 0 && height > 0
                    && mimeType != null && mimeType.startsWith("image/") && mimeType.length() <= 100);
        }
        @Override public String toString() { return "MatchingImage[redacted]"; }
    }

    private static List<MatchingOption> copyMatching(List<MatchingOption> options) {
        require(options != null && options.size() >= 2 && options.size() <= 100);
        for (var option : options) require(option != null);
        return List.copyOf(options);
    }

    record FillGap(String text, List<Gap> gaps, List<Option> options) implements ParticipantContent {
        public FillGap {
            require(text != null && text.codePointCount(0, text.length()) <= 10_000 && gaps != null);
            var ids = new HashSet<Integer>();
            for (var gap : gaps) require(gap != null && ids.add(gap.id()));
            gaps = List.copyOf(gaps);
            options = copyOptions(options);
        }
    }

    record Ordering(List<Option> items) implements ParticipantContent {
        public Ordering { items = copyOptions(items); }
    }

    private static List<Option> copyOptions(List<Option> options) {
        require(options != null);
        var ids = new HashSet<UUID>();
        for (var option : options) require(option != null && ids.add(option.id()));
        return List.copyOf(options);
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid participant content");
    }
}
