package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EvidenceAuthPropertiesTest {
  @Test
  void basicModeRequiresAnExplicitLocalOrDemoOptIn() {
    EvidenceAuthProperties properties = new EvidenceAuthProperties();
    properties.setMode(EvidenceAuthProperties.Mode.BASIC);

    assertThrows(IllegalStateException.class, properties::validate);

    properties.setAllowBasic(true);
    assertDoesNotThrow(properties::validate);
  }

  @Test
  void oidcModeFailsClosedUntilACompleteUniqueContractPolicyIsConfigured() {
    EvidenceAuthProperties properties = configuredOidcPolicy();
    assertDoesNotThrow(properties::validate);

    EvidenceAuthProperties.ContractAuthorization duplicate = new EvidenceAuthProperties.ContractAuthorization();
    duplicate.setContractId("orders.created");
    duplicate.getRepositories().add("acme/orders");
    duplicate.getBranches().add("refs/heads/main");
    properties.getOidc().getContractAuthorizations().add(duplicate);
    assertThrows(IllegalStateException.class, properties::validate);
  }

  @Test
  void oidcModeRejectsBlankClaimNamesAndBlankPermissionValues() {
    EvidenceAuthProperties properties = configuredOidcPolicy();
    properties.getOidc().setRepositoryClaim(" ");
    assertThrows(IllegalStateException.class, properties::validate);

    properties = configuredOidcPolicy();
    properties.getOidc().getContractAuthorizations().getFirst().getBranches().set(0, " ");
    assertThrows(IllegalStateException.class, properties::validate);
  }

  @Test
  void oidcModeRequiresItsConfiguredIssuerToAppearInTheExplicitAllowlist() {
    EvidenceAuthProperties properties = configuredOidcPolicy();
    properties.getOidc().getTrustedIssuers().clear();
    properties.getOidc().getTrustedIssuers().add("https://another-issuer.dcg.test");

    assertThrows(IllegalStateException.class, properties::validate);
  }

  private EvidenceAuthProperties configuredOidcPolicy() {
    EvidenceAuthProperties properties = new EvidenceAuthProperties();
    properties.setMode(EvidenceAuthProperties.Mode.OIDC);
    properties.getOidc().setIssuerUri("https://issuer.dcg.test");
    properties.getOidc().getTrustedIssuers().add("https://issuer.dcg.test");
    properties.getOidc().setAudience("dcg-evidence");
    EvidenceAuthProperties.ContractAuthorization rule = new EvidenceAuthProperties.ContractAuthorization();
    rule.setContractId("orders.created");
    rule.getRepositories().add("acme/orders");
    rule.getBranches().add("refs/heads/main");
    properties.getOidc().getContractAuthorizations().add(rule);
    return properties;
  }
}
