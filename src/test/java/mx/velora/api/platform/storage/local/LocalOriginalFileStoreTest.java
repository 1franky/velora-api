package mx.velora.api.platform.storage.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.UUID;
import mx.velora.api.platform.storage.OriginalFileStore.FileTooLargeException;
import mx.velora.api.platform.storage.OriginalFileStore.StoredFile;
import mx.velora.api.platform.storage.local.LocalOriginalFileStore.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class LocalOriginalFileStoreTest {

    private static final UUID USER = UUID.fromString("6f1c3c62-5a0e-4a4e-9a51-0b2c8f8d2f10");
    private static final byte[] CONTENT = "<gpx>contenido sintético</gpx>".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    private LocalOriginalFileStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new LocalOriginalFileStore(new StorageProperties(root, DataSize.ofKilobytes(1)));
    }

    @Test
    void storesWithOpaqueKeyHashAndSize() throws Exception {
        var stored = store.store(USER, new ByteArrayInputStream(CONTENT));

        assertThat(stored.storageKey()).matches(USER + "/[0-9a-f-]{36}");
        assertThat(stored.sizeBytes()).isEqualTo(CONTENT.length);
        assertThat(stored.sha256())
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(CONTENT));
        try (var in = store.open(stored)) {
            assertThat(in.readAllBytes()).isEqualTo(CONTENT);
        }
    }

    @Test
    void storedFilesAreOnlyReadableByTheOwner() throws Exception {
        var stored = store.store(USER, new ByteArrayInputStream(CONTENT));

        var permissions = Files.getPosixFilePermissions(root.resolve(stored.storageKey()));
        assertThat(PosixFilePermissions.toString(permissions)).isEqualTo("rw-------");
    }

    @Test
    void rejectsFilesOverTheLimitWithoutLeavingAnything() throws Exception {
        var big = new byte[2_000];

        assertThatThrownBy(() -> store.store(USER, new ByteArrayInputStream(big)))
                .isInstanceOf(FileTooLargeException.class);
        assertThat(Files.list(root.resolve(".tmp"))).isEmpty();
        assertThat(Files.exists(root.resolve(USER.toString()))).isFalse();
    }

    @Test
    void rejectsEmptyFiles() {
        assertThatThrownBy(() -> store.store(USER, new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(IOException.class);
    }

    @Test
    void detectsTamperedFilesWhenReading() throws Exception {
        var stored = store.store(USER, new ByteArrayInputStream(CONTENT));
        Files.writeString(root.resolve(stored.storageKey()), "alterado");

        try (var in = store.open(stored)) {
            assertThatThrownBy(in::readAllBytes).isInstanceOf(IOException.class).hasMessageContaining("SHA-256");
        }
    }

    @Test
    void rejectsKeysOutsideTheRoot() {
        var malicious = new StoredFile("../../etc/passwd", new byte[32], 1);

        assertThatThrownBy(() -> store.open(malicious)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.delete("../" + USER + "/" + UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        var stored = store.store(USER, new ByteArrayInputStream(CONTENT));

        store.delete(stored.storageKey());
        store.delete(stored.storageKey());

        assertThat(Files.exists(root.resolve(stored.storageKey()))).isFalse();
    }

    @Test
    void deleteAllRemovesOnlyThatUsersFilesAndIsIdempotent() throws Exception {
        var other = UUID.randomUUID();
        var mine = store.store(USER, new ByteArrayInputStream(CONTENT));
        store.store(USER, new ByteArrayInputStream(CONTENT));
        var theirs = store.store(other, new ByteArrayInputStream(CONTENT));

        store.deleteAll(USER);
        store.deleteAll(USER);

        assertThat(root.resolve(USER.toString())).doesNotExist();
        assertThat(Files.exists(root.resolve(mine.storageKey()))).isFalse();
        assertThat(Files.exists(root.resolve(theirs.storageKey()))).isTrue();
        assertThat(root.resolve(".tmp")).isDirectory();
    }
}
