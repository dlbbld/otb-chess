import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { startTwoPlayerGame, TwoPlayerGame, clickRestore, expectGameResult } from './helpers/app';
import { dragPiece, pressClock, removePiece, expectPiece, expectEmpty } from './helpers/board';
import {
  freePiece, unclearPiece, pointOn, carry, releaseAt, dragFree, takeOff, placements,
  expectSamePlacements, sidePieces,
} from './helpers/free';

// Free placement of the pieces (E-005), first playable case: a piece stays where it is let go,
// and the capture that starts with the opponent's piece. After 1.e4 d5 White lifts the pawn on
// d5 to play exd5 and lets go of it on the line between d5 and e5. The arbiter says at once that
// it is still on the board; nothing is counted until the clock is pressed with the pawn still
// lying there. Both boards are watched throughout.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

const STILL_ON_THE_BOARD = "Black's pawn is still on the board. To capture it, take it off the board."
  + ' Otherwise put it back on d5.';
const BLACK_NOTICE = 'Your opponent lifted your pawn on d5 and set it down between squares.'
  + ' It is still on the board.';
const CALL = 'It is not clear on which square your opponent\'s pawn is. Put it back on d5 or take it'
  + ' off the board. Place your pieces more carefully.';

/** Plays 1.e4 d5 with real mouse events in a game with free placement. */
async function playE4D5(white: Page, black: Page): Promise<void> {
  await dragFree(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toHaveText('Your turn.');
  await dragFree(black, 'd7', 'd5');
  await pressClock(black);
  await expect(white.locator('#arbiterMessage')).toHaveText('Your turn.');
}

/** White's clock is the one that runs, on both boards. */
async function expectWhiteClockRuns(white: Page, black: Page): Promise<void> {
  await expect(white.locator('#bottomClockDisplay')).toHaveClass(/active/);
  await expect(black.locator('#topClockDisplay')).toHaveClass(/active/);
  await expect(white.locator('#chessClock')).not.toHaveClass(/paused/);
}

/** The point on the line between d5 and e5, as this player sees the board. */
async function onTheLine(page: Page) {
  const d5 = await pointOn(page, 'd5');
  const e5 = await pointOn(page, 'e5');
  return { x: (d5.x + e5.x) / 2, y: d5.y };
}

async function expectCaptureAccepted(white: Page, black: Page): Promise<void> {
  await expect(white.locator('#arbiterMessage')).toHaveText("Move accepted. Opponent's turn.");
  await expect(black.locator('#arbiterMessage')).toHaveText('Your turn.');
  for (const page of [white, black]) {
    await expect(freePiece(page, 'd5')).toHaveAttribute('data-piece', 'WHITE_PAWN');
    await expect(freePiece(page, 'e4')).toHaveCount(0);
    await expect(sidePieces(page, 'BLACK_PAWN')).toHaveCount(1);
    await expect(page.locator('#board .free-piece')).toHaveCount(31);
  }
  await expect(black.locator('#bottomClockDisplay')).toHaveClass(/active/);
  await expectSamePlacements(white, black);
}

test('the page measures a placement exactly as the server does', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { free: true });
  const file = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'placement-samples.json'), 'utf8'));
  const measured = await game.white.evaluate(
    // OtbPlacement is a constant of the page's scripts, not a property of window.
    ({ samples, radius }) => samples.map((s: any) => (0, eval)('OtbPlacement').measure(s.x, s.y, radius)),
    { samples: file.samples, radius: file.radius },
  );
  expect(file.samples.length).toBeGreaterThanOrEqual(20);
  file.samples.forEach((sample: any, i: number) => {
    expect(measured[i], sample.name).toEqual({ kind: sample.kind, square: sample.square, squares: sample.squares });
  });
  // The radius the page was sent is the one of the sample file.
  expect(await game.white.evaluate(() => (window as any).game.board.placementRadius)).toBe(file.radius);
});

test('a piece stays where it is let go, on both boards and after a reload', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { free: true });
  const { white, black } = game;

  // Off the centre of e4, but clearly on it: towards the upper right corner as White sees it.
  await dragFree(white, 'e2', 'e4', { dx: 0.2, dy: -0.15 });
  await expect(freePiece(white, 'e4')).toHaveAttribute('data-piece', 'WHITE_PAWN');
  await expect(freePiece(black, 'e4')).toHaveAttribute('data-piece', 'WHITE_PAWN');
  const pawn = (await placements(white)).find((p) => p.piece === 'WHITE_PAWN' && p.y > 2048)!;
  // 0.2 of a square to the right of the centre of the e-file, 0.15 above the centre of rank 4.
  // Within a pixel and a bit (16 units to a pixel): the mouse of the test moves in whole pixels.
  expect(Math.abs(pawn.x - (4608 + 205))).toBeLessThanOrEqual(20);
  expect(Math.abs(pawn.y - (3584 + 154))).toBeLessThanOrEqual(20);
  await expectSamePlacements(white, black);

  // Black looks at the board from the other side: the pawn is drawn mirrored, on the same spot.
  const onWhite = await freePiece(white, 'e4').boundingBox();
  const e4OnWhite = await pointOn(white, 'e4');
  const onBlack = await freePiece(black, 'e4').boundingBox();
  const e4OnBlack = await pointOn(black, 'e4');
  const shiftOnWhite = onWhite!.x + onWhite!.width / 2 - e4OnWhite.x;
  const shiftOnBlack = onBlack!.x + onBlack!.width / 2 - e4OnBlack.x;
  expect(shiftOnWhite).toBeGreaterThan(8);
  expect(Math.abs(shiftOnBlack + shiftOnWhite)).toBeLessThanOrEqual(1.5);

  // The move is accepted as it lies, and the pawn does not jump to the centre on either board.
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText("Move accepted. Opponent's turn.");
  await expect(black.locator('#arbiterMessage')).toHaveText('Your turn.');
  await expectSamePlacements(white, black);
  expect((await placements(black)).find((p) => p.id === pawn.id)).toEqual(pawn);

  await black.reload();
  await expect(freePiece(black, 'e4')).toHaveAttribute('data-piece', 'WHITE_PAWN');
  await expectSamePlacements(white, black);

  // A click does not shift a piece: it is touched and lies where it lay.
  const before = await placements(black);
  const knight = await freePiece(black, 'g8').boundingBox();
  await black.mouse.move(knight!.x + 12, knight!.y + 12);
  await black.mouse.down();
  await black.mouse.move(knight!.x + 13, knight!.y + 13);
  await black.mouse.up();
  expect(await placements(black)).toEqual(before);
});

test('capture-first: the victim let go on the line stays on the board, and exd5 is finished by taking it off',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free: true });
    const { white, black } = game;
    await playE4D5(white, black);

    // White lifts Black's pawn and carries it halfway towards e5, the mouse button still down:
    // the piece is in the hand. No call, nothing at the side, nothing counted.
    await carry(white, freePiece(white, 'd5'), await onTheLine(white));
    await expect(white.locator('.floating-piece')).toHaveCount(1);
    await expect(white.locator('#arbiterMessage')).toHaveText('Your turn.');
    await expect(black.locator('#opponentInfoPanel')).toBeHidden();
    for (const page of [white, black]) {
      await expect(sidePieces(page, 'BLACK_PAWN')).toHaveCount(0);
    }

    // Let go on the line: the pawn lies there on both boards and is still in play.
    await white.mouse.up();
    await expect(white.locator('#arbiterMessage')).toHaveText(STILL_ON_THE_BOARD);
    await expect(black.locator('#opponentInfoPanel')).toHaveText(BLACK_NOTICE);
    for (const page of [white, black]) {
      await expect(unclearPiece(page, 'd5 e5')).toHaveAttribute('data-piece', 'BLACK_PAWN');
      await expect(sidePieces(page, 'BLACK_PAWN')).toHaveCount(0);
      await expect(page.locator('#board .free-piece')).toHaveCount(32);
    }
    await expectWhiteClockRuns(white, black);
    await expect(white.locator('#arbiterButtons button')).toHaveCount(0);
    await expectSamePlacements(white, black);

    // Black reloads while the pawn straddles the line: it lies there, and Black is told again.
    await black.reload();
    await expect(unclearPiece(black, 'd5 e5')).toHaveAttribute('data-piece', 'BLACK_PAWN');
    await expect(black.locator('#opponentInfoPanel')).toHaveText(BLACK_NOTICE);
    await expect(sidePieces(black, 'BLACK_PAWN')).toHaveCount(0);
    await expectSamePlacements(white, black);

    // White finishes exd5: the same pawn off the board first, then the own pawn onto d5.
    await takeOff(white, unclearPiece(white, 'd5 e5'));
    await expect(white.locator('#arbiterMessage')).toHaveText('');
    await expect(black.locator('#opponentInfoPanel')).toBeHidden();
    for (const page of [white, black]) {
      await expect(sidePieces(page, 'BLACK_PAWN')).toHaveCount(1);
    }
    await dragFree(white, 'e4', 'd5');
    await pressClock(white);
    await expectCaptureAccepted(white, black);
  });

test('capture-first: put back, the instruction ends and the pawn is captured directly', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { free: true });
  const { white, black } = game;
  await playE4D5(white, black);

  await releaseAt(white, freePiece(white, 'd5'), await onTheLine(white));
  await expect(white.locator('#arbiterMessage')).toHaveText(STILL_ON_THE_BOARD);
  await releaseAt(white, unclearPiece(white, 'd5 e5'), await pointOn(white, 'd5'));
  await expect(white.locator('#arbiterMessage')).toHaveText('');
  await expect(black.locator('#opponentInfoPanel')).toBeHidden();
  await expect(freePiece(black, 'd5')).toHaveAttribute('data-piece', 'BLACK_PAWN');

  // The direct capture: the own pawn set clearly on d5 puts the black pawn to the side.
  await dragFree(white, 'e4', 'd5');
  for (const page of [white, black]) {
    await expect(sidePieces(page, 'BLACK_PAWN')).toHaveCount(1);
  }
  await pressClock(white);
  await expectCaptureAccepted(white, black);
});

test('capture-first: the clock pressed with the pawn on the line is called and counted, then corrected',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free: true });
    const { white, black } = game;
    await playE4D5(white, black);
    await releaseAt(white, freePiece(white, 'd5'), await onTheLine(white));
    await expect(white.locator('#arbiterMessage')).toHaveText(STILL_ON_THE_BOARD);

    await pressClock(white);
    await expect(white.locator('#arbiterMessage')).toHaveText(CALL + ' The game is paused and will continue shortly.');
    await expect(white.locator('#arbiterMessage')).toHaveClass(/error/);
    await expect(black.locator('#opponentInfoPanel')).toContainText(
      'Your opponent pressed the clock while a piece was not clearly on a square.');
    await expect(white.locator('#chessClock')).toHaveClass(/paused/);
    await expect(black.locator('#chessClock')).toHaveClass(/paused/);
    // Nothing is restored: no Revert, the pawn lies where it lay, and it is still White's move.
    await expect(white.locator('#arbiterButtons button')).toHaveCount(0);
    await expect(unclearPiece(black, 'd5 e5')).toHaveCount(1);

    // The arbiter restarts the clock; White corrects on their own running clock.
    await expect(white.locator('#arbiterMessage')).toHaveText(CALL + ' The clock has restarted.', { timeout: 15_000 });
    await expectWhiteClockRuns(white, black);
    await takeOff(white, unclearPiece(white, 'd5 e5'));
    await dragFree(white, 'e4', 'd5');
    await pressClock(white);
    await expectCaptureAccepted(white, black);
  });

test('capture-first: the capturer set on d5 first leaves both pieces until the victim is taken off',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free: true });
    const { white, black } = game;
    await playE4D5(white, black);
    await releaseAt(white, freePiece(white, 'd5'), await onTheLine(white));
    await expect(white.locator('#arbiterMessage')).toHaveText(STILL_ON_THE_BOARD);

    await dragFree(white, 'e4', 'd5');
    for (const page of [white, black]) {
      await expect(freePiece(page, 'd5')).toHaveAttribute('data-piece', 'WHITE_PAWN');
      await expect(unclearPiece(page, 'd5 e5')).toHaveAttribute('data-piece', 'BLACK_PAWN');
      await expect(sidePieces(page, 'BLACK_PAWN')).toHaveCount(0);
    }
    await expect(white.locator('#arbiterMessage')).toHaveText(STILL_ON_THE_BOARD);

    await takeOff(white, unclearPiece(white, 'd5 e5'));
    await pressClock(white);
    await expectCaptureAccepted(white, black);
  });

test('capture-first: the victim carried off its square is a moved opponent piece, restored centred',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free: true });
    const { white, black } = game;
    await playE4D5(white, black);

    // Wholly clear of d5: let go on the line between e5 and f5.
    const e5 = await pointOn(white, 'e5');
    const f5 = await pointOn(white, 'f5');
    await releaseAt(white, freePiece(white, 'd5'), { x: (e5.x + f5.x) / 2, y: e5.y });
    await expect(white.locator('#arbiterMessage')).toContainText("You moved an opponent's piece.");
    await expect(black.locator('#opponentInfoPanel')).toContainText('moved one of your pieces');
    await expect(white.locator('#chessClock')).toHaveClass(/paused/);

    await clickRestore(white);
    await expect(white.locator('#arbiterMessage')).toContainText('Game continues', { timeout: 15_000 });
    for (const page of [white, black]) {
      await expect(freePiece(page, 'd5')).toHaveAttribute('data-piece', 'BLACK_PAWN');
      await expect(page.locator('#board .free-piece[data-unclear]')).toHaveCount(0);
    }
    const pawn = (await placements(white)).filter((p) => p.piece === 'BLACK_PAWN' && p.y === 4608);
    expect(pawn).toHaveLength(1);
    expect(pawn[0].x).toBe(3584); // the centre of d5
    await expectSamePlacements(white, black);
  });

test('the Revert centres only the piece that is put back: pieces let go off the centre stay where they lie',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free: true });
    const { white, black } = game;
    // 1.e4 d5, both pawns let go off the centre of their square.
    await dragFree(white, 'e2', 'e4', { dx: 0.2, dy: -0.15 });
    await pressClock(white);
    await expect(black.locator('#arbiterMessage')).toHaveText('Your turn.');
    await dragFree(black, 'd7', 'd5', { dx: 0.18, dy: 0.12 });
    await pressClock(black);
    await expect(white.locator('#arbiterMessage')).toHaveText('Your turn.');
    const before = await placements(white);
    const offCentre = before.filter((p) => p.x % 1024 !== 512 || p.y % 1024 !== 512);
    expect(offCentre.map((p) => p.piece).sort()).toEqual(['BLACK_PAWN', 'WHITE_PAWN']);

    // White moves Black's pawn from a7 to a6: the position has to be restored.
    await dragFree(white, 'a7', 'a6', { dx: 0.1 });
    await expect(white.locator('#arbiterMessage')).toContainText("You moved an opponent's piece.");
    await clickRestore(white);
    await expect(white.locator('#arbiterMessage')).toContainText('Game continues', { timeout: 15_000 });

    for (const page of [white, black]) {
      await expect(freePiece(page, 'a7')).toHaveAttribute('data-piece', 'BLACK_PAWN');
      await expect(freePiece(page, 'a6')).toHaveCount(0);
      const after = await placements(page);
      for (const piece of offCentre) {
        expect(after.find((p) => p.id === piece.id), `${piece.piece} stays where it was let go`).toEqual(piece);
      }
      // Every other piece stands on the centre of its square, the pawn on a7 among them.
      const others = after.filter((p) => !offCentre.some((piece) => piece.id === p.id));
      expect(others).toHaveLength(30);
      expect(others.filter((p) => p.x % 1024 !== 512 || p.y % 1024 !== 512)).toEqual([]);
      expect(others.filter((p) => p.piece === 'BLACK_PAWN' && p.x === 512 && p.y === 6656)).toHaveLength(1);
    }
    await expectSamePlacements(white, black);
  });

test('at the rim the centre decides: just inside the piece is on the board, just outside it is removed',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free: true });
    const { white, black } = game;
    const a2 = await pointOn(white, 'a2');
    const a2Box = (await white.locator('#board .square[data-square="a2"]').boundingBox())!;

    // The centre three pixels inside the left edge: the piece hangs over the rim and is on the board.
    await releaseAt(white, freePiece(white, 'a2'), { x: a2Box.x + 3, y: a2.y });
    for (const page of [white, black]) {
      await expect(unclearPiece(page, 'a2')).toHaveAttribute('data-piece', 'WHITE_PAWN');
      await expect(sidePieces(page, 'WHITE_PAWN')).toHaveCount(0);
    }

    // The centre three pixels outside: taken off, although the image still overlaps the rim.
    await releaseAt(white, unclearPiece(white, 'a2'), { x: a2Box.x - 5, y: a2.y });
    for (const page of [white, black]) {
      await expect(page.locator('#board .free-piece')).toHaveCount(31);
      await expect(sidePieces(page, 'WHITE_PAWN')).toHaveCount(1);
    }
  });

test('with the option off the capture that starts with the victim works as before', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { free: false });
  const { white, black } = game;
  await dragPiece(white, 'e2', 'e4');
  await pressClock(white);
  await expect(black.locator('#arbiterMessage')).toHaveText('Your turn.');
  await dragPiece(black, 'd7', 'd5');
  await pressClock(black);
  await expect(white.locator('#arbiterMessage')).toHaveText('Your turn.');

  // No layer of free pieces: the pieces stand on their squares.
  await expect(white.locator('#board .free-piece')).toHaveCount(0);
  await removePiece(white, 'd5');
  await expectEmpty(black, 'd5');
  await dragPiece(white, 'e4', 'd5');
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText("Move accepted. Opponent's turn.");
  await expectPiece(black, 'd5', 'WHITE_PAWN');
  await expectEmpty(black, 'e4');
});

test('the entry screen offers free placement with its limit, on by default', async ({ page }) => {
  await page.goto('/');
  const option = page.locator('#freePlacement');
  await expect(option).toBeChecked();
  await expect(page.locator('#maxUnclearPlacementsRow')).toBeVisible();
  await expect(page.locator('#maxUnclearPlacements')).toHaveValue('3');
  await page.locator('#maxUnclearPlacements').selectOption('5');
  await page.locator('#createGameBtn').click();
  await expect(page).toHaveURL(/maxUnclear=5/);
  await expect(page).not.toHaveURL(/free=false/);
  await expect(page.locator('#board .free-piece')).toHaveCount(32);
});

test('the entry screen with free placement switched off starts a game with centred pieces', async ({ page }) => {
  await page.goto('/');
  await page.locator('#freePlacement').uncheck();
  await expect(page.locator('#maxUnclearPlacementsRow')).toBeHidden();
  await page.locator('#createGameBtn').click();
  await expect(page).toHaveURL(/free=false/);
  await expect(page.locator('#board .piece')).toHaveCount(32);
  await expect(page.locator('#board .free-piece')).toHaveCount(0);
});

// ----- The binding of an own piece let go across a border (Q1 to Q3) -----

/** King e2, rook a1: the rook reaches every square of the first rank. */
const ROOK = '4k3/8/8/8/8/8/4K3/R7 w - - 0 1';
const PAUSED = ' The game is paused and will continue shortly.';
const RESTARTED = ' The clock has restarted.';

/** The point on the line between two neighbouring squares, as this player sees the board. */
async function between(page: Page, first: string, second: string) {
  const a = await pointOn(page, first);
  const b = await pointOn(page, second);
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

/** The squares circled in blue on this board, in the order of the board. */
async function blueCircles(page: Page): Promise<string[]> {
  return page.locator('#board .square.mark-blue').evaluateAll(
    (squares) => squares.map((square) => (square as HTMLElement).dataset.square!).sort());
}

async function expectBlueCircles(white: Page, black: Page, squares: string[]): Promise<void> {
  for (const page of [white, black]) {
    await expect.poll(() => blueCircles(page)).toEqual(squares);
  }
}

test('a rook let go across d1 and e1 is bound to them: called, sent there from b1 without counting, then moved',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { fen: ROOK });
    const { white, black } = game;

    // Let go on the line: both boards show it there, the arbiter is silent, the clock runs.
    await releaseAt(white, freePiece(white, 'a1'), await between(white, 'd1', 'e1'));
    for (const page of [white, black]) {
      await expect(unclearPiece(page, 'd1 e1')).toHaveAttribute('data-piece', 'WHITE_ROOK');
    }
    await expectBlueCircles(white, black, []);
    await expectWhiteClockRuns(white, black);

    // The clock press: the call names the two squares and circles them on both boards.
    const call = 'It is not clear on which square your rook is. Place it on d1 or on e1, and place your pieces'
      + ' more carefully.';
    await pressClock(white);
    await expect(white.locator('#arbiterMessage')).toHaveText(call + PAUSED);
    await expect(black.locator('#opponentInfoPanel')).toContainText(
      'Your opponent pressed the clock while a piece was not clearly on a square.');
    await expectBlueCircles(white, black, ['d1', 'e1']);
    await expect(white.locator('#arbiterMessage')).toHaveText(call + RESTARTED, { timeout: 15_000 });

    // Placed clearly on b1, where it is not bound to: Rb1 is not made. The arbiter names the squares again.
    await releaseAt(white, unclearPiece(white, 'd1 e1'), await pointOn(white, 'b1'));
    await expect(freePiece(black, 'b1')).toHaveAttribute('data-piece', 'WHITE_ROOK');
    const released = 'Your rook was released on d1 and e1. Place it on d1 or on e1.';
    await pressClock(white);
    await expect(white.locator('#arbiterMessage')).toHaveText(released + PAUSED);
    await expect(black.locator('#opponentInfoPanel')).toContainText(
      "Your opponent's rook was released on d1 and e1. It has to be placed on one of them.");
    await expect(white.locator('#chessClock')).toHaveClass(/paused/);
    await expect(white.locator('#arbiterButtons button')).toHaveCount(0); // nothing is restored
    for (const page of [white, black]) {
      await expect(freePiece(page, 'b1')).toHaveAttribute('data-piece', 'WHITE_ROOK');
    }
    await expectBlueCircles(white, black, ['d1', 'e1']);
    await expect(white.locator('#arbiterMessage')).toHaveText(released + RESTARTED, { timeout: 15_000 });
    await expectWhiteClockRuns(white, black);

    // Taken to d1: the circles go on both boards and the move is accepted.
    await dragFree(white, 'b1', 'd1');
    await expectBlueCircles(white, black, []);
    await pressClock(white);
    await expect(white.locator('#arbiterMessage')).toHaveText("Move accepted. Opponent's turn.");
    await expect(black.locator('#arbiterMessage')).toHaveText('Your turn.');
    for (const page of [white, black]) {
      await expect(freePiece(page, 'd1')).toHaveAttribute('data-piece', 'WHITE_ROOK');
    }
    await expectSamePlacements(white, black);
  });

test('let go a second time across other squares, the rook stays bound to the first ones', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { fen: ROOK });
  const { white, black } = game;
  await releaseAt(white, freePiece(white, 'a1'), await between(white, 'd1', 'e1'));
  await releaseAt(white, unclearPiece(white, 'd1 e1'), await between(white, 'f1', 'g1'));
  await expect(unclearPiece(black, 'f1 g1')).toHaveCount(1);

  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText(
    'It is not clear on which square your rook is. It was released on d1 and e1. Place it on d1 or on e1.'
    + ' Place your pieces more carefully.' + PAUSED);
  await expectBlueCircles(white, black, ['d1', 'e1']);
});

test('only the square that gives a legal move is named', async ({ browser }) => {
  // The king stands on e1: the rook let go across d1 and e1 can only go to d1.
  game = await startTwoPlayerGame(browser, { fen: '4k3/8/8/8/8/8/8/R3K3 w - - 0 1' });
  const { white, black } = game;
  await releaseAt(white, freePiece(white, 'a1'), await between(white, 'd1', 'e1'));
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText(
    'It is not clear on which square your rook is. Place it on d1, and place your pieces more carefully.'
    + PAUSED);
  await expectBlueCircles(white, black, ['d1']);
});

test('a piece that can reach none of the squares is bound to nothing', async ({ browser }) => {
  // The bishop from c1 let go across c4 and d4 can reach neither: no square is named, any bishop move remains.
  game = await startTwoPlayerGame(browser, { fen: '4k3/p7/8/8/8/8/4K3/2B5 w - - 0 1' });
  const { white, black } = game;
  await releaseAt(white, freePiece(white, 'c1'), await between(white, 'c4', 'd4'));
  const call = 'It is not clear on which square your bishop is. Place your pieces more carefully.';
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText(call + PAUSED);
  await expectBlueCircles(white, black, []);
  await expect(white.locator('#arbiterMessage')).toHaveText(call + RESTARTED, { timeout: 15_000 });
  await releaseAt(white, unclearPiece(white, 'c4 d4'), await pointOn(white, 'e3'));
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText("Move accepted. Opponent's turn.");
  await expect(freePiece(black, 'e3')).toHaveAttribute('data-piece', 'WHITE_BISHOP');
});

test('a pawn let go across the border of its own square is only touched', async ({ browser }) => {
  game = await startTwoPlayerGame(browser);
  const { white, black } = game;
  await releaseAt(white, freePiece(white, 'e2'), await between(white, 'e2', 'e3'));
  await expect(unclearPiece(black, 'e2 e3')).toHaveAttribute('data-piece', 'WHITE_PAWN');
  const call = 'It is not clear on which square your pawn is. Place your pieces more carefully.';
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText(call + PAUSED);
  await expectBlueCircles(white, black, []);
  await expect(white.locator('#arbiterMessage')).toHaveText(call + RESTARTED, { timeout: 15_000 });

  // Any move of the pawn remains: e2-e4, not only e2-e3.
  await releaseAt(white, unclearPiece(white, 'e2 e3'), await pointOn(white, 'e4'));
  await pressClock(white);
  await expect(white.locator('#arbiterMessage')).toHaveText("Move accepted. Opponent's turn.");
  await expect(freePiece(black, 'e4')).toHaveAttribute('data-piece', 'WHITE_PAWN');
});

test('checkmate ends the game when the piece is let go clearly, not while it lies across a border',
  async ({ browser }) => {
    // Qg7 is mate.
    game = await startTwoPlayerGame(browser, { fen: '7k/1Q6/6K1/8/8/8/8/R7 w - - 0 1' });
    const { white, black } = game;
    await releaseAt(white, freePiece(white, 'b7'), await between(white, 'f7', 'g7'));
    await expect(unclearPiece(black, 'f7 g7')).toHaveAttribute('data-piece', 'WHITE_QUEEN');
    await expectWhiteClockRuns(white, black);
    await expect(white.locator('#gameResultPanel')).toBeHidden();

    await releaseAt(white, unclearPiece(white, 'f7 g7'), await pointOn(white, 'g7'));
    for (const page of [white, black]) {
      await expectGameResult(page, '1-0');
      await expect(page.locator('#gameResultReason')).toContainText('checkmate');
    }
  });
