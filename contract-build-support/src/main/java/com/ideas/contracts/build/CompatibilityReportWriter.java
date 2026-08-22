package com.ideas.contracts.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;

public class CompatibilityReportWriter {
  private final ObjectMapper objectMapper;

  public CompatibilityReportWriter() {
    this(new ObjectMapper().registerModule(new JavaTimeModule()));
  }

  CompatibilityReportWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public void write(Path reportFile, DcgEvidenceDocument result) {
    try {
      Path absoluteReport = reportFile.toAbsolutePath();
      Path parent = absoluteReport.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Path temporary = Files.createTempFile(parent, absoluteReport.getFileName().toString(), ".tmp");
      objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), result);
      try {
        Files.move(temporary, absoluteReport, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException ex) {
        Files.move(temporary, absoluteReport, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException ex) {
      throw new IllegalStateException("Unable to write compatibility report: " + reportFile, ex);
    }
  }
}
