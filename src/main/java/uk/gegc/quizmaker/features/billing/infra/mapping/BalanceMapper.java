package uk.gegc.quizmaker.features.billing.infra.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import uk.gegc.quizmaker.features.billing.api.dto.BalanceDto;
import uk.gegc.quizmaker.features.billing.domain.model.Balance;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BalanceMapper {
    BalanceDto toDto(Balance entity);
    List<BalanceDto> toDtos(List<Balance> entities);
}
