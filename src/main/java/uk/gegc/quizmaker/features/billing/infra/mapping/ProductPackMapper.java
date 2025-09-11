package uk.gegc.quizmaker.features.billing.infra.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import uk.gegc.quizmaker.features.billing.api.dto.PackDto;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProductPackMapper {
    PackDto toDto(ProductPack entity);
    List<PackDto> toDtos(List<ProductPack> entities);
}
