package com.ideas.contracts.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(EvidenceAuthProperties.class)
public class SecurityConfig {
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Value("${app.security.enabled:false}") boolean securityEnabled,
      @Value("${app.security.write-role:WRITER}") String writeRole,
      @Value("${app.security.operations-role:OPERATIONS}") String operationsRole,
      @Value("${app.security.retention-role:RETENTION_ADMIN}") String retentionRole,
      EvidenceAuthProperties evidenceAuth,
      ObjectProvider<JwtDecoder> configuredJwtDecoder) throws Exception {
    http.csrf(csrf -> csrf.disable());
    evidenceAuth.validate();
    EvidenceAuthProperties.Mode evidenceMode = evidenceAuth.getMode();

    http.authorizeHttpRequests(auth -> {
      auth.requestMatchers(HttpMethod.POST, "/checks/evidence/retention/run")
          .hasRole(normalizeRole(retentionRole, "RETENTION_ADMIN"));
      switch (evidenceMode) {
        case DISABLED -> auth.requestMatchers("/checks/evidence").denyAll();
        case BASIC -> auth.requestMatchers("/checks/evidence")
            .hasRole(normalizeRole(writeRole, "WRITER"));
        case OIDC -> auth.requestMatchers("/checks/evidence").authenticated();
      }
      if (!securityEnabled) {
        auth.anyRequest().permitAll();
        return;
      }
      auth
          .requestMatchers("/actuator/health", "/actuator/info").permitAll()
          .requestMatchers("/actuator/metrics/**", "/actuator/prometheus")
          .hasRole(normalizeRole(operationsRole, "OPERATIONS"))
          .requestMatchers(HttpMethod.POST, "/checks/**", "/ui/**", "/contracts/**",
              "/api/notification-deliveries/**")
          .hasRole(normalizeRole(writeRole, "WRITER"))
          .requestMatchers(HttpMethod.PUT, "/checks/**", "/ui/**", "/contracts/**",
              "/api/notification-deliveries/**")
          .hasRole(normalizeRole(writeRole, "WRITER"))
          .requestMatchers(HttpMethod.PATCH, "/checks/**", "/ui/**", "/contracts/**",
              "/api/notification-deliveries/**")
          .hasRole(normalizeRole(writeRole, "WRITER"))
          .requestMatchers(HttpMethod.DELETE, "/checks/**", "/ui/**", "/contracts/**",
              "/api/notification-deliveries/**")
          .hasRole(normalizeRole(writeRole, "WRITER"))
          .requestMatchers("/ui/**", "/checks/**", "/runs/**",
              "/api/notification-deliveries", "/api/notification-deliveries/**")
          .authenticated()
          .anyRequest().permitAll();
    });

    if (evidenceMode == EvidenceAuthProperties.Mode.OIDC) {
      JwtDecoder decoder = configuredJwtDecoder.getIfAvailable(
          () -> JwtDecoders.fromIssuerLocation(evidenceAuth.getOidc().getIssuerUri()));
      http.oauth2ResourceServer(oauth -> oauth.jwt(jwt ->
          jwt.decoder(audienceValidatingDecoder(decoder, evidenceAuth.getOidc().getAudience()))));
    }
    if (securityEnabled || evidenceMode == EvidenceAuthProperties.Mode.BASIC) {
      http.httpBasic(Customizer.withDefaults());
    } else {
      http.httpBasic(httpBasic -> httpBasic.disable());
    }
    http.formLogin(form -> form.disable());

    return http.build();
  }

  private JwtDecoder audienceValidatingDecoder(JwtDecoder delegate, String requiredAudience) {
    return token -> {
      Jwt jwt = delegate.decode(token);
      if (jwt.getAudience().contains(requiredAudience)) {
        return jwt;
      }
      throw new JwtValidationException(
          "JWT audience does not authorize DCG evidence ingestion.",
          java.util.List.of(new OAuth2Error("invalid_token", "Required audience is missing.", null)));
    };
  }

  @Bean
  public UserDetailsService userDetailsService(
      @Value("${app.security.enabled:false}") boolean securityEnabled,
      @Value("${app.security.require-non-default-credentials:false}") boolean requireNonDefaultCredentials,
      @Value("${app.security.username:admin}") String username,
      @Value("${app.security.password:change-me}") String password,
      @Value("${app.security.roles:USER,WRITER}") String roles) {
    validateCredentials(securityEnabled, requireNonDefaultCredentials, username, password);
    return new InMemoryUserDetailsManager(
        User.withUsername(username)
            .password("{noop}" + password)
            .roles(parseRoles(roles))
            .build());
  }

  static void validateCredentials(
      boolean securityEnabled,
      boolean requireNonDefaultCredentials,
      String username,
      String password) {
    if (!securityEnabled || !requireNonDefaultCredentials) {
      return;
    }
    if (username == null || username.isBlank() || password == null || password.isBlank()) {
      throw new IllegalStateException(
          "Shared profiles require APP_SECURITY_USERNAME and APP_SECURITY_PASSWORD to be set.");
    }
    if ("admin".equals(username.trim()) || "change-me".equals(password)) {
      throw new IllegalStateException(
          "Shared profiles must not use the default app security credentials. Set unique APP_SECURITY_USERNAME and APP_SECURITY_PASSWORD values.");
    }
  }

  private String[] parseRoles(String roles) {
    if (roles == null || roles.isBlank()) {
      return new String[] {"USER"};
    }
    Set<String> normalized = new LinkedHashSet<>();
    Arrays.stream(roles.split(","))
        .map(String::trim)
        .filter(role -> !role.isBlank())
        .map(role -> normalizeRole(role, null))
        .filter(role -> role != null && !role.isBlank())
        .forEach(normalized::add);
    if (normalized.isEmpty()) {
      normalized.add("USER");
    }
    return normalized.toArray(new String[0]);
  }

  private String normalizeRole(String role, String fallback) {
    if (role == null || role.isBlank()) {
      return fallback;
    }
    String trimmed = role.trim();
    if (trimmed.startsWith("ROLE_")) {
      return trimmed.substring("ROLE_".length());
    }
    return trimmed;
  }
}
