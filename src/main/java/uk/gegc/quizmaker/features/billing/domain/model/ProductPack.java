package uk.gegc.quizmaker.features.billing.domain.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "product_packs")
@Getter
@Setter
public class ProductPack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "tokens", nullable = false)
    private long tokens;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "stripe_price_id", nullable = false, length = 100)
    private String stripePriceId;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
