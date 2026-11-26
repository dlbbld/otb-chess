// The measure of free placement (E-005): where a piece lies is the centre of its image, in whole
// units with 1024 to a square, x along the files from a to h and y along the ranks from 1 to 8.
// The inner circle around that centre decides: inside one square the placement is clear, across
// a border it is unclear, and with the centre outside the board the piece is off the board.
// The board is drawn with a border as wide as the circle's radius (board.css): the centre leaves
// the squares exactly when the circle goes over the outer edge of that border.
//
// The server decides (PlacementMeasure.java); the page needs the same answer at once, for the
// capture and the sound. Both compute in whole numbers and read tests/placement-samples.json in
// their tests, so that they cannot drift apart.
const OtbPlacement = {
  UNITS: 1024,
  BOARD: 8 * 1024,

  squareAt(file, rank) {
    return 'abcdefgh'[file] + (rank + 1);
  },

  // The centre of a square, in units.
  centre(square) {
    return {
      x: (square.charCodeAt(0) - 97) * this.UNITS + this.UNITS / 2,
      y: (square.charCodeAt(1) - 49) * this.UNITS + this.UNITS / 2
    };
  },

  // -> { kind: 'CLEAR' | 'UNCLEAR' | 'OFF_BOARD', square, squares }: the square the centre is on
  // (null off the board) and the squares of the board the inner circle lies on, by file and then
  // by rank.
  measure(x, y, radius) {
    if (x < 0 || x >= this.BOARD || y < 0 || y >= this.BOARD) {
      return { kind: 'OFF_BOARD', square: null, squares: [] };
    }
    const file = Math.floor(x / this.UNITS);
    const rank = Math.floor(y / this.UNITS);
    const left = x % this.UNITS;
    const right = this.UNITS - left;
    const below = y % this.UNITS;
    const above = this.UNITS - below;
    const square = this.squareAt(file, rank);
    // Exactly the radius away from a border is clear: the circle touches the border only.
    if (left >= radius && right >= radius && below >= radius && above >= radius) {
      return { kind: 'CLEAR', square: square, squares: [square] };
    }
    const squares = [];
    for (let f = file - 1; f <= file + 1; f++) {
      for (let r = rank - 1; r <= rank + 1; r++) {
        if (f < 0 || f > 7 || r < 0 || r > 7) continue;
        const dx = f === file ? 0 : (f < file ? left : right);
        const dy = r === rank ? 0 : (r < rank ? below : above);
        if (dx * dx + dy * dy < radius * radius) squares.push(this.squareAt(f, r));
      }
    }
    return { kind: 'UNCLEAR', square: square, squares: squares };
  }
};
