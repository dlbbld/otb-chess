// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Enforces the two-line SPDX license header on every Java source file, so a missing or wrong header fails the build
 * before release. The header is added or repaired by {@code tools/java-license-headers.ps1 -Fix}; this test is the
 * {@code -Check} equivalent wired into {@code mvn test}.
 */
class TestLicenseHeaders {

  private static final List<String> SOURCE_ROOTS = List.of("src/main/java", "src/test/java");
  private static final String LINE_1 = "// Copyright (C) 2026 Daniel Baechli";
  private static final String LINE_2 = "// SPDX-License-Identifier: GPL-3.0-only";

  @Test
  void everyJavaFileStartsWithTheLicenseHeader() throws IOException {
    final List<String> offenders = new ArrayList<>();
    int scanned = 0;
    for (final String root : SOURCE_ROOTS) {
      final Path rootPath = Path.of(root);
      if (!Files.isDirectory(rootPath)) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(rootPath)) {
        final List<Path> javaFiles =
            paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".java")).toList();
        scanned += javaFiles.size();
        for (final Path file : javaFiles) {
          if (!hasHeader(file)) {
            offenders.add(rootPath.relativize(file).toString().replace('\\', '/'));
          }
        }
      }
    }
    // Guard against a silent no-op: if the source roots move, fail loudly instead of passing on zero files.
    assertTrue(scanned > 0, "No .java files were scanned; check SOURCE_ROOTS and the working directory.");
    assertTrue(offenders.isEmpty(),
        "Java files missing the exact 2-line license header (run: tools/java-license-headers.ps1 -Fix):\n  "
            + String.join("\n  ", offenders));
  }

  private static boolean hasHeader(Path file) throws IOException {
    final String normalized = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
    final String[] lines = normalized.split("\n", 3);
    return lines.length >= 2 && LINE_1.equals(lines[0]) && LINE_2.equals(lines[1]);
  }
}
