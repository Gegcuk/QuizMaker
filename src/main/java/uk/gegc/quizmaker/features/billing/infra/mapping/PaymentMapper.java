package uk.gegc.quizmaker.features.billing.infra.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;
import uk.gegc.quizmaker.features.billing.api.dto.CheckoutSessionStatus;
import uk.gegc.quizmaker.features.billing.domain.model.Payment;
import uk.gegc.quizmaker.features.billing.domain.model.PaymentStatus;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface PaymentMapper {

    @Mapping(target = "sessionId", source = "stripeSessionId")
    @Mapping(target = "status", source = "status", qualifiedByName = "statusAsString")
    @Mapping(target = "credited", source = "status", qualifiedByName = "isCredited")
    @Mapping(target = "creditedTokens", source = "creditedTokens")
    CheckoutSessionStatus toCheckoutSessionStatus(Payment payment);

    @Named("statusAsString")
    default String statusAsString(PaymentStatus status) {
        return status != null ? status.name() : null;
    }

    @Named("isCredited")
    default boolean isCredited(PaymentStatus status) {
        return status == PaymentStatus.SUCCEEDED;
    }
}
