// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

/** The two samples of the sounds (E-004): what they are, and that a browser is told they are audio. */
class TestStaticFileHandler {

  @Test
  void aWavFileIsAudio() {
    assertEquals("audio/wav", StaticFileHandler.guessContentType("static/sounds/piece.wav"));
    assertEquals("application/javascript; charset=UTF-8", StaticFileHandler.guessContentType("static/js/sounds.js"));
    assertEquals("application/octet-stream", StaticFileHandler.guessContentType("static/sounds/piece.unknown"));
  }

  @ParameterizedTest
  @ValueSource(strings = { "piece.wav", "clock.wav" })
  void theSamplesAreServedAsAudio(String name) throws Exception {
    final var httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    httpServer.createContext("/", new StaticFileHandler(Path.of("static").toAbsolutePath())::handle);
    httpServer.start();
    try {
      final var connection = (HttpURLConnection) URI
          .create("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/sounds/" + name).toURL().openConnection();

      assertEquals(200, connection.getResponseCode());
      assertEquals("audio/wav", connection.getContentType());
      try (var body = connection.getInputStream()) {
        assertEquals(Files.size(Path.of("static", "sounds", name)), body.readAllBytes().length);
      }
    } finally {
      httpServer.stop(0);
    }
  }

  /** Short, mono, uncompressed: a sample starts at once and every browser decodes it. */
  @ParameterizedTest
  @ValueSource(strings = { "piece.wav", "clock.wav" })
  void theSamplesAreShortMonoPcm(String name) throws Exception {
    final byte[] bytes = Files.readAllBytes(Path.of("static", "sounds", name));
    final ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

    assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
    assertEquals("WAVE", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
    assertEquals("fmt ", new String(bytes, 12, 4, StandardCharsets.US_ASCII));
    assertEquals(1, header.getShort(20), "PCM");
    assertEquals(1, header.getShort(22), "mono");
    assertEquals(16, header.getShort(34), "16 bit");
    assertEquals("data", new String(bytes, 36, 4, StandardCharsets.US_ASCII));
    final double seconds = header.getInt(40) / (double) header.getInt(28);
    assertTrue(seconds > 0.02 && seconds < 0.25, name + " lasts " + seconds + " s");
    assertTrue(bytes.length < 25_000, name + " has " + bytes.length + " bytes");
  }
}
