package uk.gegc.quizmaker.features.question.domain.model.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.INVALID_BINDING;

public record QuestionContractBinding(UUID questionRevisionId, ContractVersion response, ContractVersion scoring) {
    public QuestionContractBinding {
        if (questionRevisionId == null || response == null || scoring == null) {
            throw new ContractResolutionException(INVALID_BINDING);
        }
    }

    /** Fixed UTF-8 format v1. Validated ASCII identifiers need no JSON escaping. */
    public String canonicalJson() {
        return "{\"bindingFormatVersion\":1,\"questionRevisionId\":\"" + questionRevisionId
                + "\",\"response\":{\"id\":\"" + response.id() + "\",\"version\":" + response.version()
                + "},\"scoring\":{\"id\":\"" + scoring.id() + "\",\"version\":" + scoring.version() + "}}";
    }

    /** Only bounded contract metadata is digested, never question/document content. */
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
