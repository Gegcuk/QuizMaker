package uk.gegc.quizmaker.exception;

public class UnsupportedQuestionTypeException extends RuntimeException {
  public UnsupportedQuestionTypeException(String message) {
    super(message);
  }
}