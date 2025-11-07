package uk.gegc.quizmaker.features.auth.infra.mapping;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthAccountDto;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;

/**
 * MapStruct mapper for OAuthAccount entities and DTOs
 */
@Mapper(componentModel = "spring")
public interface OAuthAccountMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "provider", source = "provider")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "profileImageUrl", source = "profileImageUrl")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    OAuthAccountDto toDto(OAuthAccount oauthAccount);
}

