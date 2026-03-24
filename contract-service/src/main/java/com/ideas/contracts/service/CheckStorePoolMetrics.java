package com.ideas.contracts.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CheckStorePoolMetrics {
  public CheckStorePoolMetrics(CheckRunRepository checkRunStore, MeterRegistry meterRegistry) {
    Gauge.builder(
            "check_store.pool.connections.total",
            checkRunStore,
            store -> store.poolSnapshot().totalConnections())
        .tag("component", "check_run_store")
        .register(meterRegistry);
    Gauge.builder(
            "check_store.pool.connections.active",
            checkRunStore,
            store -> store.poolSnapshot().activeConnections())
        .tag("component", "check_run_store")
        .register(meterRegistry);
    Gauge.builder(
            "check_store.pool.connections.idle",
            checkRunStore,
            store -> store.poolSnapshot().idleConnections())
        .tag("component", "check_run_store")
        .register(meterRegistry);
    Gauge.builder(
            "check_store.pool.connections.pending",
            checkRunStore,
            store -> store.poolSnapshot().threadsAwaitingConnection())
        .tag("component", "check_run_store")
        .register(meterRegistry);
    Gauge.builder(
            "check_store.pool.connections.max",
            checkRunStore,
            store -> store.poolSnapshot().maximumPoolSize())
        .tag("component", "check_run_store")
        .register(meterRegistry);
  }
}
