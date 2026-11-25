import { Page, Locator, expect } from '@playwright/test';
import { Square, squareLocator } from './board';

// Helpers for a game with free placement of the pieces (E-005): the pieces lie in one layer over
// the squares, each where it was let go. A piece in a clear placement carries its square
// (data-square); one that lies across a border carries the squares its inner circle lies on
// (data-unclear="d5 e5").

/** A point on a square, as a share of the square from its centre: 0.5 is the border. */
export interface Offset {
  dx?: number;
  dy?: number;
}

/** The piece that stands clearly on `square`. */
export function freePiece(page: Page, square: Square): Locator {
  return page.locator(`#board .free-piece[data-square="${square}"]`);
}

/** The piece that lies unclear on exactly these squares, e.g. "d5 e5". */
export function unclearPiece(page: Page, squares: string): Locator {
  return page.locator(`#board .free-piece[data-unclear="${squares}"]`);
}

/** A point of the screen on `square`, offset in shares of a square as the player sees the board. */
export async function pointOn(page: Page, square: Square, offset: Offset = {}): Promise<{ x: number; y: number }> {
  const box = await squareLocator(page, square).boundingBox();
  if (!box) throw new Error(`Cannot locate square ${square}`);
  return {
    x: box.x + box.width * (0.5 + (offset.dx ?? 0)),
    y: box.y + box.height * (0.5 + (offset.dy ?? 0)),
  };
}

/** Presses the mouse button on the centre of a piece and carries it to a point, without letting go. */
export async function carry(page: Page, piece: Locator, to: { x: number; y: number }): Promise<void> {
  const box = await piece.boundingBox();
  if (!box) throw new Error('Cannot locate the piece to pick up');
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(to.x, to.y, { steps: 10 });
}

/** Picks a piece up by its centre and lets go of it at a point of the screen. */
export async function releaseAt(page: Page, piece: Locator, to: { x: number; y: number }): Promise<void> {
  await carry(page, piece, to);
  await page.mouse.up();
}

/** Carries the piece that stands clearly on `from` to `to` and lets go of it there, by default centred. */
export async function dragFree(page: Page, from: Square, to: Square, offset: Offset = {}): Promise<void> {
  await releaseAt(page, freePiece(page, from), await pointOn(page, to, offset));
}

/** Lets go of a piece outside the board. */
export async function takeOff(page: Page, piece: Locator): Promise<void> {
  await releaseAt(page, piece, { x: 2, y: 2 });
}

/** Where every piece lies for this page, by number: what the two boards must agree on. */
export async function placements(page: Page): Promise<Array<{ id: number; piece: string; x: number; y: number }>> {
  return page.evaluate(() => (window as any).game.board.placements
    .map((p: any) => ({ id: p.id, piece: p.piece, x: p.x, y: p.y }))
    .sort((a: any, b: any) => a.id - b.id));
}

/** Asserts (with auto-retry) that both boards show every piece on the same spot. */
export async function expectSamePlacements(a: Page, b: Page): Promise<void> {
  await expect(async () => {
    expect(await placements(b)).toEqual(await placements(a));
  }).toPass({ timeout: 5_000 });
}

/** The pieces of a kind in the side areas, e.g. the captured black pawns. */
export function sidePieces(page: Page, piece: string): Locator {
  return page.locator(`.side-piece[title="${piece}"]`);
}
