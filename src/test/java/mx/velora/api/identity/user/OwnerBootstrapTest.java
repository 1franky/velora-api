package mx.velora.api.identity.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import mx.velora.api.IntegrationTest;
import mx.velora.api.TestSessions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class OwnerBootstrapTest {

    private static final String PASSWORD = "contraseña-del-propietario";

    @Autowired
    OwnerBootstrap bootstrap;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanUsers() {
        TestSessions.resetUsers(jdbc);
    }

    @Test
    void createsOwnerWithArgon2idHashAndDefaultPreferences() {
        var id = bootstrap.createOwner("  Propietario@Example.com ", PASSWORD);

        var row = jdbc.sql(
                        "SELECT email, password_hash, locale, time_zone, distance_unit, elevation_unit FROM users WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new String[] {
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)
                })
                .single();
        assertThat(row[0]).isEqualTo("Propietario@Example.com");
        assertThat(row[1]).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").doesNotContain(PASSWORD);
        assertThat(row[2]).isEqualTo("es-MX");
        assertThat(row[3]).isEqualTo("America/Mexico_City");
        assertThat(row[4]).isEqualTo("km");
        assertThat(row[5]).isEqualTo("m");
    }

    @Test
    void refusesToCreateASecondUser() {
        bootstrap.createOwner("propietario@example.com", PASSWORD);

        assertThatThrownBy(() -> bootstrap.createOwner("otro@example.com", PASSWORD))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsShortPasswordWithoutEchoingIt() {
        assertThatThrownBy(() -> bootstrap.createOwner("propietario@example.com", "corta"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("corta");
    }

    @Test
    void rejectsInvalidEmail() {
        assertThatThrownBy(() -> bootstrap.createOwner("no-es-correo", PASSWORD))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap.createOwner(null, PASSWORD)).isInstanceOf(IllegalArgumentException.class);
    }
}
