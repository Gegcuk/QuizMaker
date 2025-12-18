package uk.gegc.quizmaker.features.billing.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.billing.api.dto.PackDto;
import uk.gegc.quizmaker.features.billing.application.StripePackSyncService;
import uk.gegc.quizmaker.features.billing.domain.model.ProductPack;
import uk.gegc.quizmaker.features.billing.infra.repository.ProductPackRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/billing")
@RequiredArgsConstructor
@Tag(name = "Billing Admin", description = "Administrative billing operations")
@SecurityRequirement(name = "Bearer Authentication")
public class BillingAdminController {

    private final StripePackSyncService stripePackSyncService;
    private final ProductPackRepository productPackRepository;

    @Operation(
            summary = "Force sync token packs from Stripe",
            description = "Triggers a sync of ProductPacks from active Stripe Prices (token packs) and returns the active packs after sync. Requires SYSTEM_ADMIN permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Sync completed",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = PackDto.class)))
            ),
            @ApiResponse(responseCode = "403", description = "Missing SYSTEM_ADMIN permission"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/packs/sync")
    @RequirePermission(PermissionName.SYSTEM_ADMIN)
    public ResponseEntity<List<PackDto>> syncStripePacks() {
        stripePackSyncService.syncActivePacks();

        List<PackDto> packs = productPackRepository.findByActiveTrue().stream()
                .sorted(Comparator.comparingLong(ProductPack::getTokens))
                .map(pack -> new PackDto(
                        pack.getId(),
                        pack.getName(),
                        pack.getDescription(),
                        pack.getTokens(),
                        pack.getPriceCents(),
                        pack.getCurrency(),
                        pack.getStripePriceId()
                ))
                .toList();

        return ResponseEntity.ok(packs);
    }
}

