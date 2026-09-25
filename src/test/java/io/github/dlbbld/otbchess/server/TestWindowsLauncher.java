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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class TestWindowsLauncher {

  private static final String DEFAULT_BRANCH = "claude/fixing";

  private static final String OTHER_BRANCH = "release/candidate";

  @TempDir
  Path temp;

  private Path checkout;

  private Path started;

  private Path output;

  @Test
  void branchComesFromConfigurationSoNoGitCommandCarriesAHardcodedName() throws Exception {
    final String launcher = Files.readString(Path.of("start.bat"));
    assertTrue(launcher.contains("if not defined OTB_TEST_BRANCH set \"OTB_TEST_BRANCH=%~1\""),
        "the first argument must be the fallback for OTB_TEST_BRANCH");
    assertTrue(launcher.contains("if not defined OTB_TEST_BRANCH set \"OTB_TEST_BRANCH=" + DEFAULT_BRANCH + "\""),
        "the default branch must be the last fallback");
    assertTrue(launcher.contains("git fetch origin \"%OTB_TEST_BRANCH%\""), "the fetch must use the resolved branch");
    assertTrue(launcher.contains("echo Serving %OTB_TEST_BRANCH% commit"), "the served branch must be echoed");
    for (final String line : launcher.lines().map(String::trim).toList()) {
      if (line.startsWith("git ")) {
        assertFalse(line.contains(DEFAULT_BRANCH), "a release must never have to edit a branch name into: " + line);
      }
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void defaultBranchIsServedWhenNothingIsConfigured() throws Exception {
    final Map<String, String> pushed = seedRemoteBranches(OTHER_BRANCH, DEFAULT_BRANCH);
    assertServedSnapshot(runLauncher(null), DEFAULT_BRANCH, pushed.get(DEFAULT_BRANCH));
  }

  @ParameterizedTest
  @ValueSource(strings = { "environment", "argument" })
  @EnabledOnOs(OS.WINDOWS)
  void configuredBranchIsServedInsteadOfTheDefault(String source) throws Exception {
    final Map<String, String> pushed = seedRemoteBranches(DEFAULT_BRANCH, OTHER_BRANCH);
    final Process process = "environment".equals(source) ? runLauncher(OTHER_BRANCH) : runLauncher(null, OTHER_BRANCH);
    assertServedSnapshot(process, OTHER_BRANCH, pushed.get(OTHER_BRANCH));
    assertNotEquals(pushed.get(DEFAULT_BRANCH), git(temp.resolve("otb-chess-stable"), "rev-parse", "HEAD"));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void unreachableRemoteCannotStartAnOlderSnapshot() throws Exception {
    seedCheckout();
    runGit(checkout, "remote", "add", "origin", temp.resolve("remote.git").toString());
    assertLauncherFailedWithoutServing(runLauncher(null));
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  void missingConfiguredBranchFailsInsteadOfServingTheDefault() throws Exception {
    // The default branch exists on the remote, so only honoring the override can fail here.
    seedRemoteBranches(DEFAULT_BRANCH);
    assertLauncherFailedWithoutServing(runLauncher("release/never-pushed"));
  }

  private void assertServedSnapshot(Process process, String branch, String expectedSha) throws Exception {
    final String log = Files.readString(output);
    assertEquals(0, process.exitValue(), log);
    assertTrue(Files.exists(started), "the resolved snapshot must start Maven: " + log);
    assertEquals(expectedSha, git(temp.resolve("otb-chess-stable"), "rev-parse", "HEAD"), log);
    assertNotEquals(git(checkout, "rev-parse", "HEAD"), expectedSha, "the local commit must never be served");
    assertTrue(log.contains("Serving " + branch + " commit " + expectedSha), log);
  }

  private void assertLauncherFailedWithoutServing(Process process) throws Exception {
    assertNotEquals(0, process.exitValue(), Files.readString(output));
    assertFalse(Files.exists(started), "a failed update must never start Maven");
    assertFalse(Files.exists(temp.resolve("otb-chess-stable")), "a failed update must not create a snapshot");
  }

  /** Creates a checkout holding one local commit that is never pushed, plus a stubbed Maven on PATH. */
  private void seedCheckout() throws Exception {
    checkout = Files.createDirectory(temp.resolve("checkout with spaces"));
    started = temp.resolve("server-started");
    output = temp.resolve("output.txt");
    Files.copy(Path.of("start.bat"), checkout.resolve("start.bat"));
    Files.writeString(Files.createDirectory(temp.resolve("bin")).resolve("mvn.cmd"),
        "@echo off\r\necho started>\"" + started + "\"\r\n");
    runGit(checkout, "init");
    commit("old local commit");
  }

  private Map<String, String> seedRemoteBranches(String... branches) throws Exception {
    seedCheckout();
    final Path remote = temp.resolve("remote.git");
    runGit(checkout, "init", "--bare", remote.toString());
    runGit(checkout, "remote", "add", "origin", remote.toString());
    final Map<String, String> pushed = new LinkedHashMap<>();
    for (final String branch : branches) {
      commit("snapshot of " + branch);
      runGit(checkout, "push", "origin", "HEAD:refs/heads/" + branch);
      pushed.put(branch, git(checkout, "rev-parse", "HEAD"));
    }
    commit("uncommitted work continues locally");
    return pushed;
  }

  private void commit(String content) throws Exception {
    Files.writeString(checkout.resolve("fixture.txt"), content);
    runGit(checkout, "add", "fixture.txt");
    runGit(checkout, "commit", "-m", content);
  }

  private Process runLauncher(String configuredBranch, String... args) throws Exception {
    final List<String> command = new ArrayList<>(List.of("cmd.exe", "/d", "/c",
        ("call \"" + checkout.resolve("start.bat") + "\" " + String.join(" ", args)).trim()));
    final ProcessBuilder builder = new ProcessBuilder(command).directory(temp.toFile())
        .redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().put("PATH", temp.resolve("bin") + ";" + System.getenv("PATH"));
    // Inherited state must not leak into the resolved snapshot.
    builder.environment().put("OTB_STABLE_SHA", "stale-inherited-commit");
    builder.environment().remove("OTB_TEST_BRANCH");
    if (configuredBranch != null) {
      builder.environment().put("OTB_TEST_BRANCH", configuredBranch);
    }
    final Process process = builder.start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "launcher did not stop");
      return process;
    } finally {
      process.destroyForcibly();
    }
  }

  private String git(Path directory, String... args) throws Exception {
    final Path captured = Files.createTempFile(temp, "git", ".txt");
    final Process process = gitBuilder(directory, args).redirectOutput(captured.toFile()).start();
    assertTrue(process.waitFor(15, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
    return Files.readString(captured).trim();
  }

  private void runGit(Path directory, String... args) throws Exception {
    final Process process = gitBuilder(directory, args).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    assertTrue(process.waitFor(15, TimeUnit.SECONDS));
    assertEquals(0, process.exitValue());
  }

  private ProcessBuilder gitBuilder(Path directory, String... args) {
    final List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=Launcher Test", "-c",
        "user.email=launcher@example.invalid"));
    command.addAll(List.of(args));
    return new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
  }
}
