package com.ideas.contracts.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("checkStore")
public class CheckStoreHealthIndicator implements HealthIndicator {
  private final CheckRunStore checkRunStore;

  public CheckStoreHealthIndicator(CheckRunStore checkRunStore) {
    this.checkRunStore = checkRunStore;
  }

  @Override
  public Health health() {
    CheckRunStore.HealthSnapshot snapshot = checkRunStore.healthSnapshot();
    if (snapshot.available()) {
      return Health.up()
          .withDetail("component", "check_run_store")
          .withDetail("dbTarget", checkRunStore.configuredDbTarget())
          .build();
    }

    return Health.down()
        .withDetail("component", "check_run_store")
        .withDetail("dbTarget", checkRunStore.configuredDbTarget())
        .withDetail("reason", snapshot.reason())
        .build();
  }
}


