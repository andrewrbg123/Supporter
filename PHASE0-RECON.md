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

**Perk 11 — `/nick`: DROPPED in Phase 6.** Not shipped. Beyond the nameplate being impossible, our chat tag wraps mini-chat-formatter rather than replacing it, so a nickname renders beside the real username — two names on one line. Original note: FactionMod renders every name by string
concatenation into chat and never sets a server-side display name. A chat-only `/nick` is
clearly buildable — the nameplate is the doubtful half. Needs the server jar. If it comes back
chat-only, say so in the command's help text as the prompt already plans.

**R1 — tab list: UNPROVEN, leaning negative.** No such API is referenced anywhere.

---

# Phase 0b — answered from the server jar

Everything in this section is `javap` output from the **live** `HytaleServer.jar`
(md5 `cb42e8cd4137b0a62873797ab816e79e`, byte-identical to the jar FactionMod compiles
against, so these are the signatures actually running on The People's Server).

This closes every open question in HANDOFF §5 except 5.6, which is a runtime question and
cannot be settled from a jar. **Two of the three negative store-page verdicts flip.**

The general lesson: the earlier "leaning negative" calls were all inferred from *FactionMod not
using an API*. That is not evidence the API is absent — and in two cases it was simply wrong.

## 5.1 — `PlayerChatEvent` cancellation · ANSWERED, and it changes the design

```java
public class PlayerChatEvent implements IAsyncEvent<String>, ICancellable {
  public PlayerRef getSender();          public void setSender(PlayerRef);
  public List<PlayerRef> getTargets();   public void setTargets(List<PlayerRef>);
  public String getContent();            public void setContent(String);
  public Formatter getFormatter();       public void setFormatter(Formatter);
  public boolean isCancelled();          public void setCancelled(boolean);
  public static final Formatter DEFAULT_FORMATTER;
}

public interface PlayerChatEvent.Formatter {
    Message format(PlayerRef sender, String content);
}
```

`isCancelled()` is public, so the guard §5.1 asked for exists. But the better answer is that
**SupporterMod must never cancel and re-broadcast at all.** It sets a `Formatter`:

```java
e.setFormatter((sender, content) -> Message.join(new Message[]{
        Message.raw("[Supporter] ").color(tagColor),
        Message.raw(sender.getUsername() + ": " + content)}));
```

The server keeps ownership of delivery, so the faction-chat leak §5.1 was worried about cannot
occur: if FactionMod cancels for faction-chat mode, the formatter is never applied and
SupporterMod re-broadcasts nothing. Keep `if (e.isCancelled()) return;` as a cheap guard, but the
safety comes from not owning delivery in the first place.

`setTargets` also exists, which is what a future private-message perk would use.

## 5.2 — Connection-stage hook (R3) · POSITIVE. The premise was wrong

The recon above concluded there was no pre-world hook because FactionMod only uses
`PlayerReadyEvent`. The server ships six connection events; FactionMod just ignores them:

```
PlayerSetupConnectEvent      PlayerConnectEvent        AddPlayerToWorldEvent
PlayerSetupDisconnectEvent   PlayerDisconnectEvent     RemovedPlayerFromWorldEvent
```

`PlayerSetupConnectEvent` is the one that matters:

```java
public class PlayerSetupConnectEvent implements IEvent<Void>, ICancellable {
  public static final Message DEFAULT_REASON;
  private final PacketHandler packetHandler;
  private final String username;
  private final UUID uuid;
  private final PlayerAuthentication auth;
  private final byte[] referralData;
  private final HostAddress referralSource;
  private boolean cancelled;
  private Message reason;
}
```

Cancellable, at authentication stage, **with a rejection `Message`**, and the UUID is already
known — so entitlement can be checked before the connection is accepted.

**Perk 10 (priority queue) is buildable.** Take it off the "do not list" pile.

## 5.3 — Player cap readable at runtime (C9) · POSITIVE

```java
HytaleServerConfig.getMaxPlayers()   // and setMaxPlayers(int)
Universe.getPlayerCount()
```

Both present, so reserved slots work as the fallback — or as the mechanism itself.

## 5.4 — Server-side display name (R4) · NEGATIVE for the nameplate

`PlayerRef` has exactly one name member:

```java
private final String username;
public String getUsername();
```

`final`, no setter, no display-name concept anywhere on the class. **The nameplate cannot be
changed server-side.** A chat-only `/nick` is buildable via the `Formatter` above.

**Perk 11 stays off the store page as written.** If it ships, the help text and the store
listing must both say "chat only — your nameplate does not change".

## 5.5 — Tab list (R1) · POSITIVE, with a real caveat

There is a full server player list. FactionMod simply never touches it:

```
com/hypixel/hytale/server/core/modules/serverplayerlist/ServerPlayerListModule
protocol/packets/interface_/AddToServerPlayerList
protocol/packets/interface_/RemoveFromServerPlayerList
protocol/packets/interface_/UpdateServerPlayerList
protocol/packets/interface_/ServerPlayerListUpdate
protocol/packets/interface_/UpdateServerPlayerListPing
protocol/packets/interface_/ServerPlayerListPlayer
```

The entry is plain mutable data:

```java
public class ServerPlayerListPlayer {
  public UUID uuid;  public String username;  public UUID worldUuid;  public int ping;
  public ServerPlayerListPlayer(UUID, String, UUID, int);
}
```

`username` is a public `String`, so a decorated name can be sent.

**The caveat is ownership.** `ServerPlayerListModule` is itself a `JavaPlugin` with a
`get()` singleton, and it rebuilds entries from a *private* `createServerPlayerListPlayer(PlayerRef)`
on `PlayerConnectEvent`, `PlayerDisconnectEvent`, `AddPlayerToWorldEvent` and a recurring
`broadcastPingUpdates()`. Anything SupporterMod writes is overwritten on the next tick of that
timer. Decoration therefore means re-applying after every one of those triggers — racing a
module we do not control.

**Verdict: possible, not free.** Treat it as a Phase 6 item with a spike first, and do not
promise it on the store page until the spike holds up under a ping update.

## 5.5b — Offline username → UUID · NEGATIVE

```java
public interface PlayerStorage {
  CompletableFuture<Holder<EntityStore>> load(UUID);
  CompletableFuture<Void> save(UUID, Holder<EntityStore>, boolean);
  CompletableFuture<Void> update(UUID, Consumer<Holder<EntityStore>>);
  CompletableFuture<Void> remove(UUID);
  Set<UUID> getPlayers() throws IOException;
}
```

Everything is keyed by UUID and there is no username index — `getPlayers()` returns UUIDs only.
Resolving a name offline would mean loading every player Holder and comparing, which is not
viable on a join path.

**`pending_grants` stays the main path, exactly as designed.** `Universe.getPlayerByUsername`
remains online-only and is the fast path when the player happens to be connected.

## 5.6 — SQLite · ANSWERED ON THE LIVE SERVER. It works; the trap is elsewhere

**Settled by deploying, 2026-08-17.** Two corrections to what this report said.

**There IS a SQLite precedent on this server.** §4.2 and E13 concluded there was none because
FactionMod contains no SQL. That was true of FactionMod and false of the server — Windskull's
plugins have been using SQLite all along:

```
Survival: Database connection established to jdbc:sqlite:/home/container/Survival/survivalbook.db
Survival: Database connection established to jdbc:sqlite:Hunger/hunger.db
Survival: Database connection established to jdbc:sqlite:Thirst/thirst.db
```

Same inference error as 5.2 and 5.5: absence in FactionMod is not absence on the server.

**The native library was never the risk.** The predicted failure — a native library that cannot
unpack — did not happen. The actual first-deploy failure was:

```
java.sql.SQLException: No suitable driver found for jdbc:sqlite:/home/container/mods/...
    at com.peoplesserver.supportermod.storage.Database.open(Database.java:26)
```

The driver was shaded correctly and `META-INF/services/java.sql.Driver` was present in the jar.
`DriverManager` runs its `ServiceLoader` discovery **once, against the system classloader**, and
Hytale loads each plugin in its own `PluginClassLoader` — which the system classloader cannot
see. So a perfectly good driver sits in the jar and never registers.

**The fix is to not use `DriverManager`.** `SQLiteDataSource` holds the driver directly and never
consults the global registry, so the classloader question does not arise. It also avoids adding
a fourth sqlite-jdbc to a registry already shared with three Windskull plugins.

Anyone writing a Hytale plugin that talks to a database should assume this, not discover it:
**`DriverManager` does not work from a plugin classloader. Use a `DataSource`.**

## 5.6 (original analysis) — the server provides nothing

No `sqlite`, `jdbc`, `h2` or `hsqldb` classes anywhere in the server jar. So `sqlite-jdbc` must
be shaded into the plugin jar (the `pluginJar` task already does this) and **nothing on the
classpath conflicts with it**.

Whether the native library can unpack at runtime is still untested **on the server** and still
the first thing to check there. Note the server runs in a Docker container (`yolks:java_25`)
with the data directory at `/home/container` — if `java.io.tmpdir` is not writable, set
`org.sqlite.tmpdir` to a path under the plugin's own directory rather than abandoning SQLite.

One data point from the build machine: the driver does load, and it announces itself:

```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by org.sqlite.SQLiteJDBCLoader
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
```

That is `System.load` of the unpacked native library — the exact call §5.6 is about. Two things
follow. On boot, **these three lines in the server log mean SQLite is working**, not failing.
And the last line is a real forward risk: a future JDK blocks restricted native access unless
the JVM is started with `--enable-native-access=ALL-UNNAMED`. The server's startup command is
edited in the Pelican panel's Startup tab, so if a JDK upgrade ever breaks SQLite, that flag is
the fix rather than a rewrite of the storage layer.

## Scheduler · confirmed absent, but there is a lifecycle hook

There is no API that *creates* scheduled work. The `Scheduler` port stays, backed by
`ScheduledExecutorService`, and — per §B7 — every callback must hop to the world thread with
`world.execute(...)` before touching entities or components.

There is, however, a registry for *tracking* work you created yourself:

```java
public class TaskRegistry extends Registry<TaskRegistration> {
  public TaskRegistration registerTask(CompletableFuture<Void>);
  public TaskRegistration registerTask(ScheduledFuture<Void>);
}
```

Reached via `getTaskRegistry()`. Handing our `ScheduledFuture` to it means the host cancels the
reconcile job during shutdown rather than us relying on our own teardown running — worth using,
given FactionMod's v1.19.8 bug where a deferred shutdown task never ran because the world thread
was already winding down.

## Plugin lifecycle · confirmed

`PluginBase` (which `JavaPlugin` extends) exposes `protected void setup()` and
`protected void shutdown()`, plus `getDataDirectory()` — an official data directory, so the
`Path.of("plugins", "<Name>")` convention in §7.2 is FactionMod's own choice rather than a
requirement.

`PlayerEvent.getPlayerRef()` returns a `Ref<EntityStore>`, **not** a `PlayerRef`. The player
identity comes from `event.getPlayer().getPlayerRef()`, which does return `PlayerRef` and hence
`getUuid()` / `getUsername()`.

## Particle systems: 540 of 598 never expire

Settled by deploying Phase 4, 2026-08-17. A trail spawned free-standing must use an effect that
expires on its own, and **most vanilla effects do not**:

```
total particlesystems : 598
no LifeSpan (unsafe)  : 540
finite LifeSpan       :  49  (<= 6s)
```

A `.particlesystem` with no top-level `LifeSpan` is meant to be attached to something with its
own lifetime — a projectile, a held torch, a status effect on an entity. Spawned at a world
position with `ParticleUtil.spawnParticleEffect`, it simply stays there. Picking six by
plausible name (`Fire_Teal`, `Dust_Sparkles_Fine`, `Block_Gem_Sparks`…) put six of the 540 into
production and left the world carpeted in glowing blobs.

**Before using any effect free-standing, check it expires:**

```
unzip -p Assets.zip Server/Particles/<path>.particlesystem | grep LifeSpan
```

No `LifeSpan` line means do not use it. Also prefer few `SpawnerId` entries — a seven-spawner
explosion costs seven times a one-spawner puff, and a trail emits continuously.

Note names are not unique: `Fire_Charge1` exists at both `Combat/Fire_Stick/` and `_Test/Fire/`.
`tools/` has a scanner for this (`ScanParticles`), and the shortlist worth drawing from is the
`Potion_*_Burst` family, `Block_Hit_*`, and `Potion_Health_Implosion`.

## Revised store-page verdicts

| Perk | Was | Now |
|---|---|---|
| 10 — priority queue | UNPROVEN, do not list | **Buildable** — `PlayerSetupConnectEvent` is cancellable with a reason |
| 11 — `/nick` nameplate | UNPROVEN, leaning negative | **Confirmed negative** — chat-only, and say so |
| R1 — tab list | UNPROVEN, leaning negative | **Possible**, but races `ServerPlayerListModule`; spike before promising |
