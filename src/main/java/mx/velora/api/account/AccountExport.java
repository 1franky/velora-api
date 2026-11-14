package mx.velora.api.account;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import mx.velora.api.account.AccountProblems.ExportInProgressException;
import mx.velora.api.activity.ActivityExport;
import mx.velora.api.catalog.CatalogExport;
import mx.velora.api.identity.UserAccounts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exportación completa de los datos del usuario como ZIP (ADR-028), escrita directamente en la respuesta: no queda
 * copia en el servidor. Se lee en una transacción de solo lectura REPEATABLE READ para que el ZIP sea una foto
 * consistente aunque haya importaciones en curso. Una sola exportación a la vez por usuario.
 */
@Service
class AccountExport {

    static final String FORMAT = "velora-export/1";

    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final UserAccounts accounts;
    private final CatalogExport catalog;
    private final ActivityExport activities;
    private final JsonMapper json;
    private final TransactionTemplate snapshot;
    private final Clock clock;

    AccountExport(
            UserAccounts accounts,
            CatalogExport catalog,
            ActivityExport activities,
            JsonMapper json,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.accounts = accounts;
        this.catalog = catalog;
        this.activities = activities;
        this.json = json;
        this.clock = clock;
        this.snapshot = new TransactionTemplate(transactions);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Reserva la exportación del usuario; liberarla con {@link #release(UUID)}. */
    void acquire(UUID userId) {
        if (!running.add(userId)) {
            throw new ExportInProgressException();
        }
    }

    void release(UUID userId) {
        running.remove(userId);
    }

    void write(UUID userId, OutputStream out) throws IOException {
        try {
            snapshot.executeWithoutResult(status -> {
                try (var zip = new ZipOutputStream(out)) {
                    writeAll(userId, zip);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private void writeAll(UUID userId, ZipOutputStream zip) throws IOException {
        var profile = accounts.requireProfile(userId);
        var bikes = catalog.bikes(userId);
        var devices = catalog.devices(userId);
        put(zip, "profile.json", profile);
        put(zip, "bikes.json", bikes);
        put(zip, "devices.json", devices);
        var activityCount = activities.writeTo(userId, zip, "activities");

        var counts = new LinkedHashMap<String, Integer>();
        counts.put("activities", activityCount);
        counts.put("bikes", bikes.size());
        counts.put("devices", devices.size());
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("format", FORMAT);
        manifest.put("generatedAt", clock.instant());
        manifest.put("userId", userId);
        manifest.put("counts", counts);
        manifest.put(
                "notes",
                Map.of(
                        "track", "Recorrido completo, sin simplificar ni recortar por privacidad.",
                        "excluded", "Celdas de exploración, mapa de calor e historial de reprocesos: se recalculan."));
        put(zip, "manifest.json", manifest);
    }

    private void put(ZipOutputStream zip, String path, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        zip.closeEntry();
    }
}
