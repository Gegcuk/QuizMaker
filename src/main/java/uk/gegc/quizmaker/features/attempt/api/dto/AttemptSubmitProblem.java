package uk.gegc.quizmaker.features.attempt.api.dto;

import java.util.List;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ProblemDetail;

@Schema(name = "AttemptSubmitProblem", description = "RFC 7807 error with a bounded code and safe missing IDs from the participant's own form. Other errors have an empty list.")
public final class AttemptSubmitProblem extends ProblemDetail {
    private final String code;
    private final List<UUID> missingFormItemIds;
    public AttemptSubmitProblem(ProblemDetail detail, String code, List<UUID> missingFormItemIds) {
        super(detail); this.code = code; this.missingFormItemIds = List.copyOf(missingFormItemIds);
    }
    public String getCode() { return code; }
    public List<UUID> getMissingFormItemIds() { return missingFormItemIds; }
}
