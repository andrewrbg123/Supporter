# SupporterMod — build plan

Working plan derived from `SUPPORTERMOD_CLAUDE_CODE_PROMPT.md`. This file tracks what is
built, what is blocked, and the decisions still outstanding.

## Status

| Phase | State | Notes |
|---|---|---|
| 0 — Recon | **Blocked** | Needs the server jar and FactionMod. See *Inputs needed*. |
| 1 — Entitlement | **Core done, adapter blocked** | Service, storage, config, reconcile built and tested. Plugin class, commands and events need Phase 0. |
| 2 — Identity | Not started | Blocked on R6 (chat ownership). |
| 3 — Homes | Not started | Blocked on R5. |
| 4 — Trails | Not started | Blocked on R2 and the particle asset format. |
| 5 — Tokens / shop | Not started | Blocked on Phase 4 and `ShopUI`. |
| 6 — Community | Not started | Blocked on R1, R3, R4. |

## Inputs needed

Ordered by what they unblock.

1. **`SUPPORTERMOD_SPEC.md`** — every phase implements a section of it. Without §2.1 the table
   set is guesswork, without §4 the config keys are.
2. **The Hytale server jar** — the `javap` target for Phase 0 A/B/C.
3. **`FactionMod-1_18_3.jar`**, or better its source — the reference for 10 of the 14 Phase 0
   questions.
4. **FactionMod's `build.gradle(.kts)` and `manifest.json`** — how a plugin compiles against
   the server, and the descriptor format to mirror.
5. **The server's plugin directory**, plus any homes/essentials plugin and LuckPerms-equivalent
   jars — D10 and D11 need `javap`, not a yes/no.
6. **The `FM_Boundary_Preset_Ember` asset triple** — Phase 4 copies its format exactly. These
   live under a server asset path and may not be inside the jar.
7. **The Tebex package's command string**, and whether it is configured to run offline.
8. **A staging server**, and how a plugin is deployed and reloaded on it.

## Corrections to the prompt

- **Phase 0 gates more than the prompt says.** The header claims phases 4 and 6 depend on it.
  In fact 2, 3, 4, 5 and 6 all do; Phase 1 is the only independent one. Treat Phase 0 as a hard
  gate on everything except entitlement.
- **Phase 0 is missing four questions**, each blocking work that a later phase assumes:
  - the **scheduler API** — the nightly reconcile and the Phase 4 trail interval both need it;
  - **offline username → UUID resolution** — Tebex fires `/supporter grant <name>` for players
    who may never have logged in;
  - **command permission gating** — how `/supporter grant` is restricted to console and admins;
  - **the plugin data directory** — where the SQLite file is allowed to live.
- **Tebex retries command delivery.** The prompt passes a `txn` to `grant()` but never says it
  must be unique, so the obvious implementation delivers one purchase several times. Handled
  here with a `supporter_txn` ledger keyed on the transaction id; see the tests.

## Open decisions

1. **Chat ownership (R6).** If FactionMod already cancels `PlayerChatEvent`, two plugins
   re-broadcasting will double-render or drop messages. The fix is one renderer with the other
   contributing a prefix through an API — but which plugin owns it is a call to make before any
   Phase 2 code is written, and it probably means a change to FactionMod.
2. **Chargeback vs expiry.** Both currently route through `revoke()`. A fraudulent chargeback
   arguably should also zero unspent tokens, which an ordinary expiry must not. Phase 5 needs a
   policy here.
3. **`total_months`.** The spec asks for a column; this implementation derives it from
   `total_days` instead, because an incrementing counter scores two 15-day grants as zero
   months. Confirm that matches the intent before the leaderboard ships.
4. **The other four tables.** §2.1 names six; only the ones Phase 1 needs exist so far. They
   arrive as numbered migrations once the spec does.

## Suggested phase order

`0 → 1 → 2 → 4 → 5 → 3 → 6`

Homes move late because they depend on a third-party plugin decision that may need somebody
else's cooperation. Trails move early because they are the most visible perk on the store page,
and Phase 5's shop needs them as stock.

Before Phase 6, get a firm answer on perks 10 (priority queue) and 11 (`/nick`). Both are
unverified, and the store page should not list either until Phase 0 says they are buildable.

## What is built

The API-independent half of Phase 1 — everything that is plain Java and SQL rather than Hytale
API, so it could be written and tested before the recon lands.

- `core/SupporterService` — the single source of truth. Grant, revoke, reconcile, login
  handling, and an O(1) entitlement cache for the chat and tick paths.
- `core/SupporterRecord` — pure status arithmetic, all timestamps epoch-millis UTC.
- `storage/` — SQLite with WAL, numbered migrations, and the transaction ledger.
- `config/SupporterConfig` — `supporter.json`, defaults for every key, fails loudly on a
  malformed file.
- `platform/` — ports for the four things the server must provide. No Hytale imports anywhere
  in the source tree yet, which is why the tests run without the server jar.

30 tests cover the prompt's Phase 1 verification list plus the payment-retry case.

### Still to do in Phase 1, once Phase 0 lands

- `SupporterPlugin extends JavaPlugin` with the `safe(Runnable)` wrapper.
- The `/supporter` command tree.
- Player-join wiring to `SupporterService.onLogin`.
- Hytale-backed implementations of the four ports.

## Building

```bash
./gradlew build     # compiles against JDK 25, runs the tests, produces the plugin jar
./gradlew test
```

The toolchain requests JDK 25 to match the server. Gradle will find it if the JDK is installed
locally; otherwise pass `-Porg.gradle.java.installations.paths=/path/to/jdk-25`.

`pluginJar` bundles sqlite-jdbc and gson. If the server turns out to provide either, drop it
from `build.gradle.kts` — a duplicate on the classpath is worse than a missing one.
