# Privacy

OTB Chess is designed to collect as little as possible. This page describes everything the application records about play.

## What the app logs

The server records exactly two events:

- a game is **created**
- a game is **joined**

Each entry contains only:

- a timestamp
- the event type (`created` or `joined`)
- the game's short, random id

The game id is stored for a single purpose: to tell whether a created game was actually joined by a second player. It is the short random code shown when a game is created, and it is not tied to any person.

## What the app does not log

Nothing else is recorded. The app does not log or store:

- names, emails, accounts, or any identity
- IP addresses
- moves, board positions, PGN, results, clock times, illegal-move counts, or draw claims
- cookies or tracking identifiers

## Retention

These usage entries are kept for **30 days** and then deleted.

## Operational server logs

Separately from the usage log above, the server writes ordinary operational/diagnostic logs
(standard output and errors) — for example "a connection opened/closed", a game's random id when it
is created/started/aborted/resumed/reaped, and stack traces if something goes wrong. These contain
**no personal data**: the random game id is not tied to any person, and the connection addresses are
the **local proxy** (the app only accepts connections from Cloudflare's tunnel via a loopback
address), not players' IP addresses. These are standard server logs for running and debugging the
service; they are not the analytics described above and are not subject to the 30-day usage-log
retention. They are rotated/cleared as normal server maintenance.

## In your browser

To keep your game working across a page refresh, the app stores a little data **in your own browser**
(`localStorage`): your current game's id and a random reconnect token, plus the last game id. This
stays on your device, is used only to resume your game, and is cleared when you start a new game,
abort, or the game ends. It is not sent anywhere beyond what's needed to reconnect you to your game,
and it is not a tracking identifier.

## Hosting (Cloudflare)

The site is public and served through Cloudflare. As the network provider in front of the app,
Cloudflare processes standard request metadata (such as IP addresses) to deliver traffic and protect
the service, under Cloudflare's own terms and retention — not this policy. The OTB Chess application
itself does not read or store that metadata; the usage logging described above is the only data the
app collects.
