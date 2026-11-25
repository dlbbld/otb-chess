import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, expectGameResult } from './helpers/app';
import { dragPiece, pressClock, expectPiece } from './helpers/board';

// FIDE 7.5.3: pressing the clock without making a move is considered and penalised as an
// illegal move. The arbiter stops the clock for the ruling, as for every other one, and restarts
// it after a short pause (1 s in this suite, see playwright.config.ts). A press in that pause is
// not ruled on: a double click is one illegal move. With the default limit (two illegal moves
// lose), a second press after the restart loses the game.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

const RULING = 'Illegal move: the clock was pressed without a move being made (FIDE 7.5.3).';
const arbiter = (page: Page) => page.locator('#arbiterMessage');
const clock = (page: Page) => page.locator('#chessClock');

async function ownLever(page: Page) {
  const bottomOwn = await page.locator('#bottomClockDisplay').evaluate((el) => el.classList.contains('own-clock'));
  return page.locator(bottomOwn ? '#bottomClockBtn' : '#topClockBtn');
}

test('a clock press without a move is an illegal move with a pause; the game continues with a real move', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser); // 3+0, default limit: 2 illegal moves
  const { white, black } = game;

  // White presses the clock without having touched a piece.
  await pressClock(white);
  await expect(arbiter(white)).toHaveText(
    `${RULING} This is your 1st illegal move. Your next illegal move will lose the game. Please make a move.` +
      ' The game is paused and will continue shortly.');
  await expect(arbiter(white)).not.toContainText('restore');
  await expect(arbiter(black)).not.toContainText('Your opponent made an illegal move');
  await expect(black.locator('#opponentInfoPanel')).toContainText(
    'They are requested to make a move. The game is paused; the clock will restart shortly.');
  for (const page of [white, black]) await expect(clock(page)).toHaveClass(/paused/);

  // The standard illegal-move penalty: Black gains 2 minutes (3:00 -> 5:00, clock not running).
  await expect(white.locator('#topClockTime')).toHaveText('5:00', { timeout: 10_000 });

  // The arbiter restarts the clock. The ruling stays in view; only its last sentence changes.
  await expect(arbiter(white)).toHaveText(
    `${RULING} This is your 1st illegal move. Your next illegal move will lose the game. Please make a move.` +
      ' The clock has restarted.');
  await expect(black.locator('#opponentInfoPanel')).toContainText(
    'They are requested to make a move. The clock has restarted.');
  for (const page of [white, black]) await expect(clock(page)).not.toHaveClass(/paused/);

  // Nothing to restore — White simply plays a real move and the game continues normally.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted');
  await expect(arbiter(black)).toContainText('Your turn');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
});

test('a double click on the clock without a move is one illegal move, and so is a further press in the pause', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await (await ownLever(white)).dblclick();
  await expect(arbiter(white)).toContainText('This is your 1st illegal move.');
  await expect(arbiter(white)).toContainText('The game is paused and will continue shortly.');

  // A deliberate further press, still inside the pause: the server does not rule on it and does
  // not answer it, so the ruling stays where it is.
  await white.waitForTimeout(500);
  await pressClock(white);
  await expect(arbiter(white)).toContainText('The clock has restarted.');
  await expect(arbiter(white)).toContainText('This is your 1st illegal move.');
  for (const page of [white, black]) await expect(page.locator('#gameResultPanel')).toBeHidden();

  // A move made in the pause or after it is accepted once the clock runs again.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Move accepted');
  await expect(arbiter(black)).toContainText('Your turn');
});

test('a clock press without a move after the restart is the second illegal move and loses the game (default limit)', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await pressClock(white);
  await expect(arbiter(white)).toContainText('Your next illegal move will lose the game');
  await expect(arbiter(white)).toContainText('The clock has restarted.');

  await pressClock(white);
  await expect(arbiter(white)).toContainText('White loses the game');
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
  await expect(black.locator('#gameResultReason')).toContainText('2nd illegal move by White');
});

test('a double click on the clock after a move leaves "Move accepted" in view', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'e2', 'e4');
  await (await ownLever(white)).dblclick();
  await expect(arbiter(white)).toHaveText("Move accepted. Opponent's turn.");
  await expect(arbiter(black)).toContainText('Your turn');

  // The same when the second press does reach the server before the answer to the first one has
  // arrived, as over a slow connection: two presses sent in one go, past the page's own guard.
  await dragPiece(black, 'e7', 'e5');
  await black.evaluate(() => {
    const game = (window as any).game;
    game.ws.sendClockPress(game.board.getBoardState());
    game.ws.sendClockPress(game.board.getBoardState());
  });
  await expect(arbiter(white)).toContainText('Your turn');
  await black.waitForTimeout(400);
  await expect(arbiter(black)).toHaveText("Move accepted. Opponent's turn.");
});
