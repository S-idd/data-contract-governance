package com.ideas.contracts.service;

import com.ideas.contracts.service.model.EvidenceImportRequest;
import java.util.Collection;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Enforces contract-specific CI workload identity policy after JWT signature validation. */
@Service
public class EvidenceAuthorizationService {
  private final EvidenceAuthProperties properties;

  public EvidenceAuthorizationService(EvidenceAuthProperties properties) {
    this.properties = properties;
  }

  public EvidenceProvenance authorize(Authentication authentication, EvidenceImportRequest evidence) {
    if (authentication == null || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getName())) {
      throw unauthorized("AUTH_FAILED: authenticated CI identity is required.");
    }
    return switch (properties.getMode()) {
      case DISABLED -> throw unauthorized("AUTH_FAILED: evidence ingestion is disabled.");
      case BASIC -> basicProvenance(authentication);
      case OIDC -> oidcProvenance(authentication, evidence);
    };
  }

  private EvidenceProvenance basicProvenance(Authentication authentication) {
    if (authentication instanceof JwtAuthenticationToken) {
      throw unauthorized("AUTH_FAILED: this environment only permits local Basic authentication.");
    }
    return new EvidenceProvenance("BASIC", authentication.getName(), null, authentication.getName(), null, null, null);
  }

  private EvidenceProvenance oidcProvenance(Authentication authentication, EvidenceImportRequest evidence) {
    if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
      throw unauthorized("AUTH_FAILED: OIDC bearer authentication is required for evidence ingestion.");
    }
    var jwt = jwtAuthentication.getToken();
    String issuer = jwt.getIssuer() == null ? null : jwt.getIssuer().toString();
    String subject = jwt.getSubject();
    String repository = jwt.getClaimAsString(properties.getOidc().getRepositoryClaim());
    String ref = jwt.getClaimAsString(properties.getOidc().getRefClaim());
    if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()
        || repository == null || repository.isBlank() || ref == null || ref.isBlank()) {
      throw unauthorized("AUTH_FAILED: required OIDC provenance claims are missing.");
    }
    if (!properties.getOidc().getTrustedIssuers().contains(issuer)) {
      throw unauthorized("AUTH_FAILED: token issuer is not trusted for evidence ingestion.");
    }
    EvidenceAuthProperties.ContractAuthorization rule = matchingRule(evidence.contractId());
    if (rule == null || !rule.getRepositories().contains(repository) || !rule.getBranches().contains(ref)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "CI identity is not authorized to submit evidence for contract " + evidence.contractId() + ".");
    }
    return new EvidenceProvenance(
        "OIDC", issuer + ":" + subject, issuer, subject,
        String.join(",", audience(jwt.getAudience())), repository, ref);
  }

  private EvidenceAuthProperties.ContractAuthorization matchingRule(String contractId) {
    return properties.getOidc().getContractAuthorizations().stream()
        .filter(rule -> rule != null && contractId.equals(rule.getContractId()))
        .findFirst()
        .orElse(null);
  }

  private List<String> audience(Collection<String> values) {
    return values == null ? List.of() : values.stream().sorted().toList();
  }

  private ResponseStatusException unauthorized(String message) {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
  }
}
