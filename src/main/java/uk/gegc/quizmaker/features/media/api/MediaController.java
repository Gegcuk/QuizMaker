package uk.gegc.quizmaker.features.media.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
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
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetPageResponse;
import uk.gegc.quizmaker.features.media.api.dto.MediaAssetSort;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadCompleteRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadRequest;
import uk.gegc.quizmaker.features.media.api.dto.MediaUploadResponse;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.media.domain.model.MediaAssetType;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.shared.rate_limit.RateLimitService;
import uk.gegc.quizmaker.shared.security.annotation.RequirePermission;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/media")
@Validated
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Media Library", description = "Upload and manage media assets via presigned URLs")
public class MediaController {

    private static final int UPLOAD_INTENT_RATE_LIMIT_PER_MINUTE = 10;
    private static final int UPLOAD_FINALIZATION_RATE_LIMIT_PER_MINUTE = 30;

    private final MediaAssetService mediaAssetService;
    private final RateLimitService rateLimitService;

    @Operation(
            summary = "Create a presigned upload URL for a new asset",
            description = """
                    Creates an asset in UPLOADING status and returns a short-lived presigned PUT target.
                    Upload the binary using the returned method, URL, and required headers, then call the completion endpoint.
                    The caller owns the asset unless they are a media, article, or system administrator. Upload intent creation is limited to 10 requests per minute per authenticated user.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Upload intent created",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MediaUploadResponse.class),
                            examples = @ExampleObject(
                                    name = "Image upload intent",
                                    value = """
                                            {
                                              "assetId": "8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e",
                                              "type": "IMAGE",
                                              "status": "UPLOADING",
                                              "key": "library/8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e.png",
                                              "cdnUrl": "https://cdn.quizzence.com/library/8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e.png",
                                              "mimeType": "image/png",
                                              "sizeBytes": 834233,
                                              "originalFilename": "diagram.png",
                                              "createdBy": "writer",
                                              "upload": {
                                                "method": "PUT",
                                                "url": "https://storage.example.com/presigned-upload",
                                                "headers": { "Content-Type": "image/png" },
                                                "expiresAt": "2026-07-17T16:15:00Z"
                                              }
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing MEDIA_CREATE permission", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Upload intent rate limit exceeded (10 requests per minute)", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @RequirePermission(PermissionName.MEDIA_CREATE)
    @PostMapping("/uploads")
    public ResponseEntity<MediaUploadResponse> createUploadIntent(
            Authentication authentication,
            @Valid @RequestBody MediaUploadRequest request) {
        String username = authentication != null ? authentication.getName() : "system";
        rateLimitService.checkRateLimit("media-upload-intent", username, UPLOAD_INTENT_RATE_LIMIT_PER_MINUTE);
        MediaUploadResponse response = mediaAssetService.createUploadIntent(request, username);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "Finalize an upload after the client PUT completes",
            description = """
                    Verifies the uploaded object and transitions it to READY. Image uploads must include width and height.
                    READY images are published to the CDN; document assets remain subject to storage access rules.
                    The caller must own the asset or have media, article, or system administrator permission. Finalization is limited to 30 requests per minute per authenticated user.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Upload finalized",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MediaAssetResponse.class),
                            examples = @ExampleObject(name = "Ready image", value = READY_IMAGE_EXAMPLE)
                    )
            ),
            @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Asset is not owned by the caller or MEDIA_CREATE permission is missing", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "Asset or uploaded object not found", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Upload finalization rate limit exceeded (30 requests per minute)", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @RequirePermission(PermissionName.MEDIA_CREATE)
    @PostMapping("/uploads/{assetId}/complete")
    public MediaAssetResponse finalizeUpload(
            Authentication authentication,
            @PathVariable UUID assetId,
            @Valid @RequestBody MediaUploadCompleteRequest request) {
        String username = authentication != null ? authentication.getName() : "system";
        rateLimitService.checkRateLimit("media-upload-finalization", username, UPLOAD_FINALIZATION_RATE_LIMIT_PER_MINUTE);
        return mediaAssetService.finalizeUpload(assetId, request, username);
    }

    @Operation(
            summary = "Get a media asset",
            description = "Returns an active asset by ID, including UPLOADING, READY, and FAILED states. Deleted assets return 404. The caller must own the asset or have media, article, or system administrator permission."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Asset retrieved",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MediaAssetResponse.class),
                            examples = @ExampleObject(name = "Ready image", value = READY_IMAGE_EXAMPLE)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Asset is not owned by the caller or MEDIA_READ permission is missing", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Asset does not exist or has been deleted",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Deleted asset", value = NOT_FOUND_EXAMPLE)
                    )
            )
    })
    @RequirePermission(PermissionName.MEDIA_READ)
    @GetMapping("/{assetId}")
    public MediaAssetResponse get(
            Authentication authentication,
            @PathVariable UUID assetId) {
        String username = authentication != null ? authentication.getName() : "system";
        return mediaAssetService.getById(assetId, username);
    }

    @Operation(
            summary = "Search media library assets",
            description = """
                    Returns READY assets only. Non-administrators receive only assets they created; callers with MEDIA_ADMIN, ARTICLE_ADMIN, or SYSTEM_ADMIN can search all assets.
                    Results are paginated with a zero-based page number and a server-enforced limit of 1 to 200. The default ordering is CREATED_AT descending.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Assets retrieved",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MediaAssetPageResponse.class),
                            examples = @ExampleObject(name = "Ready image page", value = MEDIA_PAGE_EXAMPLE)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid type, sort field, or sort direction",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Invalid sort", value = INVALID_SORT_EXAMPLE)
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Missing MEDIA_READ permission", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    @RequirePermission(PermissionName.MEDIA_READ)
    @GetMapping
    public Page<MediaAssetResponse> search(
            Authentication authentication,
            @Parameter(description = "Restrict results to one asset type") @RequestParam(required = false) MediaAssetType type,
            @Parameter(description = "Case-insensitive substring matched against the original filename and storage key") @RequestParam(required = false) String query,
            @Parameter(description = "Zero-based page number", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Requested page size; the server clamps it to 1-200", example = "50") @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "Field used for deterministic ordering", example = "CREATED_AT") @RequestParam(defaultValue = "CREATED_AT") MediaAssetSort sort,
            @Parameter(description = "Ordering direction", example = "DESC") @RequestParam(defaultValue = "DESC") Sort.Direction direction) {
        String username = authentication != null ? authentication.getName() : "system";
        return mediaAssetService.search(type, query, page, limit, sort, direction, username);
    }

    @Operation(
            summary = "Delete (or retire) a media asset",
            description = "Marks an asset as DELETED and optionally removes its remote object. Deleted assets are excluded from search and cannot be retrieved. The caller must own the asset or have media, article, or system administrator permission."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Asset deleted"),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Asset is not owned by the caller or MEDIA_DELETE permission is missing", content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Asset does not exist or has already been deleted",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(name = "Deleted asset", value = NOT_FOUND_EXAMPLE)
                    )
            )
    })
    @RequirePermission(PermissionName.MEDIA_DELETE)
    @DeleteMapping("/{assetId}")
    public ResponseEntity<Void> delete(
            Authentication authentication,
            @PathVariable UUID assetId) {
        String username = authentication != null ? authentication.getName() : "system";
        mediaAssetService.delete(assetId, username);
        return ResponseEntity.noContent().build();
    }

    private static final String READY_IMAGE_EXAMPLE = """
            {
              "assetId": "8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e",
              "type": "IMAGE",
              "status": "READY",
              "key": "library/8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e.png",
              "cdnUrl": "https://cdn.quizzence.com/library/8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e.png",
              "mimeType": "image/png",
              "sizeBytes": 834233,
              "width": 1280,
              "height": 720,
              "originalFilename": "diagram.png",
              "createdBy": "writer",
              "createdAt": "2026-07-17T15:00:00Z",
              "updatedAt": "2026-07-17T15:01:00Z"
            }
            """;

    private static final String MEDIA_PAGE_EXAMPLE = """
            {
              "content": [
                {
                  "assetId": "8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e",
                  "type": "IMAGE",
                  "status": "READY",
                  "key": "library/8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e.png",
                  "cdnUrl": "https://cdn.quizzence.com/library/8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e.png",
                  "mimeType": "image/png",
                  "sizeBytes": 834233,
                  "width": 1280,
                  "height": 720,
                  "originalFilename": "diagram.png",
                  "createdBy": "writer",
                  "createdAt": "2026-07-17T15:00:00Z",
                  "updatedAt": "2026-07-17T15:01:00Z"
                }
              ],
              "totalPages": 1,
              "totalElements": 1,
              "size": 50,
              "number": 0,
              "sort": { "sorted": true, "unsorted": false, "empty": false },
              "first": true,
              "last": true,
              "numberOfElements": 1,
              "empty": false,
              "pageable": {
                "pageNumber": 0,
                "pageSize": 50,
                "offset": 0,
                "sort": { "sorted": true, "unsorted": false, "empty": false },
                "paged": true,
                "unpaged": false
              }
            }
            """;

    private static final String NOT_FOUND_EXAMPLE = """
            {
              "type": "https://quizzence.com/docs/errors/resource-not-found",
              "title": "Resource Not Found",
              "status": 404,
              "detail": "Media asset 8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e not found",
              "instance": "/api/v1/media/8b5b6c1a-2fbe-45a9-8fb3-02f40893a01e"
            }
            """;

    private static final String INVALID_SORT_EXAMPLE = """
            {
              "type": "https://quizzence.com/docs/errors/type-mismatch",
              "title": "Type Mismatch",
              "status": 400,
              "detail": "Invalid value for parameter 'sort'. Expected type: MediaAssetSort.",
              "instance": "/api/v1/media",
              "parameter": "sort",
              "expectedType": "MediaAssetSort",
              "providedValue": "UNSUPPORTED"
            }
            """;
}
