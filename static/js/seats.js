/**
 * The seats this browser holds: for each game a player of this browser is in, the secret token
 * that lets a page take that player's seat again. Shared by the start screen and the game page.
 *
 * Three things are kept apart, so that two tabs of one browser can play each other and a reload
 * never gives a tab the other tab's seat:
 *
 * - The store (localStorage, shared by all tabs and kept across a browser restart): every seat
 *   with its game code, token, colour, and whether its game has ended. It serves the way back
 *   into a game from a new tab ("Return to game").
 * - The page's own seat (history state of the page's entry): a reload, or going back to the page,
 *   finds exactly the seat this page had. A page that is opened anew has none.
 * - Who has a seat open right now (Web Locks, one lock per token, held while the page lives): a
 *   seat that an open tab holds is not offered to another tab. Without the Locks API (a page not
 *   served over HTTPS or from localhost) nothing counts as held.
 */
const OtbSeats = {
  KEY: 'otbSeats',
  LEGACY_KEY: 'otbSession', // one seat per browser, up to 0.4.0
  MAX_SEATS: 30,
  ENDED_KEPT_MS: 24 * 60 * 60 * 1000,
  KEPT_MS: 7 * 24 * 60 * 60 * 1000,

  /** All stored seats, newest first. */
  all() {
    let seats = [];
    try {
      const raw = localStorage.getItem(this.KEY);
      if (raw) {
        seats = JSON.parse(raw);
      } else {
        const legacy = JSON.parse(localStorage.getItem(this.LEGACY_KEY) || 'null');
        if (legacy && legacy.gameId && legacy.token) seats = [legacy];
      }
    } catch (e) {
      return [];
    }
    if (!Array.isArray(seats)) return [];
    return seats.filter((s) => s && s.gameId && s.token)
      .sort((a, b) => (b.savedAt || 0) - (a.savedAt || 0));
  },

  _write(seats) {
    try {
      const now = Date.now();
      const kept = seats
        .filter((s) => now - (s.savedAt || now) < (s.ended ? this.ENDED_KEPT_MS : this.KEPT_MS))
        .slice(0, this.MAX_SEATS);
      localStorage.setItem(this.KEY, JSON.stringify(kept));
      localStorage.removeItem(this.LEGACY_KEY);
    } catch (e) {
      /* storage unavailable: the page's own seat still survives a reload (history state) */
    }
  },

  /** Adds the seat, or replaces the stored one with the same token (a rematch changes the colour). */
  save(seat) {
    const others = this.all().filter((s) => s.token !== seat.token);
    this._write([{ ...seat, savedAt: Date.now() }, ...others]);
  },

  remove(token) {
    if (!token) return;
    this._write(this.all().filter((s) => s.token !== token));
  },

  /** The game of this seat is over: it is kept to show the finished game, not to return to play. */
  markEnded(token) {
    const seats = this.all();
    const seat = seats.find((s) => s.token === token);
    if (!seat || seat.ended) return;
    seat.ended = true;
    this._write(seats);
  },

  sameGame(a, b) {
    return !!a && !!b && String(a).toUpperCase() === String(b).toUpperCase();
  },

  // === The page's own seat ===

  pageSeat() {
    try {
      const seat = history.state && history.state.otbSeat;
      return seat && seat.gameId && seat.token ? seat : null;
    } catch (e) {
      return null;
    }
  },

  setPageSeat(seat) {
    try {
      history.replaceState({ ...(history.state || {}), otbSeat: seat }, '');
    } catch (e) {
      /* the store still offers the seat to a page without one */
    }
  },

  // === Seats that an open tab holds ===

  _holding: new Set(),

  /** Holds the seat for as long as this page lives. */
  hold(token) {
    if (!token || this._holding.has(token) || !navigator.locks) return;
    this._holding.add(token);
    navigator.locks.request('otb-seat-' + token, () => new Promise(() => {})).catch(() => {});
  },

  /** The tokens of the seats that some open tab of this browser holds. */
  async heldTokens() {
    const held = new Set();
    if (!navigator.locks || !navigator.locks.query) return held;
    try {
      const state = await navigator.locks.query();
      for (const lock of state.held || []) {
        if (lock.name && lock.name.startsWith('otb-seat-')) held.add(lock.name.substring('otb-seat-'.length));
      }
    } catch (e) {
      /* nothing counts as held */
    }
    return held;
  },

  /** The seats of running games that no open tab holds: the games one can return to. Newest first. */
  async returnable() {
    const held = await this.heldTokens();
    return this.all().filter((s) => !s.ended && !held.has(s.token));
  },
};
