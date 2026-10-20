package uk.gegc.quizmaker.features.quiz.application.generation;

public interface GenerationWorkDispatcher {
    enum Admission { DISPATCHED, DEFERRED }
    Admission dispatch();
}
