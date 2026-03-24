package com.ideas.contracts.starter;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "contract.validation")
public class ContractValidationProperties {
  private boolean enabled = true;
  private String contractsRoot = "contracts";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getContractsRoot() {
    return contractsRoot;
  }

  public void setContractsRoot(String contractsRoot) {
    this.contractsRoot = contractsRoot;
  }
}
