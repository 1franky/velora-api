package mx.velora.api.activity;

import java.io.IOException;
import java.util.UUID;
import mx.velora.api.platform.logging.RedactedException;
import mx.velora.api.platform.storage.OriginalFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Borra una actividad con todo lo que depende de ella ({@code 02}: retención y borrado). Las filas caen en cascada;
 * el archivo original se borra solo después de confirmar la transacción, para no perderlo si la transacción falla.
 */
@Service
public class ActivityDeletion {

    private static final Logger log = LoggerFactory.getLogger(ActivityDeletion.class);

    private final JdbcClient jdbc;
    private final OriginalFileStore store;

    ActivityDeletion(JdbcClient jdbc, OriginalFileStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** @return {@code false} si la actividad no existe o es de otro usuario */
    @Transactional
    public boolean delete(UUID userId, UUID activityId) {
        var storageKeys = jdbc.sql("SELECT storage_key FROM activity_files WHERE activity_id = :id AND user_id = :user")
                .param("id", activityId)
                .param("user", userId)
                .query(String.class)
                .list();
        var deleted = jdbc.sql("DELETE FROM activities WHERE id = :id AND user_id = :user")
                .param("id", activityId)
                .param("user", userId)
                .update();
        if (deleted == 0) {
            return false;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (var key : storageKeys) {
                    try {
                        store.delete(key);
                    } catch (IOException | RuntimeException e) {
                        // La actividad ya no existe; el archivo queda huérfano y no es accesible por la API.
                        log.warn(
                                "No se pudo borrar el original de la actividad {}",
                                activityId,
                                RedactedException.of(e));
                    }
                }
            }
        });
        return true;
    }
}
