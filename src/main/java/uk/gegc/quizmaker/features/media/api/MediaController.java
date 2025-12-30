package uk.gegc.quizmaker.features.media.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media")
@Validated
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Media Library", description = "Upload and manage media assets via presigned URLs")
public class MediaController {

    private final MediaAssetService mediaAssetService;

    @Operation(summary = "Create a presigned upload URL for a new asset")
    @ApiResponse(responseCode = "201", description = "Upload intent created",
            content = @Content(schema = @Schema(implementation = MediaUploadResponse.class)))
    @RequirePermission(PermissionName.MEDIA_CREATE)
    @PostMapping("/uploads")
    public ResponseEntity<MediaUploadResponse> createUploadIntent(
            Authentication authentication,
            @Valid @RequestBody MediaUploadRequest request) {
        String username = authentication != null ? authentication.getName() : "system";
        MediaUploadResponse response = mediaAssetService.createUploadIntent(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Finalize an upload after the client PUT completes")
    @ApiResponse(responseCode = "200", description = "Upload finalized",
            content = @Content(schema = @Schema(implementation = MediaAssetResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failed",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(PermissionName.MEDIA_CREATE)
    @PostMapping("/uploads/{assetId}/complete")
    public MediaAssetResponse finalizeUpload(
            Authentication authentication,
            @PathVariable UUID assetId,
            @Valid @RequestBody MediaUploadCompleteRequest request) {
        String username = authentication != null ? authentication.getName() : "system";
        return mediaAssetService.finalizeUpload(assetId, request, username);
    }

    @Operation(summary = "Search media library assets")
    @ApiResponse(responseCode = "200", description = "Assets retrieved",
            content = @Content(schema = @Schema(implementation = MediaAssetResponse.class)))
    @RequirePermission(PermissionName.MEDIA_READ)
    @GetMapping
    public Page<MediaAssetResponse> search(
            @RequestParam(required = false) MediaAssetType type,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int limit) {
        return mediaAssetService.search(type, query, page, limit);
    }

    @Operation(summary = "Delete (or retire) a media asset")
    @ApiResponse(responseCode = "204", description = "Asset deleted")
    @RequirePermission(PermissionName.MEDIA_DELETE)
    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable UUID assetId) {
        String username = authentication != null ? authentication.getName() : "system";
        mediaAssetService.delete(assetId, username);
        return ResponseEntity.noContent().build();
    }
}
