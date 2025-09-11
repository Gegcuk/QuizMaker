package uk.gegc.quizmaker.features.billing.infra.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import uk.gegc.quizmaker.features.billing.api.dto.ReservationDto;
import uk.gegc.quizmaker.features.billing.domain.model.Reservation;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ReservationMapper {
    ReservationDto toDto(Reservation entity);
    List<ReservationDto> toDtos(List<Reservation> entities);
}
