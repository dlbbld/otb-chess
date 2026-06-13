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

/**
 * Returns the player's own colour for `page`, read from the clock. The board flips per player so
 * the bottom lever is always the player's own side, and #bottomClockLabel shows that colour.
 */
export async function colorOf(page: Page): Promise<Side> {
  const label = page.locator('#bottomClockLabel');
  await expect(label).toHaveText(/^(White|Black)$/, { timeout: 15_000 });
  const text = (await label.textContent())?.trim().toLowerCase();
  if (text === 'white' || text === 'black') return text;
  throw new Error(`Could not determine player colour (bottomClockLabel="${text}")`);
}

export interface TwoPlayerGame {
  /** Both browser contexts, for teardown. */
  contexts: BrowserContext[];
  /** The page that created the game. */
  creator: Page;
  /** The page that joined the game. */
  joiner: Page;
  /** The page playing White (resolved from the assigned side, honouring custom-FEN overrides). */
  white: Page;
  /** The page playing Black. */
  black: Page;
  gameId: string;
}

/**
 * Sets up a two-player game in two isolated browser contexts (= two real sessions): the creator
 * creates, the joiner joins, and both wait until they report "Game started".
 *
 * The creator's requested side can be overridden by a custom FEN (the FEN's side to move plays
 * first), so `white`/`black` are resolved from each page's actual assigned colour rather than
 * assuming creator = White. Close `contexts` in test teardown.
 */
export async function startTwoPlayerGame(
  browser: Browser,
  opts: CreateOptions = {},
): Promise<TwoPlayerGame> {
  const creatorContext = await browser.newContext();
  const joinerContext = await browser.newContext();
  const creator = await creatorContext.newPage();
  const joiner = await joinerContext.newPage();

  const gameId = await createGame(creator, opts);
  await joinGame(joiner, gameId);

  await expectGameStarted(creator);
  await expectGameStarted(joiner);

  const creatorColor = await colorOf(creator);
  const [white, black] = creatorColor === 'white' ? [creator, joiner] : [joiner, creator];

  return { contexts: [creatorContext, joinerContext], creator, joiner, white, black, gameId };
}

/** The drawn-game score string the result panel shows (U+00BD = ½). */
export const SCORE_DRAW = '½-½';

/** Clicks the Resign button. */
export async function resign(page: Page): Promise<void> {
  await page.locator('#resignBtn').click();
}

/** Clicks the Offer Draw button. */
export async function offerDraw(page: Page): Promise<void> {
  await page.locator('#offerDrawBtn').click();
}

/** Accepts a pending draw offer (the Accept button in the draw-offer panel). */
export async function acceptDraw(page: Page): Promise<void> {
  await page.locator('#acceptDrawBtn').click();
}

/** Asserts (with auto-retry) the game-result panel shows the given score ("1-0" / "0-1" / SCORE_DRAW). */
export async function expectGameResult(page: Page, score: string): Promise<void> {
  await expect(page.locator('#gameResultPanel')).toBeVisible({ timeout: 15_000 });
  await expect(page.locator('#gameResultScore')).toHaveText(score);
}

/** Clicks the "Claim 50-move (position)" button. */
export async function claimFiftyMoveOnBoard(page: Page): Promise<void> {
  await page.locator('#claimFiftyMoveOnBoardBtn').click();
}

/** Clicks the "Claim threefold (position)" button. */
export async function claimThreefoldOnBoard(page: Page): Promise<void> {
  await page.locator('#claimThreefoldOnBoardBtn').click();
}

/** Claims the 50-move rule with a move: opens the SAN panel, enters the move, and submits. */
export async function claimFiftyMoveWithMove(page: Page, san: string): Promise<void> {
  await page.locator('#claimFiftyMoveWithMoveBtn').click();
  await page.locator('#sanInput').fill(san);
  await page.locator('#submitClaimMoveBtn').click();
}
