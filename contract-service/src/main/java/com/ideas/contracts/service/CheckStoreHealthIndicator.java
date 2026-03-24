package com.ideas.contracts.service;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("checkStore")
public class CheckStoreHealthIndicator implements HealthIndicator {
  private final CheckRunRepository checkRunStore;

  public CheckStoreHealthIndicator(CheckRunRepository checkRunStore) {
    this.checkRunStore = checkRunStore;
  }

  @Override
  public Health health() {
    CheckRunRepository.HealthSnapshot snapshot = checkRunStore.healthSnapshot();
    CheckRunRepository.PoolSnapshot poolSnapshot = checkRunStore.poolSnapshot();
    if (snapshot.available()) {
      return baseDetails(Health.up(), poolSnapshot).build();
    }

    return baseDetails(Health.down(), poolSnapshot)
        .withDetail("reason", snapshot.reason())
        .build();
  }

  private Health.Builder baseDetails(Health.Builder builder, CheckRunRepository.PoolSnapshot poolSnapshot) {
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
