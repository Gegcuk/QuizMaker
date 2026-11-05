package uk.gegc.quizmaker.features.media.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.media.domain.model.SavedImageBinding;

import java.util.*;

public interface SavedImageBindingRepository extends JpaRepository<SavedImageBinding, UUID> {
    List<SavedImageBinding> findAllByQuestionId(UUID questionId);
    List<SavedImageBinding> findAllByQuestionIdIn(Collection<UUID> questionIds);
}
