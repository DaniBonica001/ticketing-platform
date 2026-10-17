package com.dbonilla.ticketing.api.api.security;

import com.dbonilla.ticketing.api.api.error.ProblemMapper;
import com.dbonilla.ticketing.api.api.error.ProblemWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity.AuthorizeExchangeSpec;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

import static com.dbonilla.ticketing.api.api.support.CallerResolver.ADMIN_AUTHORITY;
import static com.dbonilla.ticketing.api.api.support.CallerResolver.USER_AUTHORITY;

/**
 * Stateless OAuth2 resource server (ADR-0006): deny by default, scopes per endpoint, Problem Details for
 * 401 and 403. Security headers are written by {@code SecurityHeadersConfig}.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    @Bean
    public ReactiveJwtDecoder jwtDecoder(JwtProperties properties) {
        return JwtDecoderFactory.create(properties);
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder jwtDecoder,
                                                         ProblemWriter problems) {
        ServerAuthenticationEntryPoint entryPoint =
                (exchange, error) -> problems.write(exchange, ProblemMapper.unauthorized());
        ServerAccessDeniedHandler accessDenied =
                (exchange, error) -> problems.write(exchange, ProblemMapper.forbidden());
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .anonymous(ServerHttpSecurity.AnonymousSpec::disable)
                .headers(ServerHttpSecurity.HeaderSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .authorizeExchange(SecurityConfig::authorize)
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .build();
    }

    static void authorize(AuthorizeExchangeSpec exchanges) {
        exchanges
                .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info",
                        "/actuator/prometheus").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/*",
                        "/api/v1/events/*/availability", "/api/v1/events/*/availability/stream").permitAll()
                .pathMatchers(HttpMethod.POST, "/api/v1/events", "/api/v1/events/*/complimentary")
                .hasAuthority(ADMIN_AUTHORITY)
                .pathMatchers(HttpMethod.PUT, "/api/v1/events/*").hasAuthority(ADMIN_AUTHORITY)
                .pathMatchers(HttpMethod.POST, "/api/v1/orders", "/api/v1/orders/*/confirm")
                .hasAuthority(USER_AUTHORITY)
                .pathMatchers(HttpMethod.GET, "/api/v1/orders/*", "/api/v1/orders/*/history")
                .hasAnyAuthority(USER_AUTHORITY, ADMIN_AUTHORITY)
                .anyExchange().denyAll();
    }
}
