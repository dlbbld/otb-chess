import { test, expect } from '@playwright/test';
import {
  startTwoPlayerGame,
  TwoPlayerGame,
  expectGameResult,
  colorOf,
  resign,
} from './helpers/app';
import { dragPiece, pressClock, expectPiece, expectEmpty } from './helpers/board';

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

test('rematch: offer blinks on the opponent, accepting starts a new game with colours swapped', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  // Change the position first (1. e4) so the rematch's board RESET is actually observable,
  // then end the game normally: Black resigns, White wins.
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await resign(black);
  await expectGameResult(white, '1-0');
  await expectGameResult(black, '1-0');
  await expect(white.locator('#rematchBtn')).toBeVisible();
  await expect(black.locator('#rematchBtn')).toBeVisible();

  // White offers a rematch: their own button freezes as "Rematch offered", and — Lichess-style —
  // Black's button starts blinking as the invitation.
  await white.locator('#rematchBtn').click();
  await expect(white.locator('#rematchBtn')).toBeDisabled();
  await expect(white.locator('#rematchBtn')).toHaveText('Rematch offered');
  await expect(white.locator('#arbiterMessage')).toContainText('Rematch offer sent');
  await expect(black.locator('#rematchBtn')).toHaveClass(/rematch-blink/);
  await expect(black.locator('#arbiterMessage')).toContainText('offers a rematch');

  // Black clicks their (blinking) button: that accepts — the rematch starts.
  await black.locator('#rematchBtn').click();

  // Both players are in a fresh game with colours SWAPPED and the same time control.
  await expect(white.locator('#arbiterMessage')).toContainText('Rematch started - you now play Black');
  await expect(black.locator('#arbiterMessage')).toContainText('Rematch started - you now play White');
  expect(await colorOf(white)).toBe('black');
  expect(await colorOf(black)).toBe('white');
  await expect(white.locator('#timeControlLabel')).toHaveText('3+0 • Blitz');
  await expect(black.locator('#timeControlLabel')).toHaveText('3+0 • Blitz');
  await expect(white.locator('#gameResultPanel')).toBeHidden();
  await expect(black.locator('#gameResultPanel')).toBeHidden();

  // The board is RESET to the standard starting position (1. e4 from the previous game undone).
  await expectPiece(white, 'e2', 'WHITE_PAWN');
  await expectPiece(black, 'e2', 'WHITE_PAWN');
  await expectEmpty(black, 'e4');

  // The new game is fully playable: the NEW White (the former Black player) opens 1. e4.
  await dragPiece(black, 'e2', 'e4');
  await expectPiece(black, 'e4', 'WHITE_PAWN');
  await pressClock(black);
  await expect(black.locator('#arbiterMessage')).toContainText('Move accepted');
  await expect(white.locator('#arbiterMessage')).toContainText('Your turn');
  await expectPiece(white, 'e4', 'WHITE_PAWN');
});

test('rematch of a custom-FEN game restarts from the CUSTOM position, colours swapped', async ({ browser }) => {
  // Custom training position: White king e1 + rook h1 (castling rights), Black lone king e8.
  const CUSTOM_FEN = '4k3/8/8/8/8/8/8/4K2R w K - 0 1';
  game = await startTwoPlayerGame(browser, { fen: CUSTOM_FEN });
  const { white, black } = game;

  // Change the position (rook up the file), then end the game: Black resigns.
  await dragPiece(white, 'h1', 'h5');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toContainText('Your turn');
  await resign(black);
  await expectGameResult(white, '1-0');

  // Rematch handshake.
  await white.locator('#rematchBtn').click();
  await expect(black.locator('#rematchBtn')).toHaveClass(/rematch-blink/);
  await black.locator('#rematchBtn').click();
  await expect(white.locator('#arbiterMessage')).toContainText('Rematch started - you now play Black');
  await expect(black.locator('#arbiterMessage')).toContainText('Rematch started - you now play White');

  // The CUSTOM starting position is restored on both boards: rook back on h1, h5 empty again —
  // and e2 is empty, proving this is the custom position, NOT the standard start.
  await expectPiece(white, 'h1', 'WHITE_ROOK');
  await expectPiece(black, 'h1', 'WHITE_ROOK');
  await expectEmpty(black, 'h5');
  await expectEmpty(black, 'e2');

  // Colours swapped: the FEN's side to move (White) now belongs to the former Black player,
  // and the position is fully playable.
  expect(await colorOf(black)).toBe('white');
  expect(await colorOf(white)).toBe('black');
  await dragPiece(black, 'h1', 'h4');
  await pressClock(black);
  await expect(black.locator('#arbiterMessage')).toContainText('Move accepted');
  await expectPiece(white, 'h4', 'WHITE_ROOK');
});

test('a rematch offer during a running game is rejected', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white } = game;

  // The button is hidden mid-game (result panel), so talk to the server directly.
  await white.evaluate(() => (window as any).game.ws.send({ type: 'rematchOffer' }));
  await expect(white.locator('#arbiterMessage')).toContainText(
    'A rematch can only be offered after the game has ended');
});

test('a pending rematch becomes unavailable when the offering player closes the browser', async ({
  browser,
}) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;

  await resign(black);
  await expectGameResult(white, '1-0');

  // Black offers the rematch and then leaves before White accepts.
  await black.locator('#rematchBtn').click();
  await expect(white.locator('#rematchBtn')).toHaveClass(/rematch-blink/);
  await black.context().close();

  await expect(white.locator('#arbiterMessage')).toContainText(
    'Your opponent has disconnected. A rematch is no longer available',
    { timeout: 10_000 });
  await expect(white.locator('#rematchBtn')).toBeDisabled();
  await expect(white.locator('#rematchBtn')).toHaveText('Rematch unavailable');
  await expect(white.locator('#gameResultPanel')).toBeVisible();

  // A crafted click/message after the opponent is gone must not start a new one-player rematch.
  await white.evaluate(() => (window as any).game.ws.send({ type: 'rematchOffer' }));
  await expect(white.locator('#arbiterMessage')).toContainText('rematch is no longer available');
  await expect(white.locator('#gameResultPanel')).toBeVisible();
  await expect(white.locator('#gameBanner')).not.toHaveText('Rematch started');
});
