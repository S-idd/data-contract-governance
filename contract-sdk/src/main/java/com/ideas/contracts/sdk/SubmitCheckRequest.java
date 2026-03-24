package com.ideas.contracts.sdk;

public record SubmitCheckRequest(
    String contractId,
    String baseVersion,
    String candidateVersion,
    String mode,
    String commitSha,
    String triggeredBy
) {}
