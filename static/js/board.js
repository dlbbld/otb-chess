// Piece SVG rendering (Lichess-style: filled pieces with clear white/black distinction)
const PIECE_SVG = {
  WHITE_KING:   '<svg viewBox="0 0 45 45"><g fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22.5 11.63V6M20 8h5" stroke-linejoin="miter"/><path d="M22.5 25s4.5-7.5 3-10.5c0 0-1-2.5-3-2.5s-3 2.5-3 2.5c-1.5 3 3 10.5 3 10.5" fill="#fff" stroke-linecap="butt" stroke-linejoin="miter"/><path d="M12.5 37c5.5 3.5 14.5 3.5 20 0v-7s9-4.5 6-10.5c-4-6.5-13.5-3.5-16 4V27v-3.5c-2.5-7.5-12-10.5-16-4-3 6 6 10.5 6 10.5v7" fill="#fff"/><path d="M12.5 30c5.5-3 14.5-3 20 0M12.5 33.5c5.5-3 14.5-3 20 0M12.5 37c5.5-3 14.5-3 20 0"/></g></svg>',
  WHITE_QUEEN:  '<svg viewBox="0 0 45 45"><g fill="#fff" stroke="#000" stroke-width="1.5" stroke-linejoin="round"><path d="M9 26c8.5-1.5 21-1.5 27 0l2.5-12.5L31 25l-.3-14.1-5.2 13.6-3-14.5-3 14.5-5.2-13.6L14 25 6.5 13.5 9 26z"/><path d="M9 26c0 2 1.5 2 2.5 4 1 1.5 1 1 .5 3.5-1.5 1-1 2.5-1 2.5-1.5 1.5 0 2.5 0 2.5 6.5 1 16.5 1 23 0 0 0 1.5-1 0-2.5 0 0 .5-1.5-1-2.5-.5-2.5-.5-2 .5-3.5 1-2 2.5-2 2.5-4-8.5-1.5-18.5-1.5-27 0z" stroke-linecap="butt"/><path d="M11.5 30c3.5-1 18.5-1 22 0M12 33.5c6-1 15-1 21 0" fill="none"/><circle cx="6" cy="12" r="2"/><circle cx="14" cy="9" r="2"/><circle cx="22.5" cy="8" r="2"/><circle cx="31" cy="9" r="2"/><circle cx="39" cy="12" r="2"/></g></svg>',
  WHITE_ROOK:   '<svg viewBox="0 0 45 45"><g fill="#fff" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 39h27v-3H9v3zM12.5 32l1.5-2.5h17l1.5 2.5h-20zM12 36v-4h21v4H12z" stroke-linecap="butt"/><path d="M14 29.5v-13h17v13H14z" stroke-linecap="butt" stroke-linejoin="miter"/><path d="M14 16.5L11 14h23l-3 2.5H14zM11 14V9h4v2h5V9h5v2h5V9h4v5H11z" stroke-linecap="butt"/><path d="M12 35.5h21M13 31.5h19M14 29.5h17M14 16.5h17M11 14h23" fill="none" stroke="#000" stroke-linejoin="miter"/></g></svg>',
  WHITE_BISHOP: '<svg viewBox="0 0 45 45"><g fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><g fill="#fff" stroke-linecap="butt"><path d="M9 36c3.39-.97 10.11.43 13.5-2 3.39 2.43 10.11 1.03 13.5 2 0 0 1.65.54 3 2-.68.97-1.65.99-3 .5-3.39-.97-10.11.46-13.5-1-3.39 1.46-10.11.03-13.5 1-1.35.49-2.32.47-3-.5 1.35-1.46 3-2 3-2z"/><path d="M15 32c2.5 2.5 12.5 2.5 15 0 .5-1.5 0-2 0-2 0-2.5-2.5-4-2.5-4 5.5-1.5 6-11.5-5-15.5-11 4-10.5 14-5 15.5 0 0-2.5 1.5-2.5 4 0 0-.5.5 0 2z"/><path d="M25 8a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0z"/></g><path d="M17.5 26h10M15 30h15m-7.5-14.5v5M20 18h5" stroke-linejoin="miter"/></g></svg>',
  WHITE_KNIGHT: '<svg viewBox="0 0 45 45"><g fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22 10c10.5 1 16.5 8 16 29H15c0-9 10-6.5 8-21" fill="#fff"/><path d="M24 18c.38 2.91-5.55 7.37-8 9-3 2-2.82 4.34-5 4-1.042-.94 1.41-3.04 0-3-1 0 .19 1.23-1 2-1 0-4.003 1-4-4 0-2 6-12 6-12s1.89-1.9 2-3.5c-.73-.994-.5-2-.5-3 1-1 3 2.5 3 2.5h2s.78-1.992 2.5-3c1 0 1 3 1 3" fill="#fff"/><path d="M9.5 25.5a.5.5 0 1 1-1 0 .5.5 0 1 1 1 0zm5.433-9.75a.5 1.5 30 1 1-.866-.5.5 1.5 30 1 1 .866.5z" fill="#000"/></g></svg>',
  WHITE_PAWN:   '<svg viewBox="0 0 45 45"><path d="M22.5 9c-2.21 0-4 1.79-4 4 0 .89.29 1.71.78 2.38C17.33 16.5 16 18.59 16 21c0 2.03.94 3.84 2.41 5.03C15 27.09 10.5 31.58 10.5 39.5h24c0-7.92-4.5-12.41-7.91-13.47C28.06 24.84 29 23.03 29 21c0-2.41-1.33-4.5-3.28-5.62.49-.67.78-1.49.78-2.38 0-2.21-1.79-4-4-4z" fill="#fff" stroke="#000" stroke-width="1.5" stroke-linecap="round"/></svg>',
  BLACK_KING:   '<svg viewBox="0 0 45 45"><g fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22.5 11.63V6" stroke-linejoin="miter"/><path d="M22.5 25s4.5-7.5 3-10.5c0 0-1-2.5-3-2.5s-3 2.5-3 2.5c-1.5 3 3 10.5 3 10.5" fill="#000" stroke-linecap="butt" stroke-linejoin="miter"/><path d="M12.5 37c5.5 3.5 14.5 3.5 20 0v-7s9-4.5 6-10.5c-4-6.5-13.5-3.5-16 4V27v-3.5c-2.5-7.5-12-10.5-16-4-3 6 6 10.5 6 10.5v7" fill="#000"/><path d="M20 8h5" stroke-linejoin="miter"/><path d="M32 29.5s8.5-4 6.03-9.65C34.15 14 25 18 22.5 24.5v2.1-2.1C20 18 10.85 14 6.97 19.85 4.5 25.5 13 29.5 13 29.5" fill="none" stroke="#fff"/><path d="M12.5 30c5.5-3 14.5-3 20 0m-20 3.5c5.5-3 14.5-3 20 0m-20 3.5c5.5-3 14.5-3 20 0" fill="none" stroke="#fff"/></g></svg>',
  BLACK_QUEEN:  '<svg viewBox="0 0 45 45"><g fill="#000" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><g stroke="none"><circle cx="6" cy="12" r="2.75"/><circle cx="14" cy="9" r="2.75"/><circle cx="22.5" cy="8" r="2.75"/><circle cx="31" cy="9" r="2.75"/><circle cx="39" cy="12" r="2.75"/></g><path d="M9 26c8.5-1.5 21-1.5 27 0l2.5-12.5L31 25l-.3-14.1-5.2 13.6-3-14.5-3 14.5-5.2-13.6L14 25 6.5 13.5 9 26z" stroke-linecap="butt"/><path d="M9 26c0 2 1.5 2 2.5 4 1 1.5 1 1 .5 3.5-1.5 1-1 2.5-1 2.5-1.5 1.5 0 2.5 0 2.5 6.5 1 16.5 1 23 0 0 0 1.5-1 0-2.5 0 0 .5-1.5-1-2.5-.5-2.5-.5-2 .5-3.5 1-2 2.5-2 2.5-4-8.5-1.5-18.5-1.5-27 0z" stroke-linecap="butt"/><path d="M11.5 30c3.5-1 18.5-1 22 0M12 33.5c6-1 15-1 21 0" fill="none" stroke="#fff"/></g></svg>',
  BLACK_ROOK:   '<svg viewBox="0 0 45 45"><g fill="#000" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 39h27v-3H9v3zM12.5 32l1.5-2.5h17l1.5 2.5h-20zM12 36v-4h21v4H12z" stroke-linecap="butt"/><path d="M14 29.5v-13h17v13H14z" stroke-linecap="butt" stroke-linejoin="miter"/><path d="M14 16.5L11 14h23l-3 2.5H14zM11 14V9h4v2h5V9h5v2h5V9h4v5H11z" stroke-linecap="butt"/><path d="M12 35.5h21M13 31.5h19M14 29.5h17M14 16.5h17M11 14h23" fill="none" stroke="#fff" stroke-linejoin="miter"/></g></svg>',
  BLACK_BISHOP: '<svg viewBox="0 0 45 45"><g fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><g fill="#000" stroke-linecap="butt"><path d="M9 36c3.39-.97 10.11.43 13.5-2 3.39 2.43 10.11 1.03 13.5 2 0 0 1.65.54 3 2-.68.97-1.65.99-3 .5-3.39-.97-10.11.46-13.5-1-3.39 1.46-10.11.03-13.5 1-1.35.49-2.32.47-3-.5 1.35-1.46 3-2 3-2z"/><path d="M15 32c2.5 2.5 12.5 2.5 15 0 .5-1.5 0-2 0-2 0-2.5-2.5-4-2.5-4 5.5-1.5 6-11.5-5-15.5-11 4-10.5 14-5 15.5 0 0-2.5 1.5-2.5 4 0 0-.5.5 0 2z"/><path d="M25 8a2.5 2.5 0 1 1-5 0 2.5 2.5 0 1 1 5 0z"/></g><path d="M17.5 26h10M15 30h15m-7.5-14.5v5M20 18h5" stroke="#fff" stroke-linejoin="miter"/></g></svg>',
  BLACK_KNIGHT: '<svg viewBox="0 0 45 45"><g fill="none" fill-rule="evenodd" stroke="#000" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M22 10c10.5 1 16.5 8 16 29H15c0-9 10-6.5 8-21" fill="#000"/><path d="M24 18c.38 2.91-5.55 7.37-8 9-3 2-2.82 4.34-5 4-1.042-.94 1.41-3.04 0-3-1 0 .19 1.23-1 2-1 0-4.003 1-4-4 0-2 6-12 6-12s1.89-1.9 2-3.5c-.73-.994-.5-2-.5-3 1-1 3 2.5 3 2.5h2s.78-1.992 2.5-3c1 0 1 3 1 3" fill="#000"/><path d="M9.5 25.5a.5.5 0 1 1-1 0 .5.5 0 1 1 1 0zm5.433-9.75a.5 1.5 30 1 1-.866-.5.5 1.5 30 1 1 .866.5z" fill="#fff"/></g></svg>',
  BLACK_PAWN:   '<svg viewBox="0 0 45 45"><path d="M22.5 9c-2.21 0-4 1.79-4 4 0 .89.29 1.71.78 2.38C17.33 16.5 16 18.59 16 21c0 2.03.94 3.84 2.41 5.03C15 27.09 10.5 31.58 10.5 39.5h24c0-7.92-4.5-12.41-7.91-13.47C28.06 24.84 29 23.03 29 21c0-2.41-1.33-4.5-3.28-5.62.49-.67.78-1.49.78-2.38 0-2.21-1.79-4-4-4z" fill="#000" stroke="#000" stroke-width="1.5" stroke-linecap="round"/></svg>',
  NONE: ''
};

const FILES = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'];
const RANKS = ['1', '2', '3', '4', '5', '6', '7', '8'];

function getPieceSvg(piece) {
  return PIECE_SVG[piece] || '';
}

class ChessBoard {
  // The colours of the arbiter's circles (see setMarks).
  static MARK_COLOURS = ['green', 'red', 'blue'];

  constructor(boardElement, side) {
    this.boardEl = boardElement;
    this.side = side; // 'white' or 'black'
    this.flipped = side === 'black';
    this.squares = {}; // squareName -> { piece: 'WHITE_PAWN', element: div }
    this.pieceState = {}; // squareName -> pieceName (kept separate from DOM)
    this.dragging = null;
    this.floatingPiece = null;
    this.eventCallback = null;
    this.placedCallback = null;
    this.enabled = false;
    // Squares the arbiter points at: squareName -> 'green' | 'red' | 'blue' (see setMarks).
    this.marks = {};
    // Free placement (E-005): the pieces lie where they were let go, in a layer over the squares.
    // Off until the server sends placements (setPlacements); then this.squares holds no pieces.
    this.free = false;
    this.placements = []; // { id, piece, x, y } in units (see placement.js), the top piece last
    this.placementRadius = 0;
    this.layerEl = null;

    this.buildBoard();
    this.setupDragListeners();
  }

  buildBoard() {
    // Save current piece state before rebuilding
    const savedState = {};
    for (const [sq, data] of Object.entries(this.squares)) {
      savedState[sq] = data.piece;
    }

    this.boardEl.innerHTML = '';
    this.squares = {};

    const rankOrder = this.flipped ? RANKS.slice() : RANKS.slice().reverse();
    const fileOrder = this.flipped ? FILES.slice().reverse() : FILES.slice();

    for (let r = 0; r < 8; r++) {
      for (let f = 0; f < 8; f++) {
        const file = fileOrder[f];
        const rank = rankOrder[r];
        const squareName = file + rank;
        const isLight = (FILES.indexOf(file) + RANKS.indexOf(rank)) % 2 === 1;

        const sq = document.createElement('div');
        sq.className = 'square ' + (isLight ? 'light' : 'dark');
        sq.dataset.square = squareName;

        // Coordinate labels
        if (r === 7) {
          const fileLabel = document.createElement('span');
          fileLabel.className = 'coord file-label';
          fileLabel.textContent = file;
          sq.appendChild(fileLabel);
        }
        if (f === 0) {
          const rankLabel = document.createElement('span');
          rankLabel.className = 'coord rank-label';
          rankLabel.textContent = rank;
          sq.appendChild(rankLabel);
        }

        if (this.marks[squareName]) {
          sq.classList.add('mark-' + this.marks[squareName]);
        }
        this.boardEl.appendChild(sq);
        // Restore piece state or default to NONE
        const piece = savedState[squareName] || 'NONE';
        this.squares[squareName] = { piece: piece, element: sq };
      }
    }

    this.layerEl = null;
    this.renderAll();
  }

  setPosition(boardState) {
    if (this.free) return; // the server sends placements with every position
    for (const [squareName, piece] of Object.entries(boardState)) {
      if (this.squares[squareName]) {
        this.squares[squareName].piece = piece;
        this.renderSquare(squareName);
      }
    }
  }

  renderSquare(squareName) {
    const sq = this.squares[squareName];
    const existing = sq.element.querySelector('.piece');
    if (existing) existing.remove();

    if (!this.free && sq.piece !== 'NONE') {
      const pieceEl = document.createElement('div');
      pieceEl.className = 'piece';
      pieceEl.innerHTML = getPieceSvg(sq.piece);
      pieceEl.dataset.piece = sq.piece;
      sq.element.appendChild(pieceEl);
    }
  }

  renderAll() {
    for (const squareName of Object.keys(this.squares)) {
      this.renderSquare(squareName);
    }
    if (this.free) this.renderLayer();
  }

  // === Free placement (E-005) ===

  /**
   * Shows the pieces where the server says they lie. From the first call on the board is one with
   * free placement: a piece stays where it is let go.
   */
  setPlacements(placements, radius) {
    this.free = true;
    this.placementRadius = radius || this.placementRadius;
    this.placements = placements.map((p) => ({ id: p.id, piece: p.piece, x: p.x, y: p.y }));
    for (const sq of Object.values(this.squares)) sq.piece = 'NONE';
    this.renderAll();
  }

  measureOf(placement) {
    return OtbPlacement.measure(placement.x, placement.y, this.placementRadius);
  }

  renderLayer() {
    if (!this.layerEl || !this.boardEl.contains(this.layerEl)) {
      this.layerEl = document.createElement('div');
      this.layerEl.className = 'piece-layer';
      this.boardEl.appendChild(this.layerEl);
    }
    this.layerEl.innerHTML = '';
    for (const p of this.placements) {
      // A piece in a hand - the player's or the opponent's - is not on the board meanwhile.
      if (p.held || p.id === this.opponentDragId) continue;
      const el = document.createElement('div');
      el.className = 'piece free-piece';
      el.innerHTML = getPieceSvg(p.piece);
      el.dataset.id = p.id;
      el.dataset.piece = p.piece;
      const measured = this.measureOf(p);
      // What the placement is, for the eye of a test: the square of a clear piece, or "unclear".
      if (measured.kind === 'CLEAR') {
        el.dataset.square = measured.square;
      } else {
        el.dataset.unclear = measured.squares.join(' ');
      }
      const pos = this.toBoardShare(p.x, p.y);
      el.style.left = (pos.left * 100) + '%';
      el.style.top = (pos.top * 100) + '%';
      this.layerEl.appendChild(el);
    }
  }

  // Units -> the share of the board's width and height from its top left corner, as drawn.
  toBoardShare(x, y) {
    const fx = x / OtbPlacement.BOARD;
    const fy = y / OtbPlacement.BOARD;
    return this.flipped ? { left: 1 - fx, top: fy } : { left: fx, top: 1 - fy };
  }

  // The inside of the board (without its frame) on the screen.
  boardRect() {
    const rect = this.boardEl.getBoundingClientRect();
    return {
      left: rect.left + this.boardEl.clientLeft,
      top: rect.top + this.boardEl.clientTop,
      width: this.boardEl.clientWidth,
      height: this.boardEl.clientHeight
    };
  }

  // A point of the screen -> units.
  toUnits(clientX, clientY) {
    const rect = this.boardRect();
    let fx = (clientX - rect.left) / rect.width;
    let fy = 1 - (clientY - rect.top) / rect.height;
    if (this.flipped) {
      fx = 1 - fx;
      fy = 1 - fy;
    }
    return { x: Math.round(fx * OtbPlacement.BOARD), y: Math.round(fy * OtbPlacement.BOARD) };
  }

  // Units -> a point of the screen.
  toClient(x, y) {
    const rect = this.boardRect();
    const pos = this.toBoardShare(x, y);
    return { x: rect.left + pos.left * rect.width, y: rect.top + pos.top * rect.height };
  }

  /**
   * Sets a piece down where it was let go - by this player or, mirrored, by the opponent. A piece
   * with its centre outside the board leaves it. A clear placement on a square where another piece
   * stands clearly puts that piece to the side; an unclear placement removes nothing.
   *
   * @returns { offBoard, capturedId, changed }
   */
  applyRelease(id, piece, x, y) {
    const index = this.placements.findIndex((p) => p.id === id);
    const before = index >= 0 ? this.placements[index] : null;
    if (index >= 0) this.placements.splice(index, 1);
    const measured = OtbPlacement.measure(x, y, this.placementRadius);
    if (measured.kind === 'OFF_BOARD') {
      this.renderLayer();
      return { offBoard: true, capturedId: 0, changed: !!before };
    }
    let capturedId = 0;
    if (measured.kind === 'CLEAR') {
      const occupant = this.placements.find((p) => {
        const m = this.measureOf(p);
        return m.kind === 'CLEAR' && m.square === measured.square;
      });
      if (occupant) {
        capturedId = occupant.id;
        this.placements.splice(this.placements.indexOf(occupant), 1);
      }
    }
    this.placements.push({ id: id, piece: piece, x: x, y: y });
    this.renderLayer();
    return { offBoard: false, capturedId: capturedId, changed: !before || before.x !== x || before.y !== y };
  }

  /** The pieces on the board, also one that lies across a border or is in a hand. */
  getOnBoardPieces() {
    if (this.free) return this.placements.map((p) => p.piece);
    return Object.values(this.squares).map((sq) => sq.piece).filter((piece) => piece !== 'NONE');
  }

  flip() {
    this.flipped = !this.flipped;
    this.buildBoard();
  }

  /**
   * Circles the squares of the arbiter's ruling, as the server sent them: green for a piece the
   * player must move or capture and for the start and end square of a move that was already made,
   * red for the start and end square of an illegal move, blue for the squares a piece let go
   * across a border has to be placed on. Replaces the circles shown before; an empty list removes them. The class sits on the square, so the
   * circle follows the square when the board is flipped and stays when its piece is redrawn.
   */
  setMarks(marks) {
    for (const sq of Object.values(this.squares)) {
      sq.element.classList.remove(...ChessBoard.MARK_COLOURS.map((colour) => 'mark-' + colour));
    }
    this.marks = {};
    for (const mark of marks || []) {
      const sq = this.squares[mark.square];
      if (!sq || !ChessBoard.MARK_COLOURS.includes(mark.color)) continue;
      this.marks[mark.square] = mark.color;
      sq.element.classList.add('mark-' + mark.color);
    }
  }

  getBoardState() {
    const state = {};
    for (const [squareName, data] of Object.entries(this.squares)) {
      state[squareName] = data.piece;
    }
    if (this.free) {
      // The pieces in a clear placement. The server rules on its own record of where they lie.
      for (const p of this.placements) {
        const measured = this.measureOf(p);
        if (measured.kind === 'CLEAR') state[measured.square] = p.piece;
      }
    }
    return state;
  }

  setEnabled(enabled) {
    this.enabled = enabled;
  }

  onEvent(callback) {
    this.eventCallback = callback;
  }

  /**
   * Registers what happens when a held piece is set down on a square of the board - by this player
   * or, mirrored, by the opponent: a piece moved to another square, with or without a capture, or
   * brought back from the side. Not a piece touched or put back where it stood, not a piece taken
   * off the board, and not a position the server sets.
   */
  onPiecePlaced(callback) {
    this.placedCallback = callback;
  }

  piecePlaced() {
    if (this.placedCallback) this.placedCallback();
  }

  emitEvent(eventType, square, targetSquare, piece, displacedPiece) {
    if (this.eventCallback) {
      this.eventCallback({
        eventType: eventType,
        square: square || 'NONE',
        targetSquare: targetSquare || 'NONE',
        piece: piece || 'NONE',
        displacedPiece: displacedPiece || 'NONE'
      });
    }
  }

  // === Drag and Drop ===

  setupDragListeners() {
    // Board piece drag — use mousedown on the board
    this.boardEl.addEventListener('mousedown', (e) => this.onBoardMouseDown(e));
    document.addEventListener('mousemove', (e) => this.onMouseMove(e));
    document.addEventListener('mouseup', (e) => this.onMouseUp(e));
  }

  // Start dragging from the board
  onBoardMouseDown(e) {
    if (!this.enabled) return;
    if (this.free) {
      this.onFreeMouseDown(e);
      return;
    }
    const squareEl = e.target.closest('.square');
    if (!squareEl) return;

    const squareName = squareEl.dataset.square;
    const sq = this.squares[squareName];
    if (sq.piece === 'NONE') return;

    e.preventDefault();
    this.startDrag(sq.piece, squareName, false, e.clientX, e.clientY);

    // Match a real chessboard: once the piece is in the player's hand the source
    // square is empty until something is placed there again. The piece value is
    // remembered in this.dragging so we can restore it on a CLICK (drop on the
    // same square).
    sq.piece = 'NONE';
    this.renderSquare(squareName);
  }

  // Start dragging from the side area
  startSideAreaDrag(piece, e) {
    if (!this.enabled) return;
    e.preventDefault();
    if (this.free) {
      this.startFreeDrag({ piece: piece, fromSideArea: true }, e.clientX, e.clientY, 0, 0);
      return;
    }
    this.startDrag(piece, null, true, e.clientX, e.clientY);
  }

  // Free placement: the piece whose image is under the mouse, the top one first.
  onFreeMouseDown(e) {
    const pieceEl = e.target.closest('.free-piece');
    if (!pieceEl) return;
    const placement = this.placements.find((p) => String(p.id) === pieceEl.dataset.id);
    if (!placement) return;
    e.preventDefault();
    // The piece is held where it was grabbed, not by its centre: it does not jump on the pick-up.
    const centre = this.toClient(placement.x, placement.y);
    placement.held = true;
    this.startFreeDrag({ id: placement.id, piece: placement.piece, fromSideArea: false, origin: placement },
      e.clientX, e.clientY, centre.x - e.clientX, centre.y - e.clientY);
    this.renderLayer();
  }

  startFreeDrag(dragging, x, y, offsetX, offsetY) {
    this.dragging = Object.assign(dragging, {
      startX: x, startY: y, offsetX: offsetX, offsetY: offsetY, moved: false
    });
    this.lastHoverSquare = null;
    this.floatingPiece = document.createElement('div');
    this.floatingPiece.className = 'floating-piece free';
    this.floatingPiece.innerHTML = getPieceSvg(dragging.piece);
    this.floatingPiece.style.left = (x + offsetX) + 'px';
    this.floatingPiece.style.top = (y + offsetY) + 'px';
    document.body.appendChild(this.floatingPiece);
    this.emitRaw({ eventType: 'DRAG_START', id: dragging.id || 0, piece: dragging.piece });
  }

  onFreeMouseMove(e) {
    const d = this.dragging;
    const cx = e.clientX + d.offsetX;
    const cy = e.clientY + d.offsetY;
    this.floatingPiece.style.left = cx + 'px';
    this.floatingPiece.style.top = cy + 'px';
    // A click never shifts a piece: it only moves once the mouse has travelled 1/16 of a square.
    const threshold = this.boardRect().width / 8 / 16;
    if (Math.hypot(e.clientX - d.startX, e.clientY - d.startY) > threshold) d.moved = true;
    const units = this.toUnits(cx, cy);
    const square = OtbPlacement.measure(units.x, units.y, this.placementRadius).square || 'NONE';
    if (square !== this.lastHoverSquare) {
      this.lastHoverSquare = square;
      this.emitRaw({ eventType: 'DRAG_HOVER', targetSquare: square, piece: d.piece });
    }
  }

  onFreeMouseUp(e) {
    const d = this.dragging;
    this.floatingPiece.remove();
    this.floatingPiece = null;
    this.dragging = null;
    let units = this.toUnits(e.clientX + d.offsetX, e.clientY + d.offsetY);
    if (d.fromSideArea) {
      // Let go outside the board: the piece stays at the side.
      if (OtbPlacement.measure(units.x, units.y, this.placementRadius).kind === 'OFF_BOARD') return;
      if (window.game) window.game.consumeDraggedSidePiece();
      const id = this.placements.reduce((max, p) => Math.max(max, p.id), 0) + 1;
      this.applyRelease(id, d.piece, units.x, units.y);
      this.piecePlaced();
      this.emitRaw({ eventType: 'RELEASE', id: id, x: units.x, y: units.y, fromSide: d.piece });
    } else {
      d.origin.held = false;
      if (!d.moved) units = { x: d.origin.x, y: d.origin.y };
      const result = this.applyRelease(d.id, d.piece, units.x, units.y);
      if (result.changed && !result.offBoard) this.piecePlaced();
      this.emitRaw({ eventType: 'RELEASE', id: d.id, x: units.x, y: units.y });
    }
    if (window.game) window.game.recomputeSideArea();
  }

  emitRaw(event) {
    if (this.eventCallback) this.eventCallback(event);
  }

  startDrag(piece, squareName, fromSideArea, x, y) {
    this.dragging = {
      squareName: squareName,
      piece: piece,
      fromSideArea: fromSideArea
    };
    this.lastHoverSquare = null;

    this.floatingPiece = document.createElement('div');
    this.floatingPiece.className = 'floating-piece';
    this.floatingPiece.innerHTML = getPieceSvg(piece);
    this.floatingPiece.style.left = x + 'px';
    this.floatingPiece.style.top = y + 'px';
    document.body.appendChild(this.floatingPiece);

    // Tell the opponent's board the drag has begun so they can spawn a floating piece
    // and dim the source square — exactly mirroring what the dragging player sees.
    this.emitEvent('DRAG_START', squareName || 'NONE', 'NONE', piece, 'NONE');
  }

  onMouseMove(e) {
    if (!this.floatingPiece) return;
    if (this.free) {
      if (this.dragging) this.onFreeMouseMove(e);
      return;
    }
    this.floatingPiece.style.left = e.clientX + 'px';
    this.floatingPiece.style.top = e.clientY + 'px';

    // Square-change-throttled hover stream for the opponent's view.
    // We only emit when the cursor crosses into a new square (or off the board),
    // which keeps the bandwidth tiny — at most ~10 events for a typical drag —
    // while still showing the opponent the piece gliding to its destination.
    const targetEl = document.elementFromPoint(e.clientX, e.clientY);
    const squareEl = targetEl ? targetEl.closest('.square') : null;
    const onBoard = squareEl && this.boardEl.contains(squareEl);
    const currentSquare = onBoard ? squareEl.dataset.square : 'NONE';
    if (currentSquare !== this.lastHoverSquare && this.dragging) {
      this.lastHoverSquare = currentSquare;
      this.emitEvent('DRAG_HOVER', 'NONE', currentSquare, this.dragging.piece, 'NONE');
    }
  }

  onMouseUp(e) {
    if (!this.dragging || !this.floatingPiece) return;
    if (this.free) {
      this.onFreeMouseUp(e);
      return;
    }

    this.floatingPiece.remove();
    this.floatingPiece = null;

    const fromSquare = this.dragging.squareName;
    const fromPiece = this.dragging.piece;
    const fromSideArea = this.dragging.fromSideArea;
    this.dragging = null;

    // Find target square
    const targetEl = document.elementFromPoint(e.clientX, e.clientY);
    const squareEl = targetEl ? targetEl.closest('.square') : null;
    const onBoard = squareEl && this.boardEl.contains(squareEl);

    if (fromSideArea) {
      // Dragging from side area
      if (!onBoard) {
        // Dropped back outside — do nothing, piece stays in side area
        return;
      }
      const targetSquare = squareEl.dataset.square;
      const targetPiece = this.squares[targetSquare].piece;

      // Remove from side area
      if (window.game) window.game.consumeDraggedSidePiece();

      if (targetPiece === 'NONE') {
        this.squares[targetSquare].piece = fromPiece;
        this.renderSquare(targetSquare);
        this.piecePlaced();
        this.emitEvent('RESTORE_TO_EMPTY', 'NONE', targetSquare, fromPiece, 'NONE');
      } else {
        this.squares[targetSquare].piece = fromPiece;
        this.renderSquare(targetSquare);
        this.piecePlaced();
        this.emitEvent('RESTORE_TO_OCCUPIED', 'NONE', targetSquare, fromPiece, targetPiece);
        if (window.game) window.game.addToSideArea(targetPiece, targetSquare);
      }
    } else {
      // Dragging from board
      if (!onBoard) {
        // Dropped outside the board -> REMOVE
        this.squares[fromSquare].piece = 'NONE';
        this.renderSquare(fromSquare);
        this.emitEvent('REMOVE', fromSquare, 'NONE', fromPiece, 'NONE');
        if (window.game) window.game.addToSideArea(fromPiece, fromSquare);
      } else {
        const targetSquare = squareEl.dataset.square;

        if (targetSquare === fromSquare) {
          // Dropped on same square -> CLICK. The source square was emptied at
          // mousedown, so restore the piece now that it's back in place.
          this.squares[fromSquare].piece = fromPiece;
          this.renderSquare(fromSquare);
          this.emitEvent('CLICK', fromSquare, 'NONE', fromPiece, 'NONE');
        } else {
          const targetPiece = this.squares[targetSquare].piece;

          if (targetPiece === 'NONE') {
            // DRAG_MOVE
            this.squares[fromSquare].piece = 'NONE';
            this.squares[targetSquare].piece = fromPiece;
            this.renderSquare(fromSquare);
            this.renderSquare(targetSquare);
            this.piecePlaced();
            this.emitEvent('DRAG_MOVE', fromSquare, targetSquare, fromPiece, 'NONE');
          } else {
            // DRAG_CAPTURE
            this.squares[fromSquare].piece = 'NONE';
            this.squares[targetSquare].piece = fromPiece;
            this.renderSquare(fromSquare);
            this.renderSquare(targetSquare);
            this.piecePlaced();
            this.emitEvent('DRAG_CAPTURE', fromSquare, targetSquare, fromPiece, targetPiece);
            if (window.game) window.game.addToSideArea(targetPiece, targetSquare);
          }
        }
      }
    }
  }

  highlightSquare(squareName) {
    if (this.squares[squareName]) {
      this.squares[squareName].element.classList.add('highlight');
    }
  }

  clearHighlights() {
    for (const sq of Object.values(this.squares)) {
      sq.element.classList.remove('highlight');
    }
  }

  /**
   * Applies an opponent's board event visually, without emitting events back to the server.
   * This mirrors the physical board experience: you see your opponent manipulating pieces.
   */
  applyOpponentEvent(event) {
    if (this.free) {
      this.applyOpponentFreeEvent(event);
      return;
    }
    const type = event.eventType;
    const square = event.square;
    const targetSquare = event.targetSquare;
    const piece = event.piece;

    // Any non-cosmetic event ends the drag visualisation: the dragging player has
    // either dropped the piece, removed it, or done something equivalent. Clear
    // the floating piece and un-dim the source before applying the new state.
    if (type !== 'DRAG_START' && type !== 'DRAG_HOVER') {
      this.clearOpponentDragVisuals();
    }

    switch (type) {
      case 'DRAG_START': {
        // Opponent began a drag — empty the source square (the piece is now in
        // their hand) and float a copy at its centre. For side-area drags the
        // source square is 'NONE'; we leave the floating piece hidden until the
        // first DRAG_HOVER places it on the board.
        this.opponentDragSource = square;
        this.opponentDragPiece = piece;
        if (square !== 'NONE' && this.squares[square]) {
          this.squares[square].piece = 'NONE';
          this.renderSquare(square);
        }
        this.opponentFloating = document.createElement('div');
        this.opponentFloating.className = 'floating-piece opponent-floating-piece';
        this.opponentFloating.innerHTML = getPieceSvg(piece);
        document.body.appendChild(this.opponentFloating);
        if (square !== 'NONE' && this.squares[square]) {
          const rect = this.squares[square].element.getBoundingClientRect();
          this.opponentFloating.style.left = (rect.left + rect.width / 2) + 'px';
          this.opponentFloating.style.top = (rect.top + rect.height / 2) + 'px';
        } else {
          this.opponentFloating.style.display = 'none';
        }
        break;
      }
      case 'DRAG_HOVER': {
        // Opponent's cursor entered a different square (or left the board).
        // Move the floating piece to that square's centre on THIS player's screen,
        // which works correctly even when the two players have flipped boards
        // because we look up the square element by name in our own DOM.
        if (!this.opponentFloating) break;
        if (targetSquare === 'NONE' || !this.squares[targetSquare]) {
          this.opponentFloating.style.display = 'none';
        } else {
          const rect = this.squares[targetSquare].element.getBoundingClientRect();
          this.opponentFloating.style.left = (rect.left + rect.width / 2) + 'px';
          this.opponentFloating.style.top = (rect.top + rect.height / 2) + 'px';
          this.opponentFloating.style.display = '';
        }
        break;
      }
      case 'CLICK': {
        // Opponent picked up the piece and put it back on the same square. The
        // source was emptied during DRAG_START, so restore the piece now that
        // the click ended where it began. The brief blue highlight tells the
        // viewer the square was touched (touch-move-relevant).
        if (square !== 'NONE' && this.squares[square] && piece !== 'NONE') {
          this.squares[square].piece = piece;
          this.renderSquare(square);
        }
        if (square !== 'NONE' && this.squares[square]) {
          const el = this.squares[square].element;
          el.classList.add('opponent-touch');
          setTimeout(() => el.classList.remove('opponent-touch'), 1200);
        }
        break;
      }
      case 'DRAG_MOVE': {
        // Opponent moved a piece to an empty square
        if (square !== 'NONE' && targetSquare !== 'NONE'
            && this.squares[square] && this.squares[targetSquare]) {
          this.squares[square].piece = 'NONE';
          this.squares[targetSquare].piece = piece;
          this.renderSquare(square);
          this.renderSquare(targetSquare);
          this.piecePlaced();
        }
        break;
      }
      case 'DRAG_CAPTURE': {
        // Opponent moved a piece onto an occupied square (capturing)
        if (square !== 'NONE' && targetSquare !== 'NONE'
            && this.squares[square] && this.squares[targetSquare]) {
          this.squares[square].piece = 'NONE';
          this.squares[targetSquare].piece = piece;
          this.renderSquare(square);
          this.renderSquare(targetSquare);
          this.piecePlaced();
        }
        break;
      }
      case 'REMOVE': {
        // Opponent removed a piece from the board
        if (square !== 'NONE' && this.squares[square]) {
          this.squares[square].piece = 'NONE';
          this.renderSquare(square);
        }
        break;
      }
      case 'RESTORE_TO_EMPTY': {
        // Opponent placed a piece from side area onto an empty square
        if (targetSquare !== 'NONE' && this.squares[targetSquare]) {
          this.squares[targetSquare].piece = piece;
          this.renderSquare(targetSquare);
          this.piecePlaced();
        }
        break;
      }
      case 'RESTORE_TO_OCCUPIED': {
        // Opponent placed a piece from side area onto an occupied square
        if (targetSquare !== 'NONE' && this.squares[targetSquare]) {
          this.squares[targetSquare].piece = piece;
          this.renderSquare(targetSquare);
          this.piecePlaced();
        }
        break;
      }
    }
  }

  // Free placement: the piece in the opponent's hand is shown as in every game, at the centre of
  // the square it is over; where it is let go is exact.
  applyOpponentFreeEvent(event) {
    if (event.eventType === 'DRAG_START') {
      this.clearOpponentDragVisuals();
      const held = this.placements.find((p) => p.id === event.id);
      this.opponentDragId = held ? held.id : 0;
      this.opponentFloating = document.createElement('div');
      this.opponentFloating.className = 'floating-piece free opponent-floating-piece';
      this.opponentFloating.innerHTML = getPieceSvg(event.piece);
      document.body.appendChild(this.opponentFloating);
      if (held) {
        const at = this.toClient(held.x, held.y);
        this.opponentFloating.style.left = at.x + 'px';
        this.opponentFloating.style.top = at.y + 'px';
      } else {
        this.opponentFloating.style.display = 'none';
      }
      this.renderLayer();
    } else if (event.eventType === 'DRAG_HOVER') {
      if (!this.opponentFloating) return;
      if (!event.targetSquare || event.targetSquare === 'NONE' || !this.squares[event.targetSquare]) {
        this.opponentFloating.style.display = 'none';
      } else {
        const centre = OtbPlacement.centre(event.targetSquare);
        const at = this.toClient(centre.x, centre.y);
        this.opponentFloating.style.left = at.x + 'px';
        this.opponentFloating.style.top = at.y + 'px';
        this.opponentFloating.style.display = '';
      }
    } else if (event.eventType === 'RELEASE') {
      this.clearOpponentDragVisuals();
      const result = this.applyRelease(event.id, event.piece, event.x, event.y);
      if (result.changed && !result.offBoard) this.piecePlaced();
      // A piece touched and left where it lies: the square under it flashes, as in every game.
      // An adjustment with J'adoube is no touch.
      if (!result.changed && !result.offBoard && !event.adjusted) {
        const under = OtbPlacement.measure(event.x, event.y, this.placementRadius).square;
        const el = under && this.squares[under] ? this.squares[under].element : null;
        if (el) {
          el.classList.add('opponent-touch');
          setTimeout(() => el.classList.remove('opponent-touch'), 1200);
        }
      }
    }
  }

  /**
   * Pieces currently held in a hand after being lifted off a board square: this player's own drag
   * and the opponent's mirrored drag. Their squares show empty while held, but the pieces are
   * still in play, not captured.
   */
  getLiftedBoardPieces() {
    const lifted = [];
    if (this.free) return lifted; // a held piece stays in the list of placements
    if (this.dragging && !this.dragging.fromSideArea && this.dragging.squareName) {
      lifted.push(this.dragging.piece);
    }
    if (this.opponentDragSource && this.opponentDragSource !== 'NONE' && this.opponentDragPiece) {
      lifted.push(this.opponentDragPiece);
    }
    return lifted;
  }

  /**
   * Removes the floating piece spawned by the opponent's drag stream and, if the
   * source square was emptied at DRAG_START, puts the piece back. Safe to call
   * when no opponent drag is in progress. Called automatically before any
   * non-DRAG_* event in applyOpponentEvent (so DRAG_MOVE / DRAG_CAPTURE / REMOVE
   * see a restored source which they then re-clear normally) and from external
   * callers on flip / opponent disconnect / game reset.
   */
  clearOpponentDragVisuals() {
    if (this.opponentFloating) {
      this.opponentFloating.remove();
      this.opponentFloating = null;
    }
    if (this.free) {
      this.opponentDragId = 0;
      this.renderLayer();
      return;
    }
    if (this.opponentDragSource && this.opponentDragSource !== 'NONE'
        && this.squares[this.opponentDragSource] && this.opponentDragPiece) {
      this.squares[this.opponentDragSource].piece = this.opponentDragPiece;
      this.renderSquare(this.opponentDragSource);
    }
    this.opponentDragSource = null;
    this.opponentDragPiece = null;
  }
}
