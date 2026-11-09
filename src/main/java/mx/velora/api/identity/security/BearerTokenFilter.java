package mx.velora.api.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import mx.velora.api.identity.AuthenticatedUser;
import mx.velora.api.identity.auth.TokenCodec;
import mx.velora.api.identity.auth.TokenRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica {@code Authorization: Bearer <token>} contra los access tokens vigentes. Un token inválido deja la
 * solicitud sin autenticar; la autorización decide si el recurso lo requiere (401) o es público.
 *
 * <p>No es un bean: se registra solo en la cadena de Spring Security, no como filtro de servlet global.
 */
final class BearerTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final TokenRepository tokens;
    private final Clock clock;

    BearerTokenFilter(TokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            var token = header.substring(PREFIX.length()).strip();
            if (!token.isEmpty() && token.length() <= 512) {
                tokens.findUserByActiveAccessToken(TokenCodec.hash(token), clock.instant())
                        .ifPresent(userId -> SecurityContextHolder.getContext()
                                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                                        new AuthenticatedUser(userId), null, List.of())));
            }
        }
        chain.doFilter(request, response);
    }
}
