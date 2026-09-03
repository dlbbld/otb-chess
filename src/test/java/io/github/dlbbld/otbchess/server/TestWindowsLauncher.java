// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class TestWindowsLauncher {

  @TempDir
  Path temp;

  @ParameterizedTest
  @ValueSource(booleans = { false, true })
  void unavailableRemoteOrMissingBranchCannotStartAnOlderSnapshot(boolean remoteExists) throws Exception {
    final Path checkout = Files.createDirectory(temp.resolve("checkout with spaces"));
    final Path bin = Files.createDirectory(temp.resolve("bin"));
    final Path launcher = Files.copy(Path.of("start.bat"), checkout.resolve("start.bat"));
    final Path started = temp.resolve("server-started");
    final Path remote = temp.resolve("remote.git");
    runGit(checkout, "init");
    Files.writeString(checkout.resolve("fixture.txt"), "old local commit");
    runGit(checkout, "add", "fixture.txt");
    runGit(checkout, "commit", "-m", "Old local snapshot");
    if (remoteExists) {
      runGit(checkout, "init", "--bare", remote.toString());
    }
    runGit(checkout, "remote", "add", "origin", remote.toString());
    Files.writeString(bin.resolve("mvn.cmd"), "@echo off\r\necho started>\"" + started + "\"\r\n");
    final Path output = temp.resolve("output.txt");
    final ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/d", "/c", "call \"" + launcher + "\"")
        .directory(temp.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().put("PATH", bin + ";" + System.getenv("PATH"));
    builder.environment().put("OTB_STABLE_SHA", "stale-inherited-commit");
    final Process process = builder.start();
    try {
      assertTrue(process.waitFor(15, TimeUnit.SECONDS), "launcher did not stop");
      assertNotEquals(0, process.exitValue(), Files.readString(output));
      assertFalse(Files.exists(started), "a failed update must never start Maven");
      assertFalse(Files.exists(temp.resolve("otb-chess-stable")), "a failed update must not create a snapshot");
    } finally {
      process.destroyForcibly();
    }
  }

  private void runGit(Path checkout, String... args) throws Exception {
    final List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Launcher Test", "-c",
        "user.email=launcher@example.invalid"));
    command.addAll(List.of(args));
    final Process process = new ProcessBuilder(command).directory(checkout.toFile())
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    assertTrue(process.waitFor(15, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
  }
}
