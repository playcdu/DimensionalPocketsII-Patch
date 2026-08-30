package net.playcdu.dp2patch.idle;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

import net.playcdu.dp2patch.PatchConfig;

/**
 * Decides whether a dimension is idle enough to have its tick skipped.
 *
 * <p>A dimension with no players, no loaded chunks and no force-loaded chunks has nothing to
 * simulate, yet a full level tick still walks the world border, the weather cycle, the chunk source,
 * the block-event queue and the entity tick list. That fixed cost is small on its own and ruinous
 * when a pack keeps several hundred dimensions alive.
 *
 * <p>This throttles rather than stops. An idle level still gets a full tick one tick in
 * {@code idleLevelTickInterval}, so anything that needs the chunk source to make progress - a
 * pending chunk ticket from a teleport or another mod's chunk loader - resolves within a few ticks
 * instead of never. Skipped ticks still advance the level's clock, so nothing drifts.
 */
public final class IdleLevels {

    private IdleLevels() {
    }

    public static boolean shouldThrottle(final ServerLevel level) {
        final int interval = PatchConfig.idleLevelTickInterval;
        if (interval <= 1) {
            return false;
        }
        if (!level.players().isEmpty()) {
            return false;
        }

        final ServerChunkCache cache = level.getChunkSource();
        if (cache.getLoadedChunksCount() > 0) {
            return false;
        }
        if (!level.getForcedChunks().isEmpty()) {
            return false;
        }

        final MinecraftServer server = level.getServer();
        if (server == null) {
            return false;
        }

        // Offset each dimension by a stable phase so the levels that do get a full tick are spread
        // across the interval instead of all landing on the same tick.
        final int phase = Math.floorMod(level.dimension().location().hashCode(), interval);
        return Math.floorMod(server.getTickCount() + phase, interval) != 0;
    }
}
