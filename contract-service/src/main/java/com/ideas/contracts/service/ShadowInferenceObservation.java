package com.ideas.contracts.service;

import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import java.nio.file.Path;

record ShadowInferenceObservation(
    String contractId,
    String runId,
    String baseVersion,
    String candidateVersion,
    String commitSha,
    Path baseSchemaPath,
    Path candidateSchemaPath,
    CompatibilityMode mode,
    String policyPack,
    CompatibilityResult authoritativeResult) {}
