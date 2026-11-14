package mx.velora.api.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Archivos originales de actividad (ADR-013). Las claves son opacas y las genera el almacén; nunca contienen nombres
 * de archivo del usuario ni se exponen por la API.
 */
public interface OriginalFileStore {

    /**
     * Guarda el contenido completo de forma atómica, calculando SHA-256 y tamaño mientras escribe.
     *
     * @throws FileTooLargeException si supera el tamaño máximo; no queda nada guardado
     */
    StoredFile store(UUID userId, InputStream content) throws IOException;

    /** Abre el archivo; al terminar de leerlo verifica que su SHA-256 coincide y, si no, lanza {@link IOException}. */
    InputStream open(StoredFile file) throws IOException;

    /** Idempotente: borrar una clave inexistente no falla. */
    void delete(String storageKey) throws IOException;

    /**
     * Borra todos los originales del usuario, incluidos los que hayan quedado huérfanos. Idempotente: un usuario sin
     * archivos no falla.
     */
    void deleteAll(UUID userId) throws IOException;

    /**
     * @param storageKey clave opaca
     * @param sha256 hash del contenido (32 bytes)
     * @param sizeBytes tamaño en bytes
     */
    record StoredFile(String storageKey, byte[] sha256, long sizeBytes) {

        public StoredFile {
            sha256 = sha256.clone();
            if (sha256.length != 32) {
                throw new IllegalArgumentException("sha256 debe tener 32 bytes");
            }
        }

        @Override
        public byte[] sha256() {
            return sha256.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StoredFile that
                    && storageKey.equals(that.storageKey)
                    && java.util.Arrays.equals(sha256, that.sha256)
                    && sizeBytes == that.sizeBytes;
        }

        @Override
        public int hashCode() {
            return storageKey.hashCode();
        }

        @Override
        public String toString() {
            return "StoredFile[" + storageKey + ", " + sizeBytes + " bytes]";
        }
    }

    /** El contenido supera el tamaño máximo configurado. */
    final class FileTooLargeException extends IOException {
        public FileTooLargeException(long maxBytes) {
            super("El archivo supera el tamaño máximo de " + maxBytes + " bytes");
        }
    }
}
