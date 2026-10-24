package uk.gegc.quizmaker.features.question.domain.model.contract;

import java.util.regex.Pattern;

import static uk.gegc.quizmaker.features.question.domain.model.contract.ContractResolutionException.Reason.INVALID_REFERENCE;

/** Exact project-owned identity, never a Java class name, alias or mutable "latest" selector. */
public record ContractVersion(String id, int version) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    public ContractVersion {
        if (id == null || id.length() > 128 || !ID.matcher(id).matches() || version < 1) {
            throw new ContractResolutionException(INVALID_REFERENCE);
        }
    }
}
