// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TestOtbChessServer {

  /**
   * The version shown in the page footer must be the real Maven project version in EVERY run mode,
   * not the "development" fallback. It comes from the Maven-filtered version.properties on the
   * classpath (the jar manifest's Implementation-Version exists only when running from the packaged
   * jar — e.g. `mvn exec:java` / start.bat runs straight from target/classes). This pins that the
   * resource is present and the ${project.version} placeholder was actually substituted.
   */
  @Test
  void testAppVersionComesFromFilteredResourceNotFallback() {
    final String version = OtbChessServer.appVersion();
    assertNotEquals("development", version);
    assertFalse(version.contains("${"), "version.properties was not Maven-filtered: " + version);
    assertTrue(version.matches("\\d+\\.\\d+.*"), "unexpected version format: " + version);
  }
}
