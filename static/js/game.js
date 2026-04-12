// Game controller — ties together board, websocket, and UI
class Game {
  constructor() {
    this.board = null;
    this.ws = new GameWebSocket();
    this.side = null; // 'white' or 'black'
    this.gameId = null;
    this.isMyTurn = false;
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
      this.ws.createGame(this.side, initialTimeMs, incrementMs);
    } else {
      this.ws.joinGame(this.gameId);
    }

    this.setupButtons();
    this.setupClockButtons();
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

    // Physical clock position: always on White's right side of the board.
    // When viewing as White (not flipped): clock on the right.
    // When viewing as Black (flipped): clock on the left.
    const whiteView = !this.board.flipped;
    const boardRow = document.querySelector('.board-row');
    if (boardRow) {
      if (whiteView) {
        boardRow.classList.remove('clock-on-left');
      } else {
        boardRow.classList.add('clock-on-left');
      }
    }
  }

  onClockButtonPressed(position) {
    if (!this.gameActive) return;

    const pressedColor = position === 'bottom' ? this.bottomClockColor : this.topClockColor;

    if (pressedColor === this.side) {
      this.ws.sendClockPress(this.board.getBoardState());
    } else {
      this.ws.send({ type: 'opponentClockPressed' });
    }
  }

  setupMessageHandlers() {
    this.ws.on('gameCreated', (data) => {
      this.gameId = data.gameId;
      this.side = data.side;
      localStorage.setItem('lastGameId', data.gameId);
      this.board.setPosition(data.board);
      this.board.renderAll();
      this.updateClockLabels();
      this.setupExtraQueens();
      this.showArbiterMessage('Game created. Waiting for opponent...');
      this.clearArbiterButtons();
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
      this.isMyTurn = this.side === 'white';
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
      console.log('opponentMoved received');
      if (data.board) {
        this.board.setPosition(data.board);
        this.board.renderAll();
        this.board.clearHighlights();
        this.recomputeSideArea();
      }
      if (data.havingMove) {
        this.isMyTurn = data.havingMove === this.side;
        this.board.setEnabled(this.isMyTurn);
        this.updateButtons();
      }
      if (data.isCheck && this.isMyTurn) {
        this.highlightKingInCheck();
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
      this.board.setPosition(data.board);
      this.board.renderAll();
      this.board.clearHighlights();
      this.recomputeSideArea();
      this.isMyTurn = data.havingMove === this.side;
      this.board.setEnabled(this.isMyTurn);
      this.updateButtons();
      if (data.isCheck && this.isMyTurn) {
        this.highlightKingInCheck();
      }
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

    this.ws.on('restoreRequired', (data) => {
      this.showArbiterMessage(data.message, 'info');
      this.clearArbiterButtons();
      this.showArbiterButton('Do this for me', () => {
        this.ws.send({ type: 'restorePosition' });
      });
    });

    this.ws.on('positionRestored', (data) => {
      if (data.board) {
        this.board.setPosition(data.board);
        this.board.renderAll();
        this.board.clearHighlights();
        this.recomputeSideArea();
      }
      this.showArbiterMessage(data.message, 'info');
      this.clearArbiterButtons();
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

    this.ws.on('drawRejected', (data) => {
      document.getElementById('drawOfferPanel').style.display = 'none';
      this.showArbiterMessage('Draw offer rejected.');
    });

    this.ws.on('drawClaimResult', (data) => {
      this.showArbiterMessage(data.message);
      if (data.mustExecuteMove) {
        this.showSanInput(false);
      }
    });

    this.ws.on('pgn', (data) => {
      document.getElementById('pgnText').value = data.pgn;
      document.getElementById('pgnDialog').style.display = 'block';
    });

    this.ws.on('gameEnded', (data) => {
      this.gameActive = false;
      this.board.setEnabled(false);
      this.updateButtons();
      const scoreText = data.winner === 'none' ? '\u00BD-\u00BD'
        : (data.winner === 'white' ? '1-0' : '0-1');
      document.getElementById('gameResultScore').textContent = scoreText;
      document.getElementById('gameResultReason').textContent = data.description;
      document.getElementById('gameResultPanel').style.display = 'block';
    });

    this.ws.on('opponentDisconnected', (data) => {
      this.showArbiterMessage(data.message, 'info');
    });

    this.ws.on('error', (data) => {
      this.showArbiterMessage('Error: ' + data.message, 'error');
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

    document.getElementById('acceptDrawBtn').addEventListener('click', () => {
      this.ws.sendAcceptDraw();
      document.getElementById('drawOfferPanel').style.display = 'none';
    });

    document.getElementById('rejectDrawBtn').addEventListener('click', () => {
      this.ws.sendRejectDraw();
      document.getElementById('drawOfferPanel').style.display = 'none';
    });

    document.getElementById('claimThreefoldBtn').addEventListener('click', () => {
      this.showSanInput(true, 'THREEFOLD');
    });

    document.getElementById('claimFiftyMoveBtn').addEventListener('click', () => {
      this.showSanInput(true, 'FIFTY_MOVE');
    });

    document.getElementById('claimOnBoardBtn').addEventListener('click', () => {
      const claimType = document.getElementById('sanInputPanel').dataset.claimPrefix;
      this.ws.sendClaimDraw(claimType + '_ON_BOARD');
      this.hideSanInput();
    });

    document.getElementById('claimWithMoveBtn').addEventListener('click', () => {
      const san = document.getElementById('sanInput').value.trim();
      if (!san) { this.showArbiterMessage('Please enter a move in SAN notation.'); return; }
      const claimType = document.getElementById('sanInputPanel').dataset.claimPrefix;
      this.ws.sendClaimDraw(claimType + '_WITH_MOVE', san);
      this.hideSanInput();
    });

    document.getElementById('cancelClaimBtn').addEventListener('click', () => {
      this.hideSanInput();
    });

    document.getElementById('exportPgnBtn').addEventListener('click', () => {
      this.ws.sendRequestPgn();
    });

    document.getElementById('closePgnBtn').addEventListener('click', () => {
      document.getElementById('pgnDialog').style.display = 'none';
    });

    document.getElementById('flipBoardBtn').addEventListener('click', () => {
      this.board.flip();
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
    this.ws.sendBoardEvent(event);
  }

  highlightKingInCheck() {
    const kingPiece = this.side === 'white' ? 'WHITE_KING' : 'BLACK_KING';
    for (const [sq, piece] of Object.entries(this.board.getBoardState())) {
      if (piece === kingPiece) {
        this.board.showCheck(sq);
        break;
      }
    }
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

  showSanInput(show, claimPrefix) {
    const panel = document.getElementById('sanInputPanel');
    if (show) {
      panel.style.display = 'flex';
      panel.dataset.claimPrefix = claimPrefix;
      document.getElementById('sanInput').value = '';
      document.getElementById('sanInput').focus();
    }
  }

  hideSanInput() { document.getElementById('sanInputPanel').style.display = 'none'; }

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
    document.getElementById('claimThreefoldBtn').disabled = !this.gameActive;
    document.getElementById('claimFiftyMoveBtn').disabled = !this.gameActive;
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
