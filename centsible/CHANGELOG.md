# Changelog

Each version is a Centsible release (0.2.<build>); its notes are on https://github.com/Flexingg/Centsible/releases.

## Plaid replaces Teller

- Teller closed its API in 2026. Plaid is the second bank sync provider now: set it up in the app under **Bank sync → Plaid** (free Trial plan).
- Teller's leftover settings and secrets are removed at start. Transactions Teller imported stay, and Plaid adopts them instead of duplicating them.

## First Home Assistant release

- Actual's server and the bridge in one app, on matching versions.
- Data under the app, so Home Assistant backups include the budget and household.
- One-time import from Docker Compose's `actual-data` and `bridge-data` folders.
