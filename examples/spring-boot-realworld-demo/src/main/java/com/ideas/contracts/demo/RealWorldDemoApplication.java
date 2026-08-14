package com.ideas.contracts.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(DemoDcgProperties.class)
public class RealWorldDemoApplication {
  public static void main(String[] args) {
    SpringApplication.run(RealWorldDemoApplication.class, args);
  }
}
