package com.ideas.contracts.service;

import com.ideas.contracts.service.model.CheckRunResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/checks")
public class CheckController {
  private final CheckRunStore checkRunStore;

  public CheckController(CheckRunStore checkRunStore) {
    this.checkRunStore = checkRunStore;
  }

  @GetMapping
  public List<CheckRunResponse> listChecks(
      @RequestParam(name = "contractId", required = false) String contractId,
      @RequestParam(name = "commitSha", required = false) String commitSha) {
    return checkRunStore.list(contractId, commitSha);
  }
}
