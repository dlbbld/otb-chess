// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;

import io.github.dlbbld.ashlarchess.board.Board;

/**
 * Main entry point for the OTB Chess server.
 *
 * <p>
 * Starts two servers:
 * <ul>
 * <li>HTTP server on port 8080 for serving static files (HTML, CSS, JS)</li>
 * <li>WebSocket server on port 8081 for real-time game communication</li>
 * </ul>
 */
public class OtbChessServer {

  public static void main(String[] args) throws IOException, InterruptedException {
    // 12-factor config: bind host and ports come from the environment so the same artifact runs
    // unchanged in local dev and behind the proxy/tunnel. The default bind host is 127.0.0.1
    // (loopback only) so the app is reachable solely through Caddy/Cloudflare and never directly
    // from the network. Set OTB_BIND_HOST=0.0.0.0 to expose it on all interfaces.
    final String bindHost = envStr("OTB_BIND_HOST", "127.0.0.1");
    final int httpPort = envInt("OTB_HTTP_PORT", 8080);
    final int wsPort = envInt("OTB_WS_PORT", 8081);
    final Path staticDir = Path.of(envStr("OTB_STATIC_DIR", "static"));

    // Start the WebSocket server first and wait until it is actually listening. The HTTP server
    // (below) is exposed only afterwards, so that once the page is reachable over HTTP the browser
    // can always open its WebSocket. This removes a startup race where a client could load the page
    // before the WebSocket port was bound (the client has no WebSocket-reconnect path).
    final var wsServer = new GameWebSocketServer(bindHost, wsPort);
    wsServer.start();
    try {
      if (!wsServer.awaitStarted(10, TimeUnit.SECONDS)) {
        throw new IOException("WebSocket server did not start within 10 seconds");
      }
      System.out.println("WebSocket server running at ws://" + bindHost + ":" + wsPort);

      // Start HTTP server for static files.
      final var httpServer = HttpServer.create(new InetSocketAddress(bindHost, httpPort), 0);
      final var staticHandler = new StaticFileHandler(staticDir.toAbsolutePath());
      // Liveness/readiness probe for the uptime monitor and the Cloudflare tunnel. Reports the app
      // is up and whether the WebSocket endpoint is actually listening (both must be true for the
      // game to work). Returns 200 with {"status":"ok","websocket":true} when healthy, 503 when the
      // WebSocket port is not (yet) bound.
      httpServer.createContext("/api/health", exchange -> {
        final boolean wsReady = wsServer.isListening();
        final byte[] body = healthJson(wsReady);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(wsReady ? 200 : 503, body.length);
        try (var os = exchange.getResponseBody()) {
          os.write(body);
        }
      });
      // Exposes the Maven project version embedded in the runnable JAR manifest, so static pages
      // can display the release without duplicating it in HTML or JavaScript.
      httpServer.createContext("/api/version", exchange -> {
        final byte[] body = appVersionJson();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
          os.write(body);
        }
      });
      // Validates a starting FEN before the lobby navigates to the board, so an invalid FEN keeps
      // the player on the start screen with the reason instead of stranding them on a dead board.
      // Returns {"valid":true} or {"valid":false,"message":"Invalid FEN: ..."} from Ashlar Chess.
      httpServer.createContext("/api/validateFen", exchange -> {
        final byte[] body = validateFenResponse(exchange.getRequestURI().getRawQuery());
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (var os = exchange.getResponseBody()) {
          os.write(body);
        }
      });
      httpServer.createContext("/", staticHandler::handle);
      httpServer.setExecutor(null);
      httpServer.start();
      System.out.println("HTTP server running at http://" + bindHost + ":" + httpPort);
    } catch (IOException | InterruptedException | RuntimeException e) {
      // Startup failed after the WebSocket server began listening (e.g. the HTTP port is taken).
      // Stop it so the JVM can exit instead of lingering on :8081 (its selector thread is non-daemon).
      try {
        wsServer.stop();
      } catch (final InterruptedException stopInterrupted) {
        Thread.currentThread().interrupt();
      }
      throw e;
    }

    System.out.println();
    System.out.println("OTB Chess is ready!");
    System.out.println("Open http://" + ("0.0.0.0".equals(bindHost) ? "localhost" : bindHost) + ":" + httpPort
        + " in your browser to start.");
  }

  // Package-private so the WebSocket server can read its own 12-factor config (ports, limits, etc.).
  static int envInt(String name, int defaultValue) {
    final String raw = System.getenv(name);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (final NumberFormatException e) {
      System.err.println("Invalid integer for " + name + "='" + raw + "', using default " + defaultValue);
      return defaultValue;
    }
  }

  static long envLong(String name, long defaultValue) {
    final String raw = System.getenv(name);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      return Long.parseLong(raw.trim());
    } catch (final NumberFormatException e) {
      System.err.println("Invalid long for " + name + "='" + raw + "', using default " + defaultValue);
      return defaultValue;
    }
  }

  static String envStr(String name, String defaultValue) {
    final String raw = System.getenv(name);
    return (raw == null || raw.isBlank()) ? defaultValue : raw.trim();
  }

  private static byte[] healthJson(boolean wsListening) {
    final JsonObject obj = new JsonObject();
    obj.addProperty("status", wsListening ? "ok" : "degraded");
    obj.addProperty("websocket", wsListening);
    return GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
  }

  private static final Gson GSON = new Gson();

  /**
   * Builds the {@code /api/validateFen} response body. An empty FEN means the normal starting position and is always
   * valid. Any parse failure is treated as a user FEN error (the FEN is user input) and reported verbatim from Ashlar
   * Chess, mirroring the create-game validation.
   */
  private static byte[] validateFenResponse(String rawQuery) {
    final String fen = queryParam(rawQuery, "fen");
    final JsonObject obj = new JsonObject();
    if (fen == null || fen.isBlank()) {
      obj.addProperty("valid", true);
    } else {
      try {
        Board.fromFenStrict(fen.trim());
        obj.addProperty("valid", true);
      } catch (final Exception e) {
        obj.addProperty("valid", false);
        obj.addProperty("message", "Invalid FEN: " + e.getMessage());
      }
    }
    return GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] appVersionJson() {
    final JsonObject obj = new JsonObject();
    obj.addProperty("version", appVersion());
    return GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
  }

  private static String appVersion() {
    final String version = OtbChessServer.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : version;
  }

  private static String queryParam(String rawQuery, String key) {
    if (rawQuery == null) {
      return null;
    }
    for (final String pair : rawQuery.split("&")) {
      final int eq = pair.indexOf('=');
      if (eq > 0 && key.equals(pair.substring(0, eq))) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
