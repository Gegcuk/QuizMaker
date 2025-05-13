package uk.gegc.quizmaker.repository.question;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.model.question.Answer;

import java.util.UUID;

public interface AnswerRepository extends JpaRepository<Answer, UUID> {
}
