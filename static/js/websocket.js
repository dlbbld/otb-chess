// Derive the WebSocket URL from the page origin so a single build works everywhere:
// - Behind the Caddy/Cloudflare single origin the page is HTTPS and the WebSocket is a
//   same-origin `wss://<host>/ws` that Caddy proxies to the Java WebSocket server.
// - In local dev served directly by the Java HTTP server on :8080 there is no proxy, so
//   fall back to the WebSocket server on :8081 of the same host.
// `wss` is chosen whenever the page itself is HTTPS, so the WebSocket is never downgraded
// to plaintext on a secure page (browsers block that as mixed content anyway).
function resolveWsUrl() {
  const loc = window.location;
  const scheme = loc.protocol === 'https:' ? 'wss' : 'ws';
  if (loc.port === '8080') {
    // Direct dev mode: page came straight from the Java HTTP server, no proxy in front.
    return `${scheme}://${loc.hostname}:8081`;
  }
  // Single-origin mode (Caddy/Cloudflare): same host, dedicated `/ws` path.
  return `${scheme}://${loc.host}/ws`;
}
const WS_URL = resolveWsUrl();

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

  sendAbort() {
    this.send({ type: 'abort' });
  }

  sendRequestPgn() {
    this.send({ type: 'requestPgn' });
  }

  sendReadyToContinue() {
    this.send({ type: 'readyToContinue' });
  }
}
