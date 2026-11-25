import { test, expect, BrowserContext, Page } from '@playwright/test';
import { expectGameStarted, expectGameResult, colorOf, resign } from './helpers/app';
import { dragPiece, pressClock, expectPiece } from './helpers/board';
import { soundsOff } from './helpers/sounds-off';

// Two tabs of one browser can play each other. Each page keeps its own seat (in the history
// entry of the page), so a reload gives a tab its own seat again - up to 0.4.0 all tabs shared one
// stored seat, and the creator's tab came back from a reload as the joiner, shutting the other tab
// out and leaving one colour without a player. The start screen offers "Return to game" only for
// a game that no tab has open. Two separate browsers behave as before.

let contexts: BrowserContext[] = [];

test.afterEach(async () => {
  for (const context of contexts) await context.close();
  contexts = [];
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');
const REPLACED = 'This game is now open in another window. This window no longer takes part.';

async function newBrowser(browser: any, baseURL: string) {
  const context: BrowserContext = await browser.newContext({ storageState: soundsOff(baseURL) });
  contexts.push(context);
  return context;
}

async function createOnStartScreen(page: Page) {
  await page.goto('/');
  await page.locator('#createGameBtn').click();
  const code = page.locator('.game-code-value');
  await expect(code).toBeVisible({ timeout: 15_000 });
  return ((await code.textContent()) ?? '').trim();
}

async function joinOnStartScreen(page: Page, gameId: string) {
  await page.goto('/');
  await page.locator('#gameCodeInput').fill(gameId);
  await page.locator('#joinGameBtn').click();
}

/** One browser, two tabs: the first creates on the start screen, the second joins by the code. */
async function twoTabsOfOneBrowser(browser: any, baseURL: string) {
  const context = await newBrowser(browser, baseURL);
  const white = await context.newPage();
  const gameId = await createOnStartScreen(white);
  const black = await context.newPage();
  await joinOnStartScreen(black, gameId);
  await expectGameStarted(white);
  await expectGameStarted(black);
  expect(await colorOf(white)).toBe('white');
  expect(await colorOf(black)).toBe('black');
  return { context, white, black, gameId };
}

test('both sides from two tabs of one browser: every reload gives a tab its own seat again', async ({
  browser,
  baseURL,
}) => {
  const context = await newBrowser(browser, baseURL!);
  const white = await context.newPage();
  const gameId = await createOnStartScreen(white);

  // The second tab opens the start screen. The game is open in the first tab, so there is nothing
  // to return to: no "Game in progress", and the code joins the game as the opponent.
  const black = await context.newPage();
  await black.goto('/');
  await expect(black.locator('#createGameBtn')).toBeVisible();
  await expect(black.locator('#gameInProgress')).toBeHidden();
  await black.locator('#gameCodeInput').fill(gameId);
  await black.locator('#joinGameBtn').click();
  await expectGameStarted(white);
  await expectGameStarted(black);
  expect(await colorOf(white)).toBe('white');
  expect(await colorOf(black)).toBe('black');

  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toHaveText('Your turn.');
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(arbiter(white)).toHaveText('Your turn.');

  // The creator's tab reloads: it is White again, and the other tab stays in the game.
  await white.reload();
  await expect(arbiter(white)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(white)).toBe('white');
  await expectPiece(white, 'e5', 'BLACK_PAWN');
  await expect(arbiter(black)).not.toHaveText(REPLACED);
  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await expect(arbiter(white)).toHaveText("Move accepted. Opponent's turn.");
  await expect(arbiter(black)).toHaveText('Your turn.');

  // The joiner's tab reloads: it is Black again.
  await black.reload();
  await expect(arbiter(black)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(black)).toBe('black');
  await expectPiece(black, 'f3', 'WHITE_KNIGHT');
  await expect(arbiter(white)).not.toHaveText(REPLACED);
  await dragPiece(black, 'b8', 'c6');
  await pressClock(black);
  await expect(arbiter(white)).toHaveText('Your turn.');

  // Both reload, the creator's tab twice: still one White and one Black, and the game goes on.
  await white.reload();
  await expect(arbiter(white)).toHaveText('Reconnected.', { timeout: 15_000 });
  await black.reload();
  await expect(arbiter(black)).toHaveText('Reconnected.', { timeout: 15_000 });
  await white.reload();
  await expect(arbiter(white)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(white)).toBe('white');
  expect(await colorOf(black)).toBe('black');
  await dragPiece(white, 'f1', 'c4');
  await pressClock(white);
  await expect(arbiter(black)).toHaveText('Your turn.');
  await expectPiece(black, 'c4', 'WHITE_BISHOP');

  // With both tabs open the start screen still offers no game to return to.
  const third = await context.newPage();
  await third.goto('/');
  await expect(third.locator('#createGameBtn')).toBeVisible();
  await expect(third.locator('#gameInProgress')).toBeHidden();
});

test('two tabs of one browser: a rematch swaps the colours, and a reload keeps each tab on its new side', async ({
  browser,
  baseURL,
}) => {
  const { white, black } = await twoTabsOfOneBrowser(browser, baseURL!);

  await resign(black);
  await expectGameResult(white, '1-0');
  await expectGameResult(black, '1-0');
  await white.locator('#rematchBtn').click();
  await black.locator('#rematchBtn').click();
  await expect(white.locator('#gameResultPanel')).toBeHidden({ timeout: 15_000 });
  expect(await colorOf(white)).toBe('black');
  expect(await colorOf(black)).toBe('white');

  // `white` is the tab that created the first game; it plays Black now.
  await white.reload();
  await expect(arbiter(white)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(white)).toBe('black');
  await black.reload();
  await expect(arbiter(black)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(black)).toBe('white');

  await dragPiece(black, 'd2', 'd4');
  await pressClock(black);
  await expect(arbiter(white)).toHaveText('Your turn.');
});

test('two tabs of one browser: after the game each tab reloads to its own view of the finished game', async ({
  browser,
  baseURL,
}) => {
  const { white, black } = await twoTabsOfOneBrowser(browser, baseURL!);
  await resign(white);
  await expectGameResult(black, '0-1');

  await white.reload();
  await expectGameResult(white, '0-1');
  expect(await colorOf(white)).toBe('white');
  await black.reload();
  await expectGameResult(black, '0-1');
  expect(await colorOf(black)).toBe('black');
  await expect(black.locator('#board .piece')).toHaveCount(32);
});

test('two browsers: the way back is offered once the game tab is gone, and not while it is open', async ({
  browser,
  baseURL,
}) => {
  const creatorBrowser = await newBrowser(browser, baseURL!);
  const joinerBrowser = await newBrowser(browser, baseURL!);
  const creator = await creatorBrowser.newPage();
  const gameId = await createOnStartScreen(creator);

  // The creator waits. A second tab of that browser has nothing to return to...
  const creatorSecond = await creatorBrowser.newPage();
  await creatorSecond.goto('/');
  await expect(creatorSecond.locator('#createGameBtn')).toBeVisible();
  await expect(creatorSecond.locator('#gameInProgress')).toBeHidden();
  // ...until the tab with the game is closed: then the start screen leads back into it.
  await creator.close();
  await creatorSecond.reload();
  await expect(creatorSecond.locator('#gameInProgress')).toBeVisible();
  await creatorSecond.locator('#returnToGameBtn').click();
  await expect(arbiter(creatorSecond)).toHaveText('Reconnected. Waiting for opponent…', { timeout: 15_000 });
  await expect(creatorSecond.locator('.game-code-value')).toHaveText(gameId);

  // The other browser joins; the game is played between the two browsers.
  const joiner = await joinerBrowser.newPage();
  await joinOnStartScreen(joiner, gameId);
  await expectGameStarted(joiner);
  await expect(arbiter(creatorSecond)).toContainText('clock has been started', { timeout: 15_000 });
  await dragPiece(creatorSecond, 'e2', 'e4');
  await pressClock(creatorSecond);
  await expect(arbiter(joiner)).toHaveText('Your turn.');

  // The joiner loses the tab and comes back by the code: the game is full, and the seat in it is
  // this browser's own - the new tab takes it again.
  await joiner.close();
  const joinerAgain = await joinerBrowser.newPage();
  await joinerAgain.goto('/');
  await expect(joinerAgain.locator('#gameInProgress')).toBeVisible();
  await joinerAgain.locator('#gameCodeInput').fill(gameId);
  await joinerAgain.locator('#joinGameBtn').click();
  await expect(arbiter(joinerAgain)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(joinerAgain)).toBe('black');
  await expectPiece(joinerAgain, 'e4', 'WHITE_PAWN');
  await dragPiece(joinerAgain, 'e7', 'e5');
  await pressClock(joinerAgain);
  await expect(arbiter(creatorSecond)).toHaveText('Your turn.');

  // Reloads in two browsers are what they were.
  await creatorSecond.reload();
  await expect(arbiter(creatorSecond)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(creatorSecond)).toBe('white');
  await joinerAgain.reload();
  await expect(arbiter(joinerAgain)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(joinerAgain)).toBe('black');
});

test('a full game opened again by its code in the same browser: the new tab takes the seat over', async ({
  browser,
  baseURL,
}) => {
  const creatorBrowser = await newBrowser(browser, baseURL!);
  const joinerBrowser = await newBrowser(browser, baseURL!);
  const creator = await creatorBrowser.newPage();
  const gameId = await createOnStartScreen(creator);
  const joiner = await joinerBrowser.newPage();
  await joinOnStartScreen(joiner, gameId);
  await expectGameStarted(creator);
  await expectGameStarted(joiner);

  // No seat is free, and the joiner's browser has one in this game: as with "the game opened in a
  // second window", the window opened last holds the seat.
  const second = await joinerBrowser.newPage();
  await joinOnStartScreen(second, gameId);
  await expect(arbiter(second)).toHaveText('Reconnected.', { timeout: 15_000 });
  expect(await colorOf(second)).toBe('black');
  await expect(arbiter(joiner)).toHaveText(REPLACED);
  await expect(arbiter(creator)).not.toHaveText(REPLACED);
});

test('two games in two tabs of one browser: each tab reloads into its own game', async ({ browser, baseURL }) => {
  const first = await newBrowser(browser, baseURL!);
  const other = await newBrowser(browser, baseURL!);
  const tabOne = await first.newPage();
  const gameOne = await createOnStartScreen(tabOne);
  const opponent = await other.newPage();
  await joinOnStartScreen(opponent, gameOne);
  await expectGameStarted(tabOne);

  // A second tab of the first browser creates another game. Up to 0.4.0 that replaced the stored
  // seat of the first tab, which a reload then took into the other game.
  const tabTwo = await first.newPage();
  const gameTwo = await createOnStartScreen(tabTwo);
  expect(gameTwo).not.toBe(gameOne);

  await tabOne.reload();
  await expect(arbiter(tabOne)).toHaveText('Reconnected.', { timeout: 15_000 });
  await dragPiece(tabOne, 'e2', 'e4');
  await pressClock(tabOne);
  await expect(arbiter(opponent)).toHaveText('Your turn.');

  await tabTwo.reload();
  await expect(arbiter(tabTwo)).toHaveText('Reconnected. Waiting for opponent…', { timeout: 15_000 });
  await expect(tabTwo.locator('.game-code-value')).toHaveText(gameTwo);
});
