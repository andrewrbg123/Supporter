# Phase 0 — Recon report

Source: `FactionMod-1.19.13.jar` (decompiled with Vineflower 1.11.1, plus `javap` on the
bytecode to recover exact API descriptors).

**The Hytale server jar was not available.** Everything below is derived from what FactionMod
actually calls. That is strong evidence for what *exists*, and weak evidence for what does not —
a question marked UNKNOWN means FactionMod never touches that API, not that the server lacks it.
Those answers need the server jar and are called out individually.

Note the version: the prompt names `FactionMod-1_18_3.jar`, the jar supplied is **1.19.13**.

---

## A. Identity

### A1 — Tab list / online-player list (R1) · **UNKNOWN**

No tab-list, player-list or scoreboard API appears anywhere in FactionMod. It has a
`/f list`, a leaderboard and a `/f top`, and all three render as **chat text**, not as a list UI.

Needs the server jar. **Do not put a tab-list perk on the store page yet.**

### A2 — Server-side display name (R4) · **UNKNOWN, leaning negative**

FactionMod keeps its own `displayName` field on `FactionMemberRef` and refreshes it from
`PlayerRef.getUsername()` on login. Every name it renders is built by **string concatenation
into a chat message**. It never calls a server-side display-name setter, and it never touches a
nameplate.

That is suggestive but not proof. Needs the server jar. It does mean the nameplate half of
`/nick` has no working precedent in the codebase we have.

### A3 — `MembershipSystem.onPlayerChat` (R6) · **ANSWERED — and the news is good**

```java
private void onPlayerChat(PlayerChatEvent var1) {
   PlayerRef var2 = var1.getSender();
   if (var2 == null) return;
   UUID var3 = var2.getUuid();
   if (!this.plugin.getData().isFactionChatToggled(var3)) {
      return;                                  // <-- normal chat is NOT touched
   }
   Faction var4 = this.plugin.getData().getFactionOf(var3);
   if (var4 == null) { this.plugin.getData().clearChatToggles(var3); return; }
   String var5 = var1.getContent();
   if (var5 == null || var5.isEmpty()) return;

   var1.setCancelled(true);                    // <-- only for faction-chat mode
   String var6 = "[F][" + var4.tag + "] " + var2.getUsername() + ": " + var5;
   Universe var7 = Universe.get();
   for (FactionMemberRef var9 : var4.members) {
      PlayerRef var10 = var7.getPlayer(var9.uuid);
      if (var10 != null) var10.sendMessage(Message.raw(var6).color(Messages.OK));
   }
}
```

**FactionMod does not own global chat rendering.** It cancels only when the player has faction
chat toggled on, and otherwise returns without cancelling. SupporterMod can therefore take
global chat without a fight.

One real hazard remains. A supporter who is *also* in faction-chat mode would be handled by both
plugins — FactionMod re-broadcasting to their faction, SupporterMod re-broadcasting to everyone.
That leaks private faction chat to the whole server, which is worse than a cosmetic bug.

The fix is for SupporterMod's handler to skip any event already cancelled. That needs one thing
the jar cannot tell us: whether `registerGlobal` still delivers an event to later handlers after
an earlier one cancels it, and whether handler order is deterministic. `PlayerChatEvent` has a
`setCancelled`; whether it exposes an `isCancelled` getter needs the server jar.

**Verify this before writing Phase 2 chat code.** If the event does not expose cancellation
state to later handlers, the two plugins must coordinate explicitly and FactionMod will need a
small change.

### A4 — Broadcast API · **ANSWERED: there isn't one in use**

Every broadcast in FactionMod is a manual loop. Two idioms:

```java
Universe.get().getPlayer(uuid).sendMessage(msg);   // targeted, by UUID
world.getPlayerRefs()                              // everyone in a world
```

`Universe.get()` can return null during `setup()` — FactionMod checks for it and falls back to
`AddWorldEvent`.

### A5 — `Message` API · **PARTIAL — enough for Phase 2**

Only three methods are used, but they cover the requirement:

```java
Message.raw(String)                  -> Message
Message.color(java.awt.Color)        -> Message      // the prompt was right: java.awt.Color
Message.join(Message[])              -> Message      // per-segment colouring
```

`join` is what makes `[Supporter] <title> Name: message` renderable with a different colour per
segment. `Messages.ok/err` in FactionMod are built exactly this way. The full API surface still
needs the server jar, but nothing in Phase 2 is blocked.

---

## B. Particles

### B6 — Both `spawnParticleEffect` overloads (R2) · **ANSWERED**

Exact descriptors from the bytecode:

```
spawnParticleEffect(String, org.joml.Vector3d, com.hypixel.hytale.component.ComponentAccessor)
spawnParticleEffect(String, org.joml.Vector3d, java.util.List, ComponentAccessor)
```

**The `List` is a viewer list.** `ZoneVisualizationSystem.renderClaim` buckets the world's
players by their relationship to the claim, collecting `PlayerRef.getReference()` into a
`List<Ref>` per bucket, then spawns a differently-coloured particle per bucket at the same
position:

```java
EnumMap var5 = new EnumMap<>(RelationshipTint.class);
for (PlayerRef var7 : var1.getPlayerRefs()) {
   Ref var8 = var7.getReference();
   if (var8 != null) var5.computeIfAbsent(this.resolveViewerColor(var7.getUuid(), var2),
                                          var0 -> new ArrayList<>()).add(var8);
}
...
ParticleUtil.spawnParticleEffect(particleName, position, (List) entry.getValue(), store);
```

So the same world position renders red to one player and blue to another. **Per-player trail
opt-out in Phase 4 is buildable** — leave the opted-out player out of the list.

### B7 — Scheduling and budget · **ANSWERED**

```java
this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
   Thread t = new Thread(r, "FactionMod-ZoneViz"); t.setDaemon(true); return t;
});
this.tickTask = this.scheduler.scheduleAtFixedRate(this::tick, 1500L, 1500L, MILLISECONDS);
```

There is **no server scheduler API in use** — it is a plain daemon `ScheduledExecutorService`.
This confirms the `Scheduler` port already built in Phase 1 is the right shape.

Budget controls, all of which Phase 4 should copy:

| Control | Value |
|---|---|
| Tick interval | 1500 ms |
| Rendering trigger | **on demand only** — a claim renders only inside a 30 s window a player asked for |
| View distance | 64 blocks |
| Segments per edge | 50 |
| Max vertical beam stacks | 8 |
| Error handling | first particle throw breaks the whole render loop, counted in a diagnostic |

The critical structural detail: the scheduler thread does **no** world work directly. It resolves
the world, then hands off with `world.execute(() -> ...)`. All particle spawning and all
component access happens inside that callback. Phase 4's `TrailSystem` must do the same.

The prompt's default of `trailIntervalTicks = 8` is roughly 400 ms at 20 tps — nearly 4× more
often than the busiest thing FactionMod runs, and unlike FactionMod's it would run continuously
rather than on demand. Worth reconsidering.

---

## C. Connection

### C8 — Connection-stage hook (R3) · **UNKNOWN — priority queue is unproven**

Every player lifecycle event FactionMod references, and its timing:

| Event | Timing | Can it reject? |
|---|---|---|
| `PlayerReadyEvent` | After world entry. Sends chat, reads faction state, is the de-facto login hook. | No |
| `PlayerDisconnectEvent` | On leave. | n/a |
| `PlayerChatEvent` | On chat. Cancellable. | n/a |

`PlayerReadyEvent` is far too late to hold or reject a connection — by the time it fires the
player is in the world.

FactionMod simply may not need an earlier hook, so this is not evidence one does not exist.
**Needs the server jar.** Until then, perk 10 is unproven — see the verdict at the end.

### C9 — Max-player limit readable at runtime · **UNKNOWN**

Nothing in FactionMod reads a player cap. Needs the server jar and the server config.

---

## D. Existing server state

### D10 — Homes plugin (R5) · **PARTIAL**

FactionMod has `/f home` (`FactionHomeCommands`), but that is a **single faction home** with a
warmup channel, not a player-homes system. No general homes/warps/essentials plugin is visible
from this jar alone — but this jar cannot see the rest of the server's `mods/` directory.

**Still needs the directory listing.** What the jar does confirm is the teleport pattern, which
is what Phase 3 needs either way:

```java
Teleport var20 = Teleport.createForPlayer(var17, var18, var19);
var6.addComponent(var5, Teleport.getComponentType(), var20);
```

Create the component, add it to the player's entity ref through the store. Matches the prompt.

### D11 — LuckPerms · **NOT USED, and there is no timed-group concept**

Permissions are `GameMode`-based and binary:

```java
this.setPermissionGroup(GameMode.Adventure);   // ordinary player commands
this.setPermissionGroup(GameMode.Creative);    // /f admin ...
```

No LuckPerms, no permission strings, no groups, no expiry. This has a direct consequence:
**SupporterMod cannot delegate entitlement to a permissions plugin** — which is exactly why the
Phase 1 `SupporterService` owns expiry itself. That design choice is now confirmed correct.

Whether LuckPerms is installed server-side is still unknown without the directory listing.

---

## E. Reference patterns

### E12 — `ShopUI.open` · **ANSWERED** (HyUI 0.9.8 supplied and analysed)

The UI is built on **HyUI — `au.ellie.hyui` — a third-party library that is NOT bundled in
FactionMod's jar.** It is its own plugin:

```json
{ "Group": "Ellie", "Name": "HyUI", "Version": "0.9.8",
  "ServerVersion": ">=0.5.0-pre.9 <0.6.0",
  "Dependencies": { "Hytale:AssetModule": "*" },
  "Main": "au.ellie.hyui.HyUIPlugin", "IncludesAssetPack": true }
```

A Kotlin library, ~1600 classes, shipping kotlin-stdlib and jsoup inside the `-all` jar. It must
be `compileOnly` for us — bundling it would put a second copy on the classpath.

**Latent bug in FactionMod, worth passing on:** its manifest declares no dependency on HyUI at
all, despite its entire UI resting on it. Only `Economy:EconomySystem` is listed, and as
*optional*. If HyUI ever loads after FactionMod, or fails to load, FactionMod's panels break with
no declared ordering to prevent it. HyUI's own manifest has a `LoadBefore` field that is empty.

The builder idiom FactionMod uses, and the event binding the prompt names:

```java
GroupBuilder g = GroupBuilder.group().withId("FactionShopRoot").withAnchor(anchor);
g = g.withBackground(new HyUIPatchStyle().setTexturePath("fm_panel_bg.png").setBorder(22));
CustomButtonBuilder b = CustomButtonBuilder.customTextButton().withId("FactionShopAffordToggle");
b = b.addEventListener(CustomUIEventBindingType.Activating, ctx -> { ... });
g = g.addChild(b);
```

Builders are immutable-ish — every `withX`/`addChild` returns a new instance that must be
reassigned, which is why the decompiled source is so cast-heavy.

Opening a page, from `PageBuilder`:

```java
PageBuilder.pageForPlayer(playerRef)          // or PageBuilder.detachedPage()
    .withLifetime(CustomPageLifetime.…)
    .withRefreshRate(millis)
    .onRefresh(page -> PageRefreshResult…)     // periodic server-driven refresh
    .onDismiss((page, byPlayer) -> …)
    .open(playerRef, store);                   // -> HyUIPage
```

`HyUIPage` extends `com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage`
and exposes `close()`, `updatePage(boolean)`, `editById(id, type, consumer)`, `getValue(id)` and
`triggerRefresh()`. **`editById` plus `updatePage` means the prompt's "close-and-reopen after
purchase" is unnecessary** — a purchase can mutate the open page in place.

**Recommendation: do not copy ShopUI's builder-chain style.** HyUI 0.9.8 can build a page from an
HTML template with variable substitution:

```java
T loadHtml(String path, Map<String, ?> vars);
T fromHtml(String html);
T fromUIFile(String path);
T fromTemplate(String tpl, Map<String, ?> vars);
```

backed by a real parser (`au.ellie.hyui.html.HtmlParser`, `CssPreprocessor`, `TemplateProcessor`).
FactionMod's `ShopUI` is 1251 lines of builder chains for one panel. The supporter shop, the
`/supporters` list and the Phase 6 poll panel should be HTML templates with element ids bound to
listeners — far less code, and editable without recompiling.

Element ids and listeners work the same either way:

```java
builder.withId("SupporterShopBuyTrail");
builder.addEventListener(CustomUIEventBindingType.Activating, EventData.class, data -> …);
builder.addEventListenerWithContext(type, EventData.class, (data, ctx) -> …);
```

There is also a HUD API (`player.hud.HudManager`, `CustomUIHud`, and HyUI's `HudBuilder` /
`MultipleHUD`). If R1 comes back negative, a small HUD overlay is a plausible substitute for a
tab-list supporter badge — noted as a fallback, not a plan.

### E13 — SQLite initialisation · **THE PREMISE IS WRONG**

**FactionMod contains no SQL of any kind.** No `java.sql` import, no JDBC, no prepared
statement, no WAL, anywhere in 171 classes.

Persistence is JSON files plus in-memory maps:

```java
Path base = Path.of("plugins", "FactionMod");
this.data = new FactionDataManager(base);      // factionsDir, playersDir, claimsDir, outpostsDir
this.bucksConfig = BucksConfig.loadOrCreate(base);
```

with `ConcurrentHashMap` caches, explicit `saveFaction`/`savePlayerData` calls after each
mutation, and a full save in `shutdown()`.

Two consequences:

1. **`plugins/<PluginName>/` is the data directory convention** — that closes one of the gaps I
   flagged. Asset packs are separate and live in `mods/`.
2. **There is no precedent for SQLite on this server.** SupporterMod's Phase 1 uses it anyway —
   an audit log of paid transactions genuinely wants transactions and a real query engine, and
   money data should not be a JSON file rewritten on every mutation. But it is now a
   *first*, and it carries a specific risk: `sqlite-jdbc` unpacks a native library to a temp
   directory at first use. If the server environment forbids that, it fails at runtime, not at
   compile time. **This must be the first thing tested on the staging server.** If it fails, the
   fallback is FactionMod's JSON approach with an append-only log file.

### E14 — `UseBlockEvent.Pre` handler · **ANSWERED**

This is an ECS system, not an event-registry listener:

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
      if (!FactionUtils.isStatueBlockTypeId(blockId)) return;
      ...
   }
}
```

Registered with `getEntityStoreRegistry().registerSystem(new StatueUseSystem(this))`.

Separately, an `Interaction` codec is registered so the client shows a proper prompt rather than
plain text:

```java
this.getCodecRegistry(Interaction.CODEC)
    .register("FactionMod_open_faction_statue", OpenFactionStatueInteraction.class,
              OpenFactionStatueInteraction.CODEC);
```

The Phase 2 spawn plaque should copy both halves.

---

## Particle asset format (Phase 4) — fully recovered

The assets ship **inside** the plugin jar, with `"IncludesAssetPack": true` in the manifest. The
prompt's paths were exactly right. Three files per trail:

```
Server/Particles/<Plugin>/Presets/<id>.particlesystem
Server/Particles/<Plugin>/Spawners/<id>.particlespawner
Common/Particles/Textures/<Plugin>/Presets/<id>.png
```

`FM_Boundary_Preset_Ember.particlesystem` — the whole file:

```json
{
  "LifeSpan": 4.0,
  "Spawners": [ { "SpawnerId": "FM_Boundary_Preset_Ember" } ]
}
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
      "0":   { "FrameIndex": {"Min":0,"Max":0}, "Opacity": 0.0, "Color": "#ffffff", "Scale": {...}, "Rotation": {...} },
      "10":  { "Opacity": 1.0, "Color": "#ffffff", "Scale": {...} },
      "90":  { "Opacity": 1.0, "Color": "#ffffff", "Scale": {...} },
      "100": { "Opacity": 0.0, "Color": "#ffffff", "Scale": {...} }
    },
    "InitialAnimationFrame": { "FrameIndex": {"Min":0,"Max":0}, "Opacity": 0.7, "Color": "#ffffff", "Scale": {...}, "Rotation": {...} }
  }
}
```

Notes for building the four supporter trails:

- The `Texture` path is relative to `Common/`, so it omits the `Common/` prefix.
- `SpawnerId` matches the spawner **filename**, and the name passed to `spawnParticleEffect` is
  the **particlesystem** filename (e.g. `"FM_Boundary_Own_V3"`, `"Memory_Projectile_Sparks"`).
- `Animation` keys are percentages of lifespan, 0–100. The fade-in/hold/fade-out shape above is
  the one to keep.
- The texture is a vertical strip: 32 wide, 512 tall.
- FactionMod already ships five such presets (`ember`, `ocean`, `void`, `forest`, `candy_floss`)
  selected by `/f colour`. That is a working precedent for the trail-selection UX.

---

## Plugin manifest format — recovered

```json
{
  "Group": "ThePeoplesServer",
  "Name": "FactionMod",
  "Version": "1.19.13",
  "Description": "...",
  "Authors": [ { "Name": "ThePeoplesServer" } ],
  "ServerVersion": ">=0.5.0 <0.6.0",
  "Dependencies": {},
  "OptionalDependencies": { "Economy:EconomySystem": ">=0.0.0" },
  "DisabledByDefault": false,
  "IncludesAssetPack": true,
  "Main": "com.factionmod.FactionPlugin"
}
```

`Description` is used as a full changelog — many kilobytes of prose. That is the "detailed
FactionMod style" the prompt refers to in Phase 6.

There is also an **Economy plugin** on this server (`Economy:EconomySystem`), reached through
`com.factionmod.integration.Economy` with `init()`, `isAvailable()`, `currencyNamePlural()`.
Relevant because Phase 5 must stay strictly separated from it.

---

## Corrections to the build prompt

1. **E13's SQLite premise is false.** FactionMod is JSON-backed. There is nothing to copy.
2. **The command pattern in Phase 1 will break the Tebex integration.** The prompt says to
   follow `AbstractCommandCollection` + `AbstractPlayerCommand`. But `AbstractPlayerCommand`'s
   entry point is
   `execute(CommandContext, Store, Ref, PlayerRef, World)` — it *requires* a player, and
   `/supporter grant` is fired from console with no player context. The console-capable path is
   `CommandBase`:

   ```java
   protected void executeSync(CommandContext ctx) {
      if (!ctx.isPlayer()) { ... }          // console is a first-class case
      UUID uuid = ctx.sender().getUuid();
      ctx.sendMessage(Message.raw("...").color(Messages.ERR));
   }
   ```

   Admin subcommands must extend `CommandBase` and use `executeSync`. Player-facing ones can
   stay on `AbstractPlayerCommand`.
3. **HyUI is an undeclared third-party dependency** (`au.ellie.hyui`), not part of the server and
   not bundled. Needed for Phases 2, 5 and 6.
4. **`trailIntervalTicks = 8` is aggressive** — see B7.
5. **Version mismatch** — prompt says 1.18.3, jar supplied is 1.19.13.

## Inputs still needed

| Input | Unblocks | Status |
|---|---|---|
| **Hytale server jar** | R1, R3, R4, C9, the `PlayerChatEvent` cancellation semantics | in transit |
| **HyUI jar (`au.ellie.hyui`)** | Every UI panel — Phases 2, 5, 6 | **supplied, 0.9.8** |
| **FactionMod jar** | 10 of the 14 questions here | **supplied, 1.19.13** |
| **`mods/` + `plugins/` directory listing** | R5, D11 | outstanding |
| **`SUPPORTERMOD_SPEC.md`** | §2.1 tables, §3.x perks, §4 config keys | outstanding |
| **Tebex package command string** | Confirms the `/supporter grant` signature | outstanding |
| **Staging server** | The sqlite-jdbc native-library test above all else | outstanding |

## Verdict on the two store-page perks

**Perk 10 — priority queue: UNPROVEN.** No connection-stage hook is visible, and
`PlayerReadyEvent` fires after world entry, far too late to hold or reject. The reserved-slots
fallback also needs C9, which is equally unknown. Both halves need the server jar. **Do not list
this perk until that is resolved.**

**Perk 11 — `/nick`: UNPROVEN, leaning negative.** FactionMod renders every name by string
concatenation into chat and never sets a server-side display name. A chat-only `/nick` is
clearly buildable — the nameplate is the doubtful half. Needs the server jar. If it comes back
chat-only, say so in the command's help text as the prompt already plans.

**R1 — tab list: UNPROVEN, leaning negative.** No such API is referenced anywhere.
