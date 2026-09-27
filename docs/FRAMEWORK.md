# Actual × Monarch for Android: Framework Design

**Status:** v0.3 (Phase 0 built: bridge + Android shell, tested against Actual 26.9.0) · **Date:** 2026-09-27
**Goal:** A native Android app that sits on a self-hosted **Actual Budget** server. It supports all of Actual's features, uses a **Monarch-style** UI/UX, and survives Actual upgrades without rewrites. Once the core works, it gets an extension layer for new features.

---

## 1. The key decision: don't reimplement Actual

Actual is **local-first**. `actual-server` (sync-server) is mostly a dumb store. It holds budget files and CRDT sync messages, and it proxies bank sync. **All the business logic lives in the client engine (`loot-core`, JavaScript):**

- the SQLite schema and its migrations
- CRDT sync (HULC timestamps, merkle trie, protobuf messages, optional E2E encryption)
- budget math (envelope and tracking "spreadsheet" engine, carryover, hold for next month)
- the rules engine, schedules, transfers, split transactions, goal templates

A native Kotlin client that talks to `actual-server` directly would have to **port all of that**. It would also break whenever Actual changes a migration, a sync detail, or a budget calculation. That's the opposite of "handle updates gracefully."

**Principle:** *Actual's own code is the engine. The app is a shell over a stable contract.*

### Options considered

| Option | How it works | Fidelity | Offline | Upgrade risk | Effort |
|---|---|---|---|---|---|
| A. Kotlin port of loot-core | App speaks the Actual sync protocol natively | Drifts over time | Full | **Very high** | Huge |
| **B. Bridge service (recommended)** | Small Node service using the official `@actual-app/api`, exposing a versioned REST contract | Exact (it *is* Actual) | Cached reads + queued writes | **Low**: bump one dependency | Medium |
| C. Embedded engine | Runs the loot-core browser bundle inside the app (headless WebView or JS runtime) and bridges to Kotlin | Exact | Full | Medium: swap the bundle | High |

**Recommendation:** start with **B**. Hide it behind a Kotlin `BudgetEngine` interface so that **C** can be added later as a second implementation for true offline use, without touching the UI.

Since you already self-host, running the bridge is just one more container next to `actual-server` in your `docker-compose`.

---

## 2. System architecture

```
┌──────────────────────── Android app (Kotlin / Compose) ────────────────────────┐
│  feature/* screens  ──►  ViewModels  ──►  Use cases (core/domain)              │
│                                              │                                  │
│                                   Repositories (core/data)                      │
│                         ┌────────────────────┼────────────────────┐             │
│                    Room cache           Outbox (queued     BudgetEngine (port)  │
│                  (offline reads)        offline writes)          │              │
│                                                    ┌─────────────┴──────────┐   │
│                                                    │ BridgeEngine (Phase 1) │   │
│                                                    │ EmbeddedEngine (later) │   │
└────────────────────────────────────────────────────┴───────────┬────────────┴───┘
                                                                  │ HTTPS, JSON, /v1 contract
┌──────────────────── Bridge ("actual-bridge", Node/TS) ─────────▼────────────────┐
│  HTTP layer (Fastify, OpenAPI-validated)                                        │
│  Contract mappers  ◄── the ONLY code that knows Actual's shapes                 │
│  Actual adapter: @actual-app/api (pinned to the server version)                 │
│  Extension store (SQLite): data for add-on features, keyed by Actual IDs        │
└──────────────────────────────────────────┬──────────────────────────────────────┘
                                           │ Actual sync protocol
                               ┌───────────▼───────────┐
                               │  actual-server        │  (unchanged, self-hosted)
                               └───────────────────────┘
```

Other Actual clients (web and desktop) keep working side by side. Everything goes through Actual's normal CRDT sync.

---

## 3. Built to absorb Actual updates

Actual ships roughly monthly (`YY.M.x`). Defense in depth:

1. **One integration point.** Only `bridge/src/actual/` imports `@actual-app/api`. Everything else speaks the app's own contract.
2. **Tiered adapter surface**, ranked by stability:
   - **Tier 1: public API methods** (`getAccounts`, `addTransactions`, `setBudgetAmount`, `getRules`, `getSchedules`, `runBankSync`, …). Preferred.
   - **Tier 2: AQL queries** (`runQuery(q('transactions').filter(...).groupBy(...))`). Used for reads and aggregations the API doesn't wrap (reports, search, tags, custom report definitions).
   - **Tier 3: internal handlers** (the API's `internal`/`send` passthrough to loot-core handlers). Only for features with no public route, such as reconciliation helpers, file import parsing, and undo. Each one sits behind a **capability flag** and a **contract test**, so a break turns the feature off instead of crashing the app.
3. **Version pinning and a compatibility check.** The bridge image pins `@actual-app/api` to the server's release line. At startup it reads the server version. On a mismatch it reports `degraded` in `/v1/capabilities` rather than risk a migration mismatch.
4. **Capability negotiation.** The app never assumes a feature exists:
   ```json
   GET /v1/capabilities
   { "contract": "1.4", "actualServer": "26.9.0", "actualApi": "26.9.0",
     "status": "ok",
     "features": { "budget.envelope": true, "budget.tracking": true,
                   "bankSync.gocardless": true, "bankSync.simplefin": true,
                   "bankSync.pluggy": false, "tags": true, "schedules.write": true,
                   "reconcile": true, "import.ofx": true, "undo": false } }
   ```
   The UI hides or disables anything that is `false`. New Actual features show up as new flags.
5. **Tolerant contract.** The contract only changes additively within `/v1`. The app uses `ignoreUnknownKeys = true`, every enum has an `Unknown` fallback, and new optional fields have defaults. A breaking change means `/v2`, served in parallel for one release cycle.
6. **Automated upgrade pipeline.** Renovate opens a PR when `@actual-app/api` bumps. CI starts the matching `actualbudget/actual-server:<tag>` image, seeds a fixture budget, and runs the **bridge contract test suite**, which exercises every endpoint and every Tier-3 call. A nightly job runs the same suite against `edge` so you see breakage *before* the release.
7. **The app release is decoupled from Actual.** Most Actual upgrades only need a new bridge image. The app only needs an update when you want to *surface* a new feature.

---

## 4. Android tech stack

| Concern | Choice |
|---|---|
| Language / UI | Kotlin 2.x, Jetpack Compose, Material 3 (custom Monarch-like theme) |
| Architecture | MVVM + unidirectional data flow (`UiState` / `UiEvent`), Clean-ish layers |
| DI | Hilt (multibindings for extension points) |
| Async | Coroutines + Flow |
| Network | Ktor client or Retrofit/OkHttp + kotlinx.serialization; client generated from `contract/openapi.yaml` |
| Local | Room (cache + outbox), DataStore (prefs), Keystore-backed encrypted token storage |
| Background | WorkManager (periodic refresh, outbox flush, bill reminders) |
| Lists | Paging 3 for transactions |
| Charts | Vico (Compose-native) |
| Navigation | Navigation Compose, type-safe routes; adaptive layouts for tablets and foldables |
| Security | Biometric app lock, optional `FLAG_SECURE`. No certificate pinning: Cloudflare rotates edge certificates, so pinning would break the app. |
| Testing | JUnit5, Turbine, MockK, Roborazzi screenshot tests, Maestro end-to-end tests against a docker Actual |
| Future | Keep `core/model` and `core/domain` as pure Kotlin so they can move to KMP for iOS |

### Module layout (monorepo)

"Canopy" is a placeholder app name; package `app.canopy`.

```
actual-monarch-android/
├─ contract/                 openapi.yaml (source of truth) + fixtures/ recorded from a real Actual
├─ bridge/                   Node/TypeScript service + Dockerfile
│  ├─ src/actual/            ← the ONLY place that imports @actual-app/api
│  ├─ src/auth/              household store: members, devices, pairing, tokens, audit
│  ├─ src/http/routes/       v1 endpoints
│  ├─ src/mappers/           Actual shapes → contract DTOs
│  └─ test/                  unit + contract suite against a real actual-server
├─ deploy/                   docker-compose (Actual + bridge + cloudflared), .env.example
└─ android/                  Gradle project
   ├─ build-logic/           convention plugins (canopy.android.feature, canopy.jvm.library, …)
   ├─ app/                   shell: navigation, session routing, deep links
   ├─ core/model/            pure Kotlin: Money, ids, entities, Capabilities
   ├─ core/domain/           BudgetEngine port, gateways, use cases, PairingLinks
   ├─ core/network/          Ktor client, contract DTOs, token refresh, CF Access headers
   ├─ core/engine-bridge/    BudgetEngine over the /v1 contract
   ├─ core/data/             encrypted session store, DI bindings (the engine seam)
   ├─ core/designsystem/     theme + components (MoneyText, progress bars, avatars, cards)
   ├─ core/extensions/       extension points (DashboardWidget, …)
   ├─ core/testing/          FakeBudgetEngine + sample household for tests and screenshots
   └─ feature/               onboarding, dashboard, accounts, transactions, budget, settings
```

**Dependency rules** (enforced with a Gradle convention plugin or Konsist tests):
- `feature/*` depends on `core/domain`, `core/designsystem`, and `core/model` only.
- Only `core/engine-*` knows about the network or JS.
- Nothing depends on `feature/*`, except `app`.

---

## 5. Core code sketches

### 5.1 Domain model basics

```kotlin
@JvmInline value class AccountId(val raw: String)
@JvmInline value class CategoryId(val raw: String)
@JvmInline value class TransactionId(val raw: String)
@JvmInline value class YearMonth(val raw: String) // "2026-09"

/** Actual stores amounts as integer minor units (cents). Never use Double. */
@JvmInline value class Money(val minor: Long) {
    operator fun plus(o: Money) = Money(minor + o.minor)
    operator fun minus(o: Money) = Money(minor - o.minor)
    val isNegative get() = minor < 0
}

enum class BudgetType { Envelope, Tracking, Unknown }
```

### 5.2 The engine port: the seam that makes upgrades and future offline mode safe

```kotlin
interface BudgetEngine {
    suspend fun capabilities(): Capabilities
    suspend fun sync(): SyncResult

    // Accounts
    fun accounts(): Flow<List<Account>>
    suspend fun createAccount(input: NewAccount, initialBalance: Money): AccountId
    suspend fun updateAccount(id: AccountId, patch: AccountPatch)
    suspend fun closeAccount(id: AccountId, transferTo: AccountId?, transferCategory: CategoryId?)

    // Transactions
    suspend fun transactions(query: TxQuery, page: PageToken?): Page<Transaction>
    suspend fun addTransactions(account: AccountId, txs: List<NewTransaction>, idempotencyKey: String)
    suspend fun updateTransaction(id: TransactionId, patch: TransactionPatch)
    suspend fun deleteTransaction(id: TransactionId)
    suspend fun importFile(account: AccountId, file: ImportFile): ImportPreview

    // Budget
    suspend fun budgetMonth(month: YearMonth): BudgetMonth
    suspend fun setBudgeted(month: YearMonth, category: CategoryId, amount: Money)
    suspend fun setCarryover(month: YearMonth, category: CategoryId, enabled: Boolean)
    suspend fun moveBudget(month: YearMonth, from: CategoryId?, to: CategoryId?, amount: Money)
    suspend fun holdForNextMonth(month: YearMonth, amount: Money)
    suspend fun applyTemplates(month: YearMonth, scope: TemplateScope)

    // Rules, schedules, payees, categories, bank sync, reports: same pattern
}
```

The feature layer only ever sees `BudgetEngine` through repositories. Moving from Bridge to Embedded is a DI binding change.

### 5.3 Capability-gated UI

```kotlin
@Composable
fun FeatureGate(flag: Feature, content: @Composable () -> Unit) {
    val caps by LocalCapabilities.current.collectAsState()
    if (caps.has(flag)) content()
}
```

### 5.4 Bridge adapter example (TypeScript)

```ts
// bridge/src/actual/tier1/budget.ts: the only file that knows getBudgetMonth's shape
import * as api from '@actual-app/api';
import { toBudgetMonthDto } from '../../mappers/budget';

export async function getBudgetMonth(month: string) {
  await ensureSynced();                        // debounced api.sync()
  const raw = await api.getBudgetMonth(month);
  return toBudgetMonthDto(raw);                // contract DTO, stable across Actual versions
}
```

---

## 6. Bridge contract (v1 outline)

All amounts are integer minor units. All list endpoints use cursor pagination. All writes accept an `Idempotency-Key` header, so replaying the outbox is safe.

```
GET    /v1/capabilities
POST   /v1/auth/pair            QR/device pairing → device token (revocable)
POST   /v1/auth/refresh
GET    /v1/budgets                              list budget files
POST   /v1/budgets/{syncId}/open                (handles E2E key on the bridge)
GET    /v1/changes?since={token}                cheap "what changed" for cache invalidation

GET|POST|PATCH        /v1/accounts[/{id}]   + /close /reopen /balance-history
GET|POST|PATCH|DELETE /v1/transactions[/{id}]   (splits, transfers, cleared, notes, tags)
POST                  /v1/accounts/{id}/import  (OFX/QFX/QIF/CSV/CAMT → preview → commit)
POST                  /v1/accounts/{id}/reconcile
POST                  /v1/accounts/{id}/bank-sync   ·  GET /v1/bank-sync/providers

GET   /v1/budget/months                 ·  GET /v1/budget/months/{yyyy-mm}
PUT   /v1/budget/months/{m}/categories/{id}   { budgeted, carryover }
POST  /v1/budget/months/{m}/move | /hold | /reset-hold | /apply-templates | /copy-last

CRUD  /v1/categories  /v1/category-groups  /v1/payees (+ /merge)
CRUD  /v1/rules (+ /preview, /run)    ·   CRUD /v1/schedules (+ /upcoming)
POST  /v1/reports/query      aggregations: spending by category/payee/tag over time,
                             cash flow, net worth series, budget vs actual

/v1/ext/{extension}/...      extension routes (see §9)
```

**Sync behavior:** the bridge keeps the budget loaded. It calls `api.sync()` before reads when the last sync is older than N seconds, and always after writes. It keeps a monotonic change token for `/v1/changes`.

**Auth:** the bridge alone holds the Actual server password (or OIDC session) and the E2E budget key. Phones pair once by scanning a QR code shown in the bridge's tiny admin page. Each phone gets its own token, which you can revoke.

---

## 7. Feature parity map: Actual feature → Monarch-style screen

| Actual feature | Monarch-style surface | Adapter tier | Phase |
|---|---|---|---|
| Multiple budget files, open/switch | Profile switcher (top-left avatar) | 1 | 1 |
| E2E-encrypted budgets | Handled on the bridge; lock icon in switcher | 1 | 2 |
| Accounts (on/off budget, closed) | **Accounts**: grouped by type, balance, sparkline | 1 | 1 |
| Transactions (add/edit/delete) | **Transactions** list + detail sheet | 1 | 1 |
| Split transactions | Split editor in detail sheet | 1 | 1 |
| Transfers (transfer payees) | "Transfer" type in add flow | 1 | 1 |
| Cleared / reconciled | Status chip; reconcile flow in account detail | 1/3 | 2 |
| Notes, tags (`#tag`) | Notes field; tag chips + tag filter | 1/2 | 2 |
| Search & filters | Monarch filter sheet (date, account, category, merchant, amount, tag) | 2 | 1 |
| File import (OFX/QFX/QIF/CSV/CAMT) | Import wizard with preview + duplicate detection | 1/3 | 2 |
| Bank sync (GoCardless, SimpleFIN, Pluggy.ai) | "Connections" in Accounts; pull-to-sync | 1 | 2 |
| Categories & groups (hide, reorder) | Category manager | 1 | 1 |
| **Envelope budgeting** | **Budget** screen, "Left to budget" header | 1 | 1 |
| **Tracking budgeting** | Same screen, "Income vs Expenses" header (closest to Monarch) | 1 | 1 |
| Carryover / rollover | Rollover toggle per category | 1 | 1 |
| Move money between categories | "Move money" sheet | 1/3 | 1 |
| Hold for next month | Header action in envelope mode | 1 | 2 |
| Goal templates (`#template`) | **Goals** tab (category goals) + "Apply goals" | 3 | 2 |
| Schedules | **Recurring**: calendar + upcoming list | 1 | 2 |
| Rules (conditions/actions, ranking) | Rules manager; "Create rule from this" on a transaction | 1 | 2 |
| Payees (merge, auto-rules) | **Merchants** manager | 1 | 2 |
| Reports: net worth, cash flow, spending, custom | **Reports** + **Cash Flow** tabs | 2 | 2 |
| Undo/redo | Snackbar "Undo" | 3 | 3 |
| Preferences (number/date format, first day of week, currency) | Settings, read from budget prefs | 2 | 1 |
| Budget file management (create, import YNAB, export) | Settings → Budgets (import stays web-first) | 1/3 | 3 |

**Concept translation rules**
- Monarch's "Budget" = Actual budgeted / spent / balance. In envelope mode the header shows *To Budget*. In tracking mode it shows *Income − Expenses*.
- Monarch "Recurring" = Actual Schedules. Monarch "Merchants" = Actual Payees.
- Monarch has "Needs review", category emoji/icons, account goals, and investments. **Actual has no equivalent**, so these become extensions (§9), never hacks into Actual's data.

---

## 8. Monarch-style UX spec (core screens)

**Navigation:** bottom bar with **Dashboard · Accounts · Transactions · Budget · More** (More = Cash Flow, Recurring, Reports, Goals, Rules, Merchants, Settings). A global "+" FAB adds a transaction. Tablets use a nav rail with list-detail panes.

**Design language:** a light, airy canvas with rounded 16dp cards, soft elevation, and generous whitespace. Money uses tabular numerals. Green/red only for semantic meaning. Progress bars on everything budget-related. Emoji or icon avatars for categories and merchants. Full dark theme.

```
Dashboard                              Budget  ◄ Sep 2026 ►
┌──────────────────────────────┐       ┌──────────────────────────────┐
│ Good morning                 │       │ Left to budget      $412.00  │
│ Net worth  $84,210  ▲ 2.1%   │       │ Income $6,200 · Spent $3,944 │
│ ╱╲__╱╲___╱‾‾ (6-mo chart)    │       ├──────────────────────────────┤
├──────────────────────────────┤       │ ▾ Essentials                 │
│ Budget this month            │       │ 🏠 Rent     ██████████  $0   │
│ ███████░░░  $2,110 left      │       │ 🛒 Grocery  ██████░░░░ $214  │
├──────────────────────────────┤       │ ⛽ Gas      ████████▓▓ -$18  │
│ Upcoming recurring (3)       │       │ ▾ Lifestyle                  │
│ Netflix   Sep 29   $15.49    │       │ 🍽 Dining   ███░░░░░░░ $190  │
├──────────────────────────────┤       └──────────────────────────────┘
│ Recent transactions          │        tap row → sheet: trend chart,
│ Trader Joe's  🛒 Grocery -$64│        transactions, budget edit,
└──────────────────────────────┘        rollover, goal
```

- **Transactions:** grouped by date with sticky headers. Each row has a merchant avatar, category chip, account caption, and a right-aligned amount. Swipe to categorize or delete. Long-press for bulk edit. The filter sheet saves filters.
- **Account detail:** balance chart, cleared vs working balance, reconcile button, bank-sync status, and the transactions list.
- **Cash Flow:** income vs expense bars by month, a savings-rate stat, and breakdowns by category, group, or merchant.
- **Dashboard widgets** come from a registry (§9), so both core and extensions can add cards.

---

## 9. Extension framework (for the "add features on top" phase)

**Rule:** extensions **never** write to Actual's database except through the public `BudgetEngine` operations. Their own data lives in the bridge's **extension store**, keyed by Actual IDs, so it survives any Actual upgrade.

**Android extension points** (Hilt `@IntoSet` multibindings):

```kotlin
interface AppExtension {
    val id: String                         // "category-icons"
    val requires: Set<Feature>             // capability gating
    fun dashboardWidgets(): List<DashboardWidget> = emptyList()
    fun transactionDetailSections(): List<TxDetailSection> = emptyList()
    fun navDestinations(): List<ExtensionDestination> = emptyList()  // show under "More"
    fun settingsPages(): List<SettingsPage> = emptyList()
    fun transactionDecorators(): List<TxRowDecorator> = emptyList()   // badges, icons
}

@Module @InstallIn(SingletonComponent::class)
object CategoryIconsModule {
    @Provides @IntoSet fun ext(): AppExtension = CategoryIconsExtension()
}
```

**Bridge extension points:** `/v1/ext/{id}/*` routes, a namespaced SQLite schema with its own migrations, and optional hooks such as `onSyncCompleted(changes)` for things like snapshots and notifications.

**Candidate extensions (in rough order):**
1. **Category & merchant icons/colors.** Tiny, and it proves the framework. It's also needed for the Monarch look.
2. **Transaction review queue** ("Needs review" inbox after bank sync).
3. **Net worth snapshots** (daily history, independent of report computation).
4. **Monarch-style savings goals** linked to accounts, with progress and target dates.
5. **Bill and large-transaction notifications**, plus home-screen widgets (Glance).
6. **Smart categorization suggestions** (local heuristics first, optional LLM later).
7. **Receipt attachments** (stored on the bridge).
8. **Investments/holdings** tracking.
9. **Household/partner** views and shared notes.

---

## 10. Offline and sync behavior (Bridge mode)

- **Reads:** Room is the source of truth for the UI (the offline-first repository pattern). Data refreshes on app foreground, pull-to-refresh, when `/v1/changes` says it's stale, and every 15 minutes via WorkManager.
- **Writes:** optimistic update in Room, then an **outbox** row with a client UUID as the idempotency key. The outbox flushes when the bridge is reachable. On a conflict or validation error, the change rolls back locally and shows a snackbar.
- **Server-computed values** (budget balances, To Budget, reports) are always refetched from the engine after writes. The app **never re-derives Actual's budget math**. This is what keeps it correct across upgrades.
- **True offline editing with full math** is what the later `EmbeddedEngine` adds.

---

## 11. Roadmap and "mastered the basics" exit criteria

| Phase | Scope | Exit criteria |
|---|---|---|
| **0: Foundations** | Repo, contract, bridge skeleton (capabilities, pairing, accounts read), Android shell, design system, CI with docker Actual + contract tests, Renovate | Phone pairs to bridge; accounts render; CI is green against two Actual versions |
| **1: Core parity MVP** | Budgets switcher, accounts, transactions (CRUD, splits, transfers, search/filter), categories, budget month (envelope + tracking, edit, rollover, move money), dashboard v1, prefs, Room cache | You can run a whole month in the app without opening Actual web |
| **2: Full parity** | Rules, schedules/recurring, payees/merchants, bank sync, import, reconcile, tags, reports + cash flow, goal templates, hold, E2E budgets, outbox writes | Every row in §7 is ✅ or capability-gated; contract tests pass on `latest` and `edge` |
| **3: Polish** | Undo, notifications, biometric lock, tablet layouts, accessibility, performance (10k+ transactions), Maestro end-to-end suite | A survived Actual upgrade needed only a bridge bump, with no app release |
| **4: Extensions** | Extension framework + items from §9 | New features ship without touching `core/engine-*` |
| **Optional: Embedded engine** | loot-core on device behind `BudgetEngine` | Airplane-mode editing with correct budget math |

---

## 12. Decisions (locked 2026-09-27)

| # | Question | Decision | Consequence |
|---|---|---|---|
| 1 | Run a bridge container? | **Yes** | Option B is the Phase 0–3 engine |
| 2 | Remote access | **Cloudflare Tunnel** (reverse proxy) | See §14. No cert pinning. Long operations run as async jobs. |
| 3 | Users | **Household** | See §13. The bridge owns member identity, roles, and attribution. |
| 4 | Budget mode | **Envelope** | The envelope Budget screen is built first. Tracking mode is capability-gated for later. |
| 5 | Min Android | API 26 | — |

---

## 13. Household design

**Identity lives in the bridge, not in Actual.** The Actual API authenticates as *one* server identity: the server password, or a session token when Actual runs in OIDC multi-user mode. It has no concept of "which person made this change." So the bridge holds the household model:

| Entity | Purpose |
|---|---|
| `member` | A person: display name, role (`owner` · `member` · `viewer`), disabled flag |
| `device` | A paired phone or tablet belonging to a member. Revocable individually. |
| `member_budget` | Which Actual budget files a member may open. Owners see all. A typical setup is one shared "Household" budget plus optional personal budgets. |
| `pairing_code` | Single-use, 10-minute, hashed at rest. Shown as a QR code. |
| `token` | Opaque access tokens (1 h) and refresh tokens (90 d, rotated on use), hashed at rest |
| `audit_log` | Who changed what, from which device. Actual doesn't record this, so the bridge does. It also powers a future "Alex categorized 12 transactions" activity feed. |
| `idempotency` | Replay-safe writes from the phone's outbox |

**Roles**
- `viewer`: read only. Good for teens, or a partner who only wants to look.
- `member`: read and write transactions and the budget.
- `owner`: the above, plus household management (invite, revoke, grant budgets) and bridge settings.

**Onboarding flow**
1. The first owner is created on the server with `docker compose exec bridge node dist/admin/cli.js add-member --name "Jo" --role owner --pair`. This prints a QR code in the terminal.
2. The owner scans it in the app. The app is paired.
3. The owner invites the rest of the household in-app (Settings → Household → Add person). The app shows a QR code for the other person to scan.

**Concurrency.** `@actual-app/api` is a process-wide singleton: one open budget per process. The bridge funnels all Actual calls through a serialized `ActualHost`, which switches budgets on demand. Switching is cheap because files are cached locally, and sync happens on load. When a household uses several budgets heavily at once, Phase 2 moves to **one worker process per budget file** behind the same `ActualHost` interface.

**Household-flavored extensions** (Phase 4): per-member "needs review" queues, an "assigned to" field on transactions, an activity feed from the audit log, and a notification when someone overspends a shared category.

---

## 14. Cloudflare Tunnel deployment

```
Phone ──HTTPS──► Cloudflare edge ──(Access policy)──► cloudflared ──► bridge:8787 ──► actual-server:5006
                                                           └──────────────────────────► actual-server (web UI, own hostname)
```

- **Separate hostname** for the bridge, e.g. `budget-api.example.com`, alongside your existing `actual.example.com`.
- **Cloudflare Access** (recommended): put the bridge hostname behind an Access application with a **Service Auth** policy, and create one service token per household.
  - The pairing QR code carries the service token, so the app sends `CF-Access-Client-Id` / `CF-Access-Client-Secret` on every request.
  - Without Access, the bridge's own tokens still protect everything. Access adds a second layer that keeps scanners away from the bridge completely.
- **Edge limits shape the API:**
  - Cloudflare's roughly 100 s origin timeout means bank sync and big imports are **async jobs** (`POST` returns `202 {jobId}`, the app polls `GET /v1/jobs/{id}`).
  - Uploads are capped (100 MB on the Free plan), which is fine for OFX/CSV files.
  - Every response is `Cache-Control: no-store`.
- **No certificate pinning.** The edge certificate rotates. Rely on TLS and Access instead.
- The bridge trusts `CF-Connecting-IP` for rate limiting and audit logs, but only when `BRIDGE_TRUST_PROXY=true`.

---

## 15. Verified against Actual 26.9.0

A probe ran against a real `@actual-app/sync-server@26.9.0` with `@actual-app/api@26.9.0`. The bridge's contract tests do the same thing on every run.

**Tier 1 (public API) confirmed:**
- **Budget files:** `getBudgets`, `downloadBudget`, `sync`.
- **Accounts:** `getAccounts`, `createAccount`, `updateAccount`, `closeAccount`, `reopenAccount`, `getAccountBalance`, `getAccountGroups`.
- **Categories:** `getCategoryGroups`, `getCategories`.
- **Envelope budget:** `getBudgetMonths`, `getBudgetMonth`, `setBudgetAmount`, `setBudgetCarryover`, `holdBudgetForNextMonth`, `resetBudgetHold`.
- **Transactions:** `addTransactions`, `importTransactions`, `getTransactions`, `updateTransaction`, `deleteTransaction`.
- **Payees:** `getPayees`, `mergePayees`.
- **Tags:** `getTags` and tag CRUD.
- **Rules and schedules:** rules CRUD, schedules CRUD.
- **Other:** `runBankSync`, `getNote`/`updateNote`, `getPreferences`, `getServerVersion`, `aqlQuery`.

**Useful facts:**
- `addTransactions` accepts a **client-supplied `id`**. The phone generates the UUID, so creates are naturally idempotent.
- `getBudgetMonth` returns `toBudget`, `forNextMonth`, `lastMonthOverspent`, and per category `budgeted` / `spent` / `balance` / `carryover`. Income categories return `received` instead.
- The sign conventions are odd (`totalBudgeted` is negative). The bridge mappers normalize them, and the contract documents the result.
- AQL with `.options({ splits: 'grouped' })` returns parents with `subtransactions`, which is what the transactions list needs.

**Tier 3 (internal handlers), each behind a capability flag:**
- `budget/transfer-category` (move money, including to and from "To Budget") and `budget/cover-overspending`.
- `budget/apply-multiple-templates` and friends (goal templates).
- `transactions-parse-file` / `transactions-import` (file import).
- A missing handler raises `handler is not a function`. The bridge catches that, flips the feature flag off, and returns `501 feature_unavailable`, so an Actual upgrade that removes a handler degrades one feature instead of crashing.

**Not available at any tier yet:**
- Undo/redo (it lives in the web client, not the engine API).
- Custom report definitions: Tier 2 AQL is possible, to be confirmed later.
- Reconciliation, which the bridge will compose from Tier 1 calls: mark cleared transactions as reconciled, plus an adjustment transaction.

**Operational notes:**
- `sync-server` must bind to IPv4 (`ACTUAL_HOSTNAME=0.0.0.0`) in environments without IPv6.
- The API logs noisily to the console. The bridge routes that output to debug level.
