package uk.gegc.quizmaker.features.question.application;

import uk.gegc.quizmaker.shared.exception.ValidationException;

/** A bounded diagnostic: never retains rejected content or client field names. */
public final class QuestionContractException extends ValidationException {
    private final String code;
    private final String field;

    public QuestionContractException(String code, String field) {
        this(code, field, "Question data does not match contract v1.");
    }

    QuestionContractException(String code, String field, String message) {
        super(message);
        this.code = code;
        this.field = field;
    }

    public String getCode() { return code; }
    public String getField() { return field; }
}
