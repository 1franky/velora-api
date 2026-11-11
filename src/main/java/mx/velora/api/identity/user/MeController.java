package mx.velora.api.identity.user;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.identity.TrackPrivacyChanged;
import mx.velora.api.identity.UserTrackPrivacy;
import mx.velora.api.identity.auth.AuthProblems.UnauthenticatedException;
import mx.velora.api.identity.user.PreferencesProblems.EmptyPreferencesUpdateException;
import mx.velora.api.identity.user.PreferencesProblems.InvalidTimeZoneException;
import mx.velora.api.identity.user.PreferencesProblems.InvalidTrackPrivacyException;
import mx.velora.api.identity.user.PreferencesProblems.UnsupportedUnitException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/me} según {@code openapi/openapi-v1.yaml}. */
@RestController
@RequestMapping("/api/v1/me")
class MeController {

    /** Únicos valores documentados en la especificación; ampliar requiere decisión de producto. */
    static final Set<String> DISTANCE_UNITS = Set.of("km");

    static final Set<String> ELEVATION_UNITS = Set.of("m");

    record Preferences(
            String locale, String timeZone, String distanceUnit, String elevationUnit, int trackPrivacyMeters) {

        static Preferences of(UserRepository.Profile profile) {
            return new Preferences(
                    profile.locale(),
                    profile.timeZone(),
                    profile.distanceUnit(),
                    profile.elevationUnit(),
                    profile.trackPrivacyMeters());
        }
    }

    record Me(UUID id, String email, Preferences preferences) {}

    record PreferencesUpdate(
            @Nullable String timeZone,
            @Nullable String distanceUnit,
            @Nullable String elevationUnit,
            @Nullable Integer trackPrivacyMeters) {}

    private final UserRepository users;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    MeController(UserRepository users, Clock clock, ApplicationEventPublisher events) {
        this.users = users;
        this.clock = clock;
        this.events = events;
    }

    @GetMapping
    Me me(@AuthenticationPrincipal AuthenticatedUser user) {
        var profile = profile(user);
        return new Me(profile.id(), profile.email(), Preferences.of(profile));
    }

    @PatchMapping("/preferences")
    @Transactional
    Preferences updatePreferences(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestBody PreferencesUpdate update) {
        if (update.timeZone() == null
                && update.distanceUnit() == null
                && update.elevationUnit() == null
                && update.trackPrivacyMeters() == null) {
            throw new EmptyPreferencesUpdateException();
        }
        if (update.timeZone() != null && !isIanaZone(update.timeZone())) {
            throw new InvalidTimeZoneException();
        }
        if ((update.distanceUnit() != null && !DISTANCE_UNITS.contains(update.distanceUnit()))
                || (update.elevationUnit() != null && !ELEVATION_UNITS.contains(update.elevationUnit()))) {
            throw new UnsupportedUnitException();
        }
        if (update.trackPrivacyMeters() != null
                && !UserTrackPrivacy.ALLOWED_METERS.contains(update.trackPrivacyMeters())) {
            throw new InvalidTrackPrivacyException();
        }
        var previousPrivacy = profile(user).trackPrivacyMeters();
        if (!users.updatePreferences(
                user.userId(),
                update.timeZone(),
                update.distanceUnit(),
                update.elevationUnit(),
                update.trackPrivacyMeters(),
                clock.instant())) {
            throw new UnauthenticatedException();
        }
        if (update.trackPrivacyMeters() != null && update.trackPrivacyMeters() != previousPrivacy) {
            events.publishEvent(new TrackPrivacyChanged(user.userId(), update.trackPrivacyMeters()));
        }
        return Preferences.of(profile(user));
    }

    /** Solo identificadores de región IANA (p. ej. America/Mexico_City); no desplazamientos fijos como +05:00. */
    private static boolean isIanaZone(String value) {
        return ZoneId.getAvailableZoneIds().contains(value);
    }

    /** Un token vigente de un usuario borrado no debería existir (cascada), pero se trata como no autenticado. */
    private UserRepository.Profile profile(AuthenticatedUser user) {
        return users.findProfile(user.userId()).orElseThrow(UnauthenticatedException::new);
    }
}
