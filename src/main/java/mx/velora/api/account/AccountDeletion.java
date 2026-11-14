package mx.velora.api.account;

import java.io.IOException;
import java.util.UUID;
import mx.velora.api.identity.UserAccounts;
import mx.velora.api.platform.logging.RedactedException;
import mx.velora.api.platform.storage.OriginalFileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Borrado total de la cuenta (ADR-028). Las filas caen en cascada desde {@code users} en una transacción; los
 * originales se borran después de confirmarla, para no perderlos si la transacción falla. Las copias en los
 * respaldos cifrados expiran según su retención.
 */
@Service
class AccountDeletion {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletion.class);

    private final UserAccounts accounts;
    private final OriginalFileStore store;

    AccountDeletion(UserAccounts accounts, OriginalFileStore store) {
        this.accounts = accounts;
        this.store = store;
    }

    @Transactional
    public void delete(UUID userId, String password, String clientAddress) {
        accounts.confirmPassword(userId, password, clientAddress);
        accounts.delete(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    store.deleteAll(userId);
                } catch (IOException | RuntimeException e) {
                    // Los datos ya no existen; los archivos que queden no son accesibles por la API.
                    log.error("No se pudieron borrar los originales del usuario {}", userId, RedactedException.of(e));
                }
            }
        });
        log.info("Cuenta {} borrada por su titular", userId);
    }
}
