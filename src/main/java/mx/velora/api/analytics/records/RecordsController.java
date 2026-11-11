package mx.velora.api.analytics.records;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import mx.velora.api.activity.ActivitySubType;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.identity.UserTimeZones;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/analytics/records} y {@code /analytics/eddington} según el contrato (ADR-020). */
@RestController
class RecordsController {

    record ActivityRecord(
            String kind,
            double value,
            UUID activityId,
            String title,
            LocalDate localStartDate,
            @Nullable String source) {}

    record DayRecord(LocalDate date, double distanceMeters, long activityCount) {}

    record Records(
            String algorithmVersion,
            boolean includeIndoor,
            double minSpeedDistanceMeters,
            double minSpeedMovingSeconds,
            List<ActivityRecord> records,
            @Nullable DayRecord biggestDay) {}

    record EddingtonValue(int number, int daysForNext, int missingForNext) {}

    record EddingtonResponse(
            String algorithmVersion,
            boolean includeIndoor,
            int year,
            long daysWithRides,
            EddingtonValue overall,
            EddingtonValue thisYear) {}

    private final PersonalRecords records;
    private final UserTimeZones timeZones;
    private final Clock clock;

    RecordsController(PersonalRecords records, UserTimeZones timeZones, Clock clock) {
        this.records = records;
        this.timeZones = timeZones;
        this.clock = clock;
    }

    @GetMapping("/api/v1/analytics/records")
    Records records(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean includeIndoor,
            @RequestParam(required = false) @Nullable ActivitySubType subType) {
        var filter = new PersonalRecords.Filter(includeIndoor, subType);
        var found = records.records(user.userId(), filter).stream()
                .map(r -> new ActivityRecord(
                        r.kind().name().toLowerCase(Locale.ROOT),
                        r.value(),
                        r.activityId(),
                        r.title(),
                        r.date(),
                        r.source()))
                .toList();
        var day = records.biggestDay(user.userId(), filter)
                .map(d -> new DayRecord(d.date(), d.distanceMeters(), d.activityCount()))
                .orElse(null);
        return new Records(
                PersonalRecords.ALGORITHM_VERSION,
                includeIndoor,
                PersonalRecords.MIN_SPEED_DISTANCE_METERS,
                PersonalRecords.MIN_SPEED_MOVING_SECONDS,
                found,
                day);
    }

    @GetMapping("/api/v1/analytics/eddington")
    EddingtonResponse eddington(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean includeIndoor,
            @RequestParam(required = false) @Nullable ActivitySubType subType) {
        var filter = new PersonalRecords.Filter(includeIndoor, subType);
        var year = LocalDate.now(clock.withZone(timeZones.timeZoneOf(user.userId())))
                .getYear();
        var days = records.dailyKilometers(user.userId(), filter, null);
        var overall = Eddington.of(days);
        var current = Eddington.of(records.dailyKilometers(user.userId(), filter, year));
        return new EddingtonResponse(
                Eddington.ALGORITHM_VERSION,
                includeIndoor,
                year,
                days.size(),
                new EddingtonValue(overall.number(), overall.daysForNext(), overall.missingForNext()),
                new EddingtonValue(current.number(), current.daysForNext(), current.missingForNext()));
    }
}
