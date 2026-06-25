// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.net.httpserver.HttpExchange;

/**
 * Serves static files from a configured directory.
 */
public class StaticFileHandler {

  private final Path staticDir;

  public StaticFileHandler(Path staticDir) {
    this.staticDir = staticDir;
  }

  public void handle(HttpExchange exchange) throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(405, -1);
      return;
    }

    String path = exchange.getRequestURI().getPath();
    if ("/".equals(path)) {
      path = "/index.html";
    }

    // Security: prevent directory traversal
    final Path filePath = staticDir.resolve(path.substring(1)).normalize();
    if (!filePath.startsWith(staticDir)) {
      exchange.sendResponseHeaders(403, -1);
      return;
    }

    if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
      exchange.sendResponseHeaders(404, -1);
      return;
    }

    final byte[] bytes = Files.readAllBytes(filePath);
    final String contentType = guessContentType(filePath.toString());

    exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private static String guessContentType(String path) {
    if (path.endsWith(".html")) {
      return "text/html; charset=UTF-8";
    }
    if (path.endsWith(".css")) {
      return "text/css; charset=UTF-8";
    }
    if (path.endsWith(".js")) {
      return "application/javascript; charset=UTF-8";
    }
    if (path.endsWith(".svg")) {
      return "image/svg+xml";
    }
    if (path.endsWith(".png")) {
      return "image/png";
    }
    if (path.endsWith(".json")) {
      return "application/json; charset=UTF-8";
    }
    return "application/octet-stream";
  }
}
