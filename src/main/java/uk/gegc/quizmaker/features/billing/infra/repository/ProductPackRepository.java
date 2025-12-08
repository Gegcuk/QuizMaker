package uk.gegc.quizmaker.features.billing.infra.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductPackRepository extends JpaRepository<ProductPack, UUID> {
    Optional<ProductPack> findByStripePriceId(String stripePriceId);

    List<ProductPack> findByActiveTrue();
}
