import { test, expect, Page } from '@playwright/test';
import {
  startTwoPlayerGame, clickRestore, expectGameResumed, expectGameResult, offerDraw, resign, TwoPlayerGame,
} from './helpers/app';
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
const BOTH_CASTLINGS = 'r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1';
/** The knight on f3 gives check: White cannot castle. */
const WHITE_IN_CHECK = 'r3k2r/pppppppp/8/8/8/5n2/PPPPP1PP/R3K2R w KQkq - 0 1';

/** The circled squares of a board, as "square:colour", in alphabetical order. */
const marks = (page: Page) => page.evaluate(() =>
  [...document.querySelectorAll('#board .square')]
    .flatMap((sq) => ['green', 'red', 'blue'] // 'blue' was a third colour for a day; it must not come back
      .filter((colour) => sq.classList.contains(`mark-${colour}`))
      .map((colour) => `${(sq as HTMLElement).dataset.square}:${colour}`))
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

test('an own piece and the opponent piece it can capture are both circled green', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: CAPTURE_ON_D5 });
  const { white, black } = game;

  await clickSquare(white, 'e4');
  await clickSquare(white, 'd5');
  await dragPiece(white, 'g1', 'f3');
  await pressOwnClock(white);

  await expect(arbiter(white)).toContainText('d5');
  for (const page of [white, black]) await expectMarks(page, ['d5:green', 'e4:green']);
});

test('king and rook touched for castling are circled where they stand', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: BOTH_CASTLINGS });
  const { white, black } = game;

  // The king is put on the other side than the rook that was touched: it has to go back to e1.
  await clickSquare(white, 'e1');
  await clickSquare(white, 'a1');
  await dragPiece(white, 'e1', 'g1');
  await pressClock(white);

  await expect(arbiter(white)).toContainText('queenside');
  for (const page of [white, black]) await expectMarks(page, ['a1:green', 'e1:green']);
});

test('a king that was put on its castling square is circled there, not on the square it left', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: BOTH_CASTLINGS });
  const { white, black } = game;

  // King and rook touched, the king put on g1, the rook forgotten: only the rook is still to be moved.
  await clickSquare(white, 'e1');
  await clickSquare(white, 'h1');
  await dragPiece(white, 'e1', 'g1');
  await pressClock(white);

  await expect(arbiter(white)).toContainText('castl');
  for (const page of [white, black]) {
    await expectPiece(page, 'g1', 'WHITE_KING');
    await expectMarks(page, ['g1:green', 'h1:green']);
  }

  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');
  for (const page of [white, black]) await expectMarks(page, []);
});

test('the green circle of a touched piece outlives an illegal move made after it', async ({ browser }) => {
  // Two illegal moves follow: the limit is raised so that the second one does not end the game.
  game = await startTwoPlayerGame(browser, { maxIllegal: 5 });
  const { white, black } = game;

  await clickSquare(white, 'g1');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);
  await clickRestore(white);
  await expectGameResumed(white);

  // The bound knight is moved illegally: red is over the green circle on g1 while the move is on the board.
  await dragPiece(white, 'g1', 'g3');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Illegal move');
  for (const page of [white, black]) await expectMarks(page, ['g1:red', 'g3:red']);

  // Back on g1 the knight still has to move: its circle is there again, on both boards.
  await clickRestore(white);
  await expectPiece(black, 'g1', 'WHITE_KNIGHT');
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);

  // An illegal move of another piece is no illegal move here: the knight on g1 is owed, so this is
  // a touch-move violation. Only the green circle is on the board, and nothing is circled red.
  await expectGameResumed(white);
  await dragPiece(white, 'b1', 'b3');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Touch-move violation: You first touched the knight on g1');
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);
});

test('an illegal move shown with a draw offer is circled red like one shown with a clock press', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'b1', 'b3');
  await offerDraw(white);

  await expect(arbiter(white)).toContainText('Illegal move');
  for (const page of [white, black]) await expectMarks(page, ['b1:red', 'b3:red']);

  await clickRestore(white);
  await expectGameResumed(white);
  for (const page of [white, black]) await expectMarks(page, []);
});

test('an illegal move of the touched piece shown with a draw offer is circled red, then green again', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // Touch g1, play e2-e4, press the clock and revert.
  await clickSquare(white, 'g1');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await clickRestore(white);
  await expectGameResumed(white);
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);

  // Play g1-g3 and offer a draw: the arbiter orders a revert, and the boards show what it speaks of.
  await dragPiece(white, 'g1', 'g3');
  await offerDraw(white);
  await expect(arbiter(white)).toContainText('Illegal move');
  for (const page of [white, black]) await expectMarks(page, ['g1:red', 'g3:red']);

  await clickRestore(white);
  await expectPiece(black, 'g1', 'WHITE_KNIGHT');
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);
});

test('a touch-move ruling on a draw offer circles the touched piece green', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await clickSquare(white, 'g1');
  await dragPiece(white, 'e2', 'e4');
  await offerDraw(white);

  await expect(arbiter(white)).toContainText('knight on g1');
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);
});

test('the circles are taken off both boards when the game ends another way', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await clickSquare(white, 'g1');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  for (const page of [white, black]) await expectMarks(page, ['g1:green']);

  // White resigns instead of restoring the position: no move is owed any more.
  await resign(white);
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expectMarks(page, []);
  }
});

test('the illegal move that loses the game stays circled, and the rematch starts without circles', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { maxIllegal: 1 });
  const { white, black } = game;

  await dragPiece(white, 'b1', 'b3');
  await pressClock(white);

  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expectMarks(page, ['b1:red', 'b3:red']);
  }

  await white.locator('#rematchBtn').click();
  await black.locator('#rematchBtn').click();
  await expect(white.locator('#gameResultPanel')).toBeHidden();
  for (const page of [white, black]) await expectMarks(page, []);
});

test('two pieces of the same kind that moved are not circled as one move', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // The knight from g1 goes to g3 and the one from b1 takes its place: the board looks like b1-g3.
  await dragPiece(white, 'g1', 'g3');
  await dragPiece(white, 'b1', 'g1');
  await pressClock(white);

  await expect(arbiter(white)).toContainText('Illegal move');
  await expect(black.locator('#opponentInfoPanel')).toBeVisible();
  for (const page of [white, black]) await expectMarks(page, []);
});

test('an illegal castling repeated after the Revert is circled again, also when it loses the game', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: WHITE_IN_CHECK });
  const { white, black } = game;

  // Castling out of check.
  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Illegal move');
  for (const page of [white, black]) await expectMarks(page, ['e1:red', 'g1:red']);

  await clickRestore(white);
  await expectGameResumed(white);
  for (const page of [white, black]) await expectMarks(page, []);

  // The same again in the same turn: the second illegal move ends the game, and it stays marked.
  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);
  for (const page of [white, black]) {
    await expectGameResult(page, '0-1');
    await expectMarks(page, ['e1:red', 'g1:red']);
  }
});

// Reported wish (2026-11-25): 1. e4, then Black plays the knight from g8 to f6, puts it back,
// plays the pawn from e7 to e6 and presses the clock. The arbiter says that the move was already
// made; two circles show which move that was - green, like everything the player has to play
// (decided on 2026-11-25: no third colour).
test('the move that was already made is circled green on its start and end square until it is accepted', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(arbiter(black)).toContainText('Your turn');

  await dragPiece(black, 'g8', 'f6');
  await dragPiece(black, 'f6', 'g8');
  await dragPiece(black, 'e7', 'e6');
  await pressOwnClock(black);

  await expect(arbiter(black)).toContainText('Released-piece violation: You already released the knight on f6');
  for (const page of [white, black]) await expectMarks(page, ['f6:green', 'g8:green']);
  expect(await ringColour(black, 'g8')).toBe('15781B');
  expect(await ringColour(white, 'f6')).toBe('15781B');

  // The Revert puts the knight on f6 again. The move is still to be shown with the clock, so it
  // stays circled, as a touched piece does.
  await clickRestore(black);
  for (const page of [white, black]) {
    await expectPiece(page, 'f6', 'BLACK_KNIGHT');
    await expectPiece(page, 'e7', 'BLACK_PAWN');
    await expectMarks(page, ['f6:green', 'g8:green']);
  }
  await expect(arbiter(black)).toContainText('your move is final');
  await pressOwnClock(black);
  await expect(arbiter(black)).toContainText('Move accepted');
  await expect(arbiter(white)).toContainText('Your turn');
  for (const page of [white, black]) await expectMarks(page, []);
});
