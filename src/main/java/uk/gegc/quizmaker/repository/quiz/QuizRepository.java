package uk.gegc.quizmaker.repository.quiz;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.model.quiz.Quiz;

import java.util.UUID;

public interface QuizRepository extends JpaRepository<Quiz, UUID> {
}
