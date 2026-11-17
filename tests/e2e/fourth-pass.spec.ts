import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, clickRestore, expectGameResult, SCORE_DRAW, TwoPlayerGame } from './helpers/app';
import { clickSquare, dragPiece, expectEmpty, expectPiece, pressClock } from './helpers/board';

// Regressions for the third review pass. Each test replays the journey the finding was reproduced with:
// a release and a touch must never give opposite orders, "your move is final" must be true, and a draw
// offer can neither be replaced by a counter offer nor withdrawn by its maker.

let game: TwoPlayerGame | undefined;
test.afterEach(async () => {
  for (const context of game?.contexts ?? []) await context.close();
  game = undefined;
});

const BOTH_CASTLINGS = 'r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1';
const arbiter = (page: Page) => page.locator('#arbiterMessage');
const offerPanel = (page: Page) => page.locator('#drawOfferPanel');
const revertButton = (page: Page) => page.getByRole('button', { name: 'Revert', exact: true });
const boardEnabled = (page: Page) => page.evaluate(() => (window as any).game?.board?.enabled);

async function revertAndWaitForClock(page: Page, resumeText = 'Clock restarted. Game continues.') {
  await clickRestore(page);
  await expect(arbiter(page)).toHaveText(resumeText, { timeout: 15_000 });
}

async function expectMoveAccepted(mover: Page, opponent: Page) {
  await expect(arbiter(mover)).toContainText('Move accepted');
  await expect(arbiter(opponent)).toContainText('Your turn');
  await expect(revertButton(mover)).toHaveCount(0);
}

test('a king released for kingside castling is not overruled by the other rook moved later', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: BOTH_CASTLINGS });
  const { white, black } = game;
  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'g1', 'e1');
  await dragPiece(white, 'a1', 'b1');
  await pressClock(white);

  await expect(arbiter(white)).toHaveText('Castling has been started. Because the king was released on g1 and'
    + ' kingside castling is legal, you must complete the castling move by moving the rook from h1 to f1.');
  await revertAndWaitForClock(white);
  await expectPiece(white, 'g1', 'WHITE_KING');
  await expectPiece(white, 'a1', 'WHITE_ROOK');

  // Exactly what the arbiter asked for. This used to be answered with "please perform the castling move",
  // meaning queenside - the side the arbiter had never named.
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);
  await expectMoveAccepted(white, black);
  await expectPiece(black, 'g1', 'WHITE_KING');
  await expectPiece(black, 'f1', 'WHITE_ROOK');
});

test('a rook touched first rules out castling, and the ruling names the rook from the start', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: BOTH_CASTLINGS });
  const { white, black } = game;
  await clickSquare(white, 'a1');
  await dragPiece(white, 'e1', 'c1');
  await pressClock(white);

  // Not "Castling has been started ... complete the castling move": that castling was never available.
  await expect(arbiter(white)).toContainText('Touch-move violation: You first touched the rook on a1');
  await expect(arbiter(white)).not.toContainText('Castling has been started');
  await expect(black.locator('#opponentInfoPanel')).toContainText('They first touched the rook on a1');
  await revertAndWaitForClock(white);
  await expectPiece(white, 'e1', 'WHITE_KING');

  // Castling in full gets the same ruling - the two answers no longer contradict each other.
  await dragPiece(white, 'e1', 'c1');
  await dragPiece(white, 'a1', 'd1');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Touch-move violation: You first touched the rook on a1');
  await revertAndWaitForClock(white);

  await dragPiece(white, 'a1', 'b1');
  await pressClock(white);
  await expectMoveAccepted(white, black);
});

test('a move that breaks touch-move is not called final after a later intervention', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await clickSquare(white, 'g2');
  await dragPiece(white, 'h2', 'h3');
  await dragPiece(white, 'a7', 'a6');
  await expect(arbiter(white)).toContainText('You moved an opponent\'s piece');

  await clickRestore(white);
  await expect(arbiter(white)).toHaveText('Position restored. Restarting the clock shortly. Please be ready.');
  await expect(arbiter(white)).toHaveText('Clock restarted. Game continues.', { timeout: 15_000 });
  // h2-h3 was no move to go back to: the whole turn is restored.
  await expectPiece(white, 'h2', 'WHITE_PAWN');
  await expectEmpty(white, 'h3');
  await expectPiece(white, 'a7', 'BLACK_PAWN');

  await dragPiece(white, 'g2', 'g3');
  await pressClock(white);
  await expectMoveAccepted(white, black);
});

test('a started castling is not called final until the rook has moved', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: BOTH_CASTLINGS });
  const { white, black } = game;
  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'g1', 'f1');
  await pressClock(white);
  await expect(arbiter(white)).toContainText('Castling has been started');

  await clickRestore(white);
  await expect(arbiter(white)).toHaveText('Position restored. Restarting the clock shortly. Please be ready.');
  await expect(arbiter(white)).toHaveText('Clock restarted. Game continues.', { timeout: 15_000 });
  await expectPiece(white, 'g1', 'WHITE_KING');

  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);
  await expectMoveAccepted(white, black);
});

test('a completed move is still called final, and its clock press is accepted', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragPiece(white, 'e2', 'e4');
  await dragPiece(white, 'a7', 'a6');

  await clickRestore(white);
  await expect(arbiter(white)).toHaveText('Position restored — your move is final. Press the clock to continue.');
  await expect(arbiter(white)).toHaveText('Clock restarted. Your move is final — press the clock to continue.',
    { timeout: 15_000 });
  await pressClock(white);
  await expectMoveAccepted(white, black);
});

test('a counter offer agrees the draw instead of replacing the pending offer', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await white.locator('#offerDrawBtn').click();
  await expect(offerPanel(black)).toBeVisible();

  await black.locator('#offerDrawBtn').click();

  for (const page of [white, black]) {
    await expectGameResult(page, SCORE_DRAW);
    await expect(page.locator('#gameResultReason')).toHaveText('The game is drawn by agreement.');
    await expect(offerPanel(page)).toBeHidden();
  }
  await expect(arbiter(black)).toHaveText('You accepted the draw offer.');
  await expect(arbiter(white)).toHaveText('Your opponent accepted the draw offer.');
});

test('an own offer closes the outdated panel, and no button can withdraw the offer', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await black.locator('#offerDrawBtn').click();
  await expect(offerPanel(white)).toBeVisible();
  // White answers with a move: Black's offer can no longer be accepted, its panel stays by design.
  await dragPiece(white, 'e2', 'e4');
  await expect(offerPanel(white)).toBeVisible();

  await white.locator('#offerDrawBtn').click();
  await expect(arbiter(white)).toHaveText('Draw offer sent.');
  await expect(offerPanel(black)).toBeVisible();
  // The panel White still had stood for Black's old offer. Its Reject button used to cancel White's
  // new offer and tell Black "Your opponent rejected the draw offer".
  await expect(offerPanel(white)).toBeHidden();

  // A Reject that reaches the server anyway (pressed at the same instant, or a modified client).
  await white.evaluate(() => (window as any).game.ws.send({ type: 'rejectDraw' }));
  await expect(arbiter(white)).toHaveText('You cannot reject your own draw offer.');
  await expect(offerPanel(black)).toBeVisible();
  await expect(arbiter(black)).not.toContainText('rejected');

  await black.locator('#acceptDrawBtn').click();
  for (const page of [white, black]) {
    await expectGameResult(page, SCORE_DRAW);
  }
  await expect(arbiter(black)).toHaveText('You accepted the draw offer.');
});

test('a refresh inside the automatic resume delay keeps the board locked until the clock restarts', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await dragPiece(white, 'a7', 'a6');
  await expect(revertButton(white)).toBeVisible();

  await revertButton(white).click();
  await white.reload({ waitUntil: 'commit' });

  // The reconnect lands inside the 1.5 s pause: the server takes no board events yet, so the board must
  // not be usable. It used to be enabled here, and pieces moved now were lost for both players.
  await expect(arbiter(white)).toHaveText('Position restored. Restarting the clock shortly. Please be ready.');
  expect(await boardEnabled(white)).toBe(false);

  await expect(arbiter(white)).toHaveText('Clock restarted. Game continues.', { timeout: 15_000 });
  expect(await boardEnabled(white)).toBe(true);
  await dragPiece(white, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(white);
  await expectMoveAccepted(white, black);
});
