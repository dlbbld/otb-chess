# Sounds

The two samples played by `static/js/sounds.js` (see E-004 in `docs/enhancements.md`).

| File | Played | Format | Source |
|---|---|---|---|
| `piece.wav` | a piece is set down on a square | stereo, 16 bit, 24 kHz, 0.07 s | `piece_release - wood.wav`, a piece set down on a wood board, supplied by the project owner on 2026-11-24. Stored unchanged. It replaces the sample of 2026-11-23 (`piece_release.wav`), which was of an alabaster board. |
| `clock.wav` | a clock lever is pressed down | mono, 16 bit, 48 kHz, 0.15 s | `clock_press.wav`, supplied by the project owner on 2026-11-23. The first 0.14 s before the click - room noise and the travel of the lever - are cut off so that the click is heard at once, with a 3 ms fade-in at the cut. Nothing else is changed. |

Neither sample is taken from Lichess or from another project.

To replace a sample, keep it short (under 0.25 s), mono or stereo, 16-bit PCM WAV, and let the sound start
at the beginning of the file: silence before it is heard as a delay. `TestStaticFileHandler`
checks the format.
