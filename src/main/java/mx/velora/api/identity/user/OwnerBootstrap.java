package mx.velora.api.identity.user;

import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea el usuario propietario una sola vez (ADR-010). No existe registro público: si ya hay un usuario, falla.
 *
 * <p>Los mensajes de error nunca incluyen la contraseña.
 */
@Service
public class OwnerBootstrap {

    static final int MIN_PASSWORD_LENGTH = 12;
    static final int MAX_PASSWORD_LENGTH = 1024;

    /** Validación básica de forma; la verificación real es poder iniciar sesión. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    OwnerBootstrap(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public UUID createOwner(@Nullable String email, @Nullable String password) {
        if (email == null
                || email.isBlank()
                || email.strip().length() > 254
                || !EMAIL.matcher(email.strip()).matches()) {
            throw new IllegalArgumentException("VELORA_OWNER_EMAIL no es un correo válido");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("VELORA_OWNER_PASSWORD debe tener entre " + MIN_PASSWORD_LENGTH + " y "
                    + MAX_PASSWORD_LENGTH + " caracteres");
        }
        if (users.anyUserExists()) {
            throw new IllegalStateException("Ya existe un usuario; el propietario solo se crea una vez");
        }
        var id = UUID.randomUUID();
        users.insert(id, email.strip(), passwordEncoder.encode(password), clock.instant());
        return id;
    }
}
