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

### In Home Assistant (recommended if you run it)

Actual's server and the bridge run together as one Home Assistant app, updated from Home Assistant and included in its backups. No Docker Compose, Watchtower or separate tunnel container.

1. **Settings → Apps → App store → ⋮ → Repositories**, add `https://github.com/Flexingg/Centsible#home-assistant`, then install **Centsible**.
2. Follow the app's **Documentation** tab: set the public address, start it, and use the setup code from its log. Moving from Docker Compose is a copy of two folders, described there too.

### With Docker Compose


1. From the [latest release](https://github.com/Flexingg/Centsible/releases/latest), download `docker-compose.yml` and `env.example` into one folder on your server, and rename `env.example` to `.env`. Set `BRIDGE_PUBLIC_URL` and `CLOUDFLARE_TUNNEL_TOKEN`; everything else is optional. (No need to clone this repo: the bridge image is published to `ghcr.io/flexingg/centsible-bridge`.)
2. In the Cloudflare dashboard, give your tunnel two public hostnames:
   - `actual.example.com` → `http://actual:5006`
   - `budget-api.example.com` → `http://bridge:8787`

   Optionally, put the bridge hostname behind Cloudflare Access with a Service Auth token.
3. Start everything, and note the setup code the bridge prints:
   ```sh
   docker compose up -d
   docker compose logs bridge     # "Setup code: ABCD-EFGH"
   ```
4. Install the app from the [latest release](https://github.com/Flexingg/Centsible/releases/latest) (download the `.apk` on your phone and allow installing from your browser). Enter your bridge's address, and the app walks you through the rest:
   - the setup code, and your name (you become the household owner)
   - Actual's password: on a brand-new Actual server you choose it; otherwise you enter the one you already use. The bridge keeps it, so it never goes in `.env`.
   - your first budget, if Actual has none yet
5. Invite the rest of the household from **More → Settings → Household**: add each person, then tap **Invite** to show a QR code for them to scan.

The setup code works once, until the first owner exists; knowing the bridge's address isn't enough to claim it. Prefer the command line? `docker compose exec bridge node dist/admin/cli.js add-member --name "Jo" --role owner --pair` still prints an owner invite QR code.

## Bank sync: SimpleFIN and Plaid

Accounts can sync through either provider, and you can mix them account by account. Both run on the bridge's schedule and on **Sync now**.

- **SimpleFIN** (about $15 a year): set up in Actual's way, with a setup token from bridge.simplefin.org. **More → Settings → Bank sync**.
- **Plaid** (free on Plaid's Trial plan, up to 10 bank logins, for developers in the US and Canada): useful when a bank keeps breaking in SimpleFIN (Wells Fargo, Chase, Discover…). Actual has no Plaid support, so the bridge does the syncing itself and hands posted transactions to Actual's importer (which runs your rules). It replaced Teller, which closed its API in 2026.
  In the app, open **Bank sync → Plaid**. A four-step guide walks through it:
  1. **Make a free Plaid account** at [dashboard.plaid.com](https://dashboard.plaid.com/signup) and check it's on the Trial plan.
  2. **Copy your keys** from **Developers → Keys**: the `client_id` and the Production secret. The bridge checks them with Plaid, then keeps the secret in `plaid.json`, readable only by the bridge.
  3. **Allow the Centsible app.** Under **Developers → API → Allowed Android package names**, add `app.centsible.budget`. Banks that sign in through their own app or website hand you back to Centsible only when it's allowed.
  4. **Connect a bank.** Plaid's own sign-in screens open, so Centsible never sees your bank password. Then tap each account to link it to an account in your budget. Purchases come in negative and payments positive, for cards too; pending transactions wait until they post, and corrections or reversals from the bank are followed. An account that syncs through SimpleFIN (or synced through Teller) now moves over to Plaid, and what's already there is matched rather than duplicated.

  **Older history:** linking imports the last 30 days. In **Bank sync → Plaid → Older history**, pick 3 months to 2 years and tap **Import history**: Plaid's full history for each linked account comes in (usually up to two years, depending on the bank), matched against what's already there so nothing is duplicated.

  When a bank asks you to sign in again, the account shows **needs reconnecting**. Tap **Reconnect**: the bank login is repaired in place, and the same links keep working.

## Mortgage linked to the bank

Set up the mortgage in **More → Mortgage** for its terms (rate, schedule, payoff, interest). If your bank's mortgage account syncs (SimpleFIN or Plaid), open the mortgage and tap **Link bank account**: pick that account and its balance becomes what you owe, with nothing to record by hand. If Centsible kept a loan account up by hand before, it can be closed at zero (its history stays) so net worth doesn't count the loan twice.

## Account types

Actual has no account types, so Centsible keeps them on the bridge: **Cash, Savings, Credit card, Investment, Mortgage, Loan**. They decide how the Accounts page groups accounts. On **Accounts → Edit types**, tap an account's type to change it, or choose **Automatic** to go back to guessing from the name and balance. Linking an account through Plaid sets its type from what the bank reports, and **Add account** asks for one.

## Categories in up to five levels

Groups are the top level and their categories the second; any category can hold sub-categories, down to level 5 (say **Needs › Food › Groceries › Costco › Bulk snacks**). Every level from 2 down holds its own transactions and its own envelope, so a transaction can stay on **Food** while others go on **Groceries**. A transaction with no category at all is uncategorized.

- **Budget → Edit categories**: ⋮ on a category offers **Add sub-category** and **Move…** (under another category, or to the top of a group, with its own sub-categories along). Deleting a category moves its sub-categories up a level.
- **The budget** lists sub-categories under their parent, whose figures include theirs. A category's transactions include its sub-categories'.
- **Picking a category** shows the tree, folded; search finds any level by its path, like "food groc".
- **Home's dial** zooms in: tap a group (on the ring or in the list) and its categories sweep in, measured against its own budget; tap again to go down a level. Back, or the breadcrumb, zooms out. The parent's own spending shows as "Food (general)". The **Where it went** chart on Home zooms the same way.
- **Reports → Money flow**: a Sankey of where the money went, from each income category through each group and down the levels, with what was saved (or drawn from savings). Pick this month or 3, 6 or 12 months and how many levels to show; tap a box for its share and transactions.

Actual itself has two levels, so the bridge keeps the parent links (in its own store, per budget). In Actual's own app, sub-categories show as ordinary categories in their group: budgets, rules and sync are untouched.

## The assistant (bring your own Claude key)

The round button at the bottom of every tab opens the assistant: a financial planner that works with your real budget. Ask how the month is going, where you could cut back, whether you can afford something, how fast a card or loan gets paid off, or to plan next month's budget.

- **Your own key.** It runs on Claude through your own Anthropic API key (create one at [console.anthropic.com](https://console.anthropic.com) → API keys) and add it in the assistant's settings. You pay Anthropic directly for what it uses. The key is encrypted with this phone's Keystore and is sent only to Anthropic. Each person in the household uses their own.
- **It reads everything** the app can show, except bank sync: accounts, transactions, the budget and its categories (every level), recurring bills, subscriptions, goals and targets, yearly budgets, budget automations, rules, merchants, tags, reports (cash flow, spending, money flow, net worth, daily spending), insights, period reviews, the forecast, income and pay stubs, mortgages, the review inbox, transfers and duplicates, and the household and server status.
- **It changes nothing unless you let it.** Read-only by default. In settings, under **Permissions**, turn on what it may change: transactions, budget amounts, categories, rules/merchants/tags, recurring bills, goals and plans, or accounts. Even then, every change shows as a card in the chat with exactly what will change, and only happens when you tap **Approve**. Most changes can be undone from the card. A viewer in the household can't change anything through it.
- **Model and depth.** Claude Opus 5.5 by default; Sonnet 5.5, Haiku 5.5 or Fable 5.2 can be picked instead, along with how hard it thinks. It can run calculations (projections, amortization) in Anthropic's sandbox, and optionally search the web (off by default).
- **Memory and history.** It can keep short notes between chats (your goals, when you're paid), which you can see and delete in settings. Chats are kept encrypted on the phone. Signing out removes the key, chats and notes.

What you ask, and the budget data the assistant reads to answer, are sent to Anthropic under your API account's terms. Link: `centsible://open/assistant`.

## Widgets and Quick Settings tiles

**Settings → Widgets & tiles** shows every chart widget on your home screen and both figure tiles, with live previews, and lets you change what each one shows.

- **Chart widgets** (long-press the home screen → Widgets → Centsible, or **Add** in Widgets & tiles). Each one shows the chart you pick when you place it; long-press it later to choose again:
  - **Spending pace**: this month's running spending against last month's, with the budget line.
  - **This week**: Monday to Sunday against your daily allowance.
  - **Where it went**: this month by category, as a ring; tap a category in the legend to open its transactions.
  - **One category**: a category's budget as a ring, what's left, and whether it's spending faster than the month.
  - **Monthly spending**: six months with the average.
  - **Income vs spending**: six months of money in and out.
  - **Net worth**: twelve months.
  - **Money flow**: this month's Sankey, income into groups and down each level (choose how deep).

  Charts use Centsible's colours, light or dark with the phone. Tapping a chart opens that screen in the app; ↻ refreshes it.
- **Budget** and **Upcoming bills** widgets: this month's to-budget and spending with **+ Add** and **Review**, and the next bills.
- **Quick Settings tiles** (pull down the shade twice → ✏️ → drag them in):
  - **Budget figure** and **Budget figure (2)**: each shows the figure you pick, such as left to spend today (what's left this month spread over the days left, minus today's spending), left this month, left this week, spent today, to budget, or one category's balance. Long-press one to change it.
  - **Add transaction**, **Review** (with the count waiting) and **Sync banks**.

They refresh about every hour, a few seconds after you change something in the app, and when you tap ↻. With the **app lock** on, they show no amounts or counts.

## Duplicates

When a bank connection is reconnected or moved to another provider, the same purchase can come in twice. **Review → ⋮ → Find duplicates** (or the banner in Review) lists pairs: same account and amount, a few days apart. **Merge** uses Actual's own merge (Actual 26.10+), which keeps the bank's copy and carries over the category and notes from either.

## Links into the app

Other apps (Home Assistant dashboards and notifications, launcher shortcuts) can open a screen directly:

| Link | Opens |
|---|---|
| `centsible://open/review` | your review inbox |
| `centsible://open/transactions`, `/accounts`, `/budget`, `/net-worth`, `/recurring`, `/trends`, `/money-flow`, `/assistant`, `/bank-sync`, `/plaid`, `/new-transaction`, `/widgets`, `/duplicates`, `/mortgage` | that screen |
| `centsible://open/account/<account id>` | one account |
| `centsible://open/category/<category id>?name=Groceries[&month=2026-11]` | a category's transactions |

Links only navigate; the app lock still applies. From a Home Assistant dashboard or notification, use the intent form so the link always reaches the app: `intent://open/review#Intent;scheme=centsible;package=app.centsible.budget;end`. The [Home Assistant integration](https://github.com/Flexingg/centsible-homeassistant) shows each account's and category's id as an attribute.

## Releases

Update the server with `docker compose pull && docker compose up -d`.


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

End-to-end: the app's data layer against a real bridge and Actual server, starting from a real pairing link.

```sh
cd bridge && npx tsx test/support/e2e-server.ts 8787    # prints {"pairingUri": ...} when ready
cd android && E2E_PAIRING_URI='actualbridge://pair?...' ./gradlew :core:engine-bridge:test --tests '*EndToEndTest*'
```

The test layers, and what each one catches:
- **Contract tests** (bridge): every response matches `contract/openapi.yaml`, against a real Actual.
- **Request contract test** (app): every request body the app sends matches the same file.
- **End-to-end test**: pairing, then every feature through the real HTTP stack. CI runs it on every push, and releases wait for it.

## Upgrading Actual

Renovate groups `@actual-app/api`, `@actual-app/sync-server`, and the `actual-server` image into one PR. CI runs the contract suite against that exact version. A nightly job also runs it against Actual's newest release, so you hear about breakage before you upgrade.

If an internal Actual function the bridge relies on disappears, only that feature switches off: it's reported via `/v1/capabilities` and hidden in the app.
