# CLAUDE.md — Solsai (server-side Fabric mod)

## Agent files

Read these before doing any work:

- `agent/SESSION_RESUME.txt` — start here every session
- `agent/CHECKLIST.txt` — pending items
- `agent/map.txt` — full architecture reference
- `agent/CHANGELOG.txt` — session history
- `agent/OBJECTIVE.txt` — what this project is trying to become

## What this is

Solsai is a **Fabric server-side mod** for Minecraft 1.20.1 (Prominence II modpack). It's the
HTTP backbone everything else in this multi-repo project talks to: the NILO/ZOTE mineflayer bots
(`/home/prizmo/nilo-project/nilo/`, `/home/prizmo/zote/`) read/act on world state through it, and
the client-side [[prizmo-system]] mod (`/home/prizmo/prizmo-system/`) both polls it for bot state
and drives most of its player-facing "manifestation" spells (shields, clones, gauntlet, arrows)
by hitting its endpoints from keybinds/UI.

Fabric mod id `solsai` (formerly `zote_context_mod` — renamed but the Java package
`com.zote.contextmod` was never renamed, don't be surprised by the mismatch). Environment:
`"server"` — no client-side jar exists or is needed; nothing here renders anything, it's a plain
HTTP API embedded in the dedicated server process.

Primary features:
- **Bot context API** — position, biome, nearby blocks, inventory, block/item registries for NILO/ZOTE.
- **Mirror/recording** — captures the owning player's C2S packets (movement, look, interact) for
  bot-side mimicry or logging.
- **Remote control / possession** — receives control state from prizmo-system's BotSneakScreen,
  NILO polls it and drives `bot.setControlState` directly.
- **"Manifestation" spell systems** — shields (Fibonacci dome + focus modes + projectile
  reflection), ghost clones (Husk meat-shields) and flying skinned clone, Iron Gauntlet ally,
  floating arrow formations (multi-circle, crosses, hunger-gated).
- **Passive enchant/effect injection** — lets a player have "always-on" enchantments/status
  effects toggled from the client UI without touching their real equipment.
- **Player body scale** (Pehkui) — size/reach multipliers.
- **Terminal bridge** — relays `/`-prefixed console commands or free-text NILO chat-commands
  from prizmo-system's TerminalScreen.

## Tech stack

- Fabric Loader 0.15.11, Fabric API 0.92.2+1.20.1, Yarn mappings 1.20.1+build.10
- Java 17 target/source (`sourceCompatibility`/`targetCompatibility` = 17), compiled with a
  JDK 17-21 toolchain — **do not** let `org.gradle.java.home` get pinned to a machine-specific
  absolute path in `gradle.properties` (bit both NOX and Apollo before); set `JAVA_HOME` per-shell
  instead.
- Gradle 8.6 (`./gradlew`)
- Optional runtime dependency: **Pehkui** 3.8.3 (`modCompileOnly`, local jar at
  `/home/prizmo/mc-prominence2/data/mods/Pehkui-3.8.3+1.14.4-1.21.jar` — already present in the
  live server's mods folder, so it's on the runtime classpath even though it's not a Loom dependency).

## Build

```bash
cd /home/prizmo/solsai
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew build          # compile + package JAR
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew compileJava    # compile only
```

Output JAR: `build/libs/solsai-<version>.jar` (version from `gradle.properties`' `mod_version`).

Deploy (server, NOX's local copy — see MACHINE CONTEXT below for why this is only half the
picture): `cp build/libs/solsai-<version>.jar /home/prizmo/mc-prominence2/data/mods/solsai.jar`,
then restart the container. **Never restart the Minecraft server/container without the user's
explicit yes on that specific action, every time** — deploying the jar file itself is fine, the
restart to load it is not.

## Key source files

| File | Purpose |
|---|---|
| `ContextMod.java` | Mod entry point — embedded `HttpServer` on port 8080, ~40 endpoint handlers, 5 `ServerTickEvents.END_SERVER_TICK` registrations |
| `ArrowManifestManager.java` | Floating arrow formations — multi-circle math, crosses, hunger mechanic |
| `ShieldManifestManager.java` | Fibonacci shield dome, split/focus modes, projectile reflection |
| `CloneManager.java` | Two features merged here: ghost clones (Husk meat-shields) + flying skinned clone (`FakePlayerEntity`) |
| `GauntletManager.java` | Iron Gauntlet (BOMD boss) ally, team + goal-replacement targeting safety |
| `EnchantTracker.java` / `mixin/LivingEntityEnchantMixin.java` | Passive enchantments via phantom `ItemStack` injection |
| `EffectTracker.java` | Passive status effects, tick-refreshed, instant remove on toggle-off |
| `PlayerBodyManager.java` | Pehkui BASE (size) + REACH scale control |
| `mixin/MirrorCaptureMixin.java` | Captures `MIRROR_PLAYER`'s C2S packets into `mirrorBuffer` |
| `FakePlayerEntity.java` / `FakeClientConnection.java` | Backing a real `ServerPlayerEntity` for the flying clone, with a stub connection that silently discards outbound packets |

## Connections to other projects

- **prizmo-system** (`/home/prizmo/prizmo-system/`) — client-side Fabric mod, port 8080 consumer.
  Most keybinds/UI buttons there are a thin wrapper around a `GET` to one of this mod's endpoints.
  Its own `CLAUDE.md`/`agent/` docs document the client side of the same features.
- **Nilo / Zote** (`/home/prizmo/nilo-project/nilo/`, `/home/prizmo/zote/`) — Node.js/mineflayer
  bots. Poll `/bot-state`, `/bot-control-state`, `/terminal-command-state`; push `/bot-mode`.
- **Minecraft server** — Docker container `prominence2`. As of 2026-08-24 this runs on **Apollo**
  (192.168.1.101), not on this machine (NOX, 192.168.1.100) — see MACHINE CONTEXT.

## Architecture notes

### HTTP dispatch pattern
Every endpoint follows the same shape: parse query params inline in the lambda passed to
`httpServer.createContext(path, exchange -> {...})`, delegate the actual work to a manager class's
static method, `sendJson(exchange, resultString)`. Manager methods that touch entity/world state
go through a `dispatch(server, playerName, action)` helper (each manager has its own copy) that
does `server.execute(...)` + `CompletableFuture` to hop onto the server thread safely from the
HTTP handler thread, with a 3s timeout.

### Fake players (flying clone)
`FakePlayerEntity` extends `ServerPlayerEntity` directly rather than using a library — overrides
`isDisconnected()`/`isSpectator()`/`isCreative()`/`writeCustomDataToNbt()` so the server treats it
as a normal, always-connected player that never persists to disk. `FakeClientConnection` gives it
a `ClientConnection` backed by an `EmbeddedChannel` that discards every outbound packet, so nothing
NPEs when server code tries to talk to "its" client.

### Goal-selector rewriting (clones, gauntlet)
`MobEntityAccessor` and `GoalSelectorAccessor` (both `@Accessor` mixins) expose vanilla's
protected/package-private goal selector fields so `CloneManager`/`GauntletManager` can strip and
replace a freshly-spawned mob's AI goals at spawn time (vanilla goals are added in the entity's
constructor, before any of this mod's tagging runs).

### Shield laser-blocking is currently disabled
`ShieldBlock` (a real `Block` with a custom collision shape used to intercept the Iron Gauntlet's
raycast laser) is commented out in `ContextMod.java` — registering it into `Registries.BLOCK`
trips Fabric's registry-sync check on any client without Solsai installed (`"Received a registry
entry that is unknown to this client"`), which breaks the server-only requirement (no client jar
exists to match). `ShieldManifestManager`'s block-placement calls are now no-ops; mob-push and
projectile reflection still work, laser-wall blocking does not. Re-enabling needs a different
approach (e.g. mixin the collision-shape query itself instead of a registered block) — see
`agent/CHECKLIST.txt`.

### Passive enchant/effect injection
Both are per-player UUID → `Set<Identifier>` registries. Enchants use phantom `ItemStack`s (probe
items chosen so `Enchantment.isAcceptableItem()` routes correctly) appended to
`getArmorItems()`/`getHandItems()` via mixin — the real equipment is never touched, so this is
invisible to the player's inventory. Effects use real `StatusEffectInstance`s refreshed every
second with a long duration so they never visibly tick down, removed within one tick when toggled
off (tracked separately from the player's actual potion effects so toggling off never strips a
real potion).

## Known limitations / TODO

See `agent/CHECKLIST.txt` for the live list. Headline items as of 2026-08-24:
- Shield laser-blocking disabled (see above).
- ZOTE has no bot-state endpoint (`/bot-state`, `/bot-inventory` etc. only really wired up for NILO
  end-to-end — check current CHECKLIST for exact status).
- No persistence for `EnchantTracker`/`EffectTracker` state across server restarts.
- Arrow manifestation's new multi-circle formation (2026-08-24) is NOX-only and not yet live-tested.

## MACHINE CONTEXT — NOX / Apollo, and the drift gotcha

This machine is **NOX** (192.168.1.100). The Minecraft server itself now runs on **Apollo**
(192.168.1.101) via Docker — `/home/prizmo/mc-prominence2` is kept in sync between the two with
Unison (`~/.unison/prominence2.prf`), but **this `solsai` source tree is not** — NOX and Apollo
each have their own independent, uncommitted git working copy of both `solsai` and `prizmo-system`,
reconciled only occasionally via manual `git merge` (see `git log --oneline`: "Merge NOX and Apollo
solsai work"), with jars/source moved between machines by hand (`scp`), not git.

**This already broke a feature once** (2026-08-24): a local rebuild+deploy on NOX silently
overwrote a newer prizmo-system jar Apollo had sent over, and independently, this file itself had
diverged — NOX's copy of `ArrowManifestManager.java` was a much older single-ring design while
Apollo's had already moved to a multi-ring one. Before making non-trivial changes here, it's worth
diffing against Apollo's copy first:

```bash
ssh prizmo@apollo 'cat ~/solsai/src/main/java/com/zote/contextmod/<File>.java'
```

and committing real work to git reasonably often, since right now almost everything of substance
in this file tree is uncommitted on one machine or the other.

GitHub: `https://github.com/PrizmoElectric/Solsai.git`
