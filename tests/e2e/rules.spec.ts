import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  resign,
  offerDraw,
  acceptDraw,
  expectGameResult,
  SCORE_DRAW,
} from './helpers/app';
import {
  dragPiece,
  clickSquare,
  removePiece,
  pressClock,
  expectPiece,
  expectEmpty,
} from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('checkmate ends the game 1-0', async ({ browser }) => {
  // White: Ra1, Kh1. Black: Kg8 boxed in by its own pawns f7/g7/h7. Ra1-a8 is mate.
  game = await startTwoPlayerGame(browser, { fen: '6k1/5ppp/8/8/8/8/8/R6K w - - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'a1', 'a8');
  await pressClock(white);

  await expectGameResult(white, '1-0');
  await expectGameResult(black, '1-0');
});

test('stalemate ends the game as a draw', async ({ browser }) => {
  // White: Kg6, Qf1. Black: Kh8 (only piece). Qf1-f7 leaves Black with no legal move, not in check.
  game = await startTwoPlayerGame(browser, { fen: '7k/8/6K1/8/8/8/8/5Q2 w - - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'f1', 'f7');
  await pressClock(white);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
});

test('resignation ends the game for the opponent', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await resign(white);

  // White resigned -> Black wins.
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
});

test('an illegal move is rejected (arbiter flags an error)', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  // A knight cannot move g1-g3.
  await dragPiece(white, 'g1', 'g3');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
});

test('touch-move: touching one piece then moving another is a violation', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  // Touch the knight (it has legal moves), then move a different piece.
  await clickSquare(white, 'g1');
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
});

test('kingside castling is accepted', async ({ browser }) => {
  // White: Ke1, Rh1 with O-O rights. Physically castle: king e1->g1, rook h1->f1.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/4K2R w K - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'e1', 'g1');
  await dragPiece(white, 'h1', 'f1');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'g1', 'WHITE_KING');
  await expectPiece(white, 'f1', 'WHITE_ROOK');
  // The opponent receives the castled position and it becomes their turn.
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(black, 'g1', 'WHITE_KING');
  await expectPiece(black, 'f1', 'WHITE_ROOK');
});

test('a draw offer accepted by the opponent ends the game as a draw', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // FIDE: offer a draw after making your move, before pressing the clock.
  await dragPiece(white, 'e2', 'e4');
  await offerDraw(white);
  await acceptDraw(black);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
});

test('en passant capture is accepted', async ({ browser }) => {
  // White Pe5, Black Pd5, with d6 as the en-passant target (Black just played d7-d5).
  // exd6 e.p.: the white pawn goes to d6 and the captured pawn on d5 is lifted off.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'e5', 'd6');
  await removePiece(white, 'd5');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'd6', 'WHITE_PAWN');
  await expectEmpty(white, 'e5');
  await expectEmpty(white, 'd5');
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(black, 'd6', 'WHITE_PAWN');
});
