package com.ideas.contracts.demo;

public record DemoCheckSubmission(
    String runId,
    String status,
    String scenario,
    String contractId,
    String baseVersion,
    String candidateVersion,
    String checkUrl
) {}
