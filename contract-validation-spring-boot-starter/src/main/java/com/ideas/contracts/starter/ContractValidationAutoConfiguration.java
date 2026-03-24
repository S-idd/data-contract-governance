package com.ideas.contracts.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(ValidateContract.class)
@EnableConfigurationProperties(ContractValidationProperties.class)
public class ContractValidationAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public ContractPayloadValidator contractPayloadValidator(
      ContractValidationProperties properties,
      ObjectMapper objectMapper) {
    return new ContractPayloadValidator(properties.getContractsRoot(), objectMapper);
  }

  @Bean
  @ConditionalOnMissingBean
  public ContractValidationRequestBodyAdvice contractValidationRequestBodyAdvice(
      ContractPayloadValidator payloadValidator,
      ContractValidationProperties properties) {
    return new ContractValidationRequestBodyAdvice(payloadValidator, properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public ContractValidationExceptionHandler contractValidationExceptionHandler() {
    return new ContractValidationExceptionHandler();
  }
}
