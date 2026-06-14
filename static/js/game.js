// Game controller — ties together board, websocket, and UI
class Game {
  constructor() {
    this.board = null;
    this.ws = new GameWebSocket();
    this.side = null; // 'white' or 'black'
    this.gameId = null;
    this.isMyTurn = false;
    // FIDE 9.2 / 9.3: at most one draw claim per move. Set when the server reports a
    // non-invalid claim outcome; reset whenever a new turn starts on this side.
    this.claimMadeThisTurn = false;
    this.pendingClaimWithMoveType = null;
    this.gameActive = false;
    this.sideAreaPieces = [];
    this.draggedSidePieceIndex = -1;
    this.clockRunning = 'none';

    this.init();
  }

  async init() {
    const params = new URLSearchParams(window.location.search);
    this.gameId = params.get('gameId');
    this.side = params.get('side');
    const isCreator = params.get('creator') === 'true';

    if (!isCreator && !this.gameId) {
      this.showArbiterMessage('No game ID specified. Go back to the lobby.');
      return;
    }

    this.board = new ChessBoard(
      document.getElementById('board'),
      this.side || 'white'
    );
    this.board.onEvent((event) => this.onBoardEvent(event));

    await this.ws.connect();
    this.setupMessageHandlers();

    if (isCreator) {
      const initialTimeMs = parseInt(params.get('time') || '1800000');
      const incrementMs = parseInt(params.get('inc') || '0');
      // maxIllegal: 1..10 = limit, -1 = unlimited, missing = FIDE default (2)
      const maxIllegalMoves = parseInt(params.get('maxIllegal') || '2');
      const autoResumeAfterRestore = params.get('autoResumeAfterRestore') !== 'false';
      const fen = params.get('fen') || '';
      this.ws.createGame(this.side, initialTimeMs, incrementMs, maxIllegalMoves,
          autoResumeAfterRestore, fen);
    } else {
      this.ws.joinGame(this.gameId);
    }

    this.setupButtons();
    this.setupClockButtons();
    this.setupDevConsoleControls();
  }

  // === Clock buttons ===
  // The clock rotates with the board for testing, so the lever nearest the
  // player's pieces is always treated as that player's clock.

  setupClockButtons() {
    document.getElementById('topClockBtn').addEventListener('click', () => {
      this.onClockButtonPressed('top');
    });
    document.getElementById('bottomClockBtn').addEventListener('click', () => {
      this.onClockButtonPressed('bottom');
    });
    document.getElementById('topClockBtn').classList.add('neutral');
    document.getElementById('bottomClockBtn').classList.add('neutral');
  }

  updateClockLabels() {
    // The bottom lever is always near the pieces at the bottom of the board.
    // When board is NOT flipped: White pieces at bottom → bottom lever = White
    // When board IS flipped: Black pieces at bottom → bottom lever = Black
    const whiteOnBottom = !this.board.flipped;

    this.bottomClockColor = whiteOnBottom ? 'white' : 'black';
    this.topClockColor = whiteOnBottom ? 'black' : 'white';

    document.getElementById('bottomClockLabel').textContent =
      this.bottomClockColor === 'white' ? 'White' : 'Black';
    document.getElementById('topClockLabel').textContent =
      this.topClockColor === 'white' ? 'White' : 'Black';

    const bottomDisplay = document.getElementById('bottomClockDisplay');
    const topDisplay = document.getElementById('topClockDisplay');
    if (bottomDisplay && topDisplay) {
      bottomDisplay.classList.toggle('own-clock', this.bottomClockColor === this.side);
      bottomDisplay.classList.toggle('opponent-clock', this.bottomClockColor !== this.side);
      topDisplay.classList.toggle('own-clock', this.topClockColor === this.side);
      topDisplay.classList.toggle('opponent-clock', this.topClockColor !== this.side);
    }

    // Physical clock position: always on White's right side of the board.
    // White view keeps the clock on the right; Black view moves it to the left.
    // Top/bottom DOM positions stay fixed while their assigned colors change,
    // so the far lever stays far and the near lever stays near.
    const whiteView = !this.board.flipped;
    const gameLayout = document.querySelector('.game-layout');
    if (gameLayout) {
      gameLayout.classList.toggle('clock-on-left-view', !whiteView);
    }
  }

  onClockButtonPressed(position) {
    if (!this.gameActive) return;

    const pressedColor = position === 'bottom' ? this.bottomClockColor : this.topClockColor;

    // Only a press of the player's OWN lever while it's their turn does anything —
    // exactly like a real chess clock where pressing the wrong side does not register.
    // We intentionally do NOT notify the server about clicks on the opponent's lever
    // (or on the player's own lever when it's not their turn): no message, no arbiter
    // intervention, no "do not press the opponent's clock" feedback. Silence keeps
    // the cursor-and-click behaviour identical for both halves and avoids leaking
    // which lever belongs to whom.
    if (pressedColor !== this.side) return;
    if (!this.isMyTurn) return;

    this.ws.sendClockPress(this.board.getBoardState());
  }

  setupMessageHandlers() {
    this.ws.on('gameCreated', (data) => {
      this.gameId = data.gameId;
      // The server may override the requested side when a custom FEN is supplied
      // (the side-to-move from the FEN wins so the creator can play first).
      this.side = data.side;
      if (this.side === 'black' && !this.board.flipped) {
        this.board.flip();
      }
      localStorage.setItem('lastGameId', data.gameId);
      this.board.setPosition(data.board);
      this.board.renderAll();
      this.updateClockLabels();
      this.setupExtraQueens();
      this.showArbiterMessage('Game created. Waiting for opponent...');
      this.clearArbiterButtons();
      // While no opponent has joined, the creator can abort the challenge (like Lichess),
      // not resign. The two swap once the game starts.
      document.getElementById('abortBtn').style.display = '';
      document.getElementById('resignBtn').style.display = 'none';
      const codeContainer = document.createElement('div');
      codeContainer.className = 'game-code-display';
      const codeLabel = document.createElement('span');
      codeLabel.textContent = 'Share this code: ';
      const codeValue = document.createElement('span');
      codeValue.className = 'game-code-value';
      codeValue.textContent = data.gameId;
      const copyBtn = document.createElement('button');
      copyBtn.className = 'action-btn';
      copyBtn.textContent = 'Copy code';
      copyBtn.addEventListener('click', () => {
        navigator.clipboard.writeText(data.gameId);
        copyBtn.textContent = 'Copied!';
        setTimeout(() => { copyBtn.textContent = 'Copy code'; }, 2000);
      });
      codeContainer.appendChild(codeLabel);
      codeContainer.appendChild(codeValue);
      document.getElementById('arbiterButtons').appendChild(codeContainer);
      document.getElementById('arbiterButtons').appendChild(copyBtn);
    });

    this.ws.on('gameJoined', (data) => {
      this.gameId = data.gameId;
      this.side = data.side;
      if (this.side === 'black' && !this.board.flipped) {
        this.board.flip();
      }
      this.board.setPosition(data.board);
      this.board.renderAll();
      this.updateClockLabels();
      this.setupExtraQueens();
      this.showArbiterMessage('Joined game. Waiting to start...');
    });

    this.ws.on('gameStarted', (data) => {
      this.gameActive = true;
      // The opponent has joined: abort is no longer available, resign takes its place.
      document.getElementById('abortBtn').style.display = 'none';
      document.getElementById('resignBtn').style.display = '';
      // Use the server-supplied side to move (necessary for custom-FEN games where
      // Black may be to move first); fall back to White for the normal case.
      const havingMove = data.havingMove || 'white';
      this.isMyTurn = havingMove === this.side;
      this.board.setEnabled(this.isMyTurn);
      this.updateButtons();
      this.showArbiterMessage('Game started! ' + (this.isMyTurn ? 'Your turn.' : "Opponent's turn."));
      this.clearArbiterButtons();
    });

    // After OUR move is accepted by the server
    this.ws.on('move_accepted', (data) => {
      // Our move was accepted — it's now the opponent's turn
      this.isMyTurn = false;
      this.board.setEnabled(false);
      this.updateButtons();
      this.recomputeSideArea();
      this.showArbiterMessage("Move accepted. Opponent's turn.");
      this.clearArbiterButtons();
    });

    // After the OPPONENT's move is accepted — we receive the new board state
    this.ws.on('opponentMoved', (data) => {
      if (data.board) {
        // setPosition already calls renderSquare on every entry in data.board
        // (the server sends all 64 squares), so a follow-up renderAll is pure
        // redundant DOM work. Skip it.
        this.board.setPosition(data.board);
        this.board.clearHighlights();
        this.recomputeSideArea();
      }
      if (data.havingMove) {
        this.isMyTurn = data.havingMove === this.side;
        this.board.setEnabled(this.isMyTurn);
        this.resetClaimUiForNewTurn();
      }
      this.showArbiterMessage('Your turn.');
      this.clearArbiterButtons();
    });

    // Real-time opponent board events (see opponent manipulate pieces)
    this.ws.on('opponentBoardEvent', (data) => {
      if (!this.gameActive) return;
      // Only show when it's NOT our turn (opponent is moving)
      if (this.isMyTurn) return;
      this.board.applyOpponentEvent(data.event);
      this.recomputeSideArea();
    });

    this.ws.on('boardUpdate', (data) => {
      // setPosition renders every server-supplied square; the explicit
      // renderAll afterwards is redundant.
      this.board.setPosition(data.board);
      this.board.clearHighlights();
      this.recomputeSideArea();
      this.isMyTurn = data.havingMove === this.side;
      this.board.setEnabled(this.isMyTurn);
      this.resetClaimUiForNewTurn();
    });

    this.ws.on('clockUpdate', (data) => {
      this.updateClocks(data);
    });

    this.ws.on('illegal_move', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('touch_move_violation', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('released_piece_violation', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('restoreRequired', (data) => {
      this.showArbiterMessage(data.message, data.style || 'info');
      this.clearArbiterButtons();
      this.showArbiterButton('Revert', () => {
        this.ws.send({ type: 'restorePosition' });
      });
    });

    this.ws.on('positionRestored', (data) => {
      if (data.board) {
        // setPosition already re-renders every supplied square.
        this.board.setPosition(data.board);
        this.board.clearHighlights();
        this.recomputeSideArea();
      }
      this.showArbiterMessage(data.message, 'info');
      this.clearArbiterButtons();
      if (data.autoResumePending) {
        this.board.setEnabled(false);
      }
    });

    this.ws.on('waitingForReady', (data) => {
      this.showArbiterMessage(data.message, 'info');
      this.clearArbiterButtons();
      this.showArbiterButton('Ready to continue', () => {
        this.ws.send({ type: 'readyToContinue' });
        this.showArbiterMessage('Waiting for your opponent...', 'info');
        this.clearArbiterButtons();
      });
    });

    this.ws.on('waitingForOpponentReady', (data) => {
      this.showArbiterMessage(data.message, 'info');
    });

    this.ws.on('gameResumed', (data) => {
      this.showArbiterMessage(data.message);
      this.clearArbiterButtons();
      if (data.havingMove) {
        this.isMyTurn = data.havingMove === this.side;
        this.board.setEnabled(this.isMyTurn);
        this.updateButtons();
      }
    });

    this.ws.on('incomplete_move', (data) => {
      this.showArbiterMessage(data.message, 'info');
    });

    this.ws.on('illegal_move_game_lost', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('revert_opponent_piece', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('revert_restoration', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('position_change', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('opponentClockPressed', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('wrongTimeDrawOffer', (data) => {
      this.showArbiterMessage(data.message, 'info');
    });

    this.ws.on('repeatedDrawOffer', (data) => {
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('drawAcceptRejected', (data) => {
      this.showArbiterMessage(data.message, 'error');
      document.getElementById('drawOfferPanel').style.display = 'none';
    });

    this.ws.on('drawOffered', (data) => {
      document.getElementById('drawOfferPanel').style.display = 'flex';
      this.showArbiterMessage('Your opponent offers a draw.');
    });

    // Bare acknowledgment to the offering player after a correct-time draw offer:
    // the move was validated and the offer forwarded to the opponent. No reminder
    // to press the clock — see design-principles P-003 (board never gives
    // procedural instructions). If the offerer forgets, time keeps running on
    // their clock while the opponent considers the offer.
    this.ws.on('drawOfferSent', (data) => {
      this.showArbiterMessage(data.message, 'info');
    });

    // The on-move player just touched a piece while a draw offer was pending.
    // Per FIDE 9.1.2.1 the right to accept is lost; hide the Accept/Reject panel
    // and show the explanation.
    this.ws.on('drawOfferInvalidated', (data) => {
      document.getElementById('drawOfferPanel').style.display = 'none';
      this.showArbiterMessage(data.message, 'error');
    });

    this.ws.on('drawRejected', (data) => {
      document.getElementById('drawOfferPanel').style.display = 'none';
      // Personalised by the server ("You rejected..." / "Your opponent rejected...").
      this.showArbiterMessage(data.message);
    });

    this.ws.on('drawClaimResult', (data) => {
      this.showArbiterMessage(data.message, data.invalidMove ? 'error' : null);
      if (data.invalidMove) {
        const input = document.getElementById('sanInput');
        if (input) {
          input.value = '';
          input.focus();
        }
      } else {
        // The claim has resolved for this turn. The server-side claim ledger now owns the
        // once-per-turn state; the UI stays locked until a new turn starts.
        this.claimMadeThisTurn = true;
        this.pendingClaimWithMoveType = null;
        this.updateButtons();
        this.hideSanInput();
      }
    });

    // Opponent broadcast: the other side made a claim event we need to display.
    this.ws.on('drawClaimOpponent', (data) => {
      this.showArbiterMessage(data.message);
    });

    this.ws.on('pgn', (data) => {
      document.getElementById('pgnText').value = data.pgn;
      document.getElementById('pgnDialog').style.display = 'block';
    });

    this.ws.on('gameEnded', (data) => {
      this.gameActive = false;
      this.board.setEnabled(false);
      this.updateButtons();
      // Game has ended — drop the PAUSE overlay because no further clockUpdate
      // will arrive to clear it via the updateClocks path.
      const clockEl = document.getElementById('chessClock');
      if (clockEl) clockEl.classList.remove('paused');
      const scoreText = data.winner === 'none' ? '\u00BD-\u00BD'
        : (data.winner === 'white' ? '1-0' : '0-1');
      document.getElementById('gameResultScore').textContent = scoreText;
      document.getElementById('gameResultReason').textContent = data.description;
      document.getElementById('gameResultPanel').style.display = 'block';

      // Personalise the arbiter message for moves that immediately end the game instead of
      // leaving the generic "Move accepted" / "Your turn" from that move. The mover (data.mover)
      // is the side that played it; the other player is the recipient.
      if (data.resultType === 'CHECKMATE') {
        this.showArbiterMessage(data.mover === this.side
          ? 'Your last move delivered checkmate.'
          : 'You have been checkmated.');
      } else if (data.resultType === 'STALEMATE') {
        this.showArbiterMessage(data.mover === this.side
          ? 'Your last move resulted in stalemate.'
          : "Your opponent's last move resulted in stalemate.");
      } else if (data.resultType === 'SEVENTY_FIVE_MOVE') {
        this.showArbiterMessage(data.mover === this.side
          ? 'Your last move led to 75 moves each without a capture or pawn move.'
          : "Your opponent's last move led to 75 moves each without a capture or pawn move.");
      } else if (data.resultType === 'FIVEFOLD_REPETITION') {
        this.showArbiterMessage(data.mover === this.side
          ? 'Your last move led to a fivefold repetition.'
          : "Your opponent's last move led to a fivefold repetition.");
      } else if ((data.resultType === 'RESIGNATION' || data.resultType === 'FLAG_FALL')
          && data.winner === 'none') {
        // FIDE draw exception: the actor resigned/flagged but the opponent cannot mate.
        // Phrase it in the second person for each player.
        const verb = data.resultType === 'RESIGNATION' ? 'resigned' : 'flagged';
        const reason = data.drawReason === 'INSUFFICIENT_MATERIAL'
          ? 'insufficient material to mate'
          : 'no potential mate';
        const msg = data.actor === this.side
          ? `You ${verb}, but because your opponent has ${reason}, the game is a draw.`
          : `Your opponent ${verb}, but because you have ${reason}, the game is a draw.`;
        this.showArbiterMessage(msg);
        document.getElementById('gameResultReason').textContent = msg;
      } else if (data.resultType === 'DRAW_AGREEMENT' && data.winner === 'none') {
        // Who accepted goes on top (arbiter message); the result panel keeps the canonical
        // "The game is drawn by agreement." after the ½-½ score.
        this.showArbiterMessage(data.actor === this.side
          ? 'You accepted the draw offer.'
          : 'Your opponent accepted the draw offer.');
      }
    });

    this.ws.on('gameAborted', () => {
      // Challenge cancelled before it started — back to the lobby to create a new one.
      window.location.href = '/';
    });

    this.ws.on('opponentDisconnected', (data) => {
      this.showArbiterMessage(data.message, 'info');
      // Drop any in-flight opponent drag visualisation — no more events will arrive.
      this.board.clearOpponentDragVisuals();
    });

    this.ws.on('error', (data) => {
      // The user sees only the friendly server-supplied message. Raw technical
      // detail (exception class, message, calling context) lives in `devDetail`
      // and is routed to the developer console at the bottom of the page —
      // never into the arbiter message area.
      this.showArbiterMessage(data.message, 'error');
      if (data.devDetail) {
        this.appendDevConsole(data.devDetail);
      }
    });
  }

  // === Developer console ===

  /**
   * Appends a technical error detail to the dev-console pane and reveals the
   * pane on first use. Out of band from the arbiter message area — the user-
   * visible UI does not see this text.
   */
  appendDevConsole(detail) {
    const console = document.getElementById('devConsole');
    const body = document.getElementById('devConsoleBody');
    if (!console || !body) return;
    const entry = document.createElement('div');
    entry.className = 'dev-console-entry';
    const ts = document.createElement('span');
    ts.className = 'ts';
    const now = new Date();
    ts.textContent = now.toTimeString().slice(0, 8);
    const text = document.createElement('span');
    text.className = 'detail';
    text.textContent = detail;
    entry.appendChild(ts);
    entry.appendChild(text);
    body.appendChild(entry);
    body.scrollTop = body.scrollHeight;
    console.style.display = 'flex';
    console.classList.remove('collapsed');
  }

  setupDevConsoleControls() {
    const console = document.getElementById('devConsole');
    const body = document.getElementById('devConsoleBody');
    const clearBtn = document.getElementById('devConsoleClearBtn');
    const toggleBtn = document.getElementById('devConsoleToggleBtn');
    if (!console || !body || !clearBtn || !toggleBtn) return;
    clearBtn.addEventListener('click', () => {
      body.innerHTML = '';
    });
    toggleBtn.addEventListener('click', () => {
      const collapsed = console.classList.toggle('collapsed');
      toggleBtn.textContent = collapsed ? 'show' : 'hide';
    });
  }

  setupButtons() {
    document.getElementById('offerDrawBtn').addEventListener('click', () => {
      if (!this.gameActive) return;
      this.ws.sendOfferDraw(this.board.getBoardState());
    });

    document.getElementById('resignBtn').addEventListener('click', () => {
      if (!this.gameActive) return;
      this.ws.sendResign();
    });

    document.getElementById('abortBtn').addEventListener('click', () => {
      // Only meaningful before the opponent joins; the button is hidden otherwise.
      this.ws.sendAbort();
    });

    document.getElementById('acceptDrawBtn').addEventListener('click', () => {
      this.ws.sendAcceptDraw();
      document.getElementById('drawOfferPanel').style.display = 'none';
    });

    document.getElementById('rejectDrawBtn').addEventListener('click', () => {
      this.ws.sendRejectDraw();
      document.getElementById('drawOfferPanel').style.display = 'none';
    });

    document.getElementById('claimThreefoldOnBoardBtn').addEventListener('click', () => {
      this.sendClaimOnBoard('THREEFOLD_ON_BOARD');
    });

    document.getElementById('claimThreefoldWithMoveBtn').addEventListener('click', () => {
      this.beginClaimWithMove('THREEFOLD_WITH_MOVE', 'Threefold move:');
    });

    document.getElementById('claimFiftyMoveOnBoardBtn').addEventListener('click', () => {
      this.sendClaimOnBoard('FIFTY_MOVE_ON_BOARD');
    });

    document.getElementById('claimFiftyMoveWithMoveBtn').addEventListener('click', () => {
      this.beginClaimWithMove('FIFTY_MOVE_WITH_MOVE', '50-move rule move:');
    });

    document.getElementById('submitClaimMoveBtn').addEventListener('click', () => {
      const san = document.getElementById('sanInput').value.trim();
      if (!san) { this.showArbiterMessage('Please enter a move in SAN notation.'); return; }
      if (!this.pendingClaimWithMoveType) return;
      this.ws.sendClaimDraw(this.pendingClaimWithMoveType, san);
    });

    document.getElementById('sanInput').addEventListener('keydown', (event) => {
      if (event.key !== 'Enter') return;
      event.preventDefault();
      document.getElementById('submitClaimMoveBtn').click();
    });

    document.getElementById('exportPgnBtn').addEventListener('click', () => {
      this.ws.sendRequestPgn();
    });

    document.getElementById('closePgnBtn').addEventListener('click', () => {
      document.getElementById('pgnDialog').style.display = 'none';
    });

    document.getElementById('flipBoardBtn').addEventListener('click', () => {
      this.board.flip();
      // After a flip the floating piece's screen position is stale; clearing it
      // keeps the visual coherent. Next opponent DRAG_HOVER will not respawn it
      // (DRAG_START is what spawns), but on the dragger's next square change a
      // hover event still fires; the small visual gap until the next drag is
      // acceptable. Most flips happen between turns anyway.
      this.board.clearOpponentDragVisuals();
      this.renderSideAreas();
      this.updateClockLabels();
      if (this._lastClockData) {
        this.updateClocks(this._lastClockData);
      }
    });

    document.getElementById('newGameBtn').addEventListener('click', () => {
      window.location.href = '/';
    });

    document.getElementById('requestPieceBtn').addEventListener('click', () => {
      this.showPromotionSelection();
    });

    document.getElementById('confirmYesBtn').addEventListener('click', () => {
      const cb = this._confirmCallback;
      this.hideConfirmation();
      if (cb) cb();
    });

    document.getElementById('confirmNoBtn').addEventListener('click', () => {
      this.hideConfirmation();
    });
  }

  onBoardEvent(event) {
    // Send the current physical board state with every event so the server can detect
    // game-ending moves (checkmate/stalemate/etc.) without waiting for a clock press.
    this.ws.sendBoardEvent(event, this.board.getBoardState());
  }

  // === Side area management ===

  static INITIAL_PIECES = [
    'WHITE_ROOK', 'WHITE_KNIGHT', 'WHITE_BISHOP', 'WHITE_QUEEN', 'WHITE_KING',
    'WHITE_BISHOP', 'WHITE_KNIGHT', 'WHITE_ROOK',
    'WHITE_PAWN', 'WHITE_PAWN', 'WHITE_PAWN', 'WHITE_PAWN',
    'WHITE_PAWN', 'WHITE_PAWN', 'WHITE_PAWN', 'WHITE_PAWN',
    'BLACK_ROOK', 'BLACK_KNIGHT', 'BLACK_BISHOP', 'BLACK_QUEEN', 'BLACK_KING',
    'BLACK_BISHOP', 'BLACK_KNIGHT', 'BLACK_ROOK',
    'BLACK_PAWN', 'BLACK_PAWN', 'BLACK_PAWN', 'BLACK_PAWN',
    'BLACK_PAWN', 'BLACK_PAWN', 'BLACK_PAWN', 'BLACK_PAWN',
  ];

  setupExtraQueens() {
    // Both extra queens for both sides — mirrors the physical board
    this.extraPieces = ['WHITE_QUEEN', 'BLACK_QUEEN'];
    this.recomputeSideArea();
  }

  recomputeSideArea() {
    const boardState = this.board.getBoardState();
    const onBoard = Object.values(boardState).filter(p => p !== 'NONE');

    const onBoardCounts = {};
    for (const p of onBoard) {
      onBoardCounts[p] = (onBoardCounts[p] || 0) + 1;
    }

    const initialCounts = {};
    for (const p of Game.INITIAL_PIECES) {
      initialCounts[p] = (initialCounts[p] || 0) + 1;
    }

    this.sideAreaPieces = [];
    for (const [piece, initialCount] of Object.entries(initialCounts)) {
      const boardCount = onBoardCounts[piece] || 0;
      const offBoard = Math.max(0, initialCount - boardCount);
      for (let i = 0; i < offBoard; i++) {
        this.sideAreaPieces.push({ piece, originalSquare: 'NONE' });
      }
    }

    for (const p of (this.extraPieces || [])) {
      this.sideAreaPieces.push({ piece: p, originalSquare: 'NONE' });
    }

    const whitePieces = this.sideAreaPieces.filter(p => p.piece.startsWith('WHITE')).map(p => p.piece);
    const blackPieces = this.sideAreaPieces.filter(p => p.piece.startsWith('BLACK')).map(p => p.piece);
    console.log('[SIDE-AREA] recompute: WHITE=[' + whitePieces.join(',') + '] BLACK=[' + blackPieces.join(',') + '] extras=[' + (this.extraPieces||[]).join(',') + ']');
    // Trace who called this
    console.trace('[SIDE-AREA] recompute caller');
    this.renderSideAreas();
  }

  addToSideArea(piece, originalSquare) {
    this.sideAreaPieces.push({ piece, originalSquare });
    this.renderSideAreas();
  }

  removeFromSideArea(index) {
    this.sideAreaPieces.splice(index, 1);
    this.renderSideAreas();
  }

  consumeDraggedSidePiece() {
    if (this.draggedSidePieceIndex >= 0) {
      this.removeFromSideArea(this.draggedSidePieceIndex);
      this.draggedSidePieceIndex = -1;
    }
  }

  renderSideAreas() {
    // The side areas follow the current board view.
    // White view: left = Black pieces, right = White pieces.
    // Black view: left = White pieces, right = Black pieces.
    const isWhiteView = !this.board.flipped;
    const leftColor = isWhiteView ? 'BLACK' : 'WHITE';
    const rightColor = isWhiteView ? 'WHITE' : 'BLACK';

    document.getElementById('leftSideLabel').textContent = leftColor === 'WHITE' ? 'White' : 'Black';
    document.getElementById('rightSideLabel').textContent = rightColor === 'WHITE' ? 'White' : 'Black';

    const leftEl = document.getElementById('leftSidePieces');
    const rightEl = document.getElementById('rightSidePieces');

    if (!leftEl || !rightEl) {
      console.error('[SIDE-AREA] DOM elements not found! leftEl=' + !!leftEl + ' rightEl=' + !!rightEl);
      return;
    }

    leftEl.innerHTML = '';
    rightEl.innerHTML = '';

    let leftCount = 0;
    let rightCount = 0;

    this.sideAreaPieces.forEach((item, index) => {
      const el = document.createElement('div');
      el.className = 'side-piece';
      el.innerHTML = getPieceSvg(item.piece);
      el.title = item.piece;
      el.dataset.index = index;
      el.addEventListener('mousedown', (e) => {
        if (!this.board.enabled) return;
        e.stopPropagation();
        this.draggedSidePieceIndex = index;
        this.board.startSideAreaDrag(item.piece, e);
      });
      if (item.piece.startsWith(leftColor)) {
        leftEl.appendChild(el);
        leftCount++;
      } else {
        rightEl.appendChild(el);
        rightCount++;
      }
    });

    console.log('[SIDE-AREA] render: view=' + (isWhiteView ? 'White' : 'Black') +
      ' left(' + leftColor + ')=' + leftCount + ' right(' + rightColor + ')=' + rightCount);
  }

  // === Inline panels ===

  showPromotionSelection() {
    const panel = document.getElementById('promotionPanel');
    const container = document.getElementById('promotionPieces');
    container.innerHTML = '';
    const side = this.side === 'white' ? 'WHITE' : 'BLACK';
    ['PAWN', 'KNIGHT', 'BISHOP', 'ROOK', 'QUEEN', 'KING'].forEach(pieceType => {
      const fullPiece = side + '_' + pieceType;
      const el = document.createElement('div');
      el.className = 'promo-piece';
      el.innerHTML = getPieceSvg(fullPiece);
      el.title = pieceType;
      el.addEventListener('click', () => {
        if (!this.extraPieces) this.extraPieces = [];
        this.extraPieces.push(fullPiece);
        this.recomputeSideArea();
        panel.style.display = 'none';
      });
      container.appendChild(el);
    });
    panel.style.display = 'block';
  }

  sendClaimOnBoard(claimType) {
    if (!this.gameActive || this.claimMadeThisTurn) return;
    this.claimMadeThisTurn = true;
    this.pendingClaimWithMoveType = null;
    this.hideSanInput();
    this.updateButtons();
    this.ws.sendClaimDraw(claimType);
  }

  beginClaimWithMove(claimType, label) {
    if (!this.gameActive || this.claimMadeThisTurn) return;
    this.claimMadeThisTurn = true;
    this.pendingClaimWithMoveType = claimType;
    this.showSanInput(label);
    this.updateButtons();
  }

  showSanInput(label) {
    const panel = document.getElementById('sanInputPanel');
    document.getElementById('sanClaimLabel').textContent = label;
    panel.style.display = 'flex';
    document.getElementById('sanInput').value = '';
    document.getElementById('sanInput').focus();
  }

  hideSanInput() { document.getElementById('sanInputPanel').style.display = 'none'; }

  resetClaimUiForNewTurn() {
    this.claimMadeThisTurn = false;
    this.pendingClaimWithMoveType = null;
    this.hideSanInput();
    this.updateButtons();
  }

  showConfirmation(message, callback) {
    document.getElementById('confirmMessage').textContent = message;
    document.getElementById('confirmPanel').style.display = 'flex';
    this._confirmCallback = callback;
  }

  hideConfirmation() {
    document.getElementById('confirmPanel').style.display = 'none';
    this._confirmCallback = null;
  }

  // === Clock display ===

  updateClocks(data) {
    this._lastClockData = data;
    this.clockRunning = data.running;

    const bottomColor = this.bottomClockColor || this.side || 'white';
    const topColor = this.topClockColor || (this.side === 'white' ? 'black' : 'white');

    const topTimeMs = topColor === 'white' ? data.whiteTimeMs : data.blackTimeMs;
    const bottomTimeMs = bottomColor === 'white' ? data.whiteTimeMs : data.blackTimeMs;

    document.getElementById('topClockTime').textContent = this.formatTime(topTimeMs);
    document.getElementById('bottomClockTime').textContent = this.formatTime(bottomTimeMs);

    const topActive = data.running === topColor;
    const bottomActive = data.running === bottomColor;
    const neutral = data.running === 'none';

    // Show the PAUSE indicator and flip the time displays when the clock is stopped
    // mid-game (arbiter intervention, restoration handshake, etc.). Skipped when the
    // game is not active so the indicator does not show before the game starts or
    // after it has ended.
    const clockEl = document.getElementById('chessClock');
    if (clockEl) {
      clockEl.classList.toggle('paused', neutral && this.gameActive);
    }

    const bottomLever = document.getElementById('bottomClockBtn');
    const topLever = document.getElementById('topClockBtn');
    const bottomDisplay = document.getElementById('bottomClockDisplay');
    const topDisplay = document.getElementById('topClockDisplay');

    for (const el of [bottomLever, topLever]) {
      el.classList.remove('pressed', 'unpressed', 'low-time', 'neutral');
    }
    for (const el of [bottomDisplay, topDisplay]) {
      el.classList.remove('active', 'low-time');
    }

    if (neutral) {
      bottomLever.classList.add('neutral');
      topLever.classList.add('neutral');
    } else {
      if (bottomActive) {
        bottomLever.classList.add('unpressed');
        topLever.classList.add('pressed');
        bottomDisplay.classList.add('active');
        if (bottomTimeMs < 30000 && bottomTimeMs > 0) {
          bottomLever.classList.add('low-time');
          bottomDisplay.classList.add('low-time');
        }
      }
      if (topActive) {
        topLever.classList.add('unpressed');
        bottomLever.classList.add('pressed');
        topDisplay.classList.add('active');
        if (topTimeMs < 30000 && topTimeMs > 0) {
          topLever.classList.add('low-time');
          topDisplay.classList.add('low-time');
        }
      }
    }
  }

  formatTime(ms) {
    if (ms <= 0) return '0:00';
    const totalSeconds = Math.ceil(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return minutes + ':' + (seconds < 10 ? '0' : '') + seconds;
  }

  // === UI updates ===

  updateButtons() {
    document.getElementById('offerDrawBtn').disabled = !this.gameActive;
    document.getElementById('resignBtn').disabled = !this.gameActive;
    document.getElementById('requestPieceBtn').disabled = !this.gameActive;
    // Claim buttons are disabled once a claim has been committed on this turn.
    const claimsAllowed = this.gameActive && !this.claimMadeThisTurn;
    document.getElementById('claimThreefoldOnBoardBtn').disabled = !claimsAllowed;
    document.getElementById('claimThreefoldWithMoveBtn').disabled = !claimsAllowed;
    document.getElementById('claimFiftyMoveOnBoardBtn').disabled = !claimsAllowed;
    document.getElementById('claimFiftyMoveWithMoveBtn').disabled = !claimsAllowed;
  }

  showArbiterMessage(message, style) {
    const el = document.getElementById('arbiterMessage');
    el.textContent = message;
    el.className = 'arbiter-message';
    if (style) el.classList.add(style);
  }

  showArbiterButton(label, callback) {
    const container = document.getElementById('arbiterButtons');
    const btn = document.createElement('button');
    btn.className = 'action-btn';
    btn.textContent = label;
    btn.addEventListener('click', callback);
    container.appendChild(btn);
  }

  clearArbiterButtons() {
    document.getElementById('arbiterButtons').innerHTML = '';
  }
}

// Initialize
window.game = new Game();
