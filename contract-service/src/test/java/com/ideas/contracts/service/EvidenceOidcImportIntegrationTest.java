package com.ideas.contracts.service;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ideas.contracts.core.CompatibilityEngineIdentity;
import com.ideas.contracts.core.PolicyPackDefaults;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Exercises signature, issuer, audience, repository/ref, and contract authorization boundaries. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(EvidenceOidcImportIntegrationTest.OidcTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
    "app.security.enabled=false",
    "app.security.evidence-auth.mode=OIDC",
    "app.security.evidence-auth.oidc.issuer-uri=https://issuer.dcg.test",
    "app.security.evidence-auth.oidc.trusted-issuers[0]=https://issuer.dcg.test",
    "app.security.evidence-auth.oidc.audience=dcg-evidence",
    "app.security.evidence-auth.oidc.contract-authorizations[0].contract-id=orders.created",
    "app.security.evidence-auth.oidc.contract-authorizations[0].repositories[0]=acme/orders",
    "app.security.evidence-auth.oidc.contract-authorizations[0].branches[0]=refs/heads/main",
    "checks.runner.enabled=false"
})
class EvidenceOidcImportIntegrationTest {
  private static final String ISSUER = "https://issuer.dcg.test";
  private static final String AUDIENCE = "dcg-evidence";
  private static Path tempRoot;
  private static Path contractsRoot;
  private static Path checksDbPath;

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtEncoder jwtEncoder;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    ensurePaths();
    registry.add("contracts.root", () -> contractsRoot.toString());
    registry.add("checks.db.path", () -> checksDbPath.toString());
  }

  @BeforeAll
  void prepareRegisteredSchemas() throws Exception {
    Path contract = contractsRoot.resolve("orders.created");
    Files.createDirectories(contract);
    Files.writeString(contract.resolve("metadata.yaml"),
        "ownerTeam: platform\ndomain: commerce\ncompatibilityMode: BACKWARD\n");
    Files.writeString(contract.resolve("v1.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}");
    Files.writeString(contract.resolve("v2.json"),
        "{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"},\"status\":{\"type\":\"string\"}}}");
  }

  @Test
  void acceptsSignedAuthorizedEvidenceAndPersistsVerifiedProvenance() throws Exception {
    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", bearer("acme/orders", "refs/heads/main", AUDIENCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-authorized", "orders.created")))
        .andExpect(status().isAccepted())
        .andExpect(content().string(containsString("\"importStatus\":\"VERIFIED\"")));

    mockMvc.perform(get("/checks/evidence")
            .header("Authorization", bearer("acme/orders", "refs/heads/main", AUDIENCE))
            .param("contractId", "orders.created"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"authenticationScheme\":\"OIDC\"")))
        .andExpect(content().string(containsString("\"oidcIssuer\":\"https://issuer.dcg.test\"")))
        .andExpect(content().string(containsString("\"oidcSubject\":\"build-123\"")))
        .andExpect(content().string(containsString("\"oidcRepository\":\"acme/orders\"")))
        .andExpect(content().string(containsString("\"oidcRef\":\"refs/heads/main\"")));
  }

  @Test
  void rejectsWrongAudienceSignatureProvenanceRepositoryBranchAndBasicAuthentication() throws Exception {
    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", bearer("acme/orders", "refs/heads/main", "another-service"))
            .contentType(MediaType.APPLICATION_JSON)
        .content(evidence("oidc-wrong-audience", "orders.created")))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", rogueBearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-forged-signature", "orders.created")))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", expiredBearer())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-expired", "orders.created")))
        .andExpect(status().isUnauthorized());

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", bearer("", "refs/heads/main", AUDIENCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-missing-provenance", "orders.created")))
        .andExpect(status().isUnauthorized())
        .andExpect(content().string(containsString("\"code\":\"AUTH_FAILED\"")));

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", bearer("evil/orders", "refs/heads/main", AUDIENCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-wrong-repository", "orders.created")))
        .andExpect(status().isForbidden())
        .andExpect(content().string(containsString("\"code\":\"CONTRACT_NOT_AUTHORIZED\"")));

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", bearer("acme/orders", "refs/heads/feature", AUDIENCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-wrong-branch", "orders.created")))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", basicAuth())
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-basic-rejected", "orders.created")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsAValidWorkloadWhenItsContractIsNotExplicitlyAuthorized() throws Exception {
    mockMvc.perform(post("/checks/evidence")
            .header("Authorization", bearer("acme/orders", "refs/heads/main", AUDIENCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content(evidence("oidc-unknown-contract", "payments.created")))
        .andExpect(status().isForbidden());
  }

  private String bearer(String repository, String ref, String audience) {
    return bearer(jwtEncoder, repository, ref, audience);
  }

  private String rogueBearer() {
    return bearer(encoderFor(OidcTestConfiguration.keyPair()), "acme/orders", "refs/heads/main",
        AUDIENCE);
  }

  private String expiredBearer() {
    return bearer(jwtEncoder, "acme/orders", "refs/heads/main", AUDIENCE, Instant.now().minusSeconds(300));
  }

  private String bearer(
      JwtEncoder encoder, String repository, String ref, String audience) {
    return bearer(encoder, repository, ref, audience, Instant.now().plusSeconds(300));
  }

  private String bearer(
      JwtEncoder encoder, String repository, String ref, String audience, Instant expiresAt) {
    Instant now = Instant.now();
    Instant issuedAt = expiresAt.isBefore(now) ? expiresAt.minusSeconds(60) : now.minusSeconds(5);
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(ISSUER)
        .subject("build-123")
        .audience(List.of(audience))
        .issuedAt(issuedAt)
        .expiresAt(expiresAt)
        .claim("repository", repository)
        .claim("ref", ref)
        .build();
    return "Bearer " + encoder.encode(JwtEncoderParameters.from(
        JwsHeader.with(SignatureAlgorithm.RS256).keyId("test-key").build(), claims)).getTokenValue();
  }

  private String evidence(String idempotencyKey, String contractId) throws Exception {
    String baseDigest = CompatibilityEngineIdentity.sha256(
        Files.readAllBytes(contractsRoot.resolve("orders.created/v1.json")));
    String candidateDigest = CompatibilityEngineIdentity.sha256(
        Files.readAllBytes(contractsRoot.resolve("orders.created/v2.json")));
    return """
        {
          "evidenceFormatVersion": "1.0",
          "idempotencyKey": "%s",
          "contractId": "%s",
          "baseVersion": "v1",
          "candidateVersion": "v2",
          "compatibilityMode": "BACKWARD",
          "commitSha": "abc123",
          "baseSchemaSha256": "%s",
          "candidateSchemaSha256": "%s",
          "engineVersion": "0.1.0-SNAPSHOT",
          "engineCompatibilityProtocol": "%s",
          "policyPackName": "baseline",
          "policyPackSha256": "%s",
          "localStatus": "PASS",
          "breakingChanges": [],
          "warnings": [],
          "executedAt": "2026-08-20T06:00:00Z",
          "ciIdentity": "untrusted-client-claim",
          "buildUrl": "https://ci.example/build/1"
        }
        """.formatted(idempotencyKey, contractId, baseDigest, candidateDigest,
        CompatibilityEngineIdentity.COMPATIBILITY_PROTOCOL,
        CompatibilityEngineIdentity.policyPackSha256(PolicyPackDefaults.baselinePack()));
  }

  private String basicAuth() {
    return "Basic " + Base64.getEncoder().encodeToString("ci-runner:secret".getBytes(StandardCharsets.UTF_8));
  }

  private static synchronized void ensurePaths() {
    if (tempRoot != null) {
      return;
    }
    try {
      tempRoot = Files.createTempDirectory("evidence-oidc-import-it-");
      contractsRoot = tempRoot.resolve("contracts");
      checksDbPath = tempRoot.resolve("checks.db");
      Files.createDirectories(contractsRoot);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to initialize OIDC evidence test paths.", exception);
    }
  }

  @TestConfiguration
  static class OidcTestConfiguration {
    private final KeyPair keyPair = keyPair();

    @Bean
    JwtDecoder jwtDecoder() {
      NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keyPair.getPublic()).build();
      decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
      return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder() {
      return encoderFor(keyPair);
    }

    static KeyPair keyPair() {
      try {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
      } catch (Exception exception) {
        throw new IllegalStateException("Unable to generate test OIDC signing key.", exception);
      }
    }
  }

  private static JwtEncoder encoderFor(KeyPair keyPair) {
    RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
        .privateKey((RSAPrivateKey) keyPair.getPrivate())
        .keyID("test-key")
        .build();
    ImmutableJWKSet<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
    return new NimbusJwtEncoder(jwkSource);
  }
}
