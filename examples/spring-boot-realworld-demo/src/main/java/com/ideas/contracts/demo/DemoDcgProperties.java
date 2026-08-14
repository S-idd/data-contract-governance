package com.ideas.contracts.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dcg.demo")
public class DemoDcgProperties {
  private String serviceBaseUrl = "http://localhost:8080";

  public String getServiceBaseUrl() {
    return serviceBaseUrl;
  }

  public void setServiceBaseUrl(String serviceBaseUrl) {
    this.serviceBaseUrl = serviceBaseUrl;
  }
}
