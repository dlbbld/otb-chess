// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only
package io.github.dlbbld.otbchess.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.dlbbld.ashlarchess.board.enums.Square;
import io.github.dlbbld.otbchess.placement.PlacementMeasure.Measured;

/**
 * The measure of free placement (E-005): the inner circle, and what it makes of a placement. The sample placements
 * are the ones the page's own measure is tested against, so the two cannot drift apart.
 */
class TestPlacementMeasure {

  /** Shared with the browser test of {@code static/js/placement.js}. */
  static final Path SAMPLES = Path.of("tests", "placement-samples.json");

  @Test
  void theRadiusFollowsFromTheShareOfThePieceArea() {
    // 0.875 of a square is the piece image; the inner circle has the given share of its area.
    assertEquals(160, PlacementMeasure.radiusFor(10));
    assertEquals(226, PlacementMeasure.radiusFor(20));
    assertEquals(277, PlacementMeasure.radiusFor(30));
    assertThrows(IllegalArgumentException.class, () -> PlacementMeasure.radiusFor(0));
    assertThrows(IllegalArgumentException.class, () -> PlacementMeasure.radiusFor(101));
  }

  @Test
  void theSamplePlacementsAreMeasuredAsWritten() throws Exception {
    final JsonObject file = JsonParser.parseString(Files.readString(SAMPLES)).getAsJsonObject();
    final int radius = file.get("radius").getAsInt();
    assertEquals(PlacementMeasure.radiusFor(PlacementMeasure.DEFAULT_EXACTNESS_PERCENT), radius);
    int checked = 0;
    for (final JsonElement element : file.getAsJsonArray("samples")) {
      final JsonObject sample = element.getAsJsonObject();
      final String name = sample.get("name").getAsString();
      final Measured measured = PlacementMeasure.measure(sample.get("x").getAsInt(), sample.get("y").getAsInt(),
          radius);
      assertEquals(sample.get("kind").getAsString(), measured.kind().name(), name);
      assertEquals(sample.get("square").isJsonNull() ? "NONE" : sample.get("square").getAsString(),
          measured.square() == Square.NONE ? "NONE" : measured.square().getName(), name);
      final List<String> squares = new ArrayList<>();
      sample.getAsJsonArray("squares").forEach(square -> squares.add(square.getAsString()));
      assertEquals(squares, measured.squares().stream().map(Square::getName).toList(), name);
      checked++;
    }
    assertTrue(checked >= 20, "the sample file was read");
  }

  @Test
  void aCentredPieceIsClearOnEverySquare() {
    final int radius = PlacementMeasure.radiusFor(20);
    for (final Square square : Square.REAL) {
      final Measured measured = PlacementMeasure.measure(PlacementMeasure.centreOfFile(square),
          PlacementMeasure.centreOfRank(square), radius);
      assertEquals(PlacementMeasure.Kind.CLEAR, measured.kind());
      assertEquals(square, measured.square());
    }
  }
}
