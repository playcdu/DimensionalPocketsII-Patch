# Dimensional Pockets II Patch

A **server-side-only** Forge 1.20.1 mod that fixes the pathological tick behaviour in
[Dimensional Pockets II](https://github.com/TheCosmosSeries/DimensionalPocketsII) 9.1.0.0 and
releases pocket dimensions nobody is using.

Clients do not need it and cannot tell it is there — it registers no blocks, items, entities or
network channels, and declares itself `IGNORESERVERONLY`, so an unmodified modpack client connects
normally. Drop it in the server's `mods/` folder alongside the unmodified
`dimensionalpocketsii-1.20.1-9.1.0.0-universal.jar`.

- **Minecraft** 1.20.1 · **Forge** 47.4.20 · runs on **Java 21**
- Jar: `dp2patch-1.20.1-1.0.0.jar`

## What was wrong

Dimensional Pockets II registers exactly one per-tick Forge handler,
`ChunkLoadingManager#onTick`, subscribed to `LevelTickEvent`. It has three defects that compound:

1. **No dimension check on pocket rooms.** `tickLoadedRooms` is handed whichever level is currently
   ticking, then iterates every registered pocket room without ever checking that the level *is* the
   pocket dimension. `tickLoadedBlocks` right above it does make that check; the room path does not.
2. **Blocking chunk retrieval.** It reaches those chunks through `Level#getChunk(int, int)`, which
   loads — and where absent, **generates** — the chunk, parking the server thread in
   `ServerChunkCache#managedBlock` until it is ready. Combined with defect 1, the server
   synchronously generates terrain at pocket-room coordinates *in every dimension it has loaded*.
   That is the native wait and the multi-second stalls.
3. **Linear registry scan per room.** Each room resolves its pocket via
   `StorageManager#getPocketFromChunkPosition`, which walks the entire pocket registry with no early
   exit — making the whole handler O(levels × rooms × pockets) per tick.

A fourth issue makes it worse over time: `addRoom` force-loads only a pocket's *dominant* chunk. The
other three chunks of a 2×2 "enhanced" pocket are pulled in by the blocking fetch every tick and
dropped again as soon as their temporary ticket expires — a permanent load/unload thrash.

## What the patch does

| | |
|---|---|
| **Fix the room tick** | Takes DP2's handler off the Forge event bus and installs a corrected one: pocket rooms are ticked **only in the pocket dimension**, chunks are fetched with the non-blocking `getChunkNow` (an unloaded chunk is skipped, never generated), and rooms resolve through an index rebuilt only when the registry changes. Non-pocket dimensions leave the handler after a single hash lookup. |
| **Unload idle pockets** | Once no player has been in a pocket room for `idleGraceSeconds`, its force-load is released and the chunks unload normally — block entities inside stop ticking. The room is claimed again the moment a player returns. Rooms nobody visits are never force-loaded at all. |
| **Throttle idle dimensions** | A dimension with no players, no loaded chunks and no force-loaded chunks gets a full tick only 1 tick in `idleLevelTickInterval`. Its clock still advances every tick, so day/night and scheduled events do not drift. |

The third item is the safe form of "unload empty dimensions". Forge never removes a `ServerLevel`
once created, and tearing one out from under a pack like Project Infinity is a good way to crash a
server. What actually costs you CPU and memory is the *chunks* and the *tick*, and both of those are
reclaimed here.

## Measured effect

Forge 47.4.20 server, 80 enhanced (2×2) pockets, no players, `/forge tps` after ~60 s of steady
running. "Stock" is this jar installed with `fixRoomTicking = false`, i.e. DP2's own code path.

| Dimension | Stock | Fix only | Defaults |
|---|---:|---:|---:|
| `minecraft:overworld` | 62.5 ms (16.0 TPS) | 1.002 ms | 0.940 ms |
| `minecraft:the_nether` | **525.7 ms (1.9 TPS)** | 0.032 ms | 0.010 ms |
| `minecraft:the_end` | 131.3 ms (7.6 TPS) | 0.011 ms | 0.025 ms |
| `dimensionalpocketsii:pocket` | 0.301 ms | 5.164 ms | 0.043 ms |
| **Loaded chunks** | **37,928** | 12,589 | **2,209** |

The nether and the end were being driven to 1.9 and 7.6 TPS by pocket-room chunk generation that had
no business happening there at all. Under "fix only" the pocket dimension's cost *rises*, from
0.3 ms to 5.2 ms — that is the room ticking DP2 always intended, finally happening in the right
place. With idle unloading on top it drops to 0.043 ms because unattended rooms are released.

Only four dimensions were loaded in this test. The wasted work scales with the number of loaded
dimensions, so on a pack sitting at 333 the ratio is far larger.

## Configuration

`config/dp2patch-common.toml`, reloadable at runtime.

| Key | Default | Meaning |
|---|---|---|
| `pockets.fixRoomTicking` | `true` | Install the corrected tick handler. Pure bug fix, no gameplay change. |
| `pockets.unloadIdlePockets` | `true` | Release unattended pocket rooms. **Overrides DP2's `keep_chunks_loaded` for rooms nobody is in** — turn it off if machines must keep running inside unattended pockets. |
| `pockets.idleGraceSeconds` | `300` | Seconds a room stays loaded after the last player leaves. |
| `dimensions.idleLevelTickInterval` | `4` | Idle dimensions tick 1 in N. `1` disables. |
| `debugLogging` | `false` | Log every force-load transition. |

## Commands

`/dp2patch` (permission level 2):

- `status` — dimension/chunk counts, throttle state, rooms tracked and force-loaded
- `release` — release every pocket room now, without waiting out the grace period
- `restore` — force-load every registered room again, undoing all idle releases

`restore` is the escape hatch. Idle releases are written to the world's persistent forced-chunk data,
so if you remove this mod after it has released rooms, run `restore` first — DP2 only force-loads a
room when the pocket is created and will not re-claim one on its own.

## Safety

- If the patch's tick handler ever throws, it logs the failure, hands ticking straight back to DP2
  and stays out of the way for the rest of the session.
- If DP2 is absent the pocket half never loads; idle-dimension throttling still works.
- The mixin config is `required: false` and gated by a plugin that checks the mod list, so a DP2
  version whose internals moved cannot stop the server from booting.
- Removing the handler from the event bus is the primary mechanism; the mixin on
  `ChunkLoadingManager#onTick` is only a fallback in case that ever stops working.

## Building

Needs a JDK 17 toolchain (Forge 1.20.1 targets Java 17 bytecode; the jar it produces runs on
Java 21). The DP2 and Cosmos Library jars are fetched from Modrinth at build time and are **not**
redistributed in this repository.

```sh
cd patch
gradle build          # -> build/libs/dp2patch-1.20.1-1.0.0.jar
```

## Licence

This patch is MIT and contains no Dimensional Pockets II code — it is an addon that requires the
mod to function, which DP2's licence expressly permits. Dimensional Pockets II remains
© TheCosmicNebula, All Rights Reserved.
