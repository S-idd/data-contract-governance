package com.ideas.contracts.gradle;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.gradle.api.Plugin;
import org.junit.jupiter.api.Test;

class DcgCompatibilityPluginTest {
  @Test
  void exposesThePluginMarkerAndOfflineDefaults() throws Exception {
    Properties marker = new Properties();
    try (var stream = getClass().getResourceAsStream(
        "/META-INF/gradle-plugins/com.ideas.contracts.governance.properties")) {
      marker.load(stream);
    }

    assertEquals(DcgCompatibilityPlugin.class.getName(), marker.getProperty("implementation-class"));
    assertInstanceOf(Plugin.class, new DcgCompatibilityPlugin());
    DcgCompatibilityExtension extension = new DcgCompatibilityExtension();
    assertEquals("BACKWARD", extension.getMode());
    assertEquals("DISABLED", extension.getRemoteReportingMode());
  }
}
