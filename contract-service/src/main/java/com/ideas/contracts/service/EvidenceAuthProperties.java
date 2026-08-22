package com.ideas.contracts.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Production authentication and authorization policy for evidence ingestion. */
@ConfigurationProperties(prefix = "app.security.evidence-auth")
public class EvidenceAuthProperties {
  public enum Mode { DISABLED, BASIC, OIDC }

  private Mode mode = Mode.DISABLED;
  private boolean allowBasic;
  private final Oidc oidc = new Oidc();

  public Mode getMode() { return mode; }
  public void setMode(Mode mode) { this.mode = mode == null ? Mode.DISABLED : mode; }
  public boolean isAllowBasic() { return allowBasic; }
  public void setAllowBasic(boolean allowBasic) { this.allowBasic = allowBasic; }
  public Oidc getOidc() { return oidc; }

  public void validate() {
    if (mode == Mode.BASIC && !allowBasic) {
      throw new IllegalStateException(
          "Basic evidence authentication is disabled. Enable it only in an explicit local/demo profile.");
    }
    if (mode != Mode.OIDC) {
      return;
    }
    if (blank(oidc.issuerUri) || blank(oidc.audience)) {
      throw new IllegalStateException(
          "OIDC evidence authentication requires issuer-uri and audience.");
    }
    if (oidc.trustedIssuers.isEmpty() || containsBlank(oidc.trustedIssuers)
        || new HashSet<>(oidc.trustedIssuers).size() != oidc.trustedIssuers.size()
        || !oidc.trustedIssuers.contains(oidc.issuerUri)) {
      throw new IllegalStateException(
          "OIDC evidence authentication requires a unique trusted-issuers allowlist containing issuer-uri.");
    }
    if (blank(oidc.repositoryClaim) || blank(oidc.refClaim)) {
      throw new IllegalStateException(
          "OIDC evidence authentication requires non-blank repository-claim and ref-claim names.");
    }
    if (oidc.contractAuthorizations.isEmpty()) {
      throw new IllegalStateException(
          "OIDC evidence authentication requires at least one contract authorization rule.");
    }
    Set<String> configuredContracts = new HashSet<>();
    for (ContractAuthorization rule : oidc.contractAuthorizations) {
      if (rule == null || blank(rule.contractId) || rule.repositories.isEmpty() || rule.branches.isEmpty()
          || containsBlank(rule.repositories) || containsBlank(rule.branches)
          || !configuredContracts.add(rule.contractId)) {
        throw new IllegalStateException(
            "Each OIDC contract authorization must have a unique contract-id and non-blank repositories and branches.");
      }
    }
  }

  public static class Oidc {
    private String issuerUri = "";
    private final List<String> trustedIssuers = new ArrayList<>();
    private String audience = "";
    private String repositoryClaim = "repository";
    private String refClaim = "ref";
    private final List<ContractAuthorization> contractAuthorizations = new ArrayList<>();

    public String getIssuerUri() { return issuerUri; }
    public void setIssuerUri(String issuerUri) { this.issuerUri = safe(issuerUri); }
    public List<String> getTrustedIssuers() { return trustedIssuers; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = safe(audience); }
    public String getRepositoryClaim() { return repositoryClaim; }
    public void setRepositoryClaim(String repositoryClaim) { this.repositoryClaim = safe(repositoryClaim); }
    public String getRefClaim() { return refClaim; }
    public void setRefClaim(String refClaim) { this.refClaim = safe(refClaim); }
    public List<ContractAuthorization> getContractAuthorizations() { return contractAuthorizations; }
  }

  public static class ContractAuthorization {
    private String contractId = "";
    private final List<String> repositories = new ArrayList<>();
    private final List<String> branches = new ArrayList<>();

    public String getContractId() { return contractId; }
    public void setContractId(String contractId) { this.contractId = safe(contractId); }
    public List<String> getRepositories() { return repositories; }
    public List<String> getBranches() { return branches; }
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static boolean containsBlank(List<String> values) {
    return values.stream().anyMatch(EvidenceAuthProperties::blank);
  }
  private static String safe(String value) { return value == null ? "" : value.trim(); }
}
