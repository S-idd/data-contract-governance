package com.ideas.contracts.service;

import com.ideas.contracts.service.model.ContractDetailResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    prefix = "checks.db.backfill",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CheckRunBackfillService {
  private static final Logger LOGGER = LoggerFactory.getLogger(CheckRunBackfillService.class);
  private static final Set<String> VALID_MODES = Set.of("BACKWARD", "FORWARD", "FULL");

  private final CheckRunRepository checkRunStore;
  private final ContractCatalogService contractCatalogService;
  private final String defaultMode;
  private final String defaultTriggeredBy;
  private final Map<String, String> modeCache = new ConcurrentHashMap<>();

  public CheckRunBackfillService(
      CheckRunRepository checkRunStore,
      ContractCatalogService contractCatalogService,
      @Value("${checks.db.backfill.default-mode:BACKWARD}") String defaultMode,
      @Value("${checks.db.backfill.default-triggered-by:legacy}") String defaultTriggeredBy) {
    this.checkRunStore = checkRunStore;
    this.contractCatalogService = contractCatalogService;
    this.defaultMode = normalizeMode(defaultMode, "BACKWARD");
    this.defaultTriggeredBy = defaultTriggeredBy == null || defaultTriggeredBy.isBlank()
        ? "legacy"
        : defaultTriggeredBy.trim();
  }

  @EventListener(ApplicationReadyEvent.class)
  public void backfillLegacyRuns() {
    try {
      int updated = checkRunStore.backfillLegacyRuns(this::resolveMode, defaultTriggeredBy, defaultMode);
      if (updated > 0) {
        LOGGER.info(
            "event=check_run_backfill_complete component=check_run_backfill updated={} default_mode={} default_triggered_by={}",
            updated,
            defaultMode,
            defaultTriggeredBy);
      }
    } catch (CheckRunStoreException ex) {
      LOGGER.warn(
          "event=check_run_backfill_failed component=check_run_backfill message={} ",
          ex.getMessage(),
          ex);
    }
  }

  private String resolveMode(String contractId) {
    if (contractId == null || contractId.isBlank()) {
      return defaultMode;
    }
    return modeCache.computeIfAbsent(contractId, id -> {
      String mode = contractCatalogService.getContract(id)
          .map(ContractDetailResponse::compatibilityMode)
          .orElse(defaultMode);
      return normalizeMode(mode, defaultMode);
    });
  }

  private String normalizeMode(String mode, String fallback) {
    if (mode == null || mode.isBlank()) {
      return fallback;
    }
    String normalized = mode.trim().toUpperCase(Locale.ROOT);
    return VALID_MODES.contains(normalized) ? normalized : fallback;
  }
}
