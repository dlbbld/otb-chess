import { Browser, BrowserContext, Locator, Page, expect } from '@playwright/test';

export type Side = 'white' | 'black';

export interface CreateOptions {
  side?: Side;
  timeMs?: number;
  incMs?: number;
  /** Optional starting FEN. The side to move in the FEN plays first; the creator gets that side. */
  fen?: string;
  /** Server default is true; set false to require an explicit "ready to continue" after a restore. */
  autoResume?: boolean;
}

/** Navigates `page` to a freshly created game and returns the game code shown on the board page. */
export async function createGame(page: Page, opts: CreateOptions = {}): Promise<string> {
  const side = opts.side ?? 'white';
  const time = opts.timeMs ?? 180_000;
  const inc = opts.incMs ?? 0;
  let url = `/game.html?creator=true&side=${side}&time=${time}&inc=${inc}`;
  if (opts.fen) url += `&fen=${encodeURIComponent(opts.fen)}`;
  if (opts.autoResume === false) url += `&autoResumeAfterRestore=false`;
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

/**
 * Asserts (with auto-retry) that the page reached the game-started state. "Game started" itself
 * is a transient banner; the arbiter message carries the short clock fact, present in all four
 * creator/joiner x first-move variants.
 */
export async function expectGameStarted(page: Page): Promise<void> {
  await expect(page.locator('#arbiterMessage')).toContainText('clock has been started', { timeout: 15_000 });
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

/**
 * Asserts that an action is not merely present, but findable and hittable by a real player.
 *
 * `toBeVisible()` is a weaker claim than it looks: it only means "has a non-empty bounding box".
 * The icon-only redesign shrank Abort to a 42x36 box whose entire on-screen content was "!", and
 * every existing assertion still passed — including `getByRole({ name })`, because the button kept
 * a perfect `aria-label` while becoming unreadable. So the two things worth pinning are the ones
 * that actually told the good state from the bad one:
 *
 *  - `innerText` (what is *rendered*), not textContent/aria-label, which survive the regression.
 *  - the rendered width, since a label the player cannot read is not an affordance.
 *
 * The clipping check catches the inverse failure: a label present in the DOM but cut off by a box
 * too small to show it (`toHaveText` would still pass, since it reads textContent).
 *
 * Height is deliberately not asserted: the buggy 42x36 button was *taller* than the 88x28 fix, so
 * a height floor discriminates nothing here and would only add a brittle threshold.
 */
export async function expectLegibleAction(
  locator: Locator,
  label: string,
  minWidth = 64,
): Promise<void> {
  await expect(locator).toBeVisible();
  const metrics = await locator.evaluate((el) => ({
    width: el.getBoundingClientRect().width,
    renderedText: (el as HTMLElement).innerText.replace(/\s+/g, ' ').trim(),
    scrollWidth: el.scrollWidth,
    clientWidth: el.clientWidth,
  }));

  expect(metrics.renderedText, `"${label}" must be rendered on the button, not only in aria-label/tooltip`)
    .toContain(label);
  expect(metrics.width, `"${label}" is ${metrics.width}px wide — too small to read or hit`)
    .toBeGreaterThanOrEqual(minWidth);
  expect(metrics.scrollWidth, `"${label}" label is clipped by its own box`)
    .toBeLessThanOrEqual(metrics.clientWidth + 1);
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

/** Claims threefold repetition with a move: opens the SAN panel, enters the move, and submits. */
export async function claimThreefoldWithMove(page: Page, san: string): Promise<void> {
  await page.locator('#claimThreefoldWithMoveBtn').click();
  await page.locator('#sanInput').fill(san);
  await page.locator('#submitClaimMoveBtn').click();
}

/** Opens the "Request Piece" chooser and selects the given type (e.g. 'KNIGHT'), adding it to the side area. */
export async function requestPiece(page: Page, pieceType: string): Promise<void> {
  await page.locator('#requestPieceBtn').click();
  await page.locator(`#promotionPieces .promo-piece[title="${pieceType}"]`).click();
}

/** Claims the 50-move rule with a move: opens the SAN panel, enters the move, and submits. */
export async function claimFiftyMoveWithMove(page: Page, san: string): Promise<void> {
  await page.locator('#claimFiftyMoveWithMoveBtn').click();
  await page.locator('#sanInput').fill(san);
  await page.locator('#submitClaimMoveBtn').click();
}

/** Clicks the "Revert" button shown to the offending player after an intervention. */
export async function clickRestore(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Revert' }).click();
}

/** Clicks the "Ready to continue" button (shown to both players after a restore when auto-resume is off). */
export async function clickReadyToContinue(page: Page): Promise<void> {
  await page.getByRole('button', { name: 'Ready to continue' }).click();
}

/** Asserts (with auto-retry) that the game resumed after an intervention/restore. */
export async function expectGameResumed(page: Page): Promise<void> {
  await expect(page.locator('#arbiterMessage')).toContainText('Game continues', { timeout: 15_000 });
}
