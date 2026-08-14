package com.ideas.contracts.demo;

import com.ideas.contracts.sdk.ContractValidationClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DemoDcgClientConfiguration {
  @Bean
  ContractValidationClient contractValidationClient(DemoDcgProperties properties) {
    return new ContractValidationClient(properties.getServiceBaseUrl());
  }
}
