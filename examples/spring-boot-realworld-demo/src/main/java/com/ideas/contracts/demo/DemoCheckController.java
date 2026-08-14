package com.ideas.contracts.demo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/checks")
class DemoCheckController {
  private final DemoCheckSubmissionService submissionService;

  DemoCheckController(DemoCheckSubmissionService submissionService) {
    this.submissionService = submissionService;
  }

  @PostMapping("/{scenario}")
  @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.ACCEPTED)
  DemoCheckSubmission submit(@PathVariable("scenario") String scenario) {
    return submissionService.submit(DemoCheckScenario.parse(scenario));
  }
}
