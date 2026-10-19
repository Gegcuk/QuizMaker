package uk.gegc.quizmaker.features.question.application;

public final class QuestionContentUnavailableException extends RuntimeException {
    public QuestionContentUnavailableException() {
        super("Question content is unavailable. The author must repair it before submission or completion.");
    }
}
