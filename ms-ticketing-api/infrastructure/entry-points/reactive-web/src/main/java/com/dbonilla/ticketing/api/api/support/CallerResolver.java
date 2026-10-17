package com.dbonilla.ticketing.api.api.support;

import com.dbonilla.ticketing.api.model.order.Caller;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.server.ServerRequest;
import reactor.core.publisher.Mono;

/**
 * Builds the domain {@link Caller} from the validated JWT ({@code sub} and scopes).
 */
public final class CallerResolver {

    public static final String USER_AUTHORITY = "SCOPE_ticketing:user";
    public static final String ADMIN_AUTHORITY = "SCOPE_ticketing:admin";

    private CallerResolver() {
    }

    public static Mono<Caller> resolve(ServerRequest request) {
        return request.principal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(CallerResolver::toCaller)
                .switchIfEmpty(Mono.error(() -> new AuthenticationCredentialsNotFoundException("No JWT principal")));
    }

    private static Caller toCaller(JwtAuthenticationToken token) {
        boolean admin = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
        return new Caller(token.getToken().getSubject(), admin);
    }
}
