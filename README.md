# Actual × Monarch for Android

A native Android app for a self-hosted [Actual Budget](https://actualbudget.org) server, with a Monarch-style UI. It's built for households and envelope budgeting, and designed so that Actual upgrades don't break it.

"Canopy" is a placeholder app name.

- **Design and roadmap:** [`docs/FRAMEWORK.md`](docs/FRAMEWORK.md)
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
5. Install the app and scan the QR code. Invite the rest of the household from **More → Household → Add person**.

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
