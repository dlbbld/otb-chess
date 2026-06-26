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

## Hosting (Cloudflare)

The site is public and served through Cloudflare. As the network provider in front of the app,
Cloudflare processes standard request metadata (such as IP addresses) to deliver traffic and protect
the service, under Cloudflare's own terms and retention — not this policy. The OTB Chess application
itself does not read or store that metadata; the usage logging described above is the only data the
app collects.
