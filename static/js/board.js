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
  constructor(boardElement, side) {
    this.boardEl = boardElement;
    this.side = side; // 'white' or 'black'
    this.flipped = side === 'black';
    this.squares = {}; // squareName -> { piece: 'WHITE_PAWN', element: div }
    this.pieceState = {}; // squareName -> pieceName (kept separate from DOM)
    this.dragging = null;
    this.floatingPiece = null;
    this.eventCallback = null;
    this.enabled = false;

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

        this.boardEl.appendChild(sq);
        // Restore piece state or default to NONE
        const piece = savedState[squareName] || 'NONE';
        this.squares[squareName] = { piece: piece, element: sq };
      }
    }

    this.renderAll();
  }

  setPosition(boardState) {
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

    if (sq.piece !== 'NONE') {
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
  }

  flip() {
    this.flipped = !this.flipped;
    this.buildBoard();
  }

  getBoardState() {
    const state = {};
    for (const [squareName, data] of Object.entries(this.squares)) {
      state[squareName] = data.piece;
    }
    return state;
  }

  setEnabled(enabled) {
    this.enabled = enabled;
  }

  onEvent(callback) {
    this.eventCallback = callback;
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
    this.startDrag(piece, null, true, e.clientX, e.clientY);
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
        this.emitEvent('RESTORE_TO_EMPTY', 'NONE', targetSquare, fromPiece, 'NONE');
      } else {
        this.squares[targetSquare].piece = fromPiece;
        this.renderSquare(targetSquare);
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
            this.emitEvent('DRAG_MOVE', fromSquare, targetSquare, fromPiece, 'NONE');
          } else {
            // DRAG_CAPTURE
            this.squares[fromSquare].piece = 'NONE';
            this.squares[targetSquare].piece = fromPiece;
            this.renderSquare(fromSquare);
            this.renderSquare(targetSquare);
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
        }
        break;
      }
      case 'RESTORE_TO_OCCUPIED': {
        // Opponent placed a piece from side area onto an occupied square
        if (targetSquare !== 'NONE' && this.squares[targetSquare]) {
          this.squares[targetSquare].piece = piece;
          this.renderSquare(targetSquare);
        }
        break;
      }
    }
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
    if (this.opponentDragSource && this.opponentDragSource !== 'NONE'
        && this.squares[this.opponentDragSource] && this.opponentDragPiece) {
      this.squares[this.opponentDragSource].piece = this.opponentDragPiece;
      this.renderSquare(this.opponentDragSource);
    }
    this.opponentDragSource = null;
    this.opponentDragPiece = null;
  }
}
