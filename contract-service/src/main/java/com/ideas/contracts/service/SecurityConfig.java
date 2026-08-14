package com.ideas.contracts.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      @Value("${app.security.enabled:false}") boolean securityEnabled,
      @Value("${app.security.write-role:WRITER}") String writeRole) throws Exception {
    http.csrf(csrf -> csrf.disable());

    if (!securityEnabled) {
      http
          .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .httpBasic(httpBasic -> httpBasic.disable())
          .formLogin(form -> form.disable());
      return http.build();
    }

    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/info").permitAll()
            .requestMatchers(HttpMethod.POST, "/checks/**", "/ui/**", "/contracts/**")
            .hasRole(normalizeRole(writeRole, "WRITER"))
            .requestMatchers(HttpMethod.PUT, "/checks/**", "/ui/**", "/contracts/**")
            .hasRole(normalizeRole(writeRole, "WRITER"))
            .requestMatchers(HttpMethod.PATCH, "/checks/**", "/ui/**", "/contracts/**")
            .hasRole(normalizeRole(writeRole, "WRITER"))
            .requestMatchers(HttpMethod.DELETE, "/checks/**", "/ui/**", "/contracts/**")
            .hasRole(normalizeRole(writeRole, "WRITER"))
            .requestMatchers("/ui/**", "/checks/**", "/runs/**", "/api/notification-deliveries")
            .authenticated()
            .anyRequest().permitAll())
        .httpBasic(Customizer.withDefaults())
        .formLogin(form -> form.disable());

    return http.build();
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
