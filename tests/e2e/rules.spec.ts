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
import { dragPiece, removePiece, pressClock, expectPiece, expectEmpty } from './helpers/board';

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
  await expect(white.locator('#arbiterMessage')).toContainText('Your last move delivered checkmate');
  await expect(black.locator('#arbiterMessage')).toContainText('You have been checkmated');
});

test('stalemate ends the game as a draw', async ({ browser }) => {
  // White: Kg6, Qf1. Black: Kh8 (only piece). Qf1-f7 leaves Black with no legal move, not in check.
  game = await startTwoPlayerGame(browser, { fen: '7k/8/6K1/8/8/8/8/5Q2 w - - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'f1', 'f7');
  await pressClock(white);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#arbiterMessage')).toContainText('Your last move resulted in stalemate');
  await expect(black.locator('#arbiterMessage')).toContainText("opponent's last move resulted in stalemate");
});

test('checkmate delivered by black is announced symmetrically', async ({ browser }) => {
  // Black: Ra8, Kh8. White: Kg1 boxed in by its own pawns f2/g2/h2. Ra8-a1 is mate.
  game = await startTwoPlayerGame(browser, { fen: 'r6k/8/8/8/8/8/5PPP/6K1 b - - 0 1' });
  const { white, black } = game; // FEN is black-to-move, so the creator plays black

  await dragPiece(black, 'a8', 'a1');
  await pressClock(black);

  await expectGameResult(black, '0-1');
  await expectGameResult(white, '0-1');
  await expect(black.locator('#arbiterMessage')).toContainText('Your last move delivered checkmate');
  await expect(white.locator('#arbiterMessage')).toContainText('You have been checkmated');
});

test('stalemate caused by black is announced symmetrically', async ({ browser }) => {
  // Black: Qf8, Kg3. White: lone Kh1. Qf8-f2 leaves White with no legal move, not in check.
  game = await startTwoPlayerGame(browser, { fen: '5q2/8/8/8/8/6k1/8/7K b - - 0 1' });
  const { white, black } = game;

  await dragPiece(black, 'f8', 'f2');
  await pressClock(black);

  await expectGameResult(black, SCORE_DRAW);
  await expectGameResult(white, SCORE_DRAW);
  await expect(black.locator('#arbiterMessage')).toContainText('Your last move resulted in stalemate');
  await expect(white.locator('#arbiterMessage')).toContainText("opponent's last move resulted in stalemate");
});

test('resignation ends the game for the opponent', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await resign(white);

  // White resigned -> Black wins.
  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
});

test('a player can resign while it is the opponent turn', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await dragPiece(white, 'e2', 'e4');
  await pressClock(white); // now it is black's turn
  await resign(white); // white resigns although it is not white's turn

  await expectGameResult(white, '0-1');
  await expectGameResult(black, '0-1');
});

test('the player not on move can resign immediately', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // At the start it is white's turn; black (not on move) resigns -> white wins.
  await resign(black);

  await expectGameResult(white, '1-0');
  await expectGameResult(black, '1-0');
});

test('resigning when the opponent has only a lone king is a draw (insufficient material)', async ({
  browser,
}) => {
  // White (Rd5, Kc4) resigns, but Black has only a lone king and can never mate -> FIDE draw.
  game = await startTwoPlayerGame(browser, { fen: '8/8/4k3/3R4/2K5/8/8/8 w - - 0 50' });
  const { white, black } = game;

  await resign(white);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#gameResultReason')).toContainText('insufficient material to mate');
});

test('resigning in a blocked position with material is a draw (no potential mate)', async ({ browser }) => {
  // A fully blocked pawn wall: Black has pawns (sufficient material) but can never break through.
  game = await startTwoPlayerGame(browser, { fen: '8/8/3k4/1p2p1p1/pP1pP1P1/P2P4/1K6/8 w - - 32 62' });
  const { white, black } = game;

  await resign(white);

  await expectGameResult(white, SCORE_DRAW);
  await expectGameResult(black, SCORE_DRAW);
  await expect(white.locator('#gameResultReason')).toContainText('no potential mate');
});

test('an illegal move is rejected (arbiter flags an error)', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  // A knight cannot move g1-g3.
  await dragPiece(white, 'g1', 'g3');
  await pressClock(white);

  await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
});

// Touch-move scenarios live in touch-move.spec.ts.

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

test('the board does not mark the king in check (no red frame)', async ({ browser }) => {
  // White: Ra1, Ke1. Black: lone Ke8. Ra1-a8+ gives check along the 8th rank. The board must
  // not inform the checked player — no red frame on the king square, then or after it moves.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/R3K3 w - - 0 1' });
  const { white, black } = game;

  await dragPiece(white, 'a1', 'a8');
  await pressClock(white); // black is now in check and on the move

  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await expect(black.locator('#board .square.check')).toHaveCount(0);
  await expect(white.locator('#board .square.check')).toHaveCount(0);

  // ...and it must not appear (or linger) once the king steps off the checked square.
  await dragPiece(black, 'e8', 'e7');
  await expect(black.locator('#board .square.check')).toHaveCount(0);
});
