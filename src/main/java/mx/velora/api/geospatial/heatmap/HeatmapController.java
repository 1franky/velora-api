package mx.velora.api.geospatial.heatmap;

import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.identity.UserTrackPrivacy;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/** {@code GET /api/v1/geo/heatmap/{z}/{x}/{y}} según el contrato (ADR-017). */
@RestController
class HeatmapController {

    static final MediaType MVT = MediaType.parseMediaType("application/vnd.mapbox-vector-tile");

    /** Más allá, el cliente amplía la tesela del zoom 16 (~2.4 m por unidad de tesela en el ecuador). */
    static final int MAX_ZOOM = 16;

    private final HeatmapTiles tiles;
    private final UserTrackPrivacy privacy;

    HeatmapController(HeatmapTiles tiles, UserTrackPrivacy privacy) {
        this.tiles = tiles;
        this.privacy = privacy;
    }

    @GetMapping("/api/v1/geo/heatmap/{z}/{x}/{y}")
    ResponseEntity<byte[]> tile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(required = false) @Nullable LocalDate from,
            @RequestParam(required = false) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID bikeId,
            @RequestParam(required = false) @Nullable ActivitySubType subType,
            WebRequest request) {
        if (z < 0 || z > MAX_ZOOM || x < 0 || y < 0 || x >= (1 << z) || y >= (1 << z)) {
            throw new InvalidTileException();
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidTileException();
        }
        var body = tiles.tile(
                user.userId(),
                z,
                x,
                y,
                new HeatmapTiles.Filter(from, to, bikeId, subType),
                privacy.metersFor(user.userId()));
        if (body.length == 0) {
            return ResponseEntity.noContent()
                    .cacheControl(CacheControl.noCache().cachePrivate())
                    .build();
        }
        // Sin caché compartida: son recorridos privados. El cliente revalida y, si nada cambió, recibe 304 sin cuerpo.
        var etag = '"' + HexFormat.of().formatHex(sha256(body), 0, 16) + '"';
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .contentType(MVT)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .eTag(etag)
                .body(body);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class InvalidTileException extends ErrorResponseException {
        InvalidTileException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }
}
