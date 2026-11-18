import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, clickRestore, expectGameResumed, TwoPlayerGame } from './helpers/app';
import { clickSquare, dragPiece, expectPiece, flipBoard, pressClock, pressOwnClock } from './helpers/board';

// The arbiter's circles: with a ruling on a clock press, the piece a touch obliges the player to move or
// capture is circled green, and the start and end square of an illegal move are circled red - on both boards.

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

const arbiter = (page: Page) => page.locator('#arbiterMessage');
/** After 1. e4 d5: White can capture on d5. */
const CAPTURE_ON_D5 = 'rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2';

/** The circled squares of a board, as "square:colour", in alphabetical order. */
const marks = (page: Page) => page.evaluate(() =>
  [...document.querySelectorAll('#board .square')]
    .filter((sq) => sq.classList.contains('mark-green') || sq.classList.contains('mark-red'))
    .map((sq) => `${(sq as HTMLElement).dataset.square}:${sq.classList.contains('mark-green') ? 'green' : 'red'}`)
    .sort());

async function expectMarks(page: Page, expected: string[]) {
  await expect.poll(() => marks(page)).toEqual(expected);
}

/** The colour of the circle that is drawn on a square (the stroke of its ring), or '' without one. */
const ringColour = (page: Page, square: string) => page.evaluate((sq) => {
  const image = getComputedStyle(document.querySelector(`#board .square[data-square="${sq}"]`)!, '::after')
    .backgroundImage;
  return /stroke='%23([0-9A-Fa-f]{6})'/.exec(image)?.[1] ?? '';
}, square);

test('a touched piece that was not moved is circled green on both boards until the move is made', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await expectMarks(white, []);

  // White touches the knight on g1 and another piece after it, then plays the e-pawn.
  await clickSquare(white, 'g1');
  await clickSquare(white, 'b1');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);

  await expect(arbiter(white)).toContainText('knight on g1');
  for (const page of [white, black]) {
    await expectMarks(page, ['g1:green']); // only the first piece touched
    expect(await ringColour(page, 'g1')).toBe('15781B');
    expect(await ringColour(page, 'b1')).toBe('');
  }

  // The circle follows its square when the board is turned, and comes back with a reloaded page.
  await flipBoard(black);
  await expectMarks(black, ['g1:green']);
  await flipBoard(black);
  await black.reload();
  await expect(arbiter(black)).toContainText('Reconnected', { timeout: 15_000 });
  await expectMarks(black, ['g1:green']);

  // The knight still has to move after the restoration: the circle stays.
  await clickRestore(white);
  await expectGameResumed(white);
  await expectPiece(white, 'e2', 'WHITE_PAWN');
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);

  await dragPiece(white, 'g1', 'f3');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  for (const page of [white, black]) await expectMarks(page, []);
});

test('a touched opponent piece that can be captured and was not is circled green', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: CAPTURE_ON_D5 });
  const { white, black } = game;

  // White touches a pawn that cannot be captured, then the one on d5 that can, then develops the knight.
  await clickSquare(white, 'a7');
  await clickSquare(white, 'd5');
  await dragPiece(white, 'g1', 'f3');
  await pressOwnClock(white);

  await expect(arbiter(white)).toContainText('d5');
  for (const page of [white, black]) await expectMarks(page, ['d5:green']);

  await clickRestore(white);
  await expectGameResumed(white);
  await dragPiece(white, 'e4', 'd5');
  await pressOwnClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  await expectPiece(black, 'd5', 'WHITE_PAWN');
  for (const page of [white, black]) await expectMarks(page, []);
});

test('an illegal move is circled red on its start and end square until the position is restored', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'b1', 'b3');
  await pressClock(white);

  await expect(arbiter(white)).toContainText('Illegal move');
  for (const page of [white, black]) {
    await expectMarks(page, ['b1:red', 'b3:red']);
    expect(await ringColour(page, 'b1')).toBe('882020');
    expect(await ringColour(page, 'b3')).toBe('882020');
  }
  await white.screenshot({ path: test.info().outputPath('illegal-move.png') });

  await clickRestore(white);
  await expectGameResumed(white);
  await expectPiece(black, 'b1', 'WHITE_KNIGHT');
  for (const page of [white, black]) await expectMarks(page, []);
});

test('an illegal move with several displaced pieces is not circled', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'b1', 'b3');
  await dragPiece(white, 'g1', 'g3');
  await pressClock(white);

  await expect(arbiter(white)).toContainText('Illegal move');
  await expect(black.locator('#opponentInfoPanel')).toBeVisible();
  for (const page of [white, black]) await expectMarks(page, []);
});
