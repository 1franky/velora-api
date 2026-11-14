package mx.velora.api.platform.storage.local;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import mx.velora.api.platform.storage.OriginalFileStore;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * Originales en disco local (ADR-013): {@code <raíz>/<userId>/<uuid>}. Escritura en un temporal del mismo volumen,
 * {@code fsync} y renombrado atómico; permisos solo para el usuario del proceso cuando el sistema lo permite.
 */
@Component
class LocalOriginalFileStore implements OriginalFileStore {

    /** Formato de clave: impide rutas arbitrarias (p. ej. {@code ../}). */
    private static final Pattern KEY = Pattern.compile("^[0-9a-f-]{36}/[0-9a-f-]{36}$");

    @ConfigurationProperties("velora.storage")
    record StorageProperties(
            @DefaultValue("data/originals") Path originalsPath,
            @DefaultValue("50MB") DataSize maxFileSize) {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageProperties.class)
    static class StorageConfiguration {}

    private final Path root;
    private final long maxBytes;

    LocalOriginalFileStore(StorageProperties properties) throws IOException {
        this.root = properties.originalsPath().toAbsolutePath().normalize();
        this.maxBytes = properties.maxFileSize().toBytes();
        createPrivateDirectories(root.resolve(".tmp"));
    }

    @Override
    public StoredFile store(UUID userId, InputStream content) throws IOException {
        var key = userId + "/" + UUID.randomUUID();
        var temp = Files.createTempFile(root.resolve(".tmp"), "upload-", ".part");
        try {
            var digest = sha256();
            long size = 0;
            try (var out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var buffer = new byte[64 * 1024];
                int read;
                while ((read = content.read(buffer)) != -1) {
                    size += read;
                    if (size > maxBytes) {
                        throw new FileTooLargeException(maxBytes);
                    }
                    digest.update(buffer, 0, read);
                    out.write(java.nio.ByteBuffer.wrap(buffer, 0, read));
                }
                out.force(true);
            }
            if (size == 0) {
                throw new IOException("El archivo está vacío");
            }
            var target = resolve(key);
            createPrivateDirectories(target.getParent());
            restrictToOwner(temp);
            move(temp, target);
            return new StoredFile(key, digest.digest(), size);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public InputStream open(StoredFile file) throws IOException {
        var expected = file.sha256();
        var digesting = new DigestInputStream(Files.newInputStream(resolve(file.storageKey())), sha256());
        return new FilterInputStream(digesting) {
            private boolean verified;

            @Override
            public int read() throws IOException {
                var b = super.read();
                verifyAtEnd(b == -1);
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                var n = super.read(buffer, offset, length);
                verifyAtEnd(n == -1);
                return n;
            }

            private void verifyAtEnd(boolean end) throws IOException {
                if (end && !verified) {
                    verified = true;
                    if (!MessageDigest.isEqual(
                            expected, digesting.getMessageDigest().digest())) {
                        throw new IOException("El original no coincide con su SHA-256: " + file.storageKey());
                    }
                }
            }
        };
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    @Override
    public void deleteAll(UUID userId) throws IOException {
        var dir = root.resolve(userId.toString()).normalize();
        if (!dir.startsWith(root) || !Files.isDirectory(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            // Del más profundo al más superficial: los archivos antes que su carpeta.
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path resolve(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Clave de almacenamiento inválida");
        }
        var path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Clave de almacenamiento inválida");
        }
        return path;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("El almacenamiento debe permitir renombrado atómico en el mismo volumen", e);
        }
    }

    private static void createPrivateDirectories(Path dir) throws IOException {
        try {
            Files.createDirectories(
                    dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir);
        } catch (FileAlreadyExistsException e) {
            // Carrera entre hilos creando el mismo directorio: no es un error.
        }
    }

    private static void restrictToOwner(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Sistemas sin POSIX (Windows en desarrollo): se usan los permisos por defecto.
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
