package uk.gegc.quizmaker.repository.question;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.model.question.Question;

import java.util.UUID;

public interface QuestionRepository extends JpaRepository<Question, UUID> {
}
