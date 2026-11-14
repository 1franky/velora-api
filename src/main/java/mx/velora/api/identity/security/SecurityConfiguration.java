package mx.velora.api.identity.security;

import java.time.Clock;
import mx.velora.api.identity.auth.AuthProblems.AccessDeniedProblemException;
import mx.velora.api.identity.auth.AuthProblems.UnauthenticatedException;
import mx.velora.api.identity.auth.TokenRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * API sin estado: solo {@code Authorization: Bearer}, sin cookies ni sesiones HTTP, por lo que CSRF no aplica
 * (ADR-010). Los 401/403 se resuelven con el mismo manejo de errores de Spring MVC para obtener Problem Details en
 * es-MX. Solo aplica a aplicaciones web; el comando de bootstrap corre sin servidor web.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TokenRepository tokens,
            Clock clock,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout")
                        .permitAll()
                        // Salud pública para el monitoreo. Actuator vive en el puerto de administración, que no se
                        // publica en el host: ahí no hay sesión que pedir.
                        .requestMatchers("/healthz", "/actuator/**")
                        .permitAll()
                        .requestMatchers("/error")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
                                resolver.resolveException(request, response, null, new UnauthenticatedException()))
                        .accessDeniedHandler((request, response, exception) ->
                                resolver.resolveException(request, response, null, new AccessDeniedProblemException())))
                .addFilterBefore(new BearerTokenFilter(tokens, clock), AnonymousAuthenticationFilter.class)
                .build();
    }
}
