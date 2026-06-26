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

## Cloudflare Access (invite-only beta)

During the invite-only beta the site sits behind Cloudflare Access. To admit invited people, Cloudflare processes their email identity and keeps its own access logs, governed by Cloudflare's terms and retention — not by this policy. The OTB Chess application does not read or store those identities; the usage logging described above is the only data the app itself collects.
