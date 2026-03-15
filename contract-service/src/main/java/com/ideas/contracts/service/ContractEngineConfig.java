package com.ideas.contracts.service;

import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.DefaultContractEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ContractEngineConfig {
  @Bean
  public ContractEngine contractEngine() {
    return new DefaultContractEngine();
  }
}
