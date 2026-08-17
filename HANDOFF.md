# SupporterMod — handoff briefing

**For a Claude Code session that has The People's Server files available.** This replaces a
prior conversation; assume none of it is in your context. Everything below is either verified
against a real artifact or explicitly marked unverified. Do not treat unverified items as known.

Repo: `andrewrbg123/Supporter`, branch `claude/planning-session-vlx7pp`.
Companion files in that repo: `PLAN.md` (build plan), `PHASE0-RECON.md` (full recon report),
`tools/recon.sh` (one-shot recon against the server jar).

---

## 1. What you are being asked to do

Two things, in this order:

1. **Answer the five open questions in §5.** They need the Hytale server jar, which the prior
   session never had. They gate the store page and all of Phase 2.
2. **Continue the build from §6**, applying the corrections in §4.

The original build prompt is `SUPPORTERMOD_CLAUDE_CODE_PROMPT.md` and the spec it references is
`SUPPORTERMOD_SPEC.md`. **The prior session never had the spec** — if you have it, reconcile it
against §3 and §7 before writing code, because several structures were reconstructed from the
phase prompts alone.

---

## 2. Current state of the repo

Built, tested and pushed: the API-independent half of Phase 1 — everything that is plain Java
and SQL rather than Hytale API. **30 tests pass. There are no `com.hypixel.hytale` imports
anywhere in the source tree**, which is why it builds and tests without the server jar.

```
src/main/java/com/peoplesserver/supportermod/
  core/SupporterService.java     the single source of truth for entitlement
  core/SupporterRecord.java      pure status arithmetic, epoch-millis UTC
  core/SupporterStatus.java      NONE / ACTIVE / GRACE / EXPIRED
  core/GrantResult.java          CREATED / EXTENDED / DUPLICATE_IGNORED / QUEUED_PENDING
  core/ReconcileJob.java         nightly sweep scheduling
  storage/SupporterStorage.java  the only class that touches the supporter tables
  storage/Migrations.java        numbered migrations, not a fixed CREATE TABLE block
  storage/Database.java          SQLite + WAL
  config/SupporterConfig.java    supporter.json
  platform/                      ports: PlayerDirectory, Messenger, Scheduler, PluginLog
src/main/resources/manifest.json
reference/FactionMod-1.19.13.jar the pattern reference for every later phase
```

Build: `./gradlew build`. Java toolchain 25 (the server runs JDK 25). Provided jars go in
`libs/` as `compileOnly` — gitignored, and an empty `libs/` still builds green.

**Not yet built, because all four need the server jar:** `SupporterPlugin extends JavaPlugin`,
the `/supporter` command tree, the player-join wiring, and the Hytale-backed implementations of
the four ports.

---

## 3. Design decisions already made, and why

Keep these unless you have a concrete reason to change them. Each exists because of a specific
failure mode.

**Renewal extends from the existing expiry, never from now.** `base = max(now, expiresAt)`.
Renewing 10 days into a 30-day period yields 50 days remaining, not 30. Buying early must never
cost somebody time. Covered by a test.

**Grants carrying a transaction id are applied at most once.** Tebex retries command delivery
until the server acknowledges it, so a naive implementation delivers one purchase several times.
There is a `supporter_txn` table whose primary key is the transaction id. This is not in the
original spec — it was added deliberately. Covered by a test. **Do not remove it.**

**Grants for unresolvable usernames are queued, not dropped.** A gift purchase for somebody who
has never logged in cannot resolve to a UUID. `pending_grants` holds it until their next login.
See §5.5 — if the server can resolve usernames offline, this becomes a fallback rather than the
main path, but keep it either way.

**`total_months` is derived from `total_days`, not incremented.** An incrementing counter using
integer division scores two 15-day grants as zero months. Confirm against spec §2.1.

**Only four tables exist so far.** Spec §2.1 names six. Without the spec, inventing the rest
would guarantee a conflict. They arrive as migration `V2` — that is why migrations are numbered.

**All timestamps are epoch milliseconds UTC.** No local time anywhere in the entitlement path; a
server timezone change must never move somebody's expiry.

**The entitlement cache is time-aware.** Presence in the cache means "flagged active", not
"currently entitled" — a record sits there between lapsing and the nightly reconcile. Every read
re-checks the timestamp. `isSupporter()` is an O(1) map lookup because it is called from the chat
event and, in Phase 4, from a repeating task.

---

## 4. Corrections to the original build prompt

These are verified against FactionMod 1.19.13 and HyUI 0.9.8. The original prompt is wrong on
each point.

**4.1 — The prescribed command pattern breaks the Tebex integration.** Phase 1 says to follow
`AbstractCommandCollection` + `AbstractPlayerCommand`. But `AbstractPlayerCommand`'s entry point
is `execute(CommandContext, Store, Ref, PlayerRef, World)` — it *requires* a player, and
`/supporter grant` is fired from console with no player context. The console-capable path is
`CommandBase`:

```java
protected void executeSync(CommandContext ctx) {
    if (!ctx.isPlayer()) { /* console — handle it, do not reject */ }
    UUID uuid = ctx.sender().getUuid();
    ctx.sendMessage(Message.raw("…").color(Messages.ERR));
}
```

Admin subcommands must extend `CommandBase` and override `executeSync`. Player-facing ones can
stay on `AbstractPlayerCommand`.

**4.2 — Question E13's premise is false.** It asks how FactionMod initialises SQLite. FactionMod
contains **no SQL of any kind** — no `java.sql` import, no JDBC, nothing, across 171 classes. It
is JSON files under `plugins/FactionMod/` with `ConcurrentHashMap` caches and explicit save calls.
There is no pattern to copy. See §5.6 for the risk this creates.

**4.3 — Phase 0 gates more than the prompt says.** The header claims phases 4 and 6 depend on it.
In fact 2, 3, 4, 5 and 6 all do; Phase 1 is the only independent one.

**4.4 — Phase 0 is missing four questions**, each blocking work a later phase assumes: the
scheduler API, offline username→UUID resolution, command permission gating, and the plugin data
directory. The last two are now answered (§7.2, §7.6); the first two are in §5.

**4.5 — HyUI is an undeclared dependency.** `au.ellie.hyui`, Group "Ellie", version 0.9.8,
`Main: au.ellie.hyui.HyUIPlugin`. FactionMod's entire UI rests on it, yet FactionMod's manifest
declares no dependency on it at all — only `Economy:EconomySystem`, and that one as *optional*.
If HyUI ever loads after FactionMod or fails to load, FactionMod's panels break with no declared
ordering to prevent it. **Worth fixing in FactionMod separately.** SupporterMod should declare it
properly: `"Dependencies": { "Ellie:HyUI": ">=0.9.8" }`.

**4.6 — Do not model the shop on `ShopUI`'s builder chains.** See §7.5.

**4.7 — `trailIntervalTicks = 8` is aggressive.** See §7.4.

**4.8 — Version drift.** The prompt names `FactionMod-1_18_3.jar`; the analysed jar is 1.19.13.

---

## 5. Open questions — answer these first

Run `bash tools/recon.sh /path/to/server.jar` for most of this in one pass. Each item says what
turns on the answer.

**5.1 — `PlayerChatEvent` cancellation semantics. Gates all of Phase 2.**
FactionMod cancels `PlayerChatEvent` **only** when the player has faction chat toggled on;
normal chat returns early, uncancelled (full code in §7.1). So SupporterMod can take global chat.
But a supporter who is *also* in faction-chat mode would be handled by both plugins — FactionMod
re-broadcasting to their faction, SupporterMod to everyone. **That leaks private faction chat
server-wide**, which is worse than a cosmetic bug.

The fix is for SupporterMod's handler to skip already-cancelled events. Determine:
does `PlayerChatEvent` expose an `isCancelled` getter? Does `EventRegistry.registerGlobal` still
deliver to later handlers after an earlier one cancels? Is handler order deterministic?
```
javap -cp server.jar com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent
javap -cp server.jar com.hypixel.hytale.event.EventRegistration
```
If the event does not expose cancellation state to later handlers, the two plugins must
coordinate explicitly and FactionMod needs a change.

**5.2 — Connection-stage hook (R3). Decides perk 10, priority queue — a store-page item.**
Is there *any* event before world entry that can reject or delay a connection? FactionMod uses
only `PlayerReadyEvent`, `PlayerDisconnectEvent`, `PlayerChatEvent`. `PlayerReadyEvent` fires
after world entry — far too late to hold a connection. FactionMod may simply not need an earlier
hook, so this is not evidence one is absent.
```
unzip -l server.jar | grep -iE 'event.*(player|connect|login|join|session|handshake)'
```
If nothing exists, fall back to reserved slots — which needs 5.3. **If neither works, say so
plainly and the perk comes off the store page. Do not fake it.**

**5.3 — Player cap readable at runtime (C9).** Needed for the reserved-slots fallback.

**5.4 — Server-side display name (R4). Decides perk 11, `/nick` — a store-page item.**
FactionMod keeps its own `displayName` field and renders every name by string concatenation into
chat; it never calls a server-side display-name setter and never touches a nameplate. Suggestive,
not proof.
```
javap -cp server.jar com.hypixel.hytale.server.core.universe.PlayerRef
```
If it is chat-only rather than nameplate, that is fine — but say so in the command's own help
text so players are not surprised.

**5.5 — Tab list (R1), and offline username lookup.**
No tab-list, player-list or scoreboard API is referenced anywhere in FactionMod or HyUI. `/f list`
and the leaderboard all render as chat text. If R1 is negative, the manifest Description must say
"chat tag only, no tab list" — and note that HyUI has a HUD API (`player.hud.HudManager`,
`CustomUIHud`, `HudBuilder`) which is a plausible substitute worth considering.

Separately: `Universe.get().getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE)` exists but
appears to be online-only. Check whether any offline lookup exists — it decides whether
`pending_grants` is the main path or a fallback.

**5.6 — Can `sqlite-jdbc` load in the server environment?** Not a jar question — a runtime one,
and the **first thing to test on a live server**. There is no SQLite precedent on this server
(§4.2). `sqlite-jdbc` unpacks a native library to a temp directory at first use; if the
environment forbids that it fails at runtime, not at compile time. If it fails, the fallback is
FactionMod's JSON approach plus an append-only audit log — the `SupporterStorage` interface is
already the only thing that touches the tables, so the blast radius is one class.

---

## 6. Phase plan

Suggested order: **0 → 1 → 2 → 4 → 5 → 3 → 6.** Homes move late because they depend on a
third-party plugin decision that may need somebody else's cooperation. Trails move early because
they are the most visible perk and Phase 5's shop needs them as stock.

| Phase | State |
|---|---|
| 0 Recon | Mostly answered — §5 is what remains |
| 1 Entitlement | Core done and tested; plugin class, commands, events, ports outstanding |
| 2 Identity | Design unblocked (§7.1); needs 5.1 answered before any chat code |
| 3 Homes | Needs the `mods/` listing; teleport pattern confirmed (§7.6) |
| 4 Trails | Design unblocked — viewer lists and asset format both confirmed (§7.3, §7.4) |
| 5 Tokens/shop | Needs Phase 4; HyUI API recovered (§7.5) |
| 6 Community | Needs 5.2, 5.4, 5.5 |

**Standing rules from the original prompt — these are not negotiable:**
- Nothing may affect combat, claims, power, outpost income, KOTJ scoring or the bucks economy.
  If a task seems to require it, stop and ask. That is the pay-to-win line.
- `SupporterService` is the only thing that reads or writes the supporter table.
- Never delete something a player paid for. Freeze it instead.
- Tokens have no exchange path to or from bucks, in either direction.
- Do not guess Hytale or HyUI signatures. Run `javap` and show the real one.
- One phase at a time; do not start the next until the live test passes.

---

## 7. Verified API reference

Recovered from FactionMod 1.19.13 and HyUI 0.9.8 by decompiling and reading bytecode descriptors.
These are real signatures, not guesses — use them rather than re-deriving.

### 7.1 Chat (Phase 2)

`MembershipSystem.onPlayerChat`, the decisive part:

```java
private void onPlayerChat(PlayerChatEvent e) {
   PlayerRef sender = e.getSender();
   if (sender == null) return;
   UUID uuid = sender.getUuid();
   if (!plugin.getData().isFactionChatToggled(uuid)) {
      return;                                   // <-- normal chat is NOT touched
   }
   ...
   e.setCancelled(true);                        // <-- only in faction-chat mode
   String line = "[F][" + f.tag + "] " + sender.getUsername() + ": " + e.getContent();
   Universe u = Universe.get();
   for (FactionMemberRef m : f.members) {
      PlayerRef p = u.getPlayer(m.uuid);
      if (p != null) p.sendMessage(Message.raw(line).color(Messages.OK));
   }
}
```

Message API — three methods, enough for the `[Supporter] <title> Name: message` line, where
`join` gives per-segment colouring:

```java
Message.raw(String)              -> Message
Message.color(java.awt.Color)    -> Message     // java.awt.Color, as the prompt said
Message.join(Message[])          -> Message
```

**There is no broadcast API.** Every broadcast is a manual loop, either
`Universe.get().getPlayer(uuid).sendMessage(…)` or over `world.getPlayerRefs()`. Note
`Universe.get()` can return null during `setup()`; FactionMod checks and falls back to
`AddWorldEvent`.

### 7.2 Plugin lifecycle and commands (Phase 1)

```java
public class FactionPlugin extends JavaPlugin {
   public FactionPlugin(JavaPluginInit init) { super(init); }
   protected void setup() { … }
   protected void shutdown() { … }
}
```

Registries, all off the plugin instance:
```java
getEventRegistry().registerGlobal(SomeEvent.class, this::handler)  // -> EventRegistration<?,?>
getCommandRegistry().registerCommand(new FactionCommand(this))
getEntityStoreRegistry().registerSystem(new StatueUseSystem(this))
getCodecRegistry(Interaction.CODEC).register(id, Class, CODEC)
getLogger().atInfo() / .at(Level.WARNING)                          // -> HytaleLogger.Api
```

`safe(Runnable)` is FactionMod's own private helper, not a platform API — copy it.

**Data directory convention: `Path.of("plugins", "<PluginName>")`.** Asset packs live in `mods/`.

Command pattern:
```java
public class FactionCommand extends AbstractPlayerCommand {
   public FactionCommand(FactionPlugin p) {
      super("f", "Faction system. Type /f help for the full list.");
      setPermissionGroup(GameMode.Adventure);      // Creative for admin commands
      addAliases(new String[]{"faction"});
      addSubCommand(new CreateSub(p));
   }
}

public static class ColorSub extends AbstractPlayerCommand {
   private final Argument presetArg;
   public ColorSub(FactionPlugin p) {
      super("colour", "…");
      setPermissionGroup(GameMode.Adventure);
      this.presetArg = withRequiredArg("preset", "…", ArgTypes.STRING);
   }
   protected void execute(CommandContext ctx, Store<EntityStore> store,
                          Ref<EntityStore> ref, PlayerRef player, World world) {
      String v = ((String) ctx.get(this.presetArg)).trim();
   }
}
```

**Permissions are `GameMode`-based and binary** — `GameMode.Adventure` for players,
`GameMode.Creative` for admin. No LuckPerms, no permission strings, no groups, no expiry. This is
precisely why `SupporterService` owns expiry itself rather than delegating to a permissions
plugin. Also: `addUsageVariant` dispatches on required-argument count, and an *optional* arg
registers as a named flag (`[--mode=?]`), not a positional — FactionMod's own changelog records
that bug twice.

### 7.3 Particles (Phase 4)

Two overloads, exact descriptors:
```
spawnParticleEffect(String, org.joml.Vector3d, ComponentAccessor)
spawnParticleEffect(String, org.joml.Vector3d, java.util.List, ComponentAccessor)
```

**The `List` is a viewer list** — `List<Ref>` built from `PlayerRef.getReference()`. The same
world position renders red to one player and blue to another:

```java
EnumMap<RelationshipTint, List<Ref>> buckets = new EnumMap<>(RelationshipTint.class);
for (PlayerRef p : world.getPlayerRefs()) {
   Ref r = p.getReference();
   if (r != null) buckets.computeIfAbsent(resolveViewerColor(p.getUuid(), faction),
                                          k -> new ArrayList<>()).add(r);
}
ParticleUtil.spawnParticleEffect(name, position, buckets.get(tint), store);
```

**So per-player trail opt-out is buildable.** Leave the opted-out player out of the list.

Scheduling — there is **no server scheduler API in use**:
```java
scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
   Thread t = new Thread(r, "FactionMod-ZoneViz"); t.setDaemon(true); return t;
});
tickTask = scheduler.scheduleAtFixedRate(this::tick, 1500L, 1500L, MILLISECONDS);
```

Budget controls to copy: 1500 ms tick; **on-demand only** — a claim renders only inside a 30 s
window a player asked for; 64-block view distance; 50 segments per edge; 8 vertical stacks.

**Structurally critical:** the scheduler thread does no world work directly. It resolves the
world, then hands off with `world.execute(() -> …)`. All particle spawning and component access
happens inside that callback. `TrailSystem` must do the same.

This is why `trailIntervalTicks = 8` (~400 ms) is aggressive: nearly 4× more often than the
busiest thing FactionMod runs, and continuously rather than on demand.

### 7.4 Particle asset format (Phase 4)

Assets ship **inside** the plugin jar with `"IncludesAssetPack": true`. Three files per trail:
```
Server/Particles/<Plugin>/Presets/<id>.particlesystem
Server/Particles/<Plugin>/Spawners/<id>.particlespawner
Common/Particles/Textures/<Plugin>/Presets/<id>.png
```

`FM_Boundary_Preset_Ember.particlesystem` in full:
```json
{ "LifeSpan": 4.0, "Spawners": [ { "SpawnerId": "FM_Boundary_Preset_Ember" } ] }
```

`FM_Boundary_Preset_Ember.particlespawner`:
```json
{
  "RenderMode": "BlendLinear",
  "EmitOffset": { "X": {"Min":0,"Max":0}, "Y": {"Min":0,"Max":0}, "Z": {"Min":0,"Max":0} },
  "ParticleRotationInfluence": "BillboardY",
  "LinearFiltering": true,
  "LightInfluence": 0,
  "ParticleRotateWithSpawner": false,
  "MaxConcurrentParticles": 4,
  "ParticleLifeSpan": { "Min": 3.5, "Max": 4.0 },
  "SpawnRate": { "Min": 1, "Max": 1 },
  "TotalParticles": { "Min": 1, "Max": 1 },
  "InitialVelocity": { "Speed": {"Min":0,"Max":0}, "Yaw": {"Min":0,"Max":0}, "Pitch": {"Min":0,"Max":0} },
  "Particle": {
    "Texture": "Particles/Textures/FactionMod/Presets/ember.png",
    "FrameSize": { "Width": 32, "Height": 512 },
    "ScaleRatioConstraint": "Preserved",
    "UVOption": "None",
    "Animation": {
      "0":   { "FrameIndex": {"Min":0,"Max":0}, "Opacity": 0.0, "Color": "#ffffff",
               "Scale": {"X":{"Min":1.0,"Max":1.0},"Y":{"Min":1.0,"Max":1.0}},
               "Rotation": {"X":{"Min":0,"Max":0},"Y":{"Min":0,"Max":0},"Z":{"Min":0,"Max":0}} },
      "10":  { "Opacity": 1.0, "Color": "#ffffff", "Scale": {"X":{"Min":1.0,"Max":1.0},"Y":{"Min":1.0,"Max":1.0}} },
      "90":  { "Opacity": 1.0, "Color": "#ffffff", "Scale": {"X":{"Min":1.0,"Max":1.0},"Y":{"Min":1.0,"Max":1.0}} },
      "100": { "Opacity": 0.0, "Color": "#ffffff", "Scale": {"X":{"Min":1.0,"Max":1.0},"Y":{"Min":1.0,"Max":1.0}} }
    },
    "InitialAnimationFrame": { "FrameIndex": {"Min":0,"Max":0}, "Opacity": 0.7, "Color": "#ffffff",
      "Scale": {"X":{"Min":1.0,"Max":1.0},"Y":{"Min":1.0,"Max":1.0}},
      "Rotation": {"X":{"Min":0,"Max":0},"Y":{"Min":0,"Max":0},"Z":{"Min":0,"Max":0}} }
  }
}
```

- `Texture` is relative to `Common/`, so it omits the `Common/` prefix.
- `SpawnerId` matches the spawner **filename**; the name passed to `spawnParticleEffect` is the
  **particlesystem** filename.
- `Animation` keys are percentages of lifespan, 0–100. Keep the fade-in/hold/fade-out shape.
- The texture is a vertical strip, 32 × 512.
- FactionMod already ships five such presets (`ember`, `ocean`, `void`, `forest`, `candy_floss`)
  selected by `/f colour` — a working precedent for trail selection UX.

### 7.5 HyUI (Phases 2, 5, 6)

Its own plugin: Group "Ellie", version 0.9.8, `Main: au.ellie.hyui.HyUIPlugin`,
`ServerVersion: ">=0.5.0-pre.9 <0.6.0"`, `Dependencies: { "Hytale:AssetModule": "*" }`. A Kotlin
library of ~1600 classes bundling kotlin-stdlib and jsoup. **`compileOnly`** — bundling it would
put a second copy on the classpath.

Opening a page:
```java
PageBuilder.pageForPlayer(playerRef)            // or PageBuilder.detachedPage()
    .withLifetime(CustomPageLifetime.…)
    .withRefreshRate(millis)
    .onRefresh(page -> PageRefreshResult…)
    .onDismiss((page, byPlayer) -> …)
    .open(playerRef, store);                    // -> HyUIPage
```

`HyUIPage` extends
`com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage` and exposes
`close()`, `updatePage(boolean)`, `editById(id, type, consumer)`, `getValue(id)`,
`triggerRefresh()`.

**`editById` + `updatePage` means the prompt's "close-and-reopen after purchase" is
unnecessary** — mutate the open page in place.

**Do not copy `ShopUI`'s builder-chain style.** It is 1251 lines for one panel. HyUI 0.9.8 builds
pages from HTML templates with variable substitution, backed by a real parser
(`html.HtmlParser`, `CssPreprocessor`, `TemplateProcessor`):
```java
loadHtml(String path, Map<String,?> vars);
fromHtml(String html);  fromUIFile(String path);  fromTemplate(String tpl, Map<String,?> vars);
```
Use templates for the supporter shop, the `/supporters` list and the Phase 6 poll panel — far
less code, and editable without recompiling.

Element ids and listeners, either style:
```java
builder.withId("SupporterShopBuyTrail");
builder.addEventListener(CustomUIEventBindingType.Activating, EventData.class, data -> …);
builder.addEventListenerWithContext(type, EventData.class, (data, ctx) -> …);
```

The builders are immutable-ish — every `withX`/`addChild` returns a new instance that must be
reassigned, which is why decompiled FactionMod UI code is so cast-heavy.

### 7.6 Block interaction and teleport (Phases 2, 3)

The spawn plaque follows `StatueUseSystem` — an ECS system, not an event-registry listener:

```java
public final class StatueUseSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {
   public StatueUseSystem(FactionPlugin p) { super(Pre.class); this.plugin = p; }

   public Query<EntityStore> getQuery() {
      return Query.and(new Query[]{ PlayerRef.getComponentType() });
   }

   public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                      CommandBuffer<EntityStore> cmd, UseBlockEvent.Pre event) {
      PlayerRef ref = (PlayerRef) chunk.getComponent(index, PlayerRef.getComponentType());
      if (ref == null || event.getBlockType() == null) return;
      String blockId = event.getBlockType().getId();
      …
   }
}
```

Registered with `getEntityStoreRegistry().registerSystem(...)`, plus an `Interaction` codec so the
client shows a proper prompt rather than plain text.

Teleport, for Phase 3 homes:
```java
Teleport t = Teleport.createForPlayer(world, position, rotation);
store.addComponent(ref, Teleport.getComponentType(), t);
```

### 7.7 Manifest format

```json
{
  "Group": "ThePeoplesServer",
  "Name": "SupporterMod",
  "Version": "0.1.0",
  "Description": "…",
  "Authors": [ { "Name": "ThePeoplesServer" } ],
  "ServerVersion": ">=0.5.0 <0.6.0",
  "Dependencies": {},
  "OptionalDependencies": {},
  "DisabledByDefault": false,
  "IncludesAssetPack": false,
  "Main": "com.peoplesserver.supportermod.SupporterPlugin"
}
```

FactionMod uses `Description` as a full changelog — many kilobytes of prose. That is the
"detailed FactionMod style" Phase 6 refers to. There is also an **Economy plugin** on this server
(`Economy:EconomySystem`), reached via `Economy.init()` / `isAvailable()` / `currencyNamePlural()`
— relevant because Phase 5 must stay strictly separated from it.

---

## 8. Verdicts to carry into the store page

- **Perk 10, priority queue — UNPROVEN.** No connection-stage hook visible; `PlayerReadyEvent`
  fires after world entry. Needs 5.2 and 5.3. Do not list until resolved.
- **Perk 11, `/nick` — UNPROVEN, leaning negative.** Chat-only is clearly buildable; the
  nameplate is the doubtful half. Needs 5.4.
- **Tab list (R1) — UNPROVEN, leaning negative.** No such API referenced anywhere. Needs 5.5.

If any comes back negative, the manifest Description must state the limitation honestly, and the
perk comes off the store page before launch.
