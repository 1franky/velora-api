package mx.velora.api.catalog.bikes;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import mx.velora.api.catalog.bikes.BikeRepository.Bike;
import mx.velora.api.catalog.bikes.BikeRepository.BikeType;
import mx.velora.api.catalog.bikes.BikeRepository.Changes;
import mx.velora.api.identity.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/bikes} según {@code openapi/openapi-v1.yaml}. Una bicicleta de otro usuario responde 404, igual que
 * una inexistente, para no revelar su existencia.
 */
@RestController
@RequestMapping("/api/v1/bikes")
class BikeController {

    record BikeList(List<Bike> items) {}

    record BikeCreate(
            @NotBlank @Size(max = 100) String name,
            @NotNull BikeType bikeType,
            @Nullable @Size(max = 100) String brand,
            @Nullable @Size(max = 100) String model,

            @Nullable @DecimalMin(value = "0", inclusive = false) @DecimalMax("50") BigDecimal weightKg) {}

    /**
     * PATCH parcial. Distingue un campo ausente (no se modifica) de un {@code null} explícito (borra un campo opcional)
     * registrando qué setters invocó Jackson; un record no permite esa distinción.
     */
    static final class BikeUpdate {
        private final Set<String> present = new HashSet<>();

        private @Nullable @Size(max = 100) String name;
        private @Nullable BikeType bikeType;
        private @Nullable @Size(max = 100) String brand;
        private @Nullable @Size(max = 100) String model;
        private @Nullable @DecimalMin(value = "0", inclusive = false) @DecimalMax("50") BigDecimal weightKg;
        private @Nullable Boolean active;

        @JsonSetter("name")
        void setName(@Nullable String value) {
            present.add("name");
            name = value;
        }

        @JsonSetter("bikeType")
        void setBikeType(@Nullable BikeType value) {
            present.add("bikeType");
            bikeType = value;
        }

        @JsonSetter("brand")
        void setBrand(@Nullable String value) {
            present.add("brand");
            brand = value;
        }

        @JsonSetter("model")
        void setModel(@Nullable String value) {
            present.add("model");
            model = value;
        }

        @JsonSetter("weightKg")
        void setWeightKg(@Nullable BigDecimal value) {
            present.add("weightKg");
            weightKg = value;
        }

        @JsonSetter("active")
        void setActive(@Nullable Boolean value) {
            present.add("active");
            active = value;
        }

        boolean isEmpty() {
            return present.isEmpty();
        }

        /** Campos obligatorios enviados con {@code null} o, en el nombre, en blanco. */
        boolean clearsRequiredField() {
            return (present.contains("name") && (name == null || name.isBlank()))
                    || (present.contains("bikeType") && bikeType == null)
                    || (present.contains("active") && active == null);
        }

        Changes toChanges() {
            return new Changes(
                    present.contains("name") && name != null ? name.strip() : null,
                    present.contains("bikeType") ? bikeType : null,
                    present.contains("brand") ? Optional.ofNullable(blankToNull(brand)) : null,
                    present.contains("model") ? Optional.ofNullable(blankToNull(model)) : null,
                    present.contains("weightKg") ? Optional.ofNullable(weightKg) : null,
                    present.contains("active") ? active : null);
        }
    }

    /** Bicicleta inexistente o de otro usuario. */
    static final class BikeNotFoundException extends ErrorResponseException {
        BikeNotFoundException() {
            super(HttpStatus.NOT_FOUND);
        }
    }

    /** PATCH sin campos o con {@code null} en un campo obligatorio. */
    static final class InvalidBikeUpdateException extends ErrorResponseException {
        InvalidBikeUpdateException() {
            super(HttpStatus.BAD_REQUEST);
        }
    }

    private final BikeRepository bikes;
    private final Clock clock;

    BikeController(BikeRepository bikes, Clock clock) {
        this.bikes = bikes;
        this.clock = clock;
    }

    @GetMapping
    BikeList list(@AuthenticationPrincipal AuthenticatedUser user) {
        return new BikeList(bikes.findAll(user.userId()));
    }

    @PostMapping
    ResponseEntity<Bike> create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody BikeCreate body) {
        var bike = bikes.insert(
                user.userId(),
                body.name().strip(),
                body.bikeType(),
                blankToNull(body.brand()),
                blankToNull(body.model()),
                body.weightKg(),
                clock.instant());
        return ResponseEntity.created(URI.create("/api/v1/bikes/" + bike.id())).body(bike);
    }

    @PatchMapping("/{bikeId}")
    Bike update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID bikeId,
            @Valid @RequestBody BikeUpdate body) {
        if (body.isEmpty() || body.clearsRequiredField()) {
            throw new InvalidBikeUpdateException();
        }
        var changes = body.toChanges();
        return bikes.update(user.userId(), bikeId, changes, clock.instant()).orElseThrow(BikeNotFoundException::new);
    }

    @DeleteMapping("/{bikeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID bikeId) {
        if (!bikes.delete(user.userId(), bikeId)) {
            throw new BikeNotFoundException();
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
