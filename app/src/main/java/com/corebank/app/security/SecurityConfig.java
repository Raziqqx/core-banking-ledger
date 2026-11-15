package com.corebank.app.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT resource server. Coarse role rules live here; per-account ownership rules live in
 * {@code AccountAccess}, because they need the account row.
 *
 * <p>The JWT decoder comes from the {@code dev} profile ({@link DevTokenIssuer}) or, in any other
 * profile, from {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}/{@code jwk-set-uri};
 * with neither the app fails at startup rather than running open.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http, ProblemSecurityHandlers problems) throws Exception {
    http
        // Bearer tokens in a header, no cookies or sessions: CSRF does not apply.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error")
            .permitAll()
            // Probes and the Prometheus scrape. Only health/info/prometheus are exposed; in a
            // cluster the scrape path is reachable inside the network only (see DECISIONS.md).
            .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health",
                "/actuator/info", "/actuator/prometheus")
            .permitAll()
            // Only mapped under the dev profile; a 404 everywhere else.
            .requestMatchers(HttpMethod.POST, "/dev/token").permitAll()
            .requestMatchers(HttpMethod.POST, "/accounts", "/accounts/*/deposits", "/transfers")
            .hasRole("ADMIN")
            .requestMatchers("/ledger/**", "/notifications/**").hasRole("ADMIN")
            .anyRequest().hasAnyRole("ADMIN", "CUSTOMER"))
        .oauth2ResourceServer(o -> o
            .jwt(j -> j.jwtAuthenticationConverter(rolesFromClaim()))
            .authenticationEntryPoint(problems)
            .accessDeniedHandler(problems))
        .exceptionHandling(e -> e.authenticationEntryPoint(problems).accessDeniedHandler(problems));
    return http.build();
  }

  /** Maps the {@code roles} claim (e.g. ["CUSTOMER"]) to ROLE_* authorities. */
  static JwtAuthenticationConverter rolesFromClaim() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter c = new JwtAuthenticationConverter();
    c.setJwtGrantedAuthoritiesConverter(roles);
    return c;
  }
}
