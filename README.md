# Centsible

A native Android app for a self-hosted [Actual Budget](https://actualbudget.org) server, with a Monarch-style UI. It's built for households and envelope budgeting, and designed so that Actual upgrades don't break it.

- **Design and roadmap:** [`docs/FRAMEWORK.md`](docs/FRAMEWORK.md)
- **Status:** Phases 0–2 are built: accounts, transactions, envelope budget, recurring, rules, merchants, tags, bank sync, file import, reconcile, reports, goals, and offline edits. None of it has been tried on a real device yet. See the Progress table in FRAMEWORK.md for known gaps.
- **Screenshots:** [`docs/screenshots/`](docs/screenshots). They're rendered from sample data by the screenshot tests.

## How it fits together

```
Android app ──HTTPS (Cloudflare Tunnel + Access)──► bridge ──► actual-server
                                                    └─ @actual-app/api (Actual's own engine)
```

The **bridge** is a small Node service. It wraps Actual's official API behind a versioned contract (`contract/openapi.yaml`). Actual's budget math, rules, and sync never get re-implemented, so upgrading Actual usually means bumping one dependency in the bridge.

## Run it

1. Copy `deploy/.env.example` to `deploy/.env` and fill it in.
2. In the Cloudflare dashboard, give your tunnel two public hostnames:
   - `actual.example.com` → `http://actual:5006`
   - `budget-api.example.com` → `http://bridge:8787`

   Optionally, put the bridge hostname behind Cloudflare Access with a Service Auth token.
3. Start everything:
   ```sh
   cd deploy && docker compose up -d
   ```
4. Create the first household owner. This prints a pairing QR code:
   ```sh
   docker compose exec bridge node dist/admin/cli.js add-member --name "Jo" --role owner --pair
   ```
5. Install the app from the [latest release](https://github.com/Flexingg/Centsible/releases/latest) (download the `.apk` on your phone and allow installing from your browser), then scan the QR code. Invite the rest of the household from **More → Household → Add person**.

## Releases

CI builds a signed APK for every push to `main` that passes the tests, and publishes it as a GitHub release (`v0.2.<build>`). Push a tag like `v0.3.0` to publish a specific version. The version code is the CI run number, so each build installs over the previous one. To get updates automatically, point [Obtainium](https://github.com/ImranR98/Obtainium) at this repo.

Signing uses four repository secrets: `CENTSIBLE_KEYSTORE_BASE64`, `CENTSIBLE_KEYSTORE_PASSWORD`, `CENTSIBLE_KEY_ALIAS` and `CENTSIBLE_KEY_PASSWORD`. Without them, CI falls back to a throwaway debug key, and that APK can't update an existing install. Keep a backup of the keystore: losing it means uninstalling to update.

## Develop

```sh
cd bridge && npm ci && npm test      # unit + contract tests against a real actual-server
cd bridge && npm run fixtures        # re-record contract fixtures for the Android tests

cd android && ./gradlew test         # unit, fixture-decoding and screenshot tests
cd android && ./gradlew recordRoborazziDebug   # update screenshots after UI changes
cd android && ./gradlew :app:assembleDebug
```

## Upgrading Actual

Renovate groups `@actual-app/api`, `@actual-app/sync-server`, and the `actual-server` image into one PR. CI runs the contract suite against that exact version. A nightly job also runs it against Actual's newest release, so you hear about breakage before you upgrade.

If an internal Actual function the bridge relies on disappears, only that feature switches off: it's reported via `/v1/capabilities` and hidden in the app.
