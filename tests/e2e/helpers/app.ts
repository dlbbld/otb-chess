import { Browser, BrowserContext, Page, expect } from '@playwright/test';

export type Side = 'white' | 'black';

export interface CreateOptions {
  side?: Side;
  timeMs?: number;
  incMs?: number;
  /** Optional starting FEN. The side to move in the FEN plays first; the creator gets that side. */
  fen?: string;
}

/** Navigates `page` to a freshly created game and returns the game code shown on the board page. */
export async function createGame(page: Page, opts: CreateOptions = {}): Promise<string> {
  const side = opts.side ?? 'white';
  const time = opts.timeMs ?? 180_000;
  const inc = opts.incMs ?? 0;
  let url = `/game.html?creator=true&side=${side}&time=${time}&inc=${inc}`;
  if (opts.fen) url += `&fen=${encodeURIComponent(opts.fen)}`;
  await page.goto(url);

  // game.js appends the code element only after the server's `gameCreated` message.
  const code = page.locator('.game-code-value');
  await expect(code).toBeVisible({ timeout: 15_000 });
  const text = (await code.textContent())?.trim() ?? '';
  if (!text) throw new Error('Game created but the game code was empty');
  return text;
}

/** Navigates `page` to join an existing game by code. */
export async function joinGame(page: Page, gameId: string): Promise<void> {
  await page.goto(`/game.html?gameId=${gameId}`);
}

/** Asserts (with auto-retry) that the page reached the "Game started" state. */
export async function expectGameStarted(page: Page): Promise<void> {
  await expect(page.locator('#arbiterMessage')).toContainText('Game started', { timeout: 15_000 });
}

export interface TwoPlayerGame {
  whiteContext: BrowserContext;
  blackContext: BrowserContext;
  white: Page;
  black: Page;
  gameId: string;
}

/**
 * Sets up a two-player game in two isolated browser contexts (= two real sessions):
 * white creates, black joins, and both wait until they report "Game started".
 * Remember to close both contexts in test teardown.
 */
export async function startTwoPlayerGame(
  browser: Browser,
  opts: CreateOptions = {},
): Promise<TwoPlayerGame> {
  const whiteContext = await browser.newContext();
  const blackContext = await browser.newContext();
  const white = await whiteContext.newPage();
  const black = await blackContext.newPage();

  const gameId = await createGame(white, opts);
  await joinGame(black, gameId);

  await expectGameStarted(white);
  await expectGameStarted(black);

  return { whiteContext, blackContext, white, black, gameId };
}
