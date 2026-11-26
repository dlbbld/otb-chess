import { test, expect, Page } from '@playwright/test';
import { startTwoPlayerGame, TwoPlayerGame } from './helpers/app';
import { pressClock, pieceLocator, squareLocator, flipBoard } from './helpers/board';
import { freePiece, unclearPiece, pointOn, releaseAt, sidePieces } from './helpers/free';

// The border of the board (E-005): around the 64 squares, as wide as the radius of the circle a
// piece stands on. A piece can hang over the squares by half its circle; when the circle goes
// over the outer edge of the border, the piece is off the board. The file letters and the rank
// numbers stand on the border. The game with centred pieces has the same border.

let game: TwoPlayerGame;

test.afterEach(async () => {
  for (const context of game?.contexts ?? []) {
    await context.close();
  }
});

/** The border of this board in pixels, the width of a square, and the size of the whole board. */
const rimOf = (page: Page) => page.evaluate(() => {
  const board = document.getElementById('board')!;
  const box = board.getBoundingClientRect();
  return {
    left: board.clientLeft,
    top: board.clientTop,
    right: box.width - board.clientLeft - board.clientWidth,
    bottom: box.height - board.clientTop - board.clientHeight,
    square: board.clientWidth / 8,
  };
});

/** The coordinates on the border as the player reads them: the letters from the left, the numbers from the top. */
const coordinatesOf = (page: Page) => page.evaluate(() => {
  const board = document.getElementById('board')!;
  const inside = { left: board.getBoundingClientRect().left + board.clientLeft,
    top: board.getBoundingClientRect().top + board.clientTop,
    right: board.getBoundingClientRect().left + board.clientLeft + board.clientWidth,
    bottom: board.getBoundingClientRect().top + board.clientTop + board.clientHeight };
  const outside = board.getBoundingClientRect();
  const labels = (selector: string) => [...board.querySelectorAll(selector)].map((label) => {
    const box = label.getBoundingClientRect();
    return { text: label.textContent!, x: box.left + box.width / 2, y: box.top + box.height / 2 };
  });
  const files = labels('.coord.file-label').sort((a, b) => a.x - b.x);
  const ranks = labels('.coord.rank-label').sort((a, b) => a.y - b.y);
  return {
    files: files.map((label) => label.text).join(''),
    ranks: ranks.map((label) => label.text).join(''),
    // Every letter is under the squares and inside the board's border; every number left of them.
    filesOnTheBorder: files.every((label) => label.y > inside.bottom && label.y < outside.bottom),
    ranksOnTheBorder: ranks.every((label) => label.x < inside.left && label.x > outside.left),
    // The n-th letter stands under the n-th file, the n-th number beside the n-th rank.
    filesUnderTheirFile: files.every((label, i) =>
      Math.abs(label.x - (inside.left + (i + 0.5) * (inside.right - inside.left) / 8)) < 1),
    ranksBesideTheirRank: ranks.every((label, i) =>
      Math.abs(label.y - (inside.top + (i + 0.5) * (inside.bottom - inside.top) / 8)) < 1),
    inTheSquares: board.querySelectorAll('.square .coord').length,
  };
});

for (const free of [true, false]) {
  const kind = free ? 'free placement' : 'centred pieces';

  test(`the board has a border as wide as half the circle a piece stands on (${kind})`, async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free });
    const { white, black } = game;
    for (const page of [white, black]) {
      const rim = await rimOf(page);
      // 226 of the 1024 units of a square: 14 pixels of a square of 64.
      expect(rim.square).toBe(64);
      expect(rim.left).toBe(14);
      expect([rim.top, rim.right, rim.bottom]).toEqual([14, 14, 14]);
    }
    if (free) {
      const radius = await white.evaluate(() => (window as any).game.board.placementRadius);
      expect(Math.round(radius / 1024 * 64)).toBe(14);
    }
  });

  test(`the letters and the numbers stand on the border, as each player sees the board (${kind})`,
    async ({ browser }) => {
      game = await startTwoPlayerGame(browser, { free });
      const { white, black } = game;
      const onTheBorder = {
        filesOnTheBorder: true, ranksOnTheBorder: true, filesUnderTheirFile: true, ranksBesideTheirRank: true,
        inTheSquares: 0,
      };
      expect(await coordinatesOf(white)).toEqual({ files: 'abcdefgh', ranks: '87654321', ...onTheBorder });
      expect(await coordinatesOf(black)).toEqual({ files: 'hgfedcba', ranks: '12345678', ...onTheBorder });

      // Turned round, the board carries them the other way.
      await flipBoard(white);
      expect(await coordinatesOf(white)).toEqual({ files: 'hgfedcba', ranks: '12345678', ...onTheBorder });
    });
}

test('a knight let go half on a3 and half on the border lies unclear and is called at the clock press',
  async ({ browser }) => {
    game = await startTwoPlayerGame(browser, { free: true });
    const { white, black } = game;
    const a3 = await pointOn(white, 'a3');
    const a3Box = (await squareLocator(white, 'a3').boundingBox())!;

    // The centre two pixels inside the edge of the squares: nearly half the circle is on the border.
    await releaseAt(white, freePiece(white, 'b1'), { x: a3Box.x + 2, y: a3.y });
    for (const page of [white, black]) {
      await expect(unclearPiece(page, 'a3')).toHaveAttribute('data-piece', 'WHITE_KNIGHT');
      await expect(sidePieces(page, 'WHITE_KNIGHT')).toHaveCount(0);
    }

    await pressClock(white);
    await expect(white.locator('#arbiterMessage')).toContainText(
      'It is not clear on which square your knight is. Place it on a3, and place your pieces more carefully.');
    await expect(black.locator('#opponentInfoPanel')).toContainText(
      'Your opponent pressed the clock while a piece was not clearly on a square.');
    for (const page of [white, black]) {
      await expect(page.locator('#board .square.mark-blue')).toHaveCount(1);
      await expect(squareLocator(page, 'a3')).toHaveClass(/mark-blue/);
    }
    await expect(white.locator('#chessClock')).toHaveClass(/paused/);

    // Placed clearly on a3 it is the move Na3.
    await expect(white.locator('#chessClock')).not.toHaveClass(/paused/, { timeout: 15_000 });
    await releaseAt(white, unclearPiece(white, 'a3'), a3);
    await pressClock(white);
    await expect(white.locator('#arbiterMessage')).toHaveText("Move accepted. Opponent's turn.");
    await expect(freePiece(black, 'a3')).toHaveAttribute('data-piece', 'WHITE_KNIGHT');
  });

test('a piece let go with its centre on the border is off the board', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { free: true });
  const { white, black } = game;
  const a3 = await pointOn(white, 'a3');
  const a3Box = (await squareLocator(white, 'a3').boundingBox())!;
  const rim = await rimOf(white);

  // In the middle of the border: the circle reaches over its outer edge.
  await releaseAt(white, freePiece(white, 'b1'), { x: a3Box.x - rim.left / 2, y: a3.y });
  for (const page of [white, black]) {
    await expect(page.locator('#board .free-piece')).toHaveCount(31);
    await expect(sidePieces(page, 'WHITE_KNIGHT')).toHaveCount(1);
  }
});

test('with centred pieces a piece let go on the border is off the board', async ({ browser }) => {
  game = await startTwoPlayerGame(browser, { free: false });
  const { white, black } = game;
  const from = (await pieceLocator(white, 'b1').boundingBox())!;
  const a3 = (await squareLocator(white, 'a3').boundingBox())!;
  const rim = await rimOf(white);

  await white.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await white.mouse.down();
  await white.mouse.move(a3.x - rim.left / 2, a3.y + a3.height / 2, { steps: 8 });
  await white.mouse.up();
  for (const page of [white, black]) {
    await expect(pieceLocator(page, 'b1')).toHaveCount(0);
    await expect(pieceLocator(page, 'a3')).toHaveCount(0);
  }
  await expect(sidePieces(white, 'WHITE_KNIGHT')).toHaveCount(1);
});
