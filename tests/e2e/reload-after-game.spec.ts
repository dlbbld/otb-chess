import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame, createGame, joinGame, expectGameStarted, expectGameResult,
  colorOf, resign } from './helpers/app';
import { dragPiece, pressClock, expectPiece, expectEmpty } from './helpers/board';

// A player who reloads the page after the game has ended sees how it ended: the board as it
// stood, the final times and the result. Before, the joiner's page came back with an empty board
// ("all the pieces disappeared", reported from the live site) and the creator's page silently
// created a new game. The seat is not given back: the page only shows the finished game.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

const RULING = 'Illegal move: the clock was pressed without a move being made (FIDE 7.5.3).';
const LOST = `${RULING} White loses the game. This was the 2nd illegal move by White.`;
const arbiter = (page: Page) => page.locator('#arbiterMessage');
const pieces = (page: Page) => page.locator('#board .piece');

/** White presses the clock twice without a move, with the arbiter's pause in between: 0-1. */
async function whiteLosesByTwoPressesWithoutAMove(white: Page, black: Page) {
  await pressClock(white);
  await expect(arbiter(white)).toContainText('The clock has restarted.', { timeout: 10_000 });
  await pressClock(white);
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
}

/** The page shows the finished game and nothing on it can be used, except the way back. */
async function expectFinishedGameShown(page: Page, score: string, reason: string) {
  await expectGameResult(page, score);
  await expect(page.locator('#gameResultReason')).toHaveText(reason);
  await expect(page.locator('#arbiterButtons').getByText('Back to lobby')).toBeVisible();
  await expect(page.locator('#rematchBtn')).toBeHidden();
  for (const id of ['#resignBtn', '#offerDrawBtn', '#exportPgnBtn', '#flipBoardBtn']) {
    await expect(page.locator(id)).toBeDisabled();
  }
  await expect(page.locator('.game-code-value')).toHaveCount(0); // no new game was created
}

test('the reported journey: two clock presses lose the game, the joiner reloads and still sees the board', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game; // White created, Black joined

  await whiteLosesByTwoPressesWithoutAMove(white, black);
  await expect(pieces(black)).toHaveCount(32);
  const times = [await black.locator('#topClockTime').textContent(), await black.locator('#bottomClockTime').textContent()];

  await black.reload();

  // All the pieces are there, Black's own side at the bottom, with the final times and the result.
  await expect(pieces(black)).toHaveCount(32);
  await expectPiece(black, 'e2', 'WHITE_PAWN');
  await expectPiece(black, 'e8', 'BLACK_KING');
  expect(await colorOf(black)).toBe('black');
  await expect(black.locator('#topClockTime')).toHaveText(times[0]!);
  await expect(black.locator('#bottomClockTime')).toHaveText(times[1]!);
  await expectFinishedGameShown(black, '0-1', LOST);
  await expect(arbiter(black)).toHaveText('This game has already ended.');

  // The opponent's page is not disturbed: no reconnect notice, the result stays as it was.
  await expect(arbiter(white)).toHaveText(LOST);
  await expect(white.locator('#opponentInfoPanel')).toBeHidden();
  await expectGameResult(white, '0-1');

  // A second reload shows the same again.
  await black.reload();
  await expect(pieces(black)).toHaveCount(32);
  await expectFinishedGameShown(black, '0-1', LOST);
});

test('the creator reloads after the game: the finished game, not a new one', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await whiteLosesByTwoPressesWithoutAMove(white, black);

  await white.reload();

  await expect(pieces(white)).toHaveCount(32);
  expect(await colorOf(white)).toBe('white');
  await expectFinishedGameShown(white, '0-1', LOST);
  await expect(arbiter(white)).toHaveText('This game has already ended.');
  await expect(arbiter(white)).not.toContainText('Waiting for opponent');
});

test('the board comes back as it stood at the end, with the arbiter speaking to the returning player', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toHaveText('Your turn.');
  await dragPiece(black, 'd7', 'd5');
  await pressClock(black);
  await expect(arbiter(white)).toHaveText('Your turn.');
  // White takes on d5; the capture stands on the board when Black resigns.
  await dragPiece(white, 'e4', 'd5');
  await pressClock(white);
  await expect(arbiter(black)).toHaveText('Your turn.');
  await resign(black);
  await expectGameResult(white, '1-0');
  const reason = (await white.locator('#gameResultReason').textContent()) ?? '';
  expect(reason).not.toBe('');

  await white.reload();
  await expect(pieces(white)).toHaveCount(31);
  await expectPiece(white, 'd5', 'WHITE_PAWN');
  await expectEmpty(white, 'e2');
  await expectEmpty(white, 'e4');
  await expectEmpty(white, 'd7');
  await expectFinishedGameShown(white, '1-0', reason);

  await black.reload();
  await expect(pieces(black)).toHaveCount(31);
  await expectPiece(black, 'd5', 'WHITE_PAWN');
  expect(await colorOf(black)).toBe('black');
  await expectFinishedGameShown(black, '1-0', reason);
});

test('checkmate: the returning players are told who mated', async ({ browser }) => {
  // Fool's mate position one move before the end: Black mates with Qh4.
  game = await startTwoPlayerGame(browser, {
    fen: 'rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2',
  });
  const { white, black } = game;

  await dragPiece(black, 'd8', 'h4');
  await pressClock(black);
  await expectGameResult(black, '0-1');
  await expectGameResult(white, '0-1');

  await black.reload();
  await expectPiece(black, 'h4', 'BLACK_QUEEN');
  await expect(arbiter(black)).toHaveText('Your last move delivered checkmate.');
  await expectGameResult(black, '0-1');
  await expect(black.locator('#arbiterButtons').getByText('Back to lobby')).toBeVisible();

  await white.reload();
  await expectPiece(white, 'h4', 'BLACK_QUEEN');
  await expect(arbiter(white)).toHaveText('You have been checkmated.');
  await expectGameResult(white, '0-1');
});

test('after the game the start screen offers no game in progress, and a new game can be created and joined', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await whiteLosesByTwoPressesWithoutAMove(white, black);

  // Back on the start screen: nothing "in progress", although the seat of the finished game is kept.
  await black.reload();
  await black.locator('#arbiterButtons').getByText('Back to lobby').click();
  await expect(black.locator('#createGameBtn')).toBeVisible();
  await expect(black.locator('#gameInProgress')).toBeHidden();

  // The code of the finished game, entered on the start screen, shows that game again.
  await black.locator('#gameCodeInput').fill(game.gameId);
  await black.locator('#joinGameBtn').click();
  await expect(pieces(black)).toHaveCount(32);
  await expectFinishedGameShown(black, '0-1', LOST);

  // The kept seat does not stand in the way of other games: White opens a new one by its address,
  // Black joins it by its address, and they play.
  await white.goto('/');
  const second = await createGame(white);
  expect(second).not.toBe(game.gameId);
  await joinGame(black, second);
  await expectGameStarted(white);
  await expectGameStarted(black);
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toHaveText('Your turn.');

  // And a reload during that game resumes it, as before.
  await black.reload();
  await expect(arbiter(black)).toHaveText('Reconnected.');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
});
