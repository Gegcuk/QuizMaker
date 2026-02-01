package uk.gegc.quizmaker.features.repetition.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.repetition.domain.model.RepetitionReviewLog;

import java.util.UUID;

public interface RepetitionReviewLogRepository extends JpaRepository<RepetitionReviewLog, UUID> {

    Page<RepetitionReviewLog> findByUser_IdOrderByReviewedAtDesc(UUID userId, Pageable pageable);

    Page<RepetitionReviewLog> findByEntry_IdOrderByReviewedAtDesc(UUID entryId, Pageable pageable);
}
