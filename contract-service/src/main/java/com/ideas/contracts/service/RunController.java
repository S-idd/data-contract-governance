package com.ideas.contracts.service;

import com.ideas.contracts.service.model.CheckRunLogResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/runs")
public class RunController {
  private final MetadataStore checkRunStore;

  public RunController(MetadataStore checkRunStore) {
    this.checkRunStore = checkRunStore;
  }

  @GetMapping("/{runId}/logs")
  public List<CheckRunLogResponse> getRunLogs(@PathVariable("runId") String runId) {
    checkRunStore.findByRunId(runId).orElseThrow(() -> new CheckRunNotFoundException(runId));
    return checkRunStore.listLogs(runId);
  }
}
