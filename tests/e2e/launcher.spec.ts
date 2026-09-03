import { test, expect } from '@playwright/test';
import { spawn, execFileSync, ChildProcess } from 'node:child_process';
import { cp, copyFile, mkdtemp, readFile, rm, writeFile, mkdir } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { resolve, join, dirname, basename } from 'node:path';
import { createServer } from 'node:net';

test('start.bat fetches the branch on each launch and serves its snapshot without local edits', async ({ page, request }) => {
  test.skip(process.platform !== 'win32', 'Windows batch launcher');
  test.setTimeout(90_000);
  const repo = process.cwd();
  const root = await mkdtemp(join(tmpdir(), 'otb-launcher-'));
  const remote = join(root, 'remote.git');
  const seed = join(root, 'seed');
  const checkout = join(root, 'checkout with spaces');
  const stable = join(root, 'otb-chess-stable');
  const bin = join(root, 'bin');
  let server: ChildProcess | undefined;
  let output = '';

  function git(cwd: string, ...args: string[]): string {
    return execFileSync('git', ['-c', 'user.name=Launcher Test', '-c', 'user.email=launcher@example.invalid', ...args],
      { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  }

  async function freePort(): Promise<number> {
    const socket = createServer();
    await new Promise<void>(done => socket.listen(0, '127.0.0.1', done));
    const port = (socket.address() as { port: number }).port;
    await new Promise<void>((done, reject) => socket.close(error => error ? reject(error) : done()));
    return port;
  }

  function stopServer(): void {
    if (server?.pid && server.exitCode === null) {
      // Kill only the process tree started by this test, never the user's dev server.
      execFileSync('taskkill', ['/PID', String(server.pid), '/T', '/F'], { stdio: 'ignore' });
    }
    server = undefined;
  }

  try {
    await mkdir(seed);
    await mkdir(bin);
    git(root, 'init', '--bare', remote);
    git(seed, 'init', '-b', 'main');
    await cp(join(repo, 'static'), join(seed, 'static'), { recursive: true });
    git(seed, 'add', 'static');
    git(seed, 'commit', '-m', 'Initial lobby');
    git(seed, 'remote', 'add', 'origin', remote);
    git(seed, 'push', 'origin', 'main');
    git(root, 'clone', '--branch', 'main', remote, checkout);
    const checkoutSha = git(checkout, 'rev-parse', 'HEAD');
    git(seed, 'switch', '-c', 'codex/further-hardening');

    const index = await readFile(join(seed, 'static', 'index.html'), 'utf8');
    const description = 'An educational chessboard that simulates physical board play with an arbiter.';
    expect(index).toContain(description);
    await writeFile(join(checkout, 'static', 'index.html'), index.replace(description, 'Uncommitted local edit'));
    await copyFile(join(repo, 'start.bat'), join(checkout, 'start.bat'));
    // Use the already-built real server; this test targets fetching/checkout/launch, not Maven itself.
    await writeFile(join(bin, 'mvn.cmd'), `@echo off\r\njava -jar "${join(repo, 'target', 'otb-chess.jar')}"\r\n`);

    for (const marker of ['First pushed branch snapshot', 'Latest pushed branch snapshot']) {
      await writeFile(join(seed, 'static', 'index.html'), index.replace(description, marker));
      git(seed, 'add', 'static/index.html');
      git(seed, 'commit', '-m', marker);
      git(seed, 'push', 'origin', 'codex/further-hardening');
      const expectedSha = git(seed, 'rev-parse', 'HEAD');
      const httpPort = await freePort();
      const wsPort = await freePort();
      const url = `http://127.0.0.1:${httpPort}`;
      output = '';
      server = spawn('cmd.exe', ['/d', '/c', 'call start.bat'], {
        cwd: checkout,
        env: {
          ...process.env,
          PATH: `${bin};${process.env.PATH}`,
          OTB_BIND_HOST: '127.0.0.1',
          OTB_HTTP_PORT: String(httpPort),
          OTB_WS_PORT: String(wsPort),
          OTB_STATIC_DIR: 'static',
        },
        windowsHide: true,
      });
      server.stdout?.on('data', chunk => { output += chunk; });
      server.stderr?.on('data', chunk => { output += chunk; });
      await expect.poll(async () => {
        if (server?.exitCode !== null) throw new Error(`Launcher exited: ${output}`);
        return request.get(`${url}/api/health`).then(response => response.ok()).catch(() => false);
      }, { timeout: 25_000 }).toBe(true);

      await page.goto(url);
      await expect(page.getByText(marker, { exact: true })).toBeVisible();
      await expect(page.getByText('Uncommitted local edit', { exact: true })).toHaveCount(0);
      expect(git(stable, 'rev-parse', 'HEAD')).toBe(expectedSha);
      expect(git(checkout, 'rev-parse', 'HEAD')).toBe(checkoutSha);
      expect(git(checkout, 'branch', '--show-current')).toBe('main');
      expect(await readFile(join(checkout, 'static', 'index.html'), 'utf8')).toContain('Uncommitted local edit');
      stopServer();
    }
  } finally {
    stopServer();
    // Delete only the exact temporary fixture directory allocated above.
    if (dirname(resolve(root)) !== resolve(tmpdir()) || !basename(root).startsWith('otb-launcher-')) {
      throw new Error(`Unexpected launcher fixture path: ${root}`);
    }
    await rm(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
});
