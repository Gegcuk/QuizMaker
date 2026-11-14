package uk.gegc.quizmaker.features.question.domain.model.contract;

/** Transaction-wide intent for immutable question identities; WRITE also permits resolution. */
public enum ContractScopeIntent {
    READ, WRITE
}
