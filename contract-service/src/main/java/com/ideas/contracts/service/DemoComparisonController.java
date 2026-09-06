package com.ideas.contracts.service;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP facade for the synchronous, presentation-only comparison flow. */
@RestController
@RequestMapping("/demo")
public class DemoComparisonController {
  private final DemoComparisonService comparisonService;

  public DemoComparisonController(DemoComparisonService comparisonService) {
    this.comparisonService = comparisonService;
  }

  @PostMapping("/compare")
  public DemoComparisonResponse compare(@RequestBody DemoComparisonRequest request) {
    return comparisonService.compare(request);
  }
}
