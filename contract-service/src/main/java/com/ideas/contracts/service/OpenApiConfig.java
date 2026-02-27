package com.ideas.contracts.service;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
  @Bean
  public OpenAPI contractServiceOpenApi() {
    return new OpenAPI()
        .info(new Info()
            .title("Data Contract Governance API")
            .description("Read APIs for contracts and compatibility check history.")
            .version("v1")
            .contact(new Contact().name("Data Contract Governance"))
            .license(new License().name("Open Source")));
  }
}
