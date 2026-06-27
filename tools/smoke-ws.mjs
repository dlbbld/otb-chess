// Deployment smoke test for the OTB Chess single origin.
//
// Opens the WebSocket at <base>/ws, creates a game, and asserts the server replies with
// `gameCreated`. Proves the full chain (edge -> Caddy -> Java WebSocket server) carries real game
// messages, not just the HTTP 101 handshake. Uses Node's built-in WebSocket (Node >= 22), so it
// needs no dependencies.
//
// Usage:
//   node tools/smoke-ws.mjs                       # defaults to ws://localhost:9000 (local Caddy)
//   node tools/smoke-ws.mjs wss://chess.example   # production, through Cloudflare
//
// A bare origin is accepted; `/ws` is appended automatically.
const arg = process.argv[2] || 'ws://localhost:9000';
const base = arg.replace(/\/+$/, '');
const url = base.endsWith('/ws') ? base : base + '/ws';

const ws = new WebSocket(url);
const timeout = setTimeout(() => {
  console.error(`FAIL: no gameCreated within 5s from ${url}`);
  process.exit(1);
}, 5000);

ws.addEventListener('open', () => {
  console.log('connected:', url);
  ws.send(JSON.stringify({
    type: 'createGame', side: 'white',
    initialTimeMs: 300000, incrementMs: 0, maxIllegalMoves: 2, autoResumeAfterRestore: true,
  }));
});

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.type === 'gameCreated') {
    clearTimeout(timeout);
    console.log(`OK: gameCreated (gameId=${msg.gameId}, side=${msg.side})`);
    ws.close();
    process.exit(0);
  }
});

ws.addEventListener('error', (e) => {
  console.error('FAIL: WebSocket error:', e.message || e);
  process.exit(1);
});
