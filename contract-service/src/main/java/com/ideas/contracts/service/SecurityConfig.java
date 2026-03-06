package com.ideas.contracts.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
      @Value("${app.security.enabled:false}") boolean securityEnabled) throws Exception {
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
            .requestMatchers("/ui/**", "/checks/**").authenticated()
            .anyRequest().permitAll())
        .httpBasic(Customizer.withDefaults())
        .formLogin(form -> form.disable());

    return http.build();
  }

  @Bean
  public UserDetailsService userDetailsService(
      @Value("${app.security.username:admin}") String username,
      @Value("${app.security.password:change-me}") String password) {
    return new InMemoryUserDetailsManager(
        User.withUsername(username)
            .password("{noop}" + password)
            .roles("USER")
            .build());
  }
}
