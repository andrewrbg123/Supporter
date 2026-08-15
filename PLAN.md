# SupporterMod — build plan

Working plan derived from `SUPPORTERMOD_CLAUDE_CODE_PROMPT.md`. This file tracks what is
built, what is blocked, and the decisions still outstanding.

## Status

| Phase | State | Notes |
|---|---|---|
| 0 — Recon | **Mostly answered** | See PHASE0-RECON.md. A1, A2, C8, C9 need the server jar. |
| 1 — Entitlement | **Core done, adapter blocked** | Service, storage, config, reconcile built and tested. Plugin class, commands and events need the server jar to compile. |
| 2 — Identity | Unblocked on design, blocked on build | R6 resolved favourably; needs the server jar. |
| 3 — Homes | Blocked | R5 needs the `mods/` listing. Teleport pattern confirmed. |
| 4 — Trails | **Unblocked on design** | R2 confirmed viewer lists; asset format fully recovered. |
| 5 — Tokens / shop | Blocked | HyUI API recovered; needs the server jar and Phase 4. |
| 6 — Community | Blocked | R1, R3, R4 all need the server jar. |

## Inputs needed

FactionMod 1.19.13 and HyUI 0.9.8 have been supplied and analysed — see PHASE0-RECON.md.
Still outstanding, ordered by what they unblock.

1. **The Hytale server jar** — R1 (tab list), R3 (connection hook), R4 (display name), C9 (player
   cap), and the `PlayerChatEvent` cancellation semantics that Phase 2 depends on. Also needed on
   the compile classpath before any Hytale-facing code can be built.
2. **`SUPPORTERMOD_SPEC.md`** — without §2.1 the table set is guesswork, without §4 the config
   keys are.
3. **The `mods/` and `plugins/` directory listing**, plus any homes/essentials and
   LuckPerms-equivalent jars — D10 and D11.
4. **The Tebex package's command string**, and whether it is configured to run offline.
5. **A staging server** — above all to test whether `sqlite-jdbc` can unpack its native library
   in the server environment. See the E13 note in the recon report; there is no SQLite precedent
   on this server, and that failure mode only appears at runtime.

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
- **The prescribed command pattern breaks the Tebex integration.** Phase 1 says to follow
  `AbstractCommandCollection` + `AbstractPlayerCommand`. But `AbstractPlayerCommand.execute`
  requires a `PlayerRef`, and `/supporter grant` runs from console with no player. Admin
  subcommands must extend `CommandBase` and override `executeSync(CommandContext)`, which
  handles the console case explicitly via `ctx.isPlayer()`.
- **E13's SQLite premise is false.** FactionMod contains no SQL at all — it is JSON files under
  `plugins/FactionMod/`. There is no pattern to copy, and no precedent for SQLite on this
  server. See the open decision below.

## Open decisions

1. **Chat ownership (R6) — largely resolved.** FactionMod cancels `PlayerChatEvent` *only* when
   the player has faction chat toggled on, so SupporterMod can take global chat. One hazard
   remains: a supporter in faction-chat mode would be handled by both plugins, leaking private
   faction chat server-wide. SupporterMod's handler must skip already-cancelled events — which
   needs confirmation from the server jar that later handlers can see cancellation state and
   that handler order is deterministic. Settle this before writing Phase 2 chat code.
2. **SQLite has no precedent on this server.** `sqlite-jdbc` unpacks a native library to a temp
   directory at first use; if the environment forbids that it fails at runtime, not at compile
   time. This should be the first thing tested on staging. Fallback is FactionMod's JSON
   approach with an append-only audit log.
3. **Chargeback vs expiry.** Both currently route through `revoke()`. A fraudulent chargeback
   arguably should also zero unspent tokens, which an ordinary expiry must not. Phase 5 needs a
   policy here.
4. **`total_months`.** The spec asks for a column; this implementation derives it from
   `total_days` instead, because an incrementing counter scores two 15-day grants as zero
   months. Confirm that matches the intent before the leaderboard ships.
5. **The other four tables.** §2.1 names six; only the ones Phase 1 needs exist so far. They
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

### Provided jars

Drop the Hytale server jar and `HyUI-0.9.8-all.jar` into `libs/`. They are `compileOnly` —
supplied at runtime by the platform, never bundled. `libs/` is gitignored (the server jar is
large, HyUI is third-party), and an empty `libs/` is fine: the entitlement core and its 30 tests
reference neither jar, so the build stays green without them.

`reference/FactionMod-1.19.13.jar` is kept in-tree deliberately — it is the pattern reference
every later phase is written against, and it is our own plugin.
