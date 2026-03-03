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
    CheckRunStore.PoolSnapshot poolSnapshot = checkRunStore.poolSnapshot();
    if (snapshot.available()) {
      return baseDetails(Health.up(), poolSnapshot).build();
    }

    return baseDetails(Health.down(), poolSnapshot)
        .withDetail("reason", snapshot.reason())
        .build();
  }

  private Health.Builder baseDetails(Health.Builder builder, CheckRunStore.PoolSnapshot poolSnapshot) {
    return builder
        .withDetail("component", "check_run_store")
        .withDetail("dbTarget", checkRunStore.configuredDbTarget())
        .withDetail("poolTotalConnections", poolSnapshot.totalConnections())
        .withDetail("poolActiveConnections", poolSnapshot.activeConnections())
        .withDetail("poolIdleConnections", poolSnapshot.idleConnections())
        .withDetail("poolThreadsAwaitingConnection", poolSnapshot.threadsAwaitingConnection())
        .withDetail("poolMaximumSize", poolSnapshot.maximumPoolSize())
        .withDetail("poolMinimumIdle", poolSnapshot.minimumIdle())
        .withDetail("poolConnectionTimeoutMs", poolSnapshot.connectionTimeoutMs());
  }
}

