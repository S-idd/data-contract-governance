package com.ideas.contracts.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Local, non-WORM archive used solely to rehearse the archive state machine outside production.
 * It never overwrites an existing evidence object and validates the payload digest on every read.
 */
public final class FilesystemEvidenceArchiveStore implements EvidenceArchiveStore {
  private final Path root;

  public FilesystemEvidenceArchiveStore(Path root) {
    if (root == null) {
      throw new IllegalArgumentException("archive root must not be null.");
    }
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public EvidenceArchiveReceipt archive(CheckEvidence evidence) {
    if (evidence == null) {
      throw new IllegalArgumentException("evidence must not be null.");
    }
    byte[] payload = evidence.rawEvidence().getBytes(StandardCharsets.UTF_8);
    String expectedDigest = evidence.payloadSha256();
    if (!expectedDigest.equals(sha256(payload))) {
      throw new IllegalStateException("Evidence payload checksum does not match its imported digest.");
    }

    Path destination = root.resolve("evidence").resolve(evidence.evidenceId() + ".json").normalize();
    if (!destination.startsWith(root)) {
      throw new IllegalArgumentException("Evidence archive destination escapes configured root.");
    }
    try {
      Files.createDirectories(destination.getParent());
      if (Files.exists(destination)) {
        return verifiedReceipt(evidence.evidenceId(), destination, expectedDigest);
      }

      Path temporary = destination.resolveSibling(
          "." + evidence.evidenceId() + "." + UUID.randomUUID() + ".tmp");
      try {
        Files.write(temporary, payload);
        moveWithoutOverwrite(temporary, destination);
      } catch (FileAlreadyExistsException ignored) {
        // A concurrent archival attempt won; validate the immutable winner below.
      } finally {
        Files.deleteIfExists(temporary);
      }
      return verifiedReceipt(evidence.evidenceId(), destination, expectedDigest);
    } catch (IOException error) {
      throw new IllegalStateException("Unable to archive evidence payload to local rehearsal storage.", error);
    }
  }

  @Override
  public void verifyReadyForRetention() {
    try {
      Files.createDirectories(root);
      if (!Files.isDirectory(root) || !Files.isWritable(root)) {
        throw new IllegalStateException("Local evidence archive root is not writable.");
      }
    } catch (IOException error) {
      throw new IllegalStateException("Local evidence archive root is unavailable.", error);
    }
  }

  @Override
  public boolean wormCapable() {
    return false;
  }

  @Override
  public String backend() {
    return "filesystem-rehearsal";
  }

  private EvidenceArchiveReceipt verifiedReceipt(String evidenceId, Path path, String expectedDigest)
      throws IOException {
    String actualDigest = sha256(Files.readAllBytes(path));
    if (!expectedDigest.equals(actualDigest)) {
      throw new IllegalStateException(
          "Existing local archive object has a checksum that differs from the evidence payload.");
    }
    return new EvidenceArchiveReceipt(evidenceId, path.toUri().toString(), actualDigest, Instant.now());
  }

  private void moveWithoutOverwrite(Path temporary, Path destination) throws IOException {
    try {
      Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, destination);
    }
  }

  private String sha256(byte[] payload) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is not available in this Java runtime.", error);
    }
  }
}
