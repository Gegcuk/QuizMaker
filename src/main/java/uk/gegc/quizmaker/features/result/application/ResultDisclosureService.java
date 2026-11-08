package uk.gegc.quizmaker.features.result.application;

import uk.gegc.quizmaker.features.result.domain.model.disclosure.ResultDisclosure;

/** Internal pure-decision boundary for verified server context. No authentication, reads or writes. */
public interface ResultDisclosureService {
    ResultDisclosure.Decisions evaluate(ResultDisclosure.Context context);
}
