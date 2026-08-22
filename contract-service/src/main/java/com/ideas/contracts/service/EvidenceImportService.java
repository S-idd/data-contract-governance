package com.ideas.contracts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ideas.contracts.core.CheckStatus;
import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.core.CompatibilityMode;
import com.ideas.contracts.core.CompatibilityResult;
import com.ideas.contracts.core.ContractEngine;
import com.ideas.contracts.core.PolicyPack;
import com.ideas.contracts.service.model.ContractDetailResponse;
import com.ideas.contracts.service.model.EvidenceImportRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Imports CI evidence and independently verifies it against registered immutable artifacts. */
@Service
public class EvidenceImportService {
  private final MetadataStore metadataStore;
  private final ContractCatalogService contractCatalogService;
  private final ArtifactStore artifactStore;
  private final PolicyPackRegistry policyPackRegistry;
  private final ContractEngine contractEngine;
  private final ObjectMapper objectMapper;
  private final int maxPayloadBytes;

  public EvidenceImportService(
      MetadataStore metadataStore,
      ContractCatalogService contractCatalogService,
      ArtifactStore artifactStore,
      PolicyPackRegistry policyPackRegistry,
      ContractEngine contractEngine,
      ObjectMapper objectMapper,
      @Value("${checks.evidence.max-payload-bytes:1048576}") int maxPayloadBytes) {
    this.metadataStore = metadataStore;
    this.contractCatalogService = contractCatalogService;
    this.artifactStore = artifactStore;
    this.policyPackRegistry = policyPackRegistry;
    this.contractEngine = contractEngine;
    this.objectMapper = objectMapper;
    this.maxPayloadBytes = Math.max(1, maxPayloadBytes);
  }

  public MetadataStore.EvidenceImportResult importEvidence(
      String rawEvidence,
      EvidenceImportRequest request,
      EvidenceProvenance provenance) {
    validatePayload(rawEvidence);
    if (request == null) {
      throw new IllegalArgumentException("Evidence request must not be null.");
    }
    if (provenance == null) {
      throw new IllegalArgumentException("Evidence provenance must not be null.");
    }
    EvidenceAssessment assessment = assess(request);
    CheckEvidence evidence = CheckEvidence.newImport(
        request,
        CompatibilityEngineIdentity.sha256(rawEvidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        rawEvidence,
        provenance,
        assessment.status(),
        assessment.reason());
    return metadataStore.importEvidence(evidence);
  }

  /** Parses and validates the untrusted client document before claim-based authorization. */
  public EvidenceImportRequest parseEvidence(String rawEvidence) {
    validatePayload(rawEvidence);
    try {
      return objectMapper.readValue(rawEvidence, EvidenceImportRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("MALFORMED_DOCUMENT: evidence is not a valid v1.0 evidence document.");
    }
  }

  private void validatePayload(String rawEvidence) {
    if (rawEvidence == null || rawEvidence.isBlank()) {
      throw new IllegalArgumentException("EVIDENCE_PAYLOAD_REQUIRED: evidence payload must not be blank.");
    }
    if (rawEvidence.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxPayloadBytes) {
      throw new IllegalArgumentException("EVIDENCE_PAYLOAD_TOO_LARGE: evidence payload exceeds the configured size limit.");
    }
  }

  private EvidenceAssessment assess(EvidenceImportRequest request) {
    if (!CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL.equals(
        request.engineCompatibilityProtocol())) {
      return EvidenceAssessment.versionSkew("ENGINE_PROTOCOL_MISMATCH");
    }

    try {
      Optional<ContractDetailResponse> contract = contractCatalogService.getContract(request.contractId());
      if (contract.isEmpty()) {
        return EvidenceAssessment.rejected("SCHEMA_NOT_FOUND: contract is not registered.");
      }
      PolicyPack serverPolicy = policyPackRegistry.resolve(contract.get().policyPack());
      if (!serverPolicy.name().equals(request.policyPackName())
          || !CompatibilityEngineIdentity.policyPackSha256(serverPolicy).equals(request.policyPackSha256())) {
        return EvidenceAssessment.versionSkew("POLICY_PACK_MISMATCH");
      }

      Path baseSchema = resolveSchema(request.contractId(), request.baseVersion());
      Path candidateSchema = resolveSchema(request.contractId(), request.candidateVersion());
      if (!CompatibilityEngineIdentity.sha256(Files.readAllBytes(baseSchema)).equals(request.baseSchemaSha256())
          || !CompatibilityEngineIdentity.sha256(Files.readAllBytes(candidateSchema))
              .equals(request.candidateSchemaSha256())) {
        return EvidenceAssessment.rejected("SCHEMA_DIGEST_MISMATCH");
      }

      CompatibilityResult authoritative = contractEngine.checkCompatibility(
          baseSchema,
          candidateSchema,
          CompatibilityMode.valueOf(request.compatibilityMode()),
          serverPolicy);
      if (authoritative.status() != CheckStatus.valueOf(request.localStatus())
          || !authoritative.breakingChanges().equals(request.breakingChanges())
          || !authoritative.warnings().equals(request.warnings())) {
        return EvidenceAssessment.rejected("LOCAL_RESULT_MISMATCH");
      }
      return EvidenceAssessment.verified();
    } catch (java.nio.file.NoSuchFileException exception) {
      return EvidenceAssessment.rejected("SCHEMA_NOT_FOUND");
    } catch (IOException exception) {
      return EvidenceAssessment.unverified("VERIFICATION_UNAVAILABLE: schema artifacts could not be read.");
    } catch (RuntimeException exception) {
      return EvidenceAssessment.unverified(
          "VERIFICATION_UNAVAILABLE: " + exception.getClass().getSimpleName());
    }
  }

  private Path resolveSchema(String contractId, String version) throws IOException {
    if (artifactStore.readSchema(contractId, version).isEmpty()) {
      throw new java.nio.file.NoSuchFileException(contractId + "/" + version);
    }
    Path schemaPath = artifactStore.schemaPath(contractId, version);
    if (!Files.isRegularFile(schemaPath)) {
      throw new java.nio.file.NoSuchFileException(schemaPath.toString());
    }
    return schemaPath;
  }

  private record EvidenceAssessment(EvidenceImportStatus status, String reason) {
    static EvidenceAssessment verified() {
      return new EvidenceAssessment(EvidenceImportStatus.VERIFIED, "SERVER_RESULT_MATCHED");
    }

    static EvidenceAssessment versionSkew(String reason) {
      return new EvidenceAssessment(EvidenceImportStatus.VERSION_SKEW, reason);
    }

    static EvidenceAssessment rejected(String reason) {
      return new EvidenceAssessment(EvidenceImportStatus.REJECTED, reason);
    }

    static EvidenceAssessment unverified(String reason) {
      return new EvidenceAssessment(EvidenceImportStatus.UNVERIFIED, reason);
    }
  }
}
