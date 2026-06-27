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
    // Reconnection state. sessionToken + gameId are captured from gameCreated/gameJoined so a
    // dropped socket can silently re-attach to the same seat (see _open / resume on the server).
    this.sessionToken = null;
    this.sessionGameId = null;
    this.shouldReconnect = true;
    this.reconnectAttempts = 0;
    this.heartbeatTimer = null;
    // Optional UI hooks set by the page.
    this.onReconnecting = null;
    this.onReconnected = null;
  }

  connect() {
    // Resolves on the FIRST open; later reconnections are silent and driven by onclose/backoff.
    return new Promise((resolve, reject) => this._open(resolve, reject, false));
  }

  _open(resolve, reject, isReconnect) {
    this.ws = new WebSocket(WS_URL);

    this.ws.onopen = () => {
      this.connected = true;
      this.reconnectAttempts = 0;
      this._startHeartbeat();
      console.log(isReconnect ? 'WebSocket reconnected' : 'WebSocket connected');
      // After a reconnect, re-attach to our seat; the server replies with a `resync`.
      if (isReconnect && this.sessionToken && this.sessionGameId) {
        this.ws.send(JSON.stringify({ type: 'resume', gameId: this.sessionGameId, token: this.sessionToken }));
      }
      if (resolve) resolve();
    };

    this.ws.onmessage = (event) => {
      const data = JSON.parse(event.data);
      // Capture the reconnect token + game id the first time we see them.
      if ((data.type === 'gameCreated' || data.type === 'gameJoined') && data.token) {
        this.sessionToken = data.token;
        this.sessionGameId = data.gameId;
      }
      if (data.type === 'pong') return; // heartbeat ack — nothing to do
      if (data.type === 'resync' && this.onReconnected) this.onReconnected();
      if (data.type === 'resumeFailed') this.shouldReconnect = false;
      const handler = this.messageHandlers[data.type];
      if (handler) {
        handler(data);
      } else {
        console.log('Unhandled message type:', data.type, data);
      }
    };

    this.ws.onclose = () => {
      this.connected = false;
      this._stopHeartbeat();
      // Only auto-reconnect once we're in a game (have a token) and weren't deliberately closed.
      if (this.shouldReconnect && this.sessionToken) {
        this._scheduleReconnect();
      }
    };

    this.ws.onerror = (err) => {
      console.error('WebSocket error:', err);
      // Reject only the initial connect; reconnect failures fall through to onclose/backoff.
      if (!isReconnect && reject) reject(err);
    };
  }

  _scheduleReconnect() {
    this.reconnectAttempts++;
    const delay = Math.min(500 * 2 ** (this.reconnectAttempts - 1), 8000);
    console.log(`WebSocket lost — reconnecting in ${delay}ms (attempt ${this.reconnectAttempts})`);
    if (this.onReconnecting) this.onReconnecting();
    setTimeout(() => this._open(null, null, true), delay);
  }

  _startHeartbeat() {
    this._stopHeartbeat();
    // App-level keepalive so Cloudflare doesn't idle the socket out (e.g. while waiting for an
    // opponent). 25s is comfortably under typical proxy idle timeouts (~100s).
    this.heartbeatTimer = setInterval(() => {
      if (this.ws && this.ws.readyState === WebSocket.OPEN) {
        this.ws.send(JSON.stringify({ type: 'keepalive' }));
      }
    }, 25000);
  }

  _stopHeartbeat() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
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
