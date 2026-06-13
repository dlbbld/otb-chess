const WS_URL = 'ws://localhost:8081';

class GameWebSocket {
  constructor() {
    this.ws = null;
    this.messageHandlers = {};
    this.connected = false;
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(WS_URL);

      this.ws.onopen = () => {
        this.connected = true;
        console.log('WebSocket connected');
        resolve();
      };

      this.ws.onmessage = (event) => {
        const data = JSON.parse(event.data);
        console.log('WS received:', data.type);
        const handler = this.messageHandlers[data.type];
        if (handler) {
          handler(data);
        } else {
          console.log('Unhandled message type:', data.type, data);
        }
      };

      this.ws.onclose = () => {
        this.connected = false;
        console.log('WebSocket disconnected');
      };

      this.ws.onerror = (err) => {
        console.error('WebSocket error:', err);
        reject(err);
      };
    });
  }

  on(type, handler) {
    this.messageHandlers[type] = handler;
  }

  send(data) {
    if (this.ws && this.connected) {
      this.ws.send(JSON.stringify(data));
    }
  }

  createGame(side, initialTimeMs, incrementMs, maxIllegalMoves, autoResumeAfterRestore, fen) {
    const msg = {
      type: 'createGame',
      side: side,
      initialTimeMs: initialTimeMs,
      incrementMs: incrementMs,
      maxIllegalMoves: maxIllegalMoves,
      autoResumeAfterRestore: autoResumeAfterRestore
    };
    // Optional starting FEN — when supplied the server validates via Ashlar Chess and
    // overrides the creator's side to whichever side is to move in the FEN.
    if (fen) {
      msg.fen = fen;
    }
    this.send(msg);
  }

  joinGame(gameId) {
    this.send({ type: 'joinGame', gameId: gameId });
  }

  sendBoardEvent(event, boardState) {
    this.send({ type: 'boardEvent', event: event, boardState: boardState });
  }

  sendClockPress(boardState) {
    this.send({ type: 'clockPress', boardState: boardState });
  }

  sendOfferDraw(boardState) {
    this.send({ type: 'offerDraw', boardState: boardState });
  }

  sendAcceptDraw() {
    this.send({ type: 'acceptDraw' });
  }

  sendRejectDraw() {
    this.send({ type: 'rejectDraw' });
  }

  sendClaimDraw(claimType, san) {
    const msg = { type: 'claimDraw', claimType: claimType };
    if (san) msg.san = san;
    this.send(msg);
  }

  sendResign() {
    this.send({ type: 'resign' });
  }

  sendRequestPgn() {
    this.send({ type: 'requestPgn' });
  }

  sendReadyToContinue() {
    this.send({ type: 'readyToContinue' });
  }
}
