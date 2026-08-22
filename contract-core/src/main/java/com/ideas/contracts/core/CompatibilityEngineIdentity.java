package com.ideas.contracts.core;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Properties;

/** Stable identity data carried with compatibility evidence. */
public final class CompatibilityEngineIdentity {
  /** Increment only when equivalent inputs can produce different compatibility outcomes. */
  public static final String COMPATIBILITY_PROTOCOL = "1";

  private CompatibilityEngineIdentity() {}

  public static String engineVersion() {
    Package packageInfo = DefaultContractEngine.class.getPackage();
    String version = packageInfo == null ? null : packageInfo.getImplementationVersion();
    if (version != null && !version.isBlank()) {
      return version.trim();
    }
    try (InputStream input = DefaultContractEngine.class.getClassLoader().getResourceAsStream(
        "META-INF/maven/com.ideas.contracts/contract-core/pom.properties")) {
      if (input != null) {
        Properties properties = new Properties();
        properties.load(input);
        String mavenVersion = properties.getProperty("version");
        if (mavenVersion != null && !mavenVersion.isBlank()) {
          return mavenVersion.trim();
        }
      }
    } catch (Exception ignored) {
      // A development classes directory has no Maven metadata; use the explicit dev fallback.
    }
    return "0.1.0-SNAPSHOT";
  }

  public static String policyPackSha256(PolicyPack policyPack) {
    if (policyPack == null) {
      throw new IllegalArgumentException("policyPack must not be null.");
    }
    String canonicalRules = policyPack.rules().entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().name()))
        .map(entry -> entry.getKey().name() + "=" + entry.getValue().name())
        .reduce("name=" + policyPack.name().trim() + "\n", (left, right) -> left + right + "\n");
    return sha256(canonicalRules.getBytes(StandardCharsets.UTF_8));
  }

  public static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }
}
