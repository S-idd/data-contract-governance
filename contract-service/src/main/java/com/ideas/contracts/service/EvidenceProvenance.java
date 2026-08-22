package com.ideas.contracts.service;

/** Authenticated source facts retained independently of the client-supplied ciIdentity field. */
public record EvidenceProvenance(
    String authenticationScheme,
    String authenticatedIdentity,
    String issuer,
    String subject,
    String audience,
    String repository,
    String ref
) {
  public EvidenceProvenance {
    authenticationScheme = required("authenticationScheme", authenticationScheme);
    authenticatedIdentity = required("authenticatedIdentity", authenticatedIdentity);
    issuer = optional(issuer);
    subject = optional(subject);
    audience = optional(audience);
    repository = optional(repository);
    ref = optional(ref);
  }

  private static String required(String name, String value) {
    String normalized = optional(value);
    if (normalized == null) {
      throw new IllegalArgumentException(name + " must not be blank.");
    }
    return normalized;
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
