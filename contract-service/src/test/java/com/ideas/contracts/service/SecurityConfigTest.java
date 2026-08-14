package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SecurityConfigTest {

  @Test
  void sharedProfileRejectsDefaultCredentials() {
    assertThrows(
        IllegalStateException.class,
        () -> SecurityConfig.validateCredentials(true, true, "admin", "change-me"));
  }

  @Test
  void sharedProfileRequiresBothCredentials() {
    assertThrows(
        IllegalStateException.class,
        () -> SecurityConfig.validateCredentials(true, true, "operator", " "));
  }

  @Test
  void localProfileCanKeepDemoDefaults() {
    assertDoesNotThrow(
        () -> SecurityConfig.validateCredentials(true, false, "admin", "change-me"));
  }

  @Test
  void sharedProfileAcceptsExplicitNonDefaultCredentials() {
    assertDoesNotThrow(
        () -> SecurityConfig.validateCredentials(true, true, "operator", "strong-demo-password"));
  }
}
