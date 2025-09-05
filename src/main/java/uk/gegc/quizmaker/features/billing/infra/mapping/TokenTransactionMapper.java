package uk.gegc.quizmaker.features.billing.infra.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import uk.gegc.quizmaker.features.billing.api.dto.TransactionDto;
import uk.gegc.quizmaker.features.billing.domain.model.TokenTransaction;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TokenTransactionMapper {
    TransactionDto toDto(TokenTransaction entity);
    List<TransactionDto> toDtos(List<TokenTransaction> entities);
}
