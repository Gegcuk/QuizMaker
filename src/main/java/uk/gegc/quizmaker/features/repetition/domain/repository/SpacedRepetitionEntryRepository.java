package uk.gegc.quizmaker.features.repetition.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uk.gegc.quizmaker.features.repetition.domain.model.SpacedRepetitionEntry;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SpacedRepetitionEntryRepository extends JpaRepository<SpacedRepetitionEntry, UUID> {

    Optional<SpacedRepetitionEntry> findByUser_IdAndQuestion_Id(UUID user_id, UUID question_id);

    Optional<SpacedRepetitionEntry> findByIdAndUser_Id(UUID entry_id, UUID user_id);

    @Query("""
        SELECT e
        FROM SpacedRepetitionEntry e
        JOIN e.question q
        WHERE e.user.id = :userId
            AND e.reminderEnabled = true
            AND e.nextReviewAt <= :now
            AND q.id IS NOT NULL
        ORDER BY e.nextReviewAt ASC
        """)
    Page<SpacedRepetitionEntry> findDueEntries(
            @Param("userId") UUID userId,
            @Param("now")Instant now,
            Pageable pageable
    );

    @Query("""
        SELECT e
        FROM SpacedRepetitionEntry e
        JOIN e.question q
        WHERE e.user.id = :userId
            AND e.reminderEnabled = true
            AND q.id IS NOT NULL
        ORDER BY e.nextReviewAt ASC
""")
    Page<SpacedRepetitionEntry> findPriorityQueue(
            @Param("userId") UUID userId,
            Pageable pageable
    );

}
