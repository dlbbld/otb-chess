import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, expectGameResult, SCORE_DRAW, TwoPlayerGame } from './helpers/app';
import { dragPiece, expectPiece, pressClock } from './helpers/board';

// Regressions for the findings of the final review pass before the release. Each test replays the journey
// its finding was reproduced with, on both boards.

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');
const offerPanel = (page: Page) => page.locator('#drawOfferPanel');
const resultPanel = (page: Page) => page.locator('#gameResultPanel');
const REPLACED = 'This game is now open in another window. This window no longer takes part.';

/** One move by each player, so that both have made a move (a draw can be agreed from then on). */
async function playOneMoveEach(white: Page, black: Page) {
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await dragPiece(black, 'e7', 'e5');
  await pressClock(black);
  await expect(arbiter(white)).toContainText('Your turn');
}

test('the game opened in a second window: the old window is out of the game and says so', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black: blackOld } = game;
  await playOneMoveEach(white, blackOld);

  // Black opens the running game again in a second window of the same browser.
  const blackNew = await game.contexts[1].newPage();
  await blackNew.goto('/game.html');
  await expect(arbiter(blackNew)).toHaveText('Reconnected.', { timeout: 15_000 });

  // The old window used to look alive: enabled buttons, a standing clock, and a server that still
  // acted on its messages.
  await expect(arbiter(blackOld)).toHaveText(REPLACED);
  for (const id of ['offerDrawBtn', 'resignBtn', 'claimThreefoldOnBoardBtn', 'flipBoardBtn']) {
    await expect(blackOld.locator(`#${id}`)).toBeDisabled();
  }
  expect(await blackOld.evaluate(() => (window as any).game.board.enabled)).toBe(false);

  await blackNew.locator('#offerDrawBtn').click();
  await expect(offerPanel(white)).toBeVisible();

  // The journey of the finding: Offer draw (and Accept) from the old window agreed the draw alone.
  await blackOld.evaluate(() => {
    const ws = (window as any).game.ws;
    ws.send({ type: 'offerDraw' });
    ws.send({ type: 'acceptDraw' });
    ws.send({ type: 'rejectDraw' });
    ws.send({ type: 'resign' });
  });
  await expect(arbiter(blackOld)).toHaveText('You are not in a game.');
  await expect(blackOld.locator('#devConsole')).toBeHidden();
  for (const page of [white, blackNew]) {
    await expect(resultPanel(page)).toBeHidden();
  }
  // White still has Black's offer, and answers it.
  await expect(offerPanel(white)).toBeVisible();
  await white.locator('#rejectDrawBtn').click();
  await expect(arbiter(white)).toHaveText('You rejected the draw offer.');
  await expect(arbiter(blackNew)).toHaveText('Your opponent rejected the draw offer.');

  // The game goes on between White and Black's new window.
  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await expect(arbiter(blackNew)).toContainText('Your turn');
  await expectPiece(blackNew, 'f3', 'WHITE_KNIGHT');
  await blackNew.locator('#offerDrawBtn').click();
  await white.locator('#acceptDrawBtn').click();
  for (const page of [white, blackNew]) {
    await expectGameResult(page, SCORE_DRAW);
  }
  await expect(arbiter(white)).toHaveText('You accepted the draw offer.');
  await expect(resultPanel(blackOld)).toBeHidden();
});

test('the window that is opened last holds the seat', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black: first } = game;
  const second = await game.contexts[1].newPage();
  await second.goto('/game.html');
  await expect(arbiter(second)).toHaveText('Reconnected.', { timeout: 15_000 });
  await expect(arbiter(first)).toHaveText(REPLACED);

  // A refresh of the first window takes the seat back; now the second window is out.
  await first.reload();
  await expect(arbiter(first)).toHaveText('Reconnected.', { timeout: 15_000 });
  await expect(arbiter(second)).toHaveText(REPLACED);
  await expect(second.locator('#resignBtn')).toBeDisabled();

  // The frozen window does not reconnect behind the player's back, and the opponent saw no disconnect.
  await second.waitForTimeout(2500);
  await expect(arbiter(first)).toHaveText('Reconnected.');
  await expect(arbiter(white)).not.toContainText('disconnected');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(first)).toContainText('Your turn');
  await expectPiece(first, 'e4', 'WHITE_PAWN');
});
