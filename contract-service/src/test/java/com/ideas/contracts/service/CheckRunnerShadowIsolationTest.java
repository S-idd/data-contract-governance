package com.ideas.contracts.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ideas.contracts.core.CheckStatus;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.PolicyPack;
import com.ideas.contracts.core.PolicyPackDefaults;
import com.ideas.contracts.service.model.ContractDetailResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CheckRunnerShadowIsolationTest {
  @TempDir
  Path tempDir;

  @Test
  void shadowFailureCannotAlterOrRefinalizeTheAuthoritativeResult(CapturedOutput output)
      throws Exception {
    Path base = tempDir.resolve("v1.json");
    Path candidate = tempDir.resolve("v2.json");
    Files.writeString(base, "{\"type\":\"object\"}");
    Files.writeString(candidate, "{\"type\":\"object\",\"required\":[\"id\"]}");

    MetadataStore store = mock(MetadataStore.class);
    ContractEngine engine = mock(ContractEngine.class);
    ContractCatalogService catalog = mock(ContractCatalogService.class);
    PolicyPackRegistry policies = mock(PolicyPackRegistry.class);
    ArtifactStore artifacts = mock(ArtifactStore.class);
    CheckMetrics metrics = mock(CheckMetrics.class);
    NotificationService notifications = mock(NotificationService.class);
    ShadowInferenceObserver shadow = mock(ShadowInferenceObserver.class);

    MetadataStore.QueuedCheckRun run = new MetadataStore.QueuedCheckRun(
        "run-1", "orders.created", "v1", "v2", "BACKWARD", "abc123", "test");
    ContractDetailResponse contract = new ContractDetailResponse(
        "orders.created", "platform", "commerce", "BACKWARD", "baseline", List.of("v1", "v2"), "active");
    PolicyPack policy = PolicyPackDefaults.baselinePack();
    CompatibilityResult authoritative = new CompatibilityResult(
        CheckStatus.FAIL,
        List.of("Required field added: id"),
        List.of("diagnostic warning"));

    when(store.claimNextQueuedRun()).thenReturn(Optional.of(run));
    when(catalog.getContract("orders.created")).thenReturn(Optional.of(contract));
    when(policies.resolve("baseline")).thenReturn(policy);
    when(artifacts.schemaPath("orders.created", "v1")).thenReturn(base);
    when(artifacts.schemaPath("orders.created", "v2")).thenReturn(candidate);
    when(engine.checkCompatibility(base, candidate, CompatibilityMode.BACKWARD, policy))
        .thenReturn(authoritative);
    when(store.completeRun(
        "run-1", "FAIL", authoritative.breakingChanges(), authoritative.warnings()))
        .thenReturn(true);
    doThrow(new IllegalStateException("simulated observer defect"))
        .when(shadow).observe(any(ShadowInferenceObservation.class));

    CheckRunner runner = new CheckRunner(
        store,
        engine,
        catalog,
        policies,
        artifacts,
        metrics,
        notifications,
        shadow,
        1,
        0);

    runner.pollQueue();

    ArgumentCaptor<ShadowInferenceObservation> observation =
        ArgumentCaptor.forClass(ShadowInferenceObservation.class);
    InOrder persistedBeforeShadow = inOrder(store, shadow);
    persistedBeforeShadow.verify(store).completeRun(
        "run-1", "FAIL", authoritative.breakingChanges(), authoritative.warnings());
    persistedBeforeShadow.verify(shadow).observe(observation.capture());
    assertSame(authoritative, observation.getValue().authoritativeResult());
    verify(store, times(1)).completeRun(
        eq("run-1"), eq("FAIL"), eq(authoritative.breakingChanges()), eq(authoritative.warnings()));
    verify(metrics).recordCompleted(eq("orders.created"), eq("FAIL"), any());
    verify(metrics).recordFailed("orders.created", "compatibility_breaking");
    verify(notifications).publish(any(NotificationEvent.class));
    assertTrue(output.toString().contains("event=shadow_inference_dispatch_failed"));
    assertTrue(output.toString().contains("role=LOG_ONLY"));
  }
}
