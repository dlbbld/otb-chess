import { APIRequestContext, Browser, expect } from '@playwright/test';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:net';
import { soundsOff } from './sounds-off';

async function freePort(): Promise<number> {
  const listener = createServer(); listener.listen(0, '127.0.0.1');
  await once(listener, 'listening');
  const port = (listener.address() as {port:number}).port;
  await new Promise<void>((resolve, reject) => listener.close(error => error ? reject(error) : resolve()));
  return port;
}

/**
 * A separate packaged server for tests needing production-length pause settings, or a server of
 * their own to stop. `ports` starts it on given ports (a restart) and `readyWithinMs` is how long
 * it may take to answer the health check.
 */
export async function startIsolatedServer(request: APIRequestContext, env: Record<string,string>,
    ports?: {httpPort: number, wsPort: number}, readyWithinMs = 15000) {
  const httpPort = ports?.httpPort ?? await freePort(); let wsPort = ports?.wsPort ?? await freePort();
  while (wsPort === httpPort) wsPort = await freePort();
  const baseURL = `http://127.0.0.1:${httpPort}`;
  const server = spawn('java', ['-jar', 'target/otb-chess.jar'], {
    windowsHide: true, stdio: 'ignore',
    env: {...process.env, ...env, OTB_BIND_HOST:'127.0.0.1', OTB_HTTP_PORT:String(httpPort), OTB_WS_PORT:String(wsPort),
      OTB_USAGE_LOG:'target/second-pass-usage.log'},
  });
  async function stop() {
    if (server.exitCode === null && server.signalCode === null) {
      const exited = once(server, 'exit'); server.kill(); await exited;
    }
  }
  try {
    await expect.poll(async () => {
      try { return (await request.get(`${baseURL}/api/health`)).status(); } catch { return 0; }
    }, {timeout:readyWithinMs}).toBe(200);
  } catch (error) { await stop(); throw error; }
  return {stop, httpPort, wsPort, baseURL, async context(browser: Browser) {
    const context = await browser.newContext({baseURL, storageState: soundsOff(baseURL)});
    await context.addInitScript(port => {
      const NativeWebSocket = window.WebSocket;
      window.WebSocket = class extends NativeWebSocket {
        constructor(url: string | URL, protocols?: string | string[]) { super(`ws://127.0.0.1:${port}`, protocols); }
      };
    }, wsPort);
    return context;
  }};
}
