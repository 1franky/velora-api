package mx.velora.api.identity.security;

import mx.velora.api.identity.auth.AuthProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Beans de identidad que no dependen del servidor web; los usa también el comando de bootstrap. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class PasswordConfiguration {

    /**
     * Argon2id con los parámetros mínimos recomendados por OWASP: 19 MiB de memoria, 2 iteraciones, paralelismo 1;
     * sal de 16 bytes y hash de 32 bytes.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);
    }
}
