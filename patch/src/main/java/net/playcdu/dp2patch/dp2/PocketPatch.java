package net.playcdu.dp2patch.dp2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.tcn.cosmoslibrary.common.lib.CosmosChunkPos;
import com.tcn.cosmoslibrary.registry.gson.object.ObjectBlockPosDimension;
import com.tcn.dimensionalpocketsii.core.management.ConfigurationManager;
import com.tcn.dimensionalpocketsii.core.management.DimensionManager;
import com.tcn.dimensionalpocketsii.pocket.core.Pocket;
import com.tcn.dimensionalpocketsii.pocket.core.gson.PocketChunkInfo;
import com.tcn.dimensionalpocketsii.pocket.core.registry.ChunkLoadingManager;
import com.tcn.dimensionalpocketsii.pocket.core.registry.StorageManager;

import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.playcdu.dp2patch.DP2Patch;
import net.playcdu.dp2patch.PatchConfig;
import net.playcdu.dp2patch.PatchState;

/**
 * Replacement for {@link ChunkLoadingManager}'s level-tick handler.
 *
 * <p>The stock handler has three problems, and this class exists to fix all three.
 *
 * <ol>
 * <li><b>No dimension check on rooms.</b> {@code tickLoadedRooms} is handed whichever level is
 * currently ticking and then walks every registered pocket room without ever checking that the level
 * <em>is</em> the pocket dimension. On a pack with hundreds of live dimensions, every pocket room's
 * chunk coordinates get touched once per dimension per tick.</li>
 * <li><b>Blocking chunk retrieval.</b> It reaches those chunks through {@code Level#getChunk(int,int)},
 * which loads - and if absent, <em>generates</em> - the chunk, parking the server thread in
 * {@code ServerChunkCache#managedBlock} until it is ready. Combined with the missing dimension check,
 * the server ends up synchronously generating pocket-room coordinates in every dimension it has
 * loaded. That is the stall.</li>
 * <li><b>Linear registry scan per room.</b> Each room resolves its pocket through
 * {@code StorageManager#getPocketFromChunkPosition}, which iterates the entire pocket registry with
 * no early exit, making the whole thing O(levels x rooms x pockets) per tick.</li>
 * </ol>
 *
 * <p>The replacement only touches the pocket dimension, never blocks (an unloaded chunk is simply
 * skipped), and resolves rooms through an index that is rebuilt only when the registry changes.
 *
 * <p>It also adds what the stock mod has no notion of: releasing the force-load on a pocket room
 * once nobody has been inside it for a while, and taking it back when a player returns.
 */
public final class PocketPatch {

    /** Upper bound on how long the room/block index may go without a rebuild, in ticks. */
    private static final int INDEX_REFRESH_TICKS = 100;

    /** Every chunk of every pocket -> the full chunk list of the pocket owning it. */
    private static Map<CosmosChunkPos, List<CosmosChunkPos>> roomChunks = Collections.emptyMap();
    /** Dimension -> the chunk-loader block chunks registered in it. */
    private static Map<ResourceLocation, List<CosmosChunkPos>> blockChunks = Collections.emptyMap();
    /** Room dominant chunk -> level game time a player was last seen in that room. */
    private static final Map<CosmosChunkPos, Long> lastOccupied = new HashMap<>();

    private static int seenRooms = -1;
    private static int seenBlocks = -1;
    private static int seenPockets = -1;
    private static int nextRefreshTick = Integer.MIN_VALUE;
    private static int lastIndexTick = Integer.MIN_VALUE;

    /**
     * How many chunks may be force-loaded with the blocking call in a single server tick while
     * warming rooms up. Keeps a cold start from turning into one long stall.
     */
    private static final int FORCE_LOAD_BUDGET_PER_TICK = 1;

    private static int forceBudget = FORCE_LOAD_BUDGET_PER_TICK;

    /** Set if our handler ever throws; we then hand ticking back to DP2 rather than break the server. */
    private static boolean failed = false;
    /** Whether DP2's own handler is currently on the event bus. It registers itself at construction. */
    private static boolean dp2HandlerRegistered = true;

    private PocketPatch() {
    }

    public static void install() {
        MinecraftForge.EVENT_BUS.register(PocketPatch.class);
        PatchConfig.changeListener = PocketPatch::applyConfig;
        applyConfig();
        DP2Patch.LOGGER.info("[dp2patch] Patched Dimensional Pockets II pocket-room ticking "
                + "(stock handler removed from the event bus: {}).", PatchState.dp2HandlerUnregistered);
    }

    /**
     * Brings the event bus in line with the config, so {@code fixRoomTicking} can be toggled at
     * runtime without a restart.
     */
    public static synchronized void applyConfig() {
        final boolean wantPatch = PatchConfig.fixRoomTicking && !failed;

        try {
            if (wantPatch && dp2HandlerRegistered) {
                MinecraftForge.EVENT_BUS.unregister(ChunkLoadingManager.class);
                dp2HandlerRegistered = false;
            } else if (!wantPatch && !dp2HandlerRegistered) {
                MinecraftForge.EVENT_BUS.register(ChunkLoadingManager.class);
                dp2HandlerRegistered = true;
            }
        } catch (Throwable t) {
            // Not fatal: the mixin fallback cancels DP2's handler at HEAD regardless.
            DP2Patch.LOGGER.warn("[dp2patch] Could not change the registration of Dimensional Pockets II's "
                    + "tick handler; falling back to the mixin.", t);
        }

        PatchState.dp2HandlerUnregistered = !dp2HandlerRegistered;
        PatchState.pocketPatchActive = wantPatch;
    }

    @SubscribeEvent
    public static void onLevelTick(final TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        if (!PatchState.pocketPatchActive) {
            return;
        }
        try {
            tick(level);
        } catch (Throwable t) {
            failed = true;
            DP2Patch.LOGGER.error("[dp2patch] Pocket-room tick patch threw; reverting to Dimensional "
                    + "Pockets II's own handler for the rest of this session.", t);
            applyConfig();
        }
    }

    private static void tick(final ServerLevel level) {
        final MinecraftServer server = level.getServer();
        final int tick = server.getTickCount();

        // The index is shared by every level, so rebuild it at most once per server tick.
        if (tick != lastIndexTick) {
            lastIndexTick = tick;
            forceBudget = FORCE_LOAD_BUDGET_PER_TICK;
            refreshIndex(tick);
        }

        final boolean pocketDim = level.dimension().equals(DimensionManager.POCKET_WORLD);
        final List<CosmosChunkPos> blocks = blockChunks.get(level.dimension().location());

        // Everything that is neither the pocket dimension nor host to a pocket chunk-loader block
        // leaves here, after one hash lookup. On a pack with hundreds of dimensions that is nearly
        // all of them - and it is exactly the work the stock handler does not skip.
        if (!pocketDim && blocks == null) {
            return;
        }

        final int tickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        final boolean keepLoaded = tickSpeed > 0 && ConfigurationManager.getInstance().getKeepChunksLoaded();

        if (blocks != null && keepLoaded) {
            tickChunkLoaderBlocks(level, blocks, tickSpeed);
        }
        if (pocketDim) {
            tickPocketRooms(level, tickSpeed, keepLoaded);
        }
    }

    /**
     * Rebuilds the lookup tables. Runs when DP2's collections change size, and otherwise at most
     * every {@link #INDEX_REFRESH_TICKS} ticks so that in-place edits are still picked up.
     */
    private static void refreshIndex(final int tick) {
        final List<CosmosChunkPos> rooms = ChunkLoadingManager.getChunkLoadedRooms();
        final Map<CosmosChunkPos, ObjectBlockPosDimension> blocks = ChunkLoadingManager.getChunkLoadedBlocks();
        final Map<PocketChunkInfo, Pocket> pockets = StorageManager.getRegistry();

        if (tick < nextRefreshTick
                && rooms.size() == seenRooms
                && blocks.size() == seenBlocks
                && pockets.size() == seenPockets) {
            return;
        }

        seenRooms = rooms.size();
        seenBlocks = blocks.size();
        seenPockets = pockets.size();
        nextRefreshTick = tick + INDEX_REFRESH_TICKS;

        final Map<ResourceLocation, List<CosmosChunkPos>> byDimension = new HashMap<>();
        for (final Map.Entry<CosmosChunkPos, ObjectBlockPosDimension> entry : blocks.entrySet()) {
            final ObjectBlockPosDimension value = entry.getValue();
            if (entry.getKey() == null || value == null || value.getDimension() == null) {
                continue;
            }
            byDimension.computeIfAbsent(value.getDimension(), k -> new ArrayList<>()).add(entry.getKey());
        }
        blockChunks = byDimension;

        // PocketChunkInfo has no equals/hashCode, which is why DP2 has to scan the registry linearly.
        // CosmosChunkPos does, so index by chunk instead and the per-room lookup becomes O(1).
        final Map<CosmosChunkPos, List<CosmosChunkPos>> byChunk = new HashMap<>();
        for (final PocketChunkInfo info : pockets.keySet()) {
            if (info == null) {
                continue;
            }
            final List<CosmosChunkPos> chunks = info.getChunks();
            if (chunks == null || chunks.isEmpty()) {
                continue;
            }
            final List<CosmosChunkPos> shared = List.copyOf(chunks);
            for (final CosmosChunkPos chunk : shared) {
                byChunk.put(chunk, shared);
            }
        }

        final Map<CosmosChunkPos, List<CosmosChunkPos>> resolved = new LinkedHashMap<>();
        for (final CosmosChunkPos room : rooms) {
            if (room == null) {
                continue;
            }
            final List<CosmosChunkPos> chunks = byChunk.get(room);
            resolved.put(room, chunks != null ? chunks : List.of(room));
        }
        roomChunks = resolved;

        lastOccupied.keySet().retainAll(resolved.keySet());
    }

    private static void tickChunkLoaderBlocks(final ServerLevel level, final List<CosmosChunkPos> chunks,
            final int tickSpeed) {
        final ServerChunkCache cache = level.getChunkSource();
        final boolean anyPlayers = !level.players().isEmpty();

        for (final CosmosChunkPos pos : chunks) {
            if (anyPlayers && hasPlayer(cache, pos)) {
                continue;
            }
            // getChunkNow never blocks and never generates: an unloaded chunk simply is not ticked.
            final LevelChunk chunk = cache.getChunkNow(pos.getX(), pos.getZ());
            if (chunk != null) {
                level.tickChunk(chunk, tickSpeed);
            }
        }
    }

    private static void tickPocketRooms(final ServerLevel level, final int tickSpeed, final boolean keepLoaded) {
        if (roomChunks.isEmpty()) {
            return;
        }

        final ServerChunkCache cache = level.getChunkSource();
        final LongSet forced = level.getForcedChunks();
        final boolean anyPlayers = !level.players().isEmpty();
        final boolean unloadIdle = PatchConfig.unloadIdlePockets;
        final long now = level.getGameTime();
        final long grace = PatchConfig.idleGraceTicks;

        for (final Map.Entry<CosmosChunkPos, List<CosmosChunkPos>> entry : roomChunks.entrySet()) {
            final CosmosChunkPos room = entry.getKey();
            final List<CosmosChunkPos> chunks = entry.getValue();

            if (anyPlayers && isOccupied(cache, chunks)) {
                lastOccupied.put(room, now);
                if (unloadIdle) {
                    // The player's own ticket already has every chunk of the room in memory, so
                    // claiming them here never blocks - and it covers the whole room, not just the
                    // dominant chunk DP2 force-loads.
                    claim(level, forced, chunks);
                }
                // Somebody is here: vanilla is ticking these chunks already. DP2 skips them too.
                continue;
            }

            if (unloadIdle) {
                final long last = lastOccupied.computeIfAbsent(room, k -> now);
                if (now - last >= grace) {
                    release(level, forced, chunks);
                    continue;
                }
            } else if (keepLoaded) {
                // Idle unloading is off, so preserve DP2's intent that an unattended pocket keeps
                // running. DP2 only force-loads a room's dominant chunk, leaving the other three of
                // an enhanced pocket to be pulled in by a blocking fetch every tick and dropped
                // again - so claim them properly instead, a little at a time.
                warm(level, forced, chunks);
            }

            if (!keepLoaded) {
                continue;
            }
            for (final CosmosChunkPos pos : chunks) {
                final LevelChunk chunk = cache.getChunkNow(pos.getX(), pos.getZ());
                if (chunk != null) {
                    level.tickChunk(chunk, tickSpeed);
                }
            }
        }
    }

    private static boolean isOccupied(final ServerChunkCache cache, final List<CosmosChunkPos> chunks) {
        for (final CosmosChunkPos pos : chunks) {
            if (hasPlayer(cache, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPlayer(final ServerChunkCache cache, final CosmosChunkPos pos) {
        return !cache.chunkMap.getPlayers(new ChunkPos(pos.getX(), pos.getZ()), false).isEmpty();
    }

    /**
     * Force-loads every chunk of a room that is already in memory.
     *
     * <p>{@code ServerLevel#setChunkForced(x, z, true)} fetches the chunk with exactly the blocking
     * call this patch exists to remove, so a chunk that is not resident yet is left alone and picked
     * up on a later tick - by which time the player's own ticket will have loaded it.
     */
    private static void claim(final ServerLevel level, final LongSet forced,
            final List<CosmosChunkPos> chunks) {
        for (final CosmosChunkPos pos : chunks) {
            if (forced.contains(ChunkPos.asLong(pos.getX(), pos.getZ()))) {
                continue;
            }
            if (level.getChunkSource().getChunkNow(pos.getX(), pos.getZ()) == null) {
                continue;
            }
            apply(level, pos, true);
        }
    }

    /** Drops the force-load on every chunk of a room, letting it unload normally. */
    private static void release(final ServerLevel level, final LongSet forced,
            final List<CosmosChunkPos> chunks) {
        for (final CosmosChunkPos pos : chunks) {
            if (!forced.contains(ChunkPos.asLong(pos.getX(), pos.getZ()))) {
                continue;
            }
            apply(level, pos, false);
        }
    }

    /**
     * Like {@link #claim} but willing to pay for a chunk that is not resident yet, up to
     * {@link #FORCE_LOAD_BUDGET_PER_TICK} chunks per server tick so the cost stays bounded.
     */
    private static void warm(final ServerLevel level, final LongSet forced,
            final List<CosmosChunkPos> chunks) {
        for (final CosmosChunkPos pos : chunks) {
            if (forced.contains(ChunkPos.asLong(pos.getX(), pos.getZ()))) {
                continue;
            }
            if (level.getChunkSource().getChunkNow(pos.getX(), pos.getZ()) == null) {
                if (forceBudget <= 0) {
                    return;
                }
                forceBudget--;
            }
            apply(level, pos, true);
        }
    }

    private static void apply(final ServerLevel level, final CosmosChunkPos pos, final boolean forced) {
        level.setChunkForced(pos.getX(), pos.getZ(), forced);
        PatchState.forceLoadTransitions++;
        if (PatchConfig.debugLogging) {
            DP2Patch.LOGGER.info("[dp2patch] Pocket chunk [{}, {}] force-load -> {}",
                    pos.getX(), pos.getZ(), forced);
        }
    }

    // ---------------------------------------------------------------- admin / status

    public static int trackedRooms() {
        return roomChunks.size();
    }

    public static int forcedRooms() {
        final ServerLevel level = StorageManager.getServerLevel();
        if (level == null) {
            return 0;
        }
        final LongSet forced = level.getForcedChunks();
        int count = 0;
        for (final CosmosChunkPos room : roomChunks.keySet()) {
            if (forced.contains(ChunkPos.asLong(room.getX(), room.getZ()))) {
                count++;
            }
        }
        return count;
    }

    /** Force-loads every registered room again, undoing all idle releases. */
    public static int restoreAll() {
        final ServerLevel level = StorageManager.getServerLevel();
        if (level == null) {
            return 0;
        }
        final LongSet forced = level.getForcedChunks();
        final long now = level.getGameTime();
        int changed = 0;
        for (final Map.Entry<CosmosChunkPos, List<CosmosChunkPos>> entry : roomChunks.entrySet()) {
            for (final CosmosChunkPos pos : entry.getValue()) {
                if (!forced.contains(ChunkPos.asLong(pos.getX(), pos.getZ()))) {
                    // Deliberately the blocking form: this is a one-off admin action and the point
                    // is that the chunks really are loaded when it returns.
                    level.setChunkForced(pos.getX(), pos.getZ(), true);
                    changed++;
                }
            }
            lastOccupied.put(entry.getKey(), now);
        }
        return changed;
    }

    /** Releases every registered room immediately, without waiting out the grace period. */
    public static int releaseAll() {
        final ServerLevel level = StorageManager.getServerLevel();
        if (level == null) {
            return 0;
        }
        final LongSet forced = level.getForcedChunks();
        int changed = 0;
        for (final Map.Entry<CosmosChunkPos, List<CosmosChunkPos>> entry : roomChunks.entrySet()) {
            for (final CosmosChunkPos pos : entry.getValue()) {
                if (forced.contains(ChunkPos.asLong(pos.getX(), pos.getZ()))) {
                    level.setChunkForced(pos.getX(), pos.getZ(), false);
                    changed++;
                }
            }
            lastOccupied.remove(entry.getKey());
        }
        return changed;
    }
}
