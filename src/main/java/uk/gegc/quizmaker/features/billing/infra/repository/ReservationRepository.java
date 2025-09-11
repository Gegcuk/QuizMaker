package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;
import uk.gegc.quizmaker.features.billing.domain.model.ReservationState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {
    Optional<Reservation> findByIdAndUserId(UUID id, UUID userId);

    List<Reservation> findByStateAndExpiresAtBefore(ReservationState state, LocalDateTime cutoff);

    Optional<Reservation> findByIdAndState(UUID id, ReservationState state);
}
